package com.intj.layover

import com.intj.layover.ble.GattReadinessSnapshot
import com.intj.layover.ble.GattSessionManager
import com.intj.layover.crypto.KeyEstablishStatus
import com.intj.layover.crypto.SessionCryptoManager
import com.intj.layover.p2p.RfcommConnectionState
import com.intj.layover.p2p.WireFrame
import com.intj.layover.p2p.WireFrameCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedDeque

class E2eeSessionAndGattReadinessTest {

    @Test
    fun sessionCryptoManager_roundTripEncryptionAndMatchingFingerprints_preferredAlgorithm() {
        val alice = SessionCryptoManager()
        val bob = SessionCryptoManager()

        val alicePub = alice.ensureLocalKeyPair()
        val aliceAlgo = alice.getAlgorithmName()
        val bobPub = bob.ensureLocalKeyPair()
        val bobAlgo = bob.getAlgorithmName()

        val aliceOutcome = alice.establishRemoteKeyForPeer(
            peerId = "dev_bob_01",
            peerAliases = setOf("AA:BB:CC:DD:EE:01"),
            remotePublicKeyBase64 = bobPub,
            algorithmName = bobAlgo
        )
        val bobOutcome = bob.establishRemoteKeyForPeer(
            peerId = "dev_alice_01",
            peerAliases = setOf("AA:BB:CC:DD:EE:02"),
            remotePublicKeyBase64 = alicePub,
            algorithmName = aliceAlgo
        )

        assertEquals(KeyEstablishStatus.ESTABLISHED, aliceOutcome.status)
        assertEquals(KeyEstablishStatus.ESTABLISHED, bobOutcome.status)
        assertNotNull(aliceOutcome.fingerprint)
        assertEquals(aliceOutcome.fingerprint, bobOutcome.fingerprint)
        assertEquals(alice.sessionFingerprint, bob.sessionFingerprint)

        // Alice -> Bob
        val encryptedAtoB = alice.encryptText("Hello Bob!", setOf("dev_bob_01"))
        assertNotNull(encryptedAtoB)
        assertEquals("Hello Bob!", bob.decryptText(encryptedAtoB!!, setOf("AA:BB:CC:DD:EE:02")))

        // Bob -> Alice
        val encryptedBtoA = bob.encryptText("Hey Alice! \uD83D\uDE80", setOf("dev_alice_01"))
        assertNotNull(encryptedBtoA)
        assertEquals("Hey Alice! \uD83D\uDE80", alice.decryptText(encryptedBtoA!!, setOf("AA:BB:CC:DD:EE:01")))
    }

    @Test
    fun sessionCryptoManager_crossApiAlgorithmNegotiation_x25519PeerFallsBackToEcForApi31Peer() {
        val modernPeer = SessionCryptoManager(forceEcOnly = false)
        val legacyEcPeer = SessionCryptoManager(forceEcOnly = true)

        val modernPub = modernPeer.ensureLocalKeyPair()
        val modernAlgo = modernPeer.getAlgorithmName()

        // Legacy EC-only peer receives X25519 (if JVM supports X25519) -> returns ALGORITHM_UNSUPPORTED with EC fallback key
        val legacyAttempt = legacyEcPeer.establishRemoteKeyForPeer(
            peerId = "dev_modern",
            remotePublicKeyBase64 = modernPub,
            algorithmName = modernAlgo
        )

        if (modernAlgo.equals("X25519", ignoreCase = true)) {
            assertEquals(KeyEstablishStatus.ALGORITHM_UNSUPPORTED, legacyAttempt.status)
            assertEquals("EC", legacyAttempt.localAlgorithm)
            assertNotNull(legacyAttempt.localPublicKeyBase64)

            // Modern peer receives EC fallback key from legacy peer -> switches to EC and replies with its EC key
            val modernOutcome = modernPeer.establishRemoteKeyForPeer(
                peerId = "dev_legacy",
                remotePublicKeyBase64 = legacyAttempt.localPublicKeyBase64!!,
                algorithmName = legacyAttempt.localAlgorithm!!
            )
            assertEquals(KeyEstablishStatus.ESTABLISHED, modernOutcome.status)
            assertEquals("EC", modernOutcome.localAlgorithm)
            assertNotNull(modernOutcome.localPublicKeyBase64)

            // Legacy peer receives modern peer's EC response -> completes handshake with identical fingerprint
            val legacyFinal = legacyEcPeer.establishRemoteKeyForPeer(
                peerId = "dev_modern",
                remotePublicKeyBase64 = modernOutcome.localPublicKeyBase64!!,
                algorithmName = modernOutcome.localAlgorithm!!
            )
            assertEquals(KeyEstablishStatus.ESTABLISHED, legacyFinal.status)
            assertEquals(modernOutcome.fingerprint, legacyFinal.fingerprint)

            val cipher = modernPeer.encryptText("Cross-API message", setOf("dev_legacy"))
            assertNotNull(cipher)
            assertEquals("Cross-API message", legacyEcPeer.decryptText(cipher!!, setOf("dev_modern")))
        }
    }

