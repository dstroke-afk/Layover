package com.intj.layover

import com.intj.layover.ble.GattSessionManager
import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.crypto.SessionCryptoManager
import com.intj.layover.model.PhotoTransferState
import com.intj.layover.p2p.WireFrame
import com.intj.layover.p2p.WireFrameCodec
import com.intj.layover.wifi.DirectPhotoSocketServer
import com.intj.layover.wifi.EncryptedPhotoStreamProtocol
import com.intj.layover.wifi.PeerDpCacheStore
import com.intj.layover.wifi.PhotoDownloadQueueStateMachine
import com.intj.layover.wifi.PhotoPreviewGenerator
import com.intj.layover.wifi.PhotoSizeValidationResult
import com.intj.layover.wifi.PhotoSizeValidator
import com.intj.layover.wifi.PhotoTransferConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.Base64

class DirectWifiPhotoTransferTest {

    @Test
    fun photoSizeCap_enforcesThreeMegabyteLimitAndFormatsLabels() {
        assertEquals(3_145_728L, PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES)

        val below = PhotoSizeValidator.validateSize(PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES - 1L)
        assertTrue(below is PhotoSizeValidationResult.Valid)

        val exact = PhotoSizeValidator.validateSize(PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES)
        assertTrue(exact is PhotoSizeValidationResult.Valid)

        val over = PhotoSizeValidator.validateSize(PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES + 1L)
        assertTrue(over is PhotoSizeValidationResult.ExceedsLimit)
        assertEquals("Photo exceeds 3 MB limit", (over as PhotoSizeValidationResult.ExceedsLimit).errorMessage)

        assertTrue(PhotoSizeValidator.validateSize(0L) is PhotoSizeValidationResult.Empty)
        assertTrue(PhotoSizeValidator.validateSize(-10L) is PhotoSizeValidationResult.Empty)

        assertEquals("2.4 MB", PhotoPreviewGenerator.formatByteSize((2.4 * 1024 * 1024).toLong()))
        assertEquals("850 KB", PhotoPreviewGenerator.formatByteSize(850L * 1024L))
    }

    @Test
    fun streamingCap_acceptsAtExactLimitAndRejectsOneByteOver() {
        val exactBytes = ByteArray(PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES.toInt()) { 0x42 }
        val readExact = PhotoSizeValidator.readBytesWithCap(ByteArrayInputStream(exactBytes))
        assertNotNull(readExact)
        assertEquals(PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES.toInt(), readExact!!.size)

        val overBytes = ByteArray(PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES.toInt() + 1) { 0x42 }
        assertNull(PhotoSizeValidator.readBytesWithCap(ByteArrayInputStream(overBytes)))
    }

