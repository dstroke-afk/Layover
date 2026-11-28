package com.intj.layover

import androidx.compose.ui.graphics.Color
import com.intj.layover.ble.GattSessionManager
import com.intj.layover.ble.LayoverAppRuntime
import com.intj.layover.ble.LayoverBlePayloadEncoder
import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.crypto.SessionCryptoManager
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.MessageDeliveryStatus
import com.intj.layover.model.PhotoAttachment
import com.intj.layover.model.PhotoTransferState
import com.intj.layover.p2p.WireFrame
import com.intj.layover.p2p.WireFrameCodec
import com.intj.layover.ui.screens.SEEN_TICK_GREEN
import com.intj.layover.ui.screens.outgoingDeliveryTickTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BlockSyncAndMessageTicksTest {

    @Test
    fun blocklistMatching_usesOnlyStableIdentifiers_andNeverCollidesOnSharedDisplayNames() {
        val peerADevId = "dev_block_sync_alpha_01"
        val peerAKey = LayoverBlePayloadEncoder.getInstanceIdHex(peerADevId)
        val peerAMac = "11:22:33:44:55:01"

        val peerBDevId = "dev_block_sync_beta_02"
        val peerBKey = LayoverBlePayloadEncoder.getInstanceIdHex(peerBDevId)
        val peerBMac = "AA:BB:CC:DD:EE:02"

        // Stable vs non-stable classification
        assertTrue(PeerAliasRegistry.isStablePeerIdentifier(peerADevId))
        assertTrue(PeerAliasRegistry.isStablePeerIdentifier(peerAKey))
        assertTrue(PeerAliasRegistry.isStablePeerIdentifier(peerAMac))
        assertTrue(PeerAliasRegistry.isStablePeerIdentifier("peer_synthetic_42"))

        assertFalse(PeerAliasRegistry.isStablePeerIdentifier("Alex"))
        assertFalse(PeerAliasRegistry.isStablePeerIdentifier("Name=Alex"))
        assertFalse(PeerAliasRegistry.isStablePeerIdentifier("Layover Peer (1234)"))
        assertFalse(PeerAliasRegistry.isStablePeerIdentifier("00000000"))
        assertFalse(PeerAliasRegistry.isStablePeerIdentifier("user"))

        // Even if linkAliases receives the same display name alongside each peer's stable IDs,
        // the two distinct peers must never merge or cross-match.
        PeerAliasRegistry.linkAliases(listOf(peerADevId, peerAKey, peerAMac, "Alex"))
        PeerAliasRegistry.linkAliases(listOf(peerBDevId, peerBKey, peerBMac, "Alex"))

        // Legacy/polluted blocklist containing Peer A's devId plus raw display names
        val blocklist = setOf(peerADevId, "Alex", "Name=Alex")

        // Peer A matches via devId, derived 8-hex key, or linked MAC
        assertTrue(PeerAliasRegistry.matchesBlocklist(listOf(peerADevId), blocklist))
        assertTrue(PeerAliasRegistry.matchesBlocklist(listOf(peerAKey), blocklist))
        assertTrue(PeerAliasRegistry.matchesBlocklist(listOf(peerAMac), blocklist))

        // Peer B (sharing display name "Alex") must NEVER match Peer A's blocklist
        assertFalse(PeerAliasRegistry.matchesBlocklist(listOf(peerBDevId, peerBKey, peerBMac, "Alex"), blocklist))
        assertFalse(PeerAliasRegistry.matchesBlocklist(listOf("Alex"), blocklist))
    }

    @Test
    fun peerBlockStateSync_updatesAndClearsBlockedByPeerIds_andValidatesWireFrame() {
        val peerDevId = "dev_blocker_gamma_03"
        val peerKey = LayoverBlePayloadEncoder.getInstanceIdHex(peerDevId)
        val peerMac = "22:33:44:55:66:03"
        val otherBlockerDevId = "dev_blocker_delta_04"

        PeerAliasRegistry.linkAliases(listOf(peerDevId, peerKey, peerMac))

        // Wire frame round-trip for PEER_BLOCK_STATE
        val blockFrame = WireFrameCodec.encodePeerBlockState(peerDevId, blocked = true)
        assertTrue(WireFrameCodec.isValidOutboundFrame(blockFrame))
        assertTrue(GattSessionManager.isAllowedOutboundFrame(blockFrame))
        val decodedBlock = WireFrameCodec.decodeFrame(blockFrame) as? WireFrame.PeerBlockState
        assertNotNull(decodedBlock)
        assertEquals(peerDevId, decodedBlock!!.senderId)
        assertTrue(decodedBlock.blocked)

        val unblockFrame = WireFrameCodec.encodePeerBlockState(peerDevId, blocked = false)
        assertTrue(WireFrameCodec.isValidOutboundFrame(unblockFrame))
        assertTrue(GattSessionManager.isAllowedOutboundFrame(unblockFrame))
        val decodedUnblock = WireFrameCodec.decodeFrame(unblockFrame) as? WireFrame.PeerBlockState
        assertNotNull(decodedUnblock)
        assertFalse(decodedUnblock!!.blocked)

        // Apply block=true: strips legacy display-name entries, keeps other blockers, adds all stable IDs for peer
        val afterBlocked = LayoverAppRuntime.applyPeerBlockStateUpdate(
            currentBlockedByPeerIds = setOf(otherBlockerDevId, "Alex", "Name=Alex"),
            peerId = peerDevId,
            additionalStableIds = listOf(peerMac, "Alex"),
            isBlockedByPeer = true
        )
        assertTrue(PeerAliasRegistry.matchesBlocklist(listOf(peerDevId), afterBlocked))
        assertTrue(PeerAliasRegistry.matchesBlocklist(listOf(peerKey), afterBlocked))
        assertTrue(PeerAliasRegistry.matchesBlocklist(listOf(peerMac), afterBlocked))
        assertTrue(PeerAliasRegistry.matchesBlocklist(listOf(otherBlockerDevId), afterBlocked))
        assertFalse(afterBlocked.contains("Alex"))
        assertFalse(afterBlocked.contains("Name=Alex"))

        // Apply block=false using only the rotated MAC or 8-hex key -> removes all aliases of that peer cleanly
        val afterUnblocked = LayoverAppRuntime.applyPeerBlockStateUpdate(
            currentBlockedByPeerIds = afterBlocked,
            peerId = peerKey,
            additionalStableIds = emptyList(),
            isBlockedByPeer = false
        )
        assertFalse(PeerAliasRegistry.matchesBlocklist(listOf(peerDevId, peerKey, peerMac), afterUnblocked))
        assertTrue(PeerAliasRegistry.matchesBlocklist(listOf(otherBlockerDevId), afterUnblocked))
    }

    @Test
    fun deliveryAndSeenAckWireFrames_roundTripEncryptedMsgIds_andRejectMalformedFrames() {
        val senderCrypto = SessionCryptoManager(forceEcOnly = true)
        val receiverCrypto = SessionCryptoManager(forceEcOnly = true)

        val senderPub = senderCrypto.ensureLocalKeyPair("EC")
        val receiverPub = receiverCrypto.ensureLocalKeyPair("EC")
        assertTrue(senderCrypto.establishRemoteKeyForPeer("dev_peer_rx", emptySet(), receiverPub, "EC").isUsableSession)
        assertTrue(receiverCrypto.establishRemoteKeyForPeer("dev_peer_tx", emptySet(), senderPub, "EC").isUsableSession)

        // Single MESSAGE_DELIVERED frame
        val deliveredPayload = senderCrypto.encryptText("msg_1764318000111", listOf("dev_peer_rx"))
        assertNotNull(deliveredPayload)
        val deliveredWire = WireFrameCodec.encodeMessageDelivered("dev_peer_tx", deliveredPayload!!)
        assertTrue(WireFrameCodec.isValidOutboundFrame(deliveredWire))
        assertTrue(GattSessionManager.isAllowedOutboundFrame(deliveredWire))

        val decodedDelivered = WireFrameCodec.decodeFrame(deliveredWire) as? WireFrame.MessageDelivered
        assertNotNull(decodedDelivered)
        assertEquals("dev_peer_tx", decodedDelivered!!.senderId)
        val decryptedDelivered = receiverCrypto.decryptText(
            payload = EncryptedPayload(
                iv = Base64.getDecoder().decode(decodedDelivered.ivBase64),
                ciphertext = Base64.getDecoder().decode(decodedDelivered.ciphertextBase64)
            ),
            peerCandidates = listOf("dev_peer_tx")
        )
        assertEquals("msg_1764318000111", decryptedDelivered)

        // Batched comma-separated MESSAGE_SEEN frame
        val seenBatch = listOf("msg_1764318000111", "photo_ph_99aabbccdd")
        val seenPayload = senderCrypto.encryptText(seenBatch.joinToString(","), listOf("dev_peer_rx"))
        assertNotNull(seenPayload)
        val seenWire = WireFrameCodec.encodeMessageSeen("dev_peer_tx", seenPayload!!)
        assertTrue(WireFrameCodec.isValidOutboundFrame(seenWire))
        assertTrue(GattSessionManager.isAllowedOutboundFrame(seenWire))

        val decodedSeen = WireFrameCodec.decodeFrame(seenWire) as? WireFrame.MessageSeen
        assertNotNull(decodedSeen)
        assertEquals("dev_peer_tx", decodedSeen!!.senderId)
        val decryptedSeen = receiverCrypto.decryptText(
            payload = EncryptedPayload(
                iv = Base64.getDecoder().decode(decodedSeen.ivBase64),
                ciphertext = Base64.getDecoder().decode(decodedSeen.ciphertextBase64)
            ),
            peerCandidates = listOf("dev_peer_tx")
        )
        assertEquals(seenBatch, decryptedSeen?.split(","))

        // Malformed frames must be rejected
        listOf(
            "MESSAGE_DELIVERED|only_sender",
            "MESSAGE_DELIVERED|dev_peer_tx|only_iv",
            "MESSAGE_DELIVERED||iv|cipher",
            "MESSAGE_DELIVERED|dev_peer_tx||cipher",
            "MESSAGE_DELIVERED|dev_peer_tx|iv|",
            "MESSAGE_SEEN|only_sender",
            "MESSAGE_SEEN|dev_peer_tx|only_iv",
            "MESSAGE_SEEN||iv|cipher",
            "MESSAGE_SEEN|dev_peer_tx||cipher",
            "MESSAGE_SEEN|dev_peer_tx|iv|"
        ).forEach { invalid ->
            assertNull("Expected null decode for '$invalid'", WireFrameCodec.decodeFrame(invalid))
            assertFalse("Expected invalid outbound frame for '$invalid'", WireFrameCodec.isValidOutboundFrame(invalid))
            assertFalse("Expected GATT rejection for '$invalid'", GattSessionManager.isAllowedOutboundFrame(invalid))
        }
    }

    @Test
    fun messageDeliveryStatusTransitions_advanceMonotonicallyAcrossAliases_andPersistCleanly() {
        val outgoingText = ChatMessage(
            id = "msg_100",
            senderId = "user",
            text = "Hello there",
            isOutgoing = true,
            timestampMillis = 1_764_318_000_000L,
            deliveryStatus = MessageDeliveryStatus.SENDING
        )
        val outgoingPhoto = ChatMessage(
            id = "photo_ph_1234567890",
            senderId = "user",
            text = "",
            isOutgoing = true,
            timestampMillis = 1_764_318_001_000L,
            deliveryStatus = MessageDeliveryStatus.SENDING,
            photoAttachment = PhotoAttachment(
                transferId = "ph_1234567890",
                fileSizeBytes = 250_000L,
                state = PhotoTransferState.AVAILABLE
            )
        )
        val incomingText = ChatMessage(
            id = "msg_200",
            senderId = "dev_peer_z",
            text = "Hey!",
            isOutgoing = false,
            timestampMillis = 1_764_318_002_000L,
            deliveryStatus = MessageDeliveryStatus.DELIVERED
        )

        val initial = mapOf(
            "dev_peer_z" to listOf(outgoingText, outgoingPhoto, incomingText),
            "33:44:55:66:77:88" to listOf(outgoingText, outgoingPhoto, incomingText)
        )

        // 1. SENDING -> SENT on BLE write/notify success
        val (afterSent, sentModified) = LayoverAppRuntime.applyMessageDeliveryStatusUpdate(
            chatMessages = initial,
            msgIds = listOf("msg_100", "photo_ph_1234567890"),
            newStatus = MessageDeliveryStatus.SENT,
            eventTimestampMillis = 1_764_318_005_000L
        )
        assertTrue(sentModified)
        assertEquals(MessageDeliveryStatus.SENT, afterSent["dev_peer_z"]!![0].deliveryStatus)
        assertEquals(MessageDeliveryStatus.SENT, afterSent["33:44:55:66:77:88"]!![1].deliveryStatus)
        assertNull(afterSent["dev_peer_z"]!![0].deliveredAtMillis)

        // 2. SENT -> DELIVERED on MESSAGE_DELIVERED ACK
        val (afterDelivered, deliveredModified) = LayoverAppRuntime.applyMessageDeliveryStatusUpdate(
            chatMessages = afterSent,
            msgIds = listOf("msg_100"),
            newStatus = MessageDeliveryStatus.DELIVERED,
            eventTimestampMillis = 1_764_318_010_000L
        )
        assertTrue(deliveredModified)
        val deliveredMsg = afterDelivered["dev_peer_z"]!![0]
        assertEquals(MessageDeliveryStatus.DELIVERED, deliveredMsg.deliveryStatus)
        assertEquals(1_764_318_010_000L, deliveredMsg.deliveredAtMillis)
        assertNull(deliveredMsg.seenAtMillis)

        // 3. DELIVERED -> SEEN (and SENT -> SEEN directly for photo) on MESSAGE_SEEN ACK
        val (afterSeen, seenModified) = LayoverAppRuntime.applyMessageDeliveryStatusUpdate(
            chatMessages = afterDelivered,
            msgIds = listOf("msg_100", "photo_ph_1234567890", "msg_200"),
            newStatus = MessageDeliveryStatus.SEEN,
            eventTimestampMillis = 1_764_318_020_000L
        )
        assertTrue(seenModified)
        val seenText = afterSeen["dev_peer_z"]!![0]
        assertEquals(MessageDeliveryStatus.SEEN, seenText.deliveryStatus)
        assertEquals(1_764_318_010_000L, seenText.deliveredAtMillis)
        assertEquals(1_764_318_020_000L, seenText.seenAtMillis)

        val seenPhoto = afterSeen["33:44:55:66:77:88"]!![1]
        assertEquals(MessageDeliveryStatus.SEEN, seenPhoto.deliveryStatus)
        assertEquals(1_764_318_020_000L, seenPhoto.deliveredAtMillis)
        assertEquals(1_764_318_020_000L, seenPhoto.seenAtMillis)

        // Incoming message must remain untouched
        assertEquals(MessageDeliveryStatus.DELIVERED, afterSeen["dev_peer_z"]!![2].deliveryStatus)

        // 4. Out-of-order late DELIVERED/SENT must NEVER regress SEEN
        val (afterLateDelivered, regressed) = LayoverAppRuntime.applyMessageDeliveryStatusUpdate(
            chatMessages = afterSeen,
            msgIds = listOf("msg_100"),
            newStatus = MessageDeliveryStatus.DELIVERED,
            eventTimestampMillis = 1_764_318_030_000L
        )
        assertFalse(regressed)
        assertEquals(MessageDeliveryStatus.SEEN, afterLateDelivered["dev_peer_z"]!![0].deliveryStatus)

        // 5. Persistence round-trip preserves deliveryStatus and timestamps
        val serialized = LayoverAppRuntime.serializeChatHistory(afterSeen)
        val restored = LayoverAppRuntime.deserializeChatHistory(serialized)
        val restoredText = restored["dev_peer_z"]!!.first { it.id == "msg_100" }
        assertEquals(MessageDeliveryStatus.SEEN, restoredText.deliveryStatus)
        assertEquals(1_764_318_010_000L, restoredText.deliveredAtMillis)
        assertEquals(1_764_318_020_000L, restoredText.seenAtMillis)

        val restoredPhoto = restored["dev_peer_z"]!!.first { it.id == "photo_ph_1234567890" }
        assertEquals(MessageDeliveryStatus.SEEN, restoredPhoto.deliveryStatus)
        assertEquals(1_764_318_020_000L, restoredPhoto.seenAtMillis)

        // 6. Legacy 7-field history line deserializes safely with default SENT for outgoing
        val legacyLine = listOf(
            Base64.getEncoder().encodeToString("dev_legacy_peer".toByteArray()),
            Base64.getEncoder().encodeToString("msg_legacy".toByteArray()),
            Base64.getEncoder().encodeToString("user".toByteArray()),
            "1764318000000",
            "1",
            Base64.getEncoder().encodeToString("Old msg".toByteArray()),
            ""
        ).joinToString("|")
        val legacyRestored = LayoverAppRuntime.deserializeChatHistory(legacyLine)
        val legacyMsg = legacyRestored["dev_legacy_peer"]!!.single()
        assertEquals(MessageDeliveryStatus.SENT, legacyMsg.deliveryStatus)
        assertNull(legacyMsg.deliveredAtMillis)
        assertNull(legacyMsg.seenAtMillis)
    }

    @Test
    fun outgoingDeliveryTickTint_usesGreenForSeen_andWhiteForSentAndDelivered() {
        assertEquals(Color.White, outgoingDeliveryTickTint(MessageDeliveryStatus.SENT))
        assertEquals(Color.White, outgoingDeliveryTickTint(MessageDeliveryStatus.DELIVERED))
        assertEquals(SEEN_TICK_GREEN, outgoingDeliveryTickTint(MessageDeliveryStatus.SEEN))
        assertFalse(
            "SEEN tick tint must be green and distinct from white SENT/DELIVERED ticks",
            outgoingDeliveryTickTint(MessageDeliveryStatus.SEEN) == Color.White
        )
    }
}