    @Test
    fun sessionCryptoManager_duplicateAndOutOfOrderKeyExchange_neverClobbersEstablishedSession() {
        val alice = SessionCryptoManager()
        val bob = SessionCryptoManager()
        val strayPeer = SessionCryptoManager()

        val alicePub = alice.ensureLocalKeyPair()
        val bobPub = bob.ensureLocalKeyPair()
        val strayPub = strayPeer.ensureLocalKeyPair()

        val initial = alice.establishRemoteKeyForPeer(
            peerId = "dev_bob",
            peerAliases = setOf("7375B46B", "48:D1:70:49:DE:E9"),
            remotePublicKeyBase64 = bobPub,
            algorithmName = bob.getAlgorithmName()
        )
        val bobInitial = bob.establishRemoteKeyForPeer(
            peerId = "dev_alice",
            remotePublicKeyBase64 = alicePub,
            algorithmName = alice.getAlgorithmName()
        )
        assertEquals(KeyEstablishStatus.ESTABLISHED, initial.status)
        assertEquals(initial.fingerprint, bobInitial.fingerprint)

        // Duplicate identical KEY_EXCHANGE is idempotent and preserves fingerprint
        val duplicate = alice.establishRemoteKeyForPeer(
            peerId = "7375B46B",
            peerAliases = setOf("dev_bob"),
            remotePublicKeyBase64 = bobPub,
            algorithmName = bob.getAlgorithmName(),
            allowOverwrite = false
        )
        assertEquals(KeyEstablishStatus.ALREADY_ESTABLISHED_SAME_KEY, duplicate.status)
        assertEquals(initial.fingerprint, duplicate.fingerprint)

        // Stray / out-of-order different KEY_EXCHANGE without explicit reset does NOT overwrite active session
        val strayAttempt = alice.establishRemoteKeyForPeer(
            peerId = "dev_bob",
            remotePublicKeyBase64 = strayPub,
            algorithmName = strayPeer.getAlgorithmName(),
            allowOverwrite = false
        )
        assertEquals(KeyEstablishStatus.IGNORED_EXISTING_SESSION, strayAttempt.status)
        assertEquals(initial.fingerprint, alice.getSessionFingerprintForPeer(setOf("dev_bob")))

        // Original Bob <-> Alice session still decrypts cleanly
        val msg = bob.encryptText("Still encrypted with original key", setOf("dev_alice"))!!
        assertEquals(
            "Still encrypted with original key",
            alice.decryptText(msg, setOf("48:D1:70:49:DE:E9"))
        )
    }

    @Test
    fun sessionCryptoManager_controlledResetUpdatesKeyAndRejectsSupersededOldKey() {
        val alice = SessionCryptoManager()
        val bobOld = SessionCryptoManager()
        val bobRestarted = SessionCryptoManager()

        val alicePub = alice.ensureLocalKeyPair()
        val bobOldPub = bobOld.ensureLocalKeyPair()
        val bobNewPub = bobRestarted.ensureLocalKeyPair()

        val oldOutcome = alice.establishRemoteKeyForPeer(
            peerId = "dev_bob",
            remotePublicKeyBase64 = bobOldPub,
            algorithmName = bobOld.getAlgorithmName()
        )
        assertEquals(KeyEstablishStatus.ESTABLISHED, oldOutcome.status)

        // Explicit reset (e.g., recovery re-handshake) replaces key cleanly
        val resetOutcome = alice.establishRemoteKeyForPeer(
            peerId = "dev_bob",
            remotePublicKeyBase64 = bobNewPub,
            algorithmName = bobRestarted.getAlgorithmName(),
            allowOverwrite = true
        )
        assertEquals(KeyEstablishStatus.ESTABLISHED, resetOutcome.status)
        assertNotEquals(oldOutcome.fingerprint, resetOutcome.fingerprint)

        val bobNewOutcome = bobRestarted.establishRemoteKeyForPeer(
            peerId = "dev_alice",
            remotePublicKeyBase64 = alicePub,
            algorithmName = alice.getAlgorithmName()
        )
        assertEquals(resetOutcome.fingerprint, bobNewOutcome.fingerprint)

        // Even after a disconnect refresh flag, an out-of-order delayed frame carrying bobOldPub is rejected
        alice.markPeerDisconnected(setOf("dev_bob"))
        val staleReplay = alice.establishRemoteKeyForPeer(
            peerId = "dev_bob",
            remotePublicKeyBase64 = bobOldPub,
            algorithmName = bobOld.getAlgorithmName(),
            allowOverwrite = false
        )
        assertEquals(KeyEstablishStatus.IGNORED_STALE_KEY, staleReplay.status)
        assertEquals(resetOutcome.fingerprint, alice.getSessionFingerprintForPeer(setOf("dev_bob")))

        val msg = bobRestarted.encryptText("Recovered session works", setOf("dev_alice"))!!
        assertEquals("Recovered session works", alice.decryptText(msg, setOf("dev_bob")))
    }