    @Test
    fun encryptedPhotoPreview_roundTripsOverSessionCryptoAndWireCodec() {
        val senderCrypto = SessionCryptoManager(forceEcOnly = true)
        val receiverCrypto = SessionCryptoManager(forceEcOnly = true)

        val senderPub = senderCrypto.ensureLocalKeyPair("EC")
        val receiverPub = receiverCrypto.ensureLocalKeyPair("EC")
        assertTrue(senderCrypto.establishRemoteKeyForPeer("dev_recv", emptySet(), receiverPub, "EC").isUsableSession)
        assertTrue(receiverCrypto.establishRemoteKeyForPeer("dev_send", emptySet(), senderPub, "EC").isUsableSession)

        val transferKey = EncryptedPhotoStreamProtocol.generateTransferKey()
        val originalPhoto = ByteArray(48 * 1024) { (it * 13 and 0xFF).toByte() }
        val sha256 = EncryptedPhotoStreamProtocol.sha256Hex(originalPhoto)
        val previewThumb = ByteArray(9_600) { (it % 251).toByte() }

        val bundle = EncryptedPhotoStreamProtocol.encodePreviewBundle(
            fileSizeBytes = originalPhoto.size.toLong(),
            mimeType = "image/jpeg",
            sha256Hex = sha256,
            transferKey = transferKey,
            previewBytes = previewThumb
        )
        val encryptedBundle = senderCrypto.encryptBytes(bundle, listOf("dev_recv"))
        assertNotNull(encryptedBundle)

        val wireText = WireFrameCodec.encodePhotoPreview(
            transferId = "ph_preview_01",
            senderId = "dev_send",
            encryptedPreviewBundle = encryptedBundle!!
        )
        val decodedFrame = WireFrameCodec.decodeFrame(wireText) as WireFrame.PhotoPreview
        assertEquals("ph_preview_01", decodedFrame.transferId)
        assertEquals("dev_send", decodedFrame.senderId)

        val iv = Base64.getDecoder().decode(decodedFrame.ivBase64)
        val cipher = Base64.getDecoder().decode(decodedFrame.ciphertextBase64)
        val decryptedBundleBytes = receiverCrypto.decryptBytes(EncryptedPayload(iv, cipher), listOf("dev_send"))
        assertNotNull(decryptedBundleBytes)

        val decodedBundle = EncryptedPhotoStreamProtocol.decodePreviewBundle(decryptedBundleBytes!!)
        assertNotNull(decodedBundle)
        assertEquals(originalPhoto.size.toLong(), decodedBundle!!.fileSizeBytes)
        assertEquals("image/jpeg", decodedBundle.mimeType)
        assertEquals(sha256, decodedBundle.sha256Hex)
        assertArrayEquals(transferKey.encoded, decodedBundle.transferKey.encoded)
        assertArrayEquals(previewThumb, decodedBundle.previewBytes)

        // Verify direct control frames round-trip cleanly
        val reqRaw = WireFrameCodec.encodePhotoDownloadRequest("ph_preview_01", "dev_recv")
        val reqDecoded = WireFrameCodec.decodeFrame(reqRaw) as WireFrame.PhotoDownloadRequest
        assertEquals("ph_preview_01", reqDecoded.transferId)
        assertEquals("dev_recv", reqDecoded.senderId)

        val readyRaw = WireFrameCodec.encodeWifiApReady(
            transferId = "ph_preview_01",
            senderId = "dev_send",
            ssid = "AndroidShare|991",
            passphrase = "p@ss|word!88",
            port = 51234,
            hostIp = "192.168.43.1",
            isReceiverFallbackHost = false
        )
        val readyDecoded = WireFrameCodec.decodeFrame(readyRaw) as WireFrame.WifiApReady
        assertEquals("AndroidShare|991", readyDecoded.ssid)
        assertEquals("p@ss|word!88", readyDecoded.passphrase)
        assertEquals(51234, readyDecoded.port)
        assertEquals("192.168.43.1", readyDecoded.hostIp)
        assertFalse(readyDecoded.isReceiverFallbackHost)

        val fallbackRaw = WireFrameCodec.encodeWifiApFallback("ph_preview_01", "dev_send", "hotspot_busy")
        val fallbackDecoded = WireFrameCodec.decodeFrame(fallbackRaw) as WireFrame.WifiApFallback
        assertEquals("hotspot_busy", fallbackDecoded.reason)

        val doneRaw = WireFrameCodec.encodePhotoTransferDone("ph_preview_01", "dev_recv", success = true)
        val doneDecoded = WireFrameCodec.decodeFrame(doneRaw) as WireFrame.PhotoTransferDone
        assertTrue(doneDecoded.success)
    }

    @Test
    fun sha256Integrity_rejectsWrongDigestAndWrongKey() {
        val transferId = "ph_sha_test"
        val key = EncryptedPhotoStreamProtocol.generateTransferKey()
        val wrongKey = EncryptedPhotoStreamProtocol.generateTransferKey()
        val photo = ByteArray(40 * 1024) { (it * 7 and 0xFF).toByte() }
        val goodSha = EncryptedPhotoStreamProtocol.sha256Hex(photo)
        val badSha = "0".repeat(64)

        val out = ByteArrayOutputStream()
        EncryptedPhotoStreamProtocol.writeEncryptedPhotoStream(photo, transferId, key, out)
        val encryptedWire = out.toByteArray()

        // Valid SHA-256 passes
        val ok = EncryptedPhotoStreamProtocol.readEncryptedPhotoStream(
            expectedPlaintextSize = photo.size.toLong(),
            expectedSha256Hex = goodSha,
            transferId = transferId,
            key = key,
            inputStream = ByteArrayInputStream(encryptedWire)
        )
        assertArrayEquals(photo, ok)

        // Wrong SHA-256 fails
        var shaFailed = false
        try {
            EncryptedPhotoStreamProtocol.readEncryptedPhotoStream(
                expectedPlaintextSize = photo.size.toLong(),
                expectedSha256Hex = badSha,
                transferId = transferId,
                key = key,
                inputStream = ByteArrayInputStream(encryptedWire)
            )
        } catch (e: IOException) {
            shaFailed = e.message?.contains("SHA-256", ignoreCase = true) == true
        }
        assertTrue("Expected SHA-256 integrity failure", shaFailed)

        // Wrong key fails GCM authentication
        var keyFailed = false
        try {
            EncryptedPhotoStreamProtocol.readEncryptedPhotoStream(
                expectedPlaintextSize = photo.size.toLong(),
                expectedSha256Hex = goodSha,
                transferId = transferId,
                key = wrongKey,
                inputStream = ByteArrayInputStream(encryptedWire)
            )
        } catch (_: IOException) {
            keyFailed = true
        }
        assertTrue("Expected AES-GCM failure with wrong key", keyFailed)
    }

