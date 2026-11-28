package com.intj.layover

import com.intj.layover.ble.LayoverBlePayloadEncoder
import com.intj.layover.model.NearbyPeer
import com.intj.layover.model.PeerAvatar
import com.intj.layover.model.PeerDpDownloadState
import com.intj.layover.model.PhotoTransferState
import com.intj.layover.ui.components.shouldTriggerOnDemandPeerDpDownload
import com.intj.layover.ui.screens.canonicalDeduplicatePeers
import com.intj.layover.ui.screens.computeCroppedFullResDimension
import com.intj.layover.ui.screens.resolvePeopleAroundCroppedDpPath
import com.intj.layover.wifi.DirectPhotoSocketServer
import com.intj.layover.wifi.EncryptedPhotoStreamProtocol
import com.intj.layover.wifi.PeerDpCacheStore
import com.intj.layover.wifi.PhotoTransferConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.nio.file.Files

class CroppedDpAndPeopleAroundTapTest {

    @Test
    fun computeCroppedFullResDimension_preservesNativeCropResolutionAndBoundsCleanly() {
        // 2000x1500 landscape source at 1x zoom -> 1500x1500 native square crop
        assertEquals(
            1500,
            computeCroppedFullResDimension(srcWidth = 2000, srcHeight = 1500, viewportSizePx = 780f, zoom = 1f)
        )

        // 2000x1500 landscape source at 2x zoom -> 750px native crop (clamped to viewport floor 780)
        assertEquals(
            780,
            computeCroppedFullResDimension(srcWidth = 2000, srcHeight = 1500, viewportSizePx = 780f, zoom = 2f)
        )

        // Huge 4000x3000 source at 1x zoom -> capped at 2048px maxDim
        assertEquals(
            2048,
            computeCroppedFullResDimension(srcWidth = 4000, srcHeight = 3000, viewportSizePx = 780f, zoom = 1f)
        )

        // Small 320x320 source at 1x zoom -> stays 320px (no artificial upscaling)
        assertEquals(
            320,
            computeCroppedFullResDimension(srcWidth = 320, srcHeight = 320, viewportSizePx = 780f, zoom = 1f)
        )
    }