    @Test
    fun sessionCryptoManager_multiPeerSessionsStayIsolatedByPeerIdAndAliases() {
        val alice = SessionCryptoManager()
        val bob = SessionCryptoManager()
        val carol = SessionCryptoManager()

        val alicePub = alice.ensureLocalKeyPair()
        val bobPub = bob.ensureLocalKeyPair()
        val carolPub = carol.ensureLocalKeyPair()

        alice.establishRemoteKeyForPeer("dev_bob", setOf("11:22:33:44:55:01"), bobPub, bob.getAlgorithmName())
        bob.establishRemoteKeyForPeer("dev_alice", emptySet(), alicePub, alice.getAlgorithmName())

        alice.establishRemoteKeyForPeer("dev_carol", setOf("11:22:33:44:55:02"), carolPub, carol.getAlgorithmName())
        carol.establishRemoteKeyForPeer("dev_alice", emptySet(), alicePub, alice.getAlgorithmName())

        val bobCipher = bob.encryptText("From Bob", setOf("dev_alice"))!!
        val carolCipher = carol.encryptText("From Carol", setOf("dev_alice"))!!

        // Alice decrypts both peers accurately using their respective aliases
        assertEquals("From Bob", alice.decryptText(bobCipher, setOf("11:22:33:44:55:01")))
        assertEquals("From Carol", alice.decryptText(carolCipher, setOf("11:22:33:44:55:02")))
        // Cross-peer decryption fails cleanly without corrupting either session
        assertNull(alice.decryptText(bobCipher, setOf("dev_carol")))
        assertEquals("From Bob", alice.decryptText(bobCipher, setOf("dev_bob")))
    }

    @Test
    fun gattFlushReadiness_gatesClientAndServerStrictly() {
        // 1. Client active but still discovering services -> MUST NOT flush even if server was previously subscribed
        assertFalse(
            GattSessionManager.canFlushOutboundQueue(
                GattReadinessSnapshot(
                    hasActiveClient = true,
                    clientConnected = true,
                    clientServicesDiscovered = false,
                    clientRxAvailable = false,
                    clientDescriptorWriteInFlight = false,
                    clientNotificationsReady = false,
                    hasActiveServer = true,
                    serverHasSubscribedClient = true,
                    connectionState = RfcommConnectionState.CONNECTED
                )
            )
        )

        // 2. Client discovered services, RX missing -> MUST NOT flush
        assertFalse(
            GattSessionManager.canFlushOutboundQueue(
                GattReadinessSnapshot(
                    hasActiveClient = true,
                    clientConnected = true,
                    clientServicesDiscovered = true,
                    clientRxAvailable = false,
                    clientDescriptorWriteInFlight = false,
                    clientNotificationsReady = true,
                    hasActiveServer = false,
                    serverHasSubscribedClient = false,
                    connectionState = RfcommConnectionState.CONNECTED
                )
            )
        )

        // 3. Client descriptor write (0x2902) still in flight -> MUST NOT flush
        assertFalse(
            GattSessionManager.canFlushOutboundQueue(
                GattReadinessSnapshot(
                    hasActiveClient = true,
                    clientConnected = true,
                    clientServicesDiscovered = true,
                    clientRxAvailable = true,
                    clientDescriptorWriteInFlight = true,
                    clientNotificationsReady = false,
                    hasActiveServer = false,
                    serverHasSubscribedClient = false,
                    connectionState = RfcommConnectionState.CONNECTED
                )
            )
        )

        // 4. Client fully ready (connected + services discovered + RX non-null + notifications ready) -> MAY flush
        assertTrue(
            GattSessionManager.canFlushOutboundQueue(
                GattReadinessSnapshot(
                    hasActiveClient = true,
                    clientConnected = true,
                    clientServicesDiscovered = true,
                    clientRxAvailable = true,
                    clientDescriptorWriteInFlight = false,
                    clientNotificationsReady = true,
                    hasActiveServer = false,
                    serverHasSubscribedClient = false,
                    connectionState = RfcommConnectionState.CONNECTED
                )
            )
        )

        // 5. Server active with transient unsubscribed client (e.g. avatar probe) -> MUST NOT flush
        assertFalse(
            GattSessionManager.canFlushOutboundQueue(
                GattReadinessSnapshot(
                    hasActiveClient = false,
                    clientConnected = false,
                    clientServicesDiscovered = false,
                    clientRxAvailable = false,
                    clientDescriptorWriteInFlight = false,
                    clientNotificationsReady = false,
                    hasActiveServer = true,
                    serverHasSubscribedClient = false,
                    connectionState = RfcommConnectionState.CONNECTED
                )
            )
        )

        // 6. Server active with 0x2902 subscribed peer client -> MAY flush
        assertTrue(
            GattSessionManager.canFlushOutboundQueue(
                GattReadinessSnapshot(
                    hasActiveClient = false,
                    clientConnected = false,
                    clientServicesDiscovered = false,
                    clientRxAvailable = false,
                    clientDescriptorWriteInFlight = false,
                    clientNotificationsReady = false,
                    hasActiveServer = true,
                    serverHasSubscribedClient = true,
                    connectionState = RfcommConnectionState.CONNECTED
                )
            )
        )
    }