    @Test
    fun stateMachine_directSenderHost_fallbackRoleInversion_andRetryTransitions() {
        val sm = PhotoDownloadQueueStateMachine()
        sm.registerAvailable("ph_1", "dev_alice")
        assertEquals(PhotoTransferState.AVAILABLE, sm.getSnapshot("ph_1")?.state)

        // Tap Download -> CONNECTING_WIFI
        val start = sm.requestDownload("ph_1")
        assertTrue(start is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.StartNow)
        assertEquals("ph_1", sm.activeTransferId())
        assertEquals(PhotoTransferState.CONNECTING_WIFI, sm.getSnapshot("ph_1")?.state)

        // Sender hotspot fails -> triggers optional 1-on-1 role-inversion fallback once
        val fallback = sm.handleSenderApFailureOrTimeout("ph_1", allowRoleInversionFallback = true)
        assertTrue(fallback is PhotoDownloadQueueStateMachine.SenderApFailureOutcome.StartReceiverFallbackHost)
        assertEquals(PhotoTransferState.CONNECTING_WIFI, sm.getSnapshot("ph_1")?.state)

        // If fallback also fails -> cleanly transitions to FAILED and clears active slot
        val finalFailure = sm.handleSenderApFailureOrTimeout("ph_1", allowRoleInversionFallback = true, reason = "Hotspot failed")
        assertTrue(finalFailure is PhotoDownloadQueueStateMachine.SenderApFailureOutcome.Failed)
        assertEquals(PhotoTransferState.FAILED, sm.getSnapshot("ph_1")?.state)
        assertNull(sm.activeTransferId())

        // Tapping Retry transitions FAILED -> CONNECTING_WIFI -> DOWNLOADING -> COMPLETED
        val retry = sm.requestDownload("ph_1")
        assertTrue(retry is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.StartNow)
        sm.markDownloading("ph_1", 0.55f)
        assertEquals(PhotoTransferState.DOWNLOADING, sm.getSnapshot("ph_1")?.state)
        assertEquals(0.55f, sm.getSnapshot("ph_1")?.progress ?: 0f, 0.001f)

        val next = sm.markCompleted("ph_1")
        assertNull(next)
        assertEquals(PhotoTransferState.COMPLETED, sm.getSnapshot("ph_1")?.state)
        assertNull(sm.activeTransferId())
    }

    @Test
    fun downloadQueueSerialization_runsOneWifiSessionAtATimeAndPromotesQueuedPhotos() {
        val sm = PhotoDownloadQueueStateMachine()
        sm.registerAvailable("ph_from_alice", "dev_alice")
        sm.registerAvailable("ph_from_bob", "dev_bob")
        sm.registerAvailable("ph_from_carol", "dev_carol")

        // Tap first photo -> starts immediately
        val first = sm.requestDownload("ph_from_alice")
        assertTrue(first is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.StartNow)
        assertEquals("ph_from_alice", sm.activeTransferId())

        // Tap second and third while first is active -> queued sequentially
        val second = sm.requestDownload("ph_from_bob")
        assertTrue(second is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.Queued)
        assertEquals(
            PhotoDownloadQueueStateMachine.WAITING_FOR_CURRENT_DOWNLOAD_LABEL,
            (second as PhotoDownloadQueueStateMachine.DownloadRequestOutcome.Queued).statusDetail
        )
        assertEquals(PhotoTransferState.QUEUED, sm.getSnapshot("ph_from_bob")?.state)

        val third = sm.requestDownload("ph_from_carol")
        assertTrue(third is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.Queued)
        assertEquals(listOf("ph_from_bob", "ph_from_carol"), sm.queuedTransferIds())

        // Cancel queued photo -> returns to AVAILABLE without disrupting active transfer
        val cancelQueued = sm.cancel("ph_from_bob")
        assertFalse(cancelQueued.wasActive)
        assertNull(cancelQueued.nextDownload)
        assertEquals(PhotoTransferState.AVAILABLE, sm.getSnapshot("ph_from_bob")?.state)
        assertEquals("ph_from_alice", sm.activeTransferId())

        // Complete active transfer -> automatically promotes next queued photo (ph_from_carol)
        val promoted = sm.markCompleted("ph_from_alice")
        assertNotNull(promoted)
        assertEquals("ph_from_carol", promoted!!.transferId)
        assertEquals("dev_carol", promoted.senderPeerId)
        assertEquals("ph_from_carol", sm.activeTransferId())
        assertEquals(PhotoTransferState.CONNECTING_WIFI, sm.getSnapshot("ph_from_carol")?.state)

        // Complete second promoted transfer -> queue and active slot are clean
        assertNull(sm.markCompleted("ph_from_carol"))
        assertNull(sm.activeTransferId())
        assertTrue(sm.queuedTransferIds().isEmpty())
    }