    @Test
    fun croppedDpPersistence_usesCroppedBytesAndDigest_replacesLegacyUncropped_andInvalidatesPeerCache() {
        val tempDir = Files.createTempDirectory("layover_cropped_dp_test").toFile()
        try {
            val store = PeerDpCacheStore(tempDir)

            val uncroppedSource = ByteArray(420 * 1024) { ((it * 7 + 3) and 0xFF).toByte() }
            val croppedFullRes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
                ByteArray(180 * 1024) { ((it * 13 + 19) and 0xFF).toByte() }
            val reCroppedFullRes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
                ByteArray(165 * 1024) { ((it * 29 + 5) and 0xFF).toByte() }

            // Even if an older uncropped file existed on disk, saving the cropped DP overwrites it
            val legacySaved = store.saveOwnFullResAvatar(uncroppedSource)
            assertNotNull(legacySaved)

            val croppedSaved = store.saveOwnFullResAvatar(croppedFullRes)
            assertNotNull(croppedSaved)
            val (croppedFile, croppedSha) = croppedSaved!!

            assertEquals(EncryptedPhotoStreamProtocol.sha256Hex(croppedFullRes), croppedSha)
            assertNotEquals(EncryptedPhotoStreamProtocol.sha256Hex(uncroppedSource), croppedSha)

            val loadedOwn = store.loadOwnFullResAvatar(
                fullResFilePath = croppedFile.absolutePath,
                fallbackAvatarBytes = null,
                fallbackCompactBytes = null
            )
            assertNotNull(loadedOwn)
            assertArrayEquals(croppedFullRes, loadedOwn!!.bytes)
            assertEquals(croppedSha, loadedOwn.sha256Hex)
            assertEquals("image/jpeg", loadedOwn.mimeType)

            // Exceeding the 3 MB cap is always rejected
            val oversizedCropped = ByteArray(PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES.toInt() + 10) { 0x42 }
            assertNull(store.saveOwnFullResAvatar(oversizedCropped))

            // BLE thumbnails derived from the first crop vs a re-crop produce distinct digests
            val bleThumbCrop1 = ByteArray(4_200) { ((it * 13 + 1) and 0xFF).toByte() }
            val bleThumbCrop2 = ByteArray(4_200) { ((it * 29 + 2) and 0xFF).toByte() }
            val digestCrop1 = PeerDpCacheStore.computeAvatarDigest(bleThumbCrop1)!!
            val digestCrop2 = PeerDpCacheStore.computeAvatarDigest(bleThumbCrop2)!!
            assertNotEquals(digestCrop1, digestCrop2)

            val phoneADevId = "dev_phone_a"
            val phoneABleKey = LayoverBlePayloadEncoder.getInstanceIdHex(phoneADevId)

            // Receiver caches cropped DP #1 under peer stable aliases (e.g. from Peer Profile)
            val cached1 = store.savePeerDp(
                canonicalPeerKey = phoneADevId,
                peerAliases = listOf(phoneADevId, phoneABleKey, "11:22:33:44:55:66"),
                avatarDigest = digestCrop1,
                bytes = croppedFullRes
            )
            assertNotNull(cached1)
            assertTrue(cached1!!.exists())

            // Later on People Around (even with only the 8-hex BLE key), lookup hits immediately
            assertArrayEquals(
                croppedFullRes,
                store.getCachedPeerDpFile(listOf(phoneABleKey), digestCrop1)!!.readBytes()
            )
            // Lookup by dev_ ID also hits immediately
            assertArrayEquals(
                croppedFullRes,
                store.getCachedPeerDpFile(listOf(phoneADevId), digestCrop1)!!.readBytes()
            )

            // Peer B with a different stable ID never collides with Phone A's cached DP
            assertNull(store.getCachedPeerDpFile(listOf("dev_phone_b", "DEADBEEF"), digestCrop1))

            // After Phone A re-crops its DP (digestCrop2), old cache misses and is pruned when crop #2 arrives
            assertNull(store.getCachedPeerDpFile(listOf(phoneADevId, phoneABleKey), digestCrop2))
            val cached2 = store.savePeerDp(
                canonicalPeerKey = phoneADevId,
                peerAliases = listOf(phoneADevId, phoneABleKey),
                avatarDigest = digestCrop2,
                bytes = reCroppedFullRes
            )
            assertNotNull(cached2)
            assertFalse("Stale cropped DP should be pruned once new crop is cached", cached1.exists())
            assertArrayEquals(
                reCroppedFullRes,
                store.getCachedPeerDpFile(listOf(phoneADevId), digestCrop2)!!.readBytes()
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun onDemandPeerDpTrigger_neverAutoStartsOnScroll_andTriggersOnlyOnTapWhenUncached() {
        val digest = "a1b2c3d4e5f60708"

        // Scrolling People Around (viewer closed) must NEVER start a Wi-Fi download
        assertFalse(
            shouldTriggerOnDemandPeerDpDownload(
                isViewerOpen = false,
                hasCachedFullResDp = false,
                isBlocked = false,
                isBlockedByPeer = false,
                avatarDigest = digest,
                currentDownloadState = null
            )
        )

        // Tapping a peer's DP whose cropped full-res DP is already cached must NOT trigger Wi-Fi
        assertFalse(
            shouldTriggerOnDemandPeerDpDownload(
                isViewerOpen = true,
                hasCachedFullResDp = true,
                isBlocked = false,
                isBlockedByPeer = false,
                avatarDigest = digest,
                currentDownloadState = null
            )
        )

        // Blocked / blocked-by peers or missing digest must NEVER trigger Wi-Fi
        assertFalse(
            shouldTriggerOnDemandPeerDpDownload(
                isViewerOpen = true,
                hasCachedFullResDp = false,
                isBlocked = true,
                isBlockedByPeer = false,
                avatarDigest = digest,
                currentDownloadState = null
            )
        )
        assertFalse(
            shouldTriggerOnDemandPeerDpDownload(
                isViewerOpen = true,
                hasCachedFullResDp = false,
                isBlocked = false,
                isBlockedByPeer = true,
                avatarDigest = digest,
                currentDownloadState = null
            )
        )
        assertFalse(
            shouldTriggerOnDemandPeerDpDownload(
                isViewerOpen = true,
                hasCachedFullResDp = false,
                isBlocked = false,
                isBlockedByPeer = false,
                avatarDigest = "   ",
                currentDownloadState = null
            )
        )

        // Tapping an uncached, unblocked peer DP triggers on null, AVAILABLE, or FAILED (on re-open)
        assertTrue(
            shouldTriggerOnDemandPeerDpDownload(
                isViewerOpen = true,
                hasCachedFullResDp = false,
                isBlocked = false,
                isBlockedByPeer = false,
                avatarDigest = digest,
                currentDownloadState = null
            )
        )
        assertTrue(
            shouldTriggerOnDemandPeerDpDownload(
                isViewerOpen = true,
                hasCachedFullResDp = false,
                isBlocked = false,
                isBlockedByPeer = false,
                avatarDigest = digest,
                currentDownloadState = PhotoTransferState.AVAILABLE
            )
        )
        assertTrue(
            shouldTriggerOnDemandPeerDpDownload(
                isViewerOpen = true,
                hasCachedFullResDp = false,
                isBlocked = false,
                isBlockedByPeer = false,
                avatarDigest = digest,
                currentDownloadState = PhotoTransferState.FAILED
            )
        )

        // In-flight or completed states must NOT emit duplicate requests
        listOf(
            PhotoTransferState.QUEUED,
            PhotoTransferState.CONNECTING_WIFI,
            PhotoTransferState.DOWNLOADING,
            PhotoTransferState.COMPLETED
        ).forEach { state ->
            assertFalse(
                "State $state must not trigger a duplicate download",
                shouldTriggerOnDemandPeerDpDownload(
                    isViewerOpen = true,
                    hasCachedFullResDp = false,
                    isBlocked = false,
                    isBlockedByPeer = false,
                    avatarDigest = digest,
                    currentDownloadState = state
                )
            )
        }
    }

    @Test
    fun directSocketCroppedDpTransfer_streamsCroppedBytes_verifiesSha256_andCachesForInstantReopen() = runBlocking {
        val senderDir = Files.createTempDirectory("layover_dp_sender").toFile()
        val receiverDir = Files.createTempDirectory("layover_dp_receiver").toFile()
        val server = DirectPhotoSocketServer()
        try {
            val senderStore = PeerDpCacheStore(senderDir)
            val receiverStore = PeerDpCacheStore(receiverDir)

            val croppedDpBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
                ByteArray(192 * 1024) { ((it * 31 + 17) and 0xFF).toByte() }
            val savedOwn = senderStore.saveOwnFullResAvatar(croppedDpBytes)!!
            val loadedOwn = senderStore.loadOwnFullResAvatar(savedOwn.first.absolutePath, null, null)!!

            val bleThumbDigest = PeerDpCacheStore.computeAvatarDigest(ByteArray(3_600) { (it and 0xFF).toByte() })!!
            val transferId = "dp_${bleThumbDigest}_abcdef"
            val transferKey = EncryptedPhotoStreamProtocol.generateTransferKey()

            val serverDeferred = async(Dispatchers.IO) {
                server.serveAsSenderHost(
                    transferId = transferId,
                    plaintextBytes = loadedOwn.bytes,
                    transferKey = transferKey
                )
            }

            val downloadedBytes = async(Dispatchers.IO) {
                Socket("127.0.0.1", server.localPort).use { socket ->
                    EncryptedPhotoStreamProtocol.writeRoleHandshake(
                        outputStream = socket.getOutputStream(),
                        role = EncryptedPhotoStreamProtocol.SocketRole.RECEIVER,
                        transferId = transferId
                    )
                    EncryptedPhotoStreamProtocol.readReadyAck(socket.getInputStream())
                    val received = EncryptedPhotoStreamProtocol.readEncryptedPhotoStream(
                        expectedPlaintextSize = loadedOwn.bytes.size.toLong(),
                        expectedSha256Hex = loadedOwn.sha256Hex,
                        transferId = transferId,
                        key = transferKey,
                        inputStream = socket.getInputStream()
                    )
                    EncryptedPhotoStreamProtocol.writeTransferCompleteAck(socket.getOutputStream())
                    received
                }
            }.await()

            serverDeferred.await()
            assertTrue("Sender socket server must close cleanly after completion ACK", server.isClosed)

            assertArrayEquals(croppedDpBytes, downloadedBytes)
            assertEquals(loadedOwn.sha256Hex, EncryptedPhotoStreamProtocol.sha256Hex(downloadedBytes))

            val senderDevId = "dev_sender_a"
            val senderBleKey = LayoverBlePayloadEncoder.getInstanceIdHex(senderDevId)

            // Cache on receiver side and verify instant hit on subsequent tap from People Around or Peer Profile
            val cachedFile = receiverStore.savePeerDp(
                canonicalPeerKey = senderDevId,
                peerAliases = listOf(senderDevId, senderBleKey),
                avatarDigest = bleThumbDigest,
                bytes = downloadedBytes
            )
            assertNotNull(cachedFile)
            val reopenHit = receiverStore.getCachedPeerDpFile(listOf(senderBleKey), bleThumbDigest)
            assertNotNull(reopenHit)
            assertArrayEquals(croppedDpBytes, reopenHit!!.readBytes())
        } finally {
            server.close()
            senderDir.deleteRecursively()
            receiverDir.deleteRecursively()
        }
    }

    @Test
    fun peerDpCacheStore_purgesLegacyUncroppedFilesAndNeverServesUncroppedLegacyPath() {
        val tempDir = Files.createTempDirectory("layover_legacy_dp_purge_test").toFile()
        try {
            val legacyUncroppedSelf = java.io.File(tempDir, "self_full_dp.bin").apply {
                writeBytes(ByteArray(300 * 1024) { 0x55 })
            }
            val legacyUncroppedPeer = java.io.File(tempDir, "dp_dev_peer_old_0123456789abcdef.jpg").apply {
                writeBytes(ByteArray(250 * 1024) { 0x66 })
            }
            assertTrue(legacyUncroppedSelf.exists())
            assertTrue(legacyUncroppedPeer.exists())

            val store = PeerDpCacheStore(tempDir)
            assertFalse("Legacy uncropped self_full_dp.bin must be purged on init", legacyUncroppedSelf.exists())
            assertFalse("Legacy uncropped dp_* peer file must be purged on init", legacyUncroppedPeer.exists())

            // Passing a legacy non-v2 path must be rejected and fall back to cropped avatar bytes
            val croppedFallback = ByteArray(16 * 1024) { (it and 0xFF).toByte() }
            val loaded = store.loadOwnFullResAvatar(
                fullResFilePath = legacyUncroppedSelf.absolutePath,
                fallbackAvatarBytes = croppedFallback,
                fallbackCompactBytes = null
            )
            assertNotNull(loaded)
            assertArrayEquals(croppedFallback, loaded!!.bytes)
            assertFalse(PeerDpCacheStore.isCroppedOwnFullResPath(legacyUncroppedSelf.absolutePath))

            // Saving cropped full-res DP writes versioned v2 file accepted by isCroppedOwnFullResPath
            val croppedV2 = ByteArray(120 * 1024) { ((it * 7) and 0xFF).toByte() }
            val saved = store.saveOwnFullResAvatar(croppedV2)
            assertNotNull(saved)
            assertTrue(PeerDpCacheStore.isCroppedOwnFullResPath(saved!!.first.absolutePath))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun peopleAroundCroppedDpResolution_upgradesRowOnCompletedTransfer_rejectsUncroppedAndStaleDigests() {
        val bleThumbV1 = ByteArray(3_200) { (it and 0xFF).toByte() }
        val bleThumbV2 = ByteArray(3_200) { ((it + 55) and 0xFF).toByte() }
        val digestV1 = PeerDpCacheStore.computeAvatarDigest(bleThumbV1)!!
        val digestV2 = PeerDpCacheStore.computeAvatarDigest(bleThumbV2)!!

        val validV2Path = "/data/user/0/com.intj.layover/files/layover_dp_cache/dp_v2_a1b2c3d4_${digestV1}.jpg"
        val legacyUncroppedPath = "/data/user/0/com.intj.layover/files/layover_dp_cache/dp_a1b2c3d4_${digestV1}.jpg"

        val baseAvatar = PeerAvatar(
            peerId = "dev_alice",
            initials = "AL",
            avatarBase64OrBytes = bleThumbV1
        )

        // 1. No cached cropped DP yet -> returns null (row falls back to BLE thumbnail)
        assertNull(
            resolvePeopleAroundCroppedDpPath(
                avatarDigest = digestV1,
                peerAvatar = baseAvatar,
                dpDownloadState = null,
                diskLookup = { null },
                fileExists = { true }
            )
        )

        // 2. Legacy uncropped path (non-dp_v2_) is strictly rejected
        assertNull(
            resolvePeopleAroundCroppedDpPath(
                avatarDigest = digestV1,
                peerAvatar = baseAvatar.copy(croppedFullResPath = legacyUncroppedPath, croppedFullResDigest = digestV1),
                dpDownloadState = PeerDpDownloadState(
                    peerId = "dev_alice",
                    avatarDigest = digestV1,
                    state = PhotoTransferState.COMPLETED,
                    localFilePath = legacyUncroppedPath
                ),
                diskLookup = { legacyUncroppedPath },
                fileExists = { true }
            )
        )

        // 3. Completed dp_v2_ download upgrades People Around row immediately
        assertEquals(
            validV2Path,
            resolvePeopleAroundCroppedDpPath(
                avatarDigest = digestV1,
                peerAvatar = baseAvatar,
                dpDownloadState = PeerDpDownloadState(
                    peerId = "dev_alice",
                    avatarDigest = digestV1,
                    state = PhotoTransferState.COMPLETED,
                    localFilePath = validV2Path
                ),
                diskLookup = { null },
                fileExists = { true }
            )
        )

        // 4. Hydrated PeerAvatar.croppedFullResPath resolves immediately when digest matches
        val hydratedAvatar = baseAvatar.copy(
            croppedFullResPath = validV2Path,
            croppedFullResDigest = digestV1
        )
        assertEquals(
            validV2Path,
            resolvePeopleAroundCroppedDpPath(
                avatarDigest = digestV1,
                peerAvatar = hydratedAvatar,
                dpDownloadState = null,
                diskLookup = { null },
                fileExists = { true }
            )
        )

        // 5. Digest change (peer re-cropped DP -> digestV2) invalidates old path cleanly
        assertNull(
            resolvePeopleAroundCroppedDpPath(
                avatarDigest = digestV2,
                peerAvatar = hydratedAvatar,
                dpDownloadState = PeerDpDownloadState(
                    peerId = "dev_alice",
                    avatarDigest = digestV1,
                    state = PhotoTransferState.COMPLETED,
                    localFilePath = validV2Path
                ),
                diskLookup = { null },
                fileExists = { true }
            )
        )

        // 6. canonicalDeduplicatePeers preserves matching croppedFullResPath and clears on digest change
        val existingPeer = NearbyPeer(
            id = "dev_alice",
            displayName = "Alice",
            avatar = hydratedAvatar
        )
        val sameDigestIncoming = NearbyPeer(
            id = "dev_alice",
            displayName = "Alice",
            avatar = PeerAvatar(peerId = "dev_alice", initials = "AL", avatarBase64OrBytes = bleThumbV1)
        )
        val mergedSame = canonicalDeduplicatePeers(listOf(existingPeer, sameDigestIncoming)).single()
        assertEquals(validV2Path, mergedSame.avatar.croppedFullResPath)
        assertEquals(digestV1, mergedSame.avatar.croppedFullResDigest)

        val newDigestIncoming = NearbyPeer(
            id = "dev_alice",
            displayName = "Alice",
            avatar = PeerAvatar(peerId = "dev_alice", initials = "AL", avatarBase64OrBytes = bleThumbV2)
        )
        val mergedChanged = canonicalDeduplicatePeers(listOf(existingPeer, newDigestIncoming)).single()
        assertNull(mergedChanged.avatar.croppedFullResPath)
        assertNull(mergedChanged.avatar.croppedFullResDigest)
    }
}