    @Test
    fun gattOutboundQueue_deduplicatesStaleKeyExchangeAndPrioritizesLatestFirst() {
        val queue = ConcurrentLinkedDeque<String>()
        GattSessionManager.enqueueOutboundFrame(queue, "CONNECTION_REQUEST|dev_a|Alice|FEMALE|25")
        GattSessionManager.enqueueOutboundFrame(queue, "KEY_EXCHANGE|dev_a|OLD_PUB_KEY|EC|0")
        GattSessionManager.enqueueOutboundFrame(queue, "PROFILE_SYNC|dev_a|Alice|FEMALE|25|")
        GattSessionManager.enqueueOutboundFrame(queue, "KEY_EXCHANGE|dev_a|NEW_PUB_KEY|EC|0|1")

        val ordered = queue.toList()
        assertEquals(3, ordered.size)
        assertEquals("KEY_EXCHANGE|dev_a|NEW_PUB_KEY|EC|0|1", ordered[0])
        assertEquals("CONNECTION_REQUEST|dev_a|Alice|FEMALE|25", ordered[1])
        assertEquals("PROFILE_SYNC|dev_a|Alice|FEMALE|25|", ordered[2])
    }

    @Test
    fun wireFrameCodec_roundTripsKeyExchangeResetFlagAndEncryptedChatEndToEnd() {
        val resetEncoded = WireFrameCodec.encodeKeyExchange(
            senderId = "dev_alice",
            publicKeyBase64 = "PUB_KEY_123",
            algorithm = "X25519",
            isResponse = false,
            isReset = true
        )
        val decodedReset = WireFrameCodec.decodeFrame(resetEncoded) as? WireFrame.KeyExchange
        assertNotNull(decodedReset)
        assertEquals("dev_alice", decodedReset!!.senderId)
        assertEquals("PUB_KEY_123", decodedReset.publicKeyBase64)
        assertEquals("X25519", decodedReset.algorithm)
        assertFalse(decodedReset.isResponse)
        assertTrue(decodedReset.isReset)

        val normalEncoded = WireFrameCodec.encodeKeyExchange(
            senderId = "dev_bob",
            publicKeyBase64 = "PUB_KEY_456",
            algorithm = "EC",
            isResponse = true,
            isReset = false
        )
        val decodedNormal = WireFrameCodec.decodeFrame(normalEncoded) as? WireFrame.KeyExchange
        assertNotNull(decodedNormal)
        assertTrue(decodedNormal!!.isResponse)
        assertFalse(decodedNormal.isReset)

        // Full WireFrameCodec + SessionCryptoManager encrypted chat round-trip
        val alice = SessionCryptoManager()
        val bob = SessionCryptoManager()
        val aPub = alice.ensureLocalKeyPair()
        val bPub = bob.ensureLocalKeyPair()
        alice.establishRemoteKeyForPeer("dev_bob", emptySet(), bPub, bob.getAlgorithmName())
        bob.establishRemoteKeyForPeer("dev_alice", emptySet(), aPub, alice.getAlgorithmName())

        val encrypted = alice.encryptText("End-to-end wire test!", setOf("dev_bob"))!!
        val wire = WireFrameCodec.encodeChatEncrypted("msg_1", "dev_alice", 123456L, encrypted)
        val decodedChat = WireFrameCodec.decodeFrame(wire) as? WireFrame.ChatEncrypted
        assertNotNull(decodedChat)

        val iv = Base64.getDecoder().decode(decodedChat!!.ivBase64)
        val ciphertext = Base64.getDecoder().decode(decodedChat.ciphertextBase64)
        val plaintext = bob.decryptText(
            com.intj.layover.crypto.EncryptedPayload(iv = iv, ciphertext = ciphertext),
            setOf(decodedChat.senderId)
        )
        assertEquals("End-to-end wire test!", plaintext)
    }
}