    @Test
    fun directPhotoSocketServer_senderAsApStreamsEncryptedPhotoAndTearsDownCleanly() = runBlocking {
        val transferId = "ph_direct_sender_ap"
        val key = EncryptedPhotoStreamProtocol.generateTransferKey()
        val originalPhoto = ByteArray(160 * 1024) { ((it * 29 + 5) and 0xFF).toByte() }
        val expectedSha = EncryptedPhotoStreamProtocol.sha256Hex(originalPhoto)

        val server = DirectPhotoSocketServer(
            bindAddress = InetAddress.getByName("127.0.0.1"),
            acceptTimeoutMs = 5_000,
            socketReadTimeoutMs = 5_000
        )
        val port = server.localPort

        val hostJob = async(Dispatchers.IO) {
            server.serveAsSenderHost(
                transferId = transferId,
                plaintextBytes = originalPhoto,
                transferKey = key
            )
        }

        val progressEvents = mutableListOf<Float>()
        val clientJob = async(Dispatchers.IO) {
            Socket("127.0.0.1", port).use { socket ->
                EncryptedPhotoStreamProtocol.writeRoleHandshake(
                    socket.getOutputStream(),
                    EncryptedPhotoStreamProtocol.SocketRole.RECEIVER,
                    transferId
                )
                EncryptedPhotoStreamProtocol.readReadyAck(socket.getInputStream())
                val bytes = EncryptedPhotoStreamProtocol.readEncryptedPhotoStream(
                    expectedPlaintextSize = originalPhoto.size.toLong(),
                    expectedSha256Hex = expectedSha,
                    transferId = transferId,
                    key = key,
                    inputStream = socket.getInputStream(),
                    onProgress = { progressEvents.add(it) }
                )
                // Server must still remain alive until receiver sends completion ACK
                assertFalse("Sender server must not close prematurely before completion ACK", server.isClosed)
                EncryptedPhotoStreamProtocol.writeTransferCompleteAck(socket.getOutputStream())
                bytes
            }
        }

        hostJob.await()
        val downloaded = clientJob.await()

        assertArrayEquals(originalPhoto, downloaded)
        assertEquals(1f, progressEvents.lastOrNull() ?: 0f, 0.001f)
        assertTrue("Direct ServerSocket must close immediately after transfer", server.isClosed)
    }

    @Test
    fun directPhotoSocketServer_receiverFallbackHostReceivesPushAndTearsDownCleanly() = runBlocking {
        val transferId = "ph_fallback_receiver_ap"
        val key = EncryptedPhotoStreamProtocol.generateTransferKey()
        val originalPhoto = ByteArray(96 * 1024) { ((it * 19 + 3) and 0xFF).toByte() }
        val expectedSha = EncryptedPhotoStreamProtocol.sha256Hex(originalPhoto)

        val server = DirectPhotoSocketServer(
            bindAddress = InetAddress.getByName("127.0.0.1"),
            acceptTimeoutMs = 5_000,
            socketReadTimeoutMs = 5_000
        )
        val port = server.localPort

        val receiverHostJob = async(Dispatchers.IO) {
            server.serveAsReceiverHost(
                transferId = transferId,
                expectedPlaintextSize = originalPhoto.size.toLong(),
                expectedSha256Hex = expectedSha,
                transferKey = key
            )
        }

        val senderClientJob = async(Dispatchers.IO) {
            Socket("127.0.0.1", port).use { socket ->
                EncryptedPhotoStreamProtocol.writeRoleHandshake(
                    socket.getOutputStream(),
                    EncryptedPhotoStreamProtocol.SocketRole.SENDER,
                    transferId
                )
                EncryptedPhotoStreamProtocol.readReadyAck(socket.getInputStream())
                EncryptedPhotoStreamProtocol.writeEncryptedPhotoStream(
                    plaintextBytes = originalPhoto,
                    transferId = transferId,
                    key = key,
                    outputStream = socket.getOutputStream()
                )
                assertTrue(
                    "Fallback receiver host must emit completion ACK after SHA-256 verification",
                    EncryptedPhotoStreamProtocol.awaitTransferCompleteAck(socket.getInputStream())
                )
            }
        }

        senderClientJob.await()
        val received = receiverHostJob.await()

        assertArrayEquals(originalPhoto, received)
        assertTrue("Fallback receiver ServerSocket must close immediately after transfer", server.isClosed)
    }

