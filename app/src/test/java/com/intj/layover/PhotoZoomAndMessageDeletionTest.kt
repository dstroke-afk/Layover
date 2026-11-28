package com.intj.layover

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.intj.layover.ble.GattSessionManager
import com.intj.layover.ble.LayoverAppRuntime
import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.crypto.SessionCryptoManager
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.PhotoAttachment
import com.intj.layover.model.PhotoTransferState
import com.intj.layover.p2p.WireFrame
import com.intj.layover.p2p.WireFrameCodec
import com.intj.layover.ui.components.PHOTO_VIEWER_DOUBLE_TAP_SCALE
import com.intj.layover.ui.components.PHOTO_VIEWER_MAX_SCALE
import com.intj.layover.ui.components.PHOTO_VIEWER_MIN_SCALE
import com.intj.layover.ui.components.clampZoomOffset
import com.intj.layover.ui.components.computeDoubleTapZoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PhotoZoomAndMessageDeletionTest {

    @Test
    fun deleteMessageWireFrame_roundTripsEncryptedMsgIdAndValidatesOutboundRules() {
        val senderCrypto = SessionCryptoManager(forceEcOnly = true)
        val receiverCrypto = SessionCryptoManager(forceEcOnly = true)

        val senderPub = senderCrypto.ensureLocalKeyPair("EC")
        val receiverPub = receiverCrypto.ensureLocalKeyPair("EC")
        assertTrue(senderCrypto.establishRemoteKeyForPeer("dev_peer_b", emptySet(), receiverPub, "EC").isUsableSession)
        assertTrue(receiverCrypto.establishRemoteKeyForPeer("dev_peer_a", emptySet(), senderPub, "EC").isUsableSession)

        val targetMsgId = "msg_1764317100555"
        val encryptedMsgId = senderCrypto.encryptText(targetMsgId, listOf("dev_peer_b"))
        assertNotNull(encryptedMsgId)

        val wireFrame = WireFrameCodec.encodeDeleteMessage(
            senderId = "dev_peer_a",
            encryptedMsgIdPayload = encryptedMsgId!!
        )
        assertTrue(WireFrameCodec.isValidOutboundFrame(wireFrame))
        assertTrue(GattSessionManager.isAllowedOutboundFrame(wireFrame))

        val decoded = WireFrameCodec.decodeFrame(wireFrame)
        assertTrue(decoded is WireFrame.DeleteMessage)
        val deleteFrame = decoded as WireFrame.DeleteMessage
        assertEquals("dev_peer_a", deleteFrame.senderId)

        val decryptedMsgId = receiverCrypto.decryptText(
            payload = EncryptedPayload(
                iv = Base64.getDecoder().decode(deleteFrame.ivBase64),
                ciphertext = Base64.getDecoder().decode(deleteFrame.ciphertextBase64)
            ),
            peerCandidates = listOf("dev_peer_a")
        )
        assertEquals(targetMsgId, decryptedMsgId)

        // Malformed DELETE_MESSAGE frames must be rejected
        listOf(
            "DELETE_MESSAGE|only_sender",
            "DELETE_MESSAGE|dev_peer_a|only_iv",
            "DELETE_MESSAGE||ivB64|cipherB64",
            "DELETE_MESSAGE|dev_peer_a||cipherB64",
            "DELETE_MESSAGE|dev_peer_a|ivB64|"
        ).forEach { invalid ->
            assertNull("Expected null decode for '$invalid'", WireFrameCodec.decodeFrame(invalid))
            assertFalse("Expected invalid outbound frame for '$invalid'", WireFrameCodec.isValidOutboundFrame(invalid))
            assertFalse("Expected GATT rejection for '$invalid'", GattSessionManager.isAllowedOutboundFrame(invalid))
        }
    }

    @Test
    fun removeMessageFromHistory_deletesAcrossPeerAliases_isolatesOtherPeers_andFindsOrphanedPhotos() {
        val photoPathToDelete = "/data/user/0/com.intj.layover/files/layover_photos/ph_abc123.jpg"
        val sharedPhotoPath = "/data/user/0/com.intj.layover/files/layover_photos/ph_shared.jpg"

        val msgTextAlice = ChatMessage(
            id = "msg_1001",
            senderId = "user",
            text = "Delete this text",
            isOutgoing = true,
            timestampMillis = 1_764_317_100_000L
        )
        val msgPhotoAlice = ChatMessage(
            id = "photo_ph_abc123",
            senderId = "user",
            text = "",
            isOutgoing = true,
            timestampMillis = 1_764_317_101_000L,
            photoAttachment = PhotoAttachment(
                transferId = "ph_abc123",
                fileSizeBytes = 120_000L,
                localFilePath = photoPathToDelete,
                state = PhotoTransferState.COMPLETED,
                progress = 1f
            )
        )
        val msgSharedAlice = ChatMessage(
            id = "photo_ph_shared_a",
            senderId = "dev_alice",
            text = "",
            isOutgoing = false,
            timestampMillis = 1_764_317_102_000L,
            photoAttachment = PhotoAttachment(
                transferId = "ph_shared_a",
                fileSizeBytes = 90_000L,
                localFilePath = sharedPhotoPath,
                state = PhotoTransferState.COMPLETED,
                progress = 1f
            )
        )
        val msgBobSameId = ChatMessage(
            id = "msg_1001",
            senderId = "dev_bob",
            text = "Bob's unrelated message",
            isOutgoing = false,
            timestampMillis = 1_764_317_103_000L
        )
        val msgBobSharedPhoto = ChatMessage(
            id = "photo_ph_shared_b",
            senderId = "dev_bob",
            text = "",
            isOutgoing = false,
            timestampMillis = 1_764_317_104_000L,
            photoAttachment = PhotoAttachment(
                transferId = "ph_shared_b",
                fileSizeBytes = 90_000L,
                localFilePath = sharedPhotoPath,
                state = PhotoTransferState.COMPLETED,
                progress = 1f
            )
        )

        val initialHistory = mapOf(
            "dev_alice" to listOf(msgTextAlice, msgPhotoAlice, msgSharedAlice),
            "AA:BB:CC:DD:EE:FF" to listOf(msgTextAlice, msgPhotoAlice, msgSharedAlice),
            "dev_bob" to listOf(msgBobSameId, msgBobSharedPhoto)
        )

        // 1. Delete text message across Alice's rotated aliases without touching Bob's bucket
        val textDelete = LayoverAppRuntime.removeMessageFromHistory(
            chatMessages = initialHistory,
            msgId = "msg_1001",
            peerAliases = setOf("dev_alice", "aa:bb:cc:dd:ee:ff")
        )
        assertEquals(1, textDelete.removedMessages.size)
        assertTrue(textDelete.orphanedPhotoFilePaths.isEmpty())
        assertEquals(
            listOf("photo_ph_abc123", "photo_ph_shared_a"),
            textDelete.updatedChatMessages["dev_alice"]?.map { it.id }
        )
        assertEquals(
            listOf("photo_ph_abc123", "photo_ph_shared_a"),
            textDelete.updatedChatMessages["AA:BB:CC:DD:EE:FF"]?.map { it.id }
        )
        assertEquals(
            listOf("msg_1001", "photo_ph_shared_b"),
            textDelete.updatedChatMessages["dev_bob"]?.map { it.id }
        )

        // 2. Delete Alice's unique photo message -> local photo file reported as orphaned
        val photoDelete = LayoverAppRuntime.removeMessageFromHistory(
            chatMessages = textDelete.updatedChatMessages,
            msgId = "photo_ph_abc123",
            peerAliases = setOf("dev_alice", "AA:BB:CC:DD:EE:FF")
        )
        assertEquals(setOf(photoPathToDelete), photoDelete.orphanedPhotoFilePaths)
        assertEquals(
            listOf("photo_ph_shared_a"),
            photoDelete.updatedChatMessages["dev_alice"]?.map { it.id }
        )

        // 3. Delete Alice's shared photo message -> NOT orphaned because Bob still references it
        val sharedDelete = LayoverAppRuntime.removeMessageFromHistory(
            chatMessages = photoDelete.updatedChatMessages,
            msgId = "photo_ph_shared_a",
            peerAliases = setOf("dev_alice", "AA:BB:CC:DD:EE:FF")
        )
        assertTrue(sharedDelete.orphanedPhotoFilePaths.isEmpty())
        assertFalse(sharedDelete.updatedChatMessages.containsKey("dev_alice"))
        assertFalse(sharedDelete.updatedChatMessages.containsKey("AA:BB:CC:DD:EE:FF"))

        // 4. Persisting & restoring history never resurrects deleted messages
        val serialized = LayoverAppRuntime.serializeChatHistory(sharedDelete.updatedChatMessages)
        val restored = LayoverAppRuntime.deserializeChatHistory(serialized)
        assertNull(restored["dev_alice"])
        assertEquals(listOf("msg_1001", "photo_ph_shared_b"), restored["dev_bob"]?.map { it.id })
    }

    @Test
    fun zoomablePhotoViewerMath_clampsScaleAndPanOffsetsAndTogglesDoubleTapCleanly() {
        val container = IntSize(width = 1000, height = 2000)

        // At 1f scale, offset always snaps to Offset.Zero
        assertEquals(
            Offset.Zero,
            clampZoomOffset(Offset(250f, -400f), PHOTO_VIEWER_MIN_SCALE, container)
        )

        // At 3f scale, maxX = (3-1)*1000/2 = 1000, maxY = (3-1)*2000/2 = 2000
        val clamped = clampZoomOffset(Offset(1800f, -2600f), 3f, container)
        assertEquals(1000f, clamped.x, 0.001f)
        assertEquals(-2000f, clamped.y, 0.001f)

        // Scale above MAX_SCALE (5f) clamps pan bounds to 5f max (maxX = 2000, maxY = 4000)
        val maxScaleClamped = clampZoomOffset(Offset(9999f, -9999f), 8f, container)
        assertEquals(((PHOTO_VIEWER_MAX_SCALE - 1f) * 1000f) / 2f, maxScaleClamped.x, 0.001f)
        assertEquals(-((PHOTO_VIEWER_MAX_SCALE - 1f) * 2000f) / 2f, maxScaleClamped.y, 0.001f)

        // Double-tap from 1f zooms to 2.5f and clamps offset within bounds
        val (zoomedScale, zoomedOffset) = computeDoubleTapZoomState(
            currentScale = PHOTO_VIEWER_MIN_SCALE,
            tapOffset = Offset(200f, 500f),
            containerSize = container
        )
        assertEquals(PHOTO_VIEWER_DOUBLE_TAP_SCALE, zoomedScale, 0.001f)
        val expectedMaxX = ((PHOTO_VIEWER_DOUBLE_TAP_SCALE - 1f) * container.width) / 2f
        val expectedMaxY = ((PHOTO_VIEWER_DOUBLE_TAP_SCALE - 1f) * container.height) / 2f
        assertTrue(zoomedOffset.x in -expectedMaxX..expectedMaxX)
        assertTrue(zoomedOffset.y in -expectedMaxY..expectedMaxY)

        // Double-tap while zoomed resets cleanly back to 1f and Offset.Zero
        val (resetScale, resetOffset) = computeDoubleTapZoomState(
            currentScale = zoomedScale,
            tapOffset = Offset(500f, 1000f),
            containerSize = container
        )
        assertEquals(PHOTO_VIEWER_MIN_SCALE, resetScale, 0.001f)
        assertEquals(Offset.Zero, resetOffset)
    }
}