    @Test
    fun gattOutboundFrameValidation_acceptsPhotoPreviewAndWifiControlFramesAndRejectsUnframedPayloads() {
        val dummyPayload = EncryptedPayload(
            iv = ByteArray(12) { 0x11 },
            ciphertext = ByteArray(48) { 0x22 }
        )

        val validFrames = listOf(
            WireFrameCodec.encodeChatEncrypted(
                msgId = "msg_1",
                senderId = "dev_send",
                timestamp = 1_700_000_000_000L,
                payload = dummyPayload
            ),
            WireFrameCodec.encodePhotoPreview(
                transferId = "ph_preview_01",
                senderId = "dev_send",
                encryptedPreviewBundle = dummyPayload
            ),
            WireFrameCodec.encodePhotoDownloadRequest(
                transferId = "ph_preview_01",
                senderId = "dev_recv"
            ),
            WireFrameCodec.encodeWifiApReady(
                transferId = "ph_preview_01",
                senderId = "dev_send",
                ssid = "AndroidShare_4821",
                passphrase = "secret-pass",
                port = 49152,
                hostIp = "192.168.43.1",
                isReceiverFallbackHost = false
            ),
            WireFrameCodec.encodeWifiApFallback(
                transferId = "ph_preview_01",
                senderId = "dev_send",
                reason = "hotspot_busy"
            ),
            WireFrameCodec.encodePhotoTransferDone(
                transferId = "ph_preview_01",
                senderId = "dev_recv",
                success = true
            )
        )

        validFrames.forEach { frame ->
            assertTrue("WireFrameCodec should accept $frame", WireFrameCodec.isValidOutboundFrame(frame))
            assertTrue("GattSessionManager should accept $frame", GattSessionManager.isAllowedOutboundFrame(frame))
        }

        val invalidPayloads = listOf(
            "",
            "hello world",
            "UNKNOWN_FRAME|abc|123",
            "PHOTO_PREVIEW|only_two_parts",
            "PHOTO_DOWNLOAD_REQUEST|missing_sender",
            "WIFI_AP_READY|t1|s1|ssid|pass|not_a_port",
            "WIFI_AP_FALLBACK|only_two_parts",
            "PHOTO_TRANSFER_DONE|only_two_parts",
            "CHAT_ENCRYPTED|only_three|parts"
        )

        invalidPayloads.forEach { payload ->
            assertFalse("WireFrameCodec should reject '$payload'", WireFrameCodec.isValidOutboundFrame(payload))
            assertFalse("GattSessionManager should reject '$payload'", GattSessionManager.isAllowedOutboundFrame(payload))
        }
    }

    @Test
    fun failureStateMachine_transitionsCleanlyToFailedWithoutRetryLoopsAndIgnoresDuplicateFailures() {
        val sm = PhotoDownloadQueueStateMachine()
        sm.registerAvailable("ph_loop_guard", "dev_sender")

        // User taps Download once
        val start = sm.requestDownload("ph_loop_guard")
        assertTrue(start is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.StartNow)
        assertEquals("ph_loop_guard", sm.activeTransferId())

        // Duplicate requestDownload while active is ignored
        assertEquals(
            PhotoDownloadQueueStateMachine.DownloadRequestOutcome.Ignored,
            sm.requestDownload("ph_loop_guard")
        )

        // First WIFI_AP_FALLBACK triggers 1-on-1 role inversion fallback once
        val firstFallback = sm.handleSenderApFailureOrTimeout("ph_loop_guard", allowRoleInversionFallback = true, reason = "Wi-Fi hotspot permission missing")
        assertTrue(firstFallback is PhotoDownloadQueueStateMachine.SenderApFailureOutcome.StartReceiverFallbackHost)

        // Second failure transitions cleanly to FAILED and releases active slot (no auto-retry)
        val secondFailure = sm.handleSenderApFailureOrTimeout("ph_loop_guard", allowRoleInversionFallback = true, reason = "Wi-Fi permission required")
        assertTrue(secondFailure is PhotoDownloadQueueStateMachine.SenderApFailureOutcome.Failed)
        assertEquals(PhotoTransferState.FAILED, sm.getSnapshot("ph_loop_guard")?.state)
        assertNull(sm.activeTransferId())

        // Duplicate WIFI_AP_FALLBACK / PHOTO_TRANSFER_DONE(success=false) while FAILED are ignored
        assertEquals(
            PhotoDownloadQueueStateMachine.SenderApFailureOutcome.Ignored,
            sm.handleSenderApFailureOrTimeout("ph_loop_guard", allowRoleInversionFallback = true, reason = "duplicate")
        )
        assertNull(sm.markFailed("ph_loop_guard", "Transfer interrupted"))
        assertEquals(PhotoTransferState.FAILED, sm.getSnapshot("ph_loop_guard")?.state)
        assertEquals("Wi-Fi permission required", sm.getSnapshot("ph_loop_guard")?.statusDetail)

        // Only an explicit user-triggered Retry re-enters CONNECTING_WIFI
        val manualRetry = sm.requestDownload("ph_loop_guard")
        assertTrue(manualRetry is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.StartNow)
        assertEquals(PhotoTransferState.CONNECTING_WIFI, sm.getSnapshot("ph_loop_guard")?.state)
    }

    @Test
    fun peerDpCacheStore_enforcesThreeMegabyteCap_fallsBackGracefully_andInvalidatesOnDigestChange() {
        val tempDir = Files.createTempDirectory("layover_dp_cache_test").toFile()
        try {
            val store = PeerDpCacheStore(tempDir)

            // 1. Own full-res avatar: rejects > 3 MB, accepts <= 3 MB
            val oversizedAvatar = ByteArray(PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES.toInt() + 1) { 0x1A }
            assertNull("Own avatar > 3 MB must be rejected", store.saveOwnFullResAvatar(oversizedAvatar))

            // Graceful fallback when no full-res file exists yet (legacy profile)
            val legacyCompact = ByteArray(3_200) { (it * 5 and 0xFF).toByte() }
            val fallbackLoaded = store.loadOwnFullResAvatar(
                fullResFilePath = null,
                fallbackAvatarBytes = null,
                fallbackCompactBytes = legacyCompact
            )
            assertNotNull(fallbackLoaded)
            assertArrayEquals(legacyCompact, fallbackLoaded!!.bytes)
            assertEquals(EncryptedPhotoStreamProtocol.sha256Hex(legacyCompact), fallbackLoaded.sha256Hex)

            // Save own full-res PNG avatar and verify load + SHA-256 + MIME detection
            val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val fullResOwn = pngHeader + ByteArray(256 * 1024) { (it * 11 and 0xFF).toByte() }
            val savedOwn = store.saveOwnFullResAvatar(fullResOwn)
            assertNotNull(savedOwn)
            val (ownFile, ownSha) = savedOwn!!
            assertTrue(ownFile.exists())
            assertEquals(EncryptedPhotoStreamProtocol.sha256Hex(fullResOwn), ownSha)

            val loadedOwn = store.loadOwnFullResAvatar(
                fullResFilePath = ownFile.absolutePath,
                fallbackAvatarBytes = legacyCompact,
                fallbackCompactBytes = legacyCompact
            )
            assertNotNull(loadedOwn)
            assertArrayEquals(fullResOwn, loadedOwn!!.bytes)
            assertEquals("image/png", loadedOwn.mimeType)
            assertEquals(ownSha, loadedOwn.sha256Hex)

            // 2. Peer DP digest-keyed caching & automatic invalidation/pruning on DP update
            val bleThumbV1 = ByteArray(4_096) { (it and 0xFF).toByte() }
            val bleThumbV2 = ByteArray(4_096) { ((it + 99) and 0xFF).toByte() }
            val digestV1 = PeerDpCacheStore.computeAvatarDigest(bleThumbV1)!!
            val digestV2 = PeerDpCacheStore.computeAvatarDigest(bleThumbV2)!!
            assertFalse(digestV1 == digestV2)

            val fullResPeerV1 = ByteArray(180 * 1024) { (it * 3 and 0xFF).toByte() }
            val fullResPeerV2 = ByteArray(210 * 1024) { (it * 17 and 0xFF).toByte() }

            // Cache miss before first download
            assertNull(store.getCachedPeerDpFile(listOf("dev_peerA", "AA:BB:CC:DD:EE:FF"), digestV1))

            // Save V1 full-res DP -> cache hit across peer aliases
            val savedV1 = store.savePeerDp(
                canonicalPeerKey = "dev_peerA",
                peerAliases = listOf("dev_peerA", "AA:BB:CC:DD:EE:FF"),
                avatarDigest = digestV1,
                bytes = fullResPeerV1
            )
            assertNotNull(savedV1)
            assertTrue(savedV1!!.exists())
            val hitV1 = store.getCachedPeerDpFile(listOf("AA:BB:CC:DD:EE:FF", "dev_peerA"), digestV1)
            assertNotNull(hitV1)
            assertArrayEquals(fullResPeerV1, hitV1!!.readBytes())

            // When peer changes DP (digestV2), old cache misses immediately
            assertNull(store.getCachedPeerDpFile(listOf("dev_peerA"), digestV2))

            // Saving V2 full-res DP caches V2 and prunes stale V1 file from disk
            val savedV2 = store.savePeerDp(
                canonicalPeerKey = "dev_peerA",
                peerAliases = listOf("dev_peerA", "AA:BB:CC:DD:EE:FF"),
                avatarDigest = digestV2,
                bytes = fullResPeerV2
            )
            assertNotNull(savedV2)
            assertTrue(savedV2!!.exists())
            assertFalse("Stale V1 peer DP file should be pruned after V2 is cached", savedV1.exists())
            val hitV2 = store.getCachedPeerDpFile(listOf("dev_peerA"), digestV2)
            assertNotNull(hitV2)
            assertArrayEquals(fullResPeerV2, hitV2!!.readBytes())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun dpTransfer_metadataOnlyPreviewAndQueueSerializationWithChatPhotos() {
        val senderCrypto = SessionCryptoManager(forceEcOnly = true)
        val receiverCrypto = SessionCryptoManager(forceEcOnly = true)
        val senderPub = senderCrypto.ensureLocalKeyPair("EC")
        val receiverPub = receiverCrypto.ensureLocalKeyPair("EC")
        assertTrue(senderCrypto.establishRemoteKeyForPeer("dev_viewer", emptySet(), receiverPub, "EC").isUsableSession)
        assertTrue(receiverCrypto.establishRemoteKeyForPeer("dev_owner", emptySet(), senderPub, "EC").isUsableSession)

        // Metadata-only PHOTO_PREVIEW (empty previewBytes) for a dp_ transfer ID round-trips and passes GATT frame validation
        val dpTransferId = "dp_9f3a2c1b0e_a1b2c3"
        val dpKey = EncryptedPhotoStreamProtocol.generateTransferKey()
        val fullResDp = ByteArray(140 * 1024) { ((it * 23 + 7) and 0xFF).toByte() }
        val dpSha = EncryptedPhotoStreamProtocol.sha256Hex(fullResDp)

        val metaBundle = EncryptedPhotoStreamProtocol.encodePreviewBundle(
            fileSizeBytes = fullResDp.size.toLong(),
            mimeType = "image/jpeg",
            sha256Hex = dpSha,
            transferKey = dpKey,
            previewBytes = ByteArray(0)
        )
        val encryptedMeta = senderCrypto.encryptBytes(metaBundle, listOf("dev_viewer"))!!
        val wireMeta = WireFrameCodec.encodePhotoPreview(dpTransferId, "dev_owner", encryptedMeta)
        assertTrue(WireFrameCodec.isValidOutboundFrame(wireMeta))
        assertTrue(GattSessionManager.isAllowedOutboundFrame(wireMeta))

        val decodedMetaFrame = WireFrameCodec.decodeFrame(wireMeta) as WireFrame.PhotoPreview
        assertEquals(dpTransferId, decodedMetaFrame.transferId)
        val decryptedMeta = receiverCrypto.decryptBytes(
            EncryptedPayload(
                Base64.getDecoder().decode(decodedMetaFrame.ivBase64),
                Base64.getDecoder().decode(decodedMetaFrame.ciphertextBase64)
            ),
            listOf("dev_owner")
        )!!
        val decodedBundle = EncryptedPhotoStreamProtocol.decodePreviewBundle(decryptedMeta)!!
        assertEquals(fullResDp.size.toLong(), decodedBundle.fileSizeBytes)
        assertEquals(dpSha, decodedBundle.sha256Hex)
        assertEquals(0, decodedBundle.previewBytes.size)
        assertArrayEquals(dpKey.encoded, decodedBundle.transferKey.encoded)

        // Serialization: DP download queues cleanly behind an active chat photo and promotes upon completion
        val sm = PhotoDownloadQueueStateMachine()
        sm.registerAvailable("ph_chat_01", "dev_owner")
        sm.registerAvailable(dpTransferId, "dev_owner")

        val chatStart = sm.requestDownload("ph_chat_01")
        assertTrue(chatStart is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.StartNow)
        assertEquals("ph_chat_01", sm.activeTransferId())

        val dpQueued = sm.requestDownload(dpTransferId)
        assertTrue(dpQueued is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.Queued)
        assertEquals(PhotoTransferState.QUEUED, sm.getSnapshot(dpTransferId)?.state)

        // Completing the chat photo automatically promotes the queued full-res DP download
        val promoted = sm.markCompleted("ph_chat_01")
        assertNotNull(promoted)
        assertEquals(dpTransferId, promoted!!.transferId)
        assertEquals(dpTransferId, sm.activeTransferId())
        assertEquals(PhotoTransferState.CONNECTING_WIFI, sm.getSnapshot(dpTransferId)?.state)

        // Progress -> Completed cleanly releases active Wi-Fi slot
        sm.markDownloading(dpTransferId, 0.72f)
        assertEquals(PhotoTransferState.DOWNLOADING, sm.getSnapshot(dpTransferId)?.state)
        assertNull(sm.markCompleted(dpTransferId))
        assertEquals(PhotoTransferState.COMPLETED, sm.getSnapshot(dpTransferId)?.state)
        assertNull(sm.activeTransferId())
    }

    @Test
    fun downloadQueue_unblocksImmediatelyOnNoResponseTimeoutAndPromotesQueuedChatPhoto() {
        val sm = PhotoDownloadQueueStateMachine()
        // Initially registered with rotated MAC, then refreshed to canonical dev_ ID before download
        sm.registerAvailable("ph_25053aa06c", "AA:BB:CC:DD:EE:FF")
        sm.registerAvailable("ph_25053aa06c", "dev_sender")
        sm.registerAvailable("ph_5069eed0ab", "dev_sender")

        val firstStart = sm.requestDownload("ph_25053aa06c")
        assertTrue(firstStart is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.StartNow)
        assertEquals("dev_sender", (firstStart as PhotoDownloadQueueStateMachine.DownloadRequestOutcome.StartNow).senderPeerId)
        assertEquals("ph_25053aa06c", sm.activeTransferId())

        val secondQueued = sm.requestDownload("ph_5069eed0ab")
        assertTrue(secondQueued is PhotoDownloadQueueStateMachine.DownloadRequestOutcome.Queued)
        assertEquals(PhotoTransferState.QUEUED, sm.getSnapshot("ph_5069eed0ab")?.state)

        // When sender never responds over BLE after retries, fail cleanly WITHOUT entering a 55s fallback hotspot
        val timeoutOutcome = sm.handleSenderApFailureOrTimeout(
            transferId = "ph_25053aa06c",
            allowRoleInversionFallback = false,
            reason = "Sender did not respond"
        )
        assertTrue(timeoutOutcome is PhotoDownloadQueueStateMachine.SenderApFailureOutcome.Failed)
        val failed = timeoutOutcome as PhotoDownloadQueueStateMachine.SenderApFailureOutcome.Failed
        assertEquals("Sender did not respond", failed.reason)
        assertEquals(PhotoTransferState.FAILED, sm.getSnapshot("ph_25053aa06c")?.state)

        // Queued transfer ph_5069eed0ab must be promoted immediately
        val promoted = failed.nextDownload
        assertNotNull(promoted)
        assertEquals("ph_5069eed0ab", promoted!!.transferId)
        assertEquals("dev_sender", promoted.senderPeerId)
        assertEquals("ph_5069eed0ab", sm.activeTransferId())
        assertEquals(PhotoTransferState.CONNECTING_WIFI, sm.getSnapshot("ph_5069eed0ab")?.state)
        assertTrue(sm.queuedTransferIds().isEmpty())

        // Verify retry timing constants stay bounded so a dead request does not stall the queue
        val totalWaitBudgetMs =
            (PhotoTransferConfig.MAX_DOWNLOAD_REQUEST_ATTEMPTS - 1) * PhotoTransferConfig.DOWNLOAD_REQUEST_RETRY_INTERVAL_MS +
                PhotoTransferConfig.POST_FINAL_RETRY_WAIT_MS
        assertEquals(3, PhotoTransferConfig.MAX_DOWNLOAD_REQUEST_ATTEMPTS)
        assertEquals(15_000L, totalWaitBudgetMs)
    }
}
