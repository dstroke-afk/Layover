package com.intj.layover

import com.intj.layover.ble.GattSessionManager
import com.intj.layover.ble.LayoverBleConstants
import com.intj.layover.ble.LayoverBlePayloadEncoder
import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.GenderOption
import com.intj.layover.model.NearbyPeer
import com.intj.layover.model.PeerAvatar
import com.intj.layover.model.formatPeerNameWithBadge
import com.intj.layover.notifications.UnreadMessageTracker
import com.intj.layover.p2p.WireFrame
import com.intj.layover.p2p.WireFrameCodec
import com.intj.layover.ui.screens.canonicalDeduplicatePeers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerDisplayPayloadAndUnreadTest {

    @Test
    fun formatPeerNameWithBadge_formatsAgeAndGenderShort() {
        assertEquals("Balaji(29M)", formatPeerNameWithBadge("Balaji", 29, GenderOption.MALE))
        assertEquals("Alex(24F)", formatPeerNameWithBadge("Alex", 24, GenderOption.FEMALE))
    }

    @Test
    fun formatPeerNameWithBadge_formatsAgeOnlyWhenGenderMissing() {
        assertEquals("Alex(24)", formatPeerNameWithBadge("Alex", 24, null))
    }

    @Test
    fun formatPeerNameWithBadge_fallsBackToDisplayNameWhenAgeMissingOrInvalid() {
        assertEquals("Balaji", formatPeerNameWithBadge("Balaji", null, GenderOption.MALE))
        assertEquals("Balaji", formatPeerNameWithBadge("Balaji", 0, GenderOption.FEMALE))
        assertEquals("Balaji", formatPeerNameWithBadge("  Balaji  ", -5, null))
    }

    @Test
    fun blePayloadEncoder_roundTripsAgeAndGenderUsingFlagBitsOnly() {
        val maleBytes = LayoverBlePayloadEncoder.encodePayload(
            displayName = "Balaji",
            isVisible = true,
            handleOrDeviceId = "dev_balaji_01",
            age = 29,
            gender = GenderOption.MALE
        )
        val femaleBytes = LayoverBlePayloadEncoder.encodePayload(
            displayName = "Alex",
            isVisible = true,
            handleOrDeviceId = "dev_alex_02",
            age = 24,
            gender = GenderOption.FEMALE
        )
        val ageOnlyBytes = LayoverBlePayloadEncoder.encodePayload(
            displayName = "Balaji",
            isVisible = true,
            handleOrDeviceId = "dev_balaji_01",
            age = 29,
            gender = null
        )

        // Encoding gender uses flag bits in byte 0 and adds zero extra payload bytes
        assertEquals(ageOnlyBytes.size, maleBytes.size)
        assertTrue(maleBytes.size <= LayoverBleConstants.MAX_SERVICE_DATA_PAYLOAD_BYTES)
        assertTrue(
            LayoverBlePayloadEncoder.calculateScanResponseBytes(maleBytes.size) <=
                LayoverBleConstants.LEGACY_BLE_MAX_PACKET_BYTES
        )

        val decodedMale = LayoverBlePayloadEncoder.decodePayload(maleBytes)
        assertNotNull(decodedMale)
        assertEquals("Balaji", decodedMale!!.displayName)
        assertTrue(decodedMale.isVisible)
        assertEquals(29, decodedMale.age)
        assertEquals(GenderOption.MALE, decodedMale.gender)

        val decodedFemale = LayoverBlePayloadEncoder.decodePayload(femaleBytes)
        assertNotNull(decodedFemale)
        assertEquals("Alex", decodedFemale!!.displayName)
        assertTrue(decodedFemale.isVisible)
        assertEquals(24, decodedFemale.age)
        assertEquals(GenderOption.FEMALE, decodedFemale.gender)

        val decodedAgeOnly = LayoverBlePayloadEncoder.decodePayload(ageOnlyBytes)
        assertNotNull(decodedAgeOnly)
        assertEquals(29, decodedAgeOnly!!.age)
        assertNull(decodedAgeOnly.gender)
    }

    @Test
    fun blePayloadEncoder_preservesSevenCharUsernameWithAgeAndGenderWithin13Bytes() {
        val payload = LayoverBlePayloadEncoder.encodePayload(
            displayName = "balajie",
            isVisible = true,
            handleOrDeviceId = "dev_balajie",
            age = 27,
            gender = GenderOption.MALE
        )
        assertEquals(13, payload.size)
        assertTrue(payload.size <= LayoverBleConstants.MAX_SERVICE_DATA_PAYLOAD_BYTES)
        assertEquals(31, LayoverBlePayloadEncoder.calculateScanResponseBytes(payload.size))

        val decoded = LayoverBlePayloadEncoder.decodePayload(payload)
        assertNotNull(decoded)
        assertEquals("balajie", decoded!!.displayName)
        assertEquals(27, decoded.age)
        assertEquals(GenderOption.MALE, decoded.gender)
        assertTrue(decoded.isVisible)
    }

    @Test
    fun blePayloadEncoder_clampsEightCharNameToSevenBytesStrictlyWithin31ByteLegacyBudget() {
        assertEquals(21, LayoverBlePayloadEncoder.calculatePrimaryAdvertiseBytes(includeFlags = true))
        assertTrue(
            LayoverBlePayloadEncoder.calculatePrimaryAdvertiseBytes(includeFlags = true) <=
                LayoverBleConstants.LEGACY_BLE_MAX_PACKET_BYTES
        )

        // 8-char username + 1-byte age clamps to 7 bytes (13B payload -> 31B scan response)
        val payloadWithAge = LayoverBlePayloadEncoder.encodePayload(
            displayName = "balajieb",
            isVisible = true,
            handleOrDeviceId = "dev_balajieb",
            age = 29,
            gender = GenderOption.MALE
        )
        assertEquals(13, payloadWithAge.size)
        assertEquals(31, LayoverBlePayloadEncoder.calculateScanResponseBytes(payloadWithAge.size))

        val decoded = LayoverBlePayloadEncoder.decodePayload(payloadWithAge)
        assertNotNull(decoded)
        assertEquals("balajie", decoded!!.displayName)
        assertEquals(29, decoded.age)
        assertEquals(GenderOption.MALE, decoded.gender)
        assertTrue(decoded.isVisible)

        // Without age, MAX_NAME_BYTE_LENGTH = 7 still caps names cleanly at 7 bytes (12B payload -> 30B scan response)
        val payloadNoAge = LayoverBlePayloadEncoder.encodePayload(
            displayName = "balajieb",
            isVisible = false,
            handleOrDeviceId = "dev_balajieb",
            age = null,
            gender = GenderOption.FEMALE
        )
        assertEquals(12, payloadNoAge.size)
        assertEquals(30, LayoverBlePayloadEncoder.calculateScanResponseBytes(payloadNoAge.size))
        val decodedNoAge = LayoverBlePayloadEncoder.decodePayload(payloadNoAge)
        assertNotNull(decodedNoAge)
        assertEquals("balajie", decodedNoAge!!.displayName)
        assertNull(decodedNoAge.age)
        assertEquals(GenderOption.FEMALE, decodedNoAge.gender)
        assertFalse(decodedNoAge.isVisible)
    }

    @Test
    fun blePayloadEncoder_neverSplitsMultiByteUtf8AndSupportsCompactFallbackTier() {
        // "A😀BC" -> 1 + 4 + 1 + 1 = 7 bytes; fits cleanly with age (7B name budget)
        val exactEmoji = LayoverBlePayloadEncoder.encodePayload(
            displayName = "A\uD83D\uDE00BC",
            isVisible = true,
            handleOrDeviceId = "dev_utf8_1",
            age = 25,
            gender = GenderOption.FEMALE
        )
        assertTrue(exactEmoji.size <= LayoverBleConstants.MAX_SERVICE_DATA_PAYLOAD_BYTES)
        val decodedExact = LayoverBlePayloadEncoder.decodePayload(exactEmoji)
        assertNotNull(decodedExact)
        assertEquals("A\uD83D\uDE00BC", decodedExact!!.displayName)

        // "Alex😀" -> 4 + 4 = 8 bytes; 7B name budget must drop entire 4-byte emoji rather than leaving invalid UTF-8
        val truncatedEmoji = LayoverBlePayloadEncoder.encodePayload(
            displayName = "Alex\uD83D\uDE00",
            isVisible = true,
            handleOrDeviceId = "dev_utf8_2",
            age = 25,
            gender = GenderOption.FEMALE
        )
        assertTrue(truncatedEmoji.size <= LayoverBleConstants.MAX_SERVICE_DATA_PAYLOAD_BYTES)
        val decodedTruncated = LayoverBlePayloadEncoder.decodePayload(truncatedEmoji)
        assertNotNull(decodedTruncated)
        assertEquals("Alex", decodedTruncated!!.displayName)
        assertFalse(decodedTruncated.displayName.contains('\uFFFD'))

        // Compact fallback tier (10B payload -> 28B scan response) preserves flags, age, gender, and 4B name
        val compactPayload = LayoverBlePayloadEncoder.encodePayload(
            displayName = "Balaji",
            isVisible = true,
            handleOrDeviceId = "dev_compact",
            age = 29,
            gender = GenderOption.MALE,
            maxPayloadBytes = LayoverBleConstants.COMPACT_SERVICE_DATA_PAYLOAD_BYTES
        )
        assertEquals(10, compactPayload.size)
        assertEquals(28, LayoverBlePayloadEncoder.calculateScanResponseBytes(compactPayload.size))
        val decodedCompact = LayoverBlePayloadEncoder.decodePayload(compactPayload)
        assertNotNull(decodedCompact)
        assertEquals("Bala", decodedCompact!!.displayName)
        assertEquals(29, decodedCompact.age)
        assertEquals(GenderOption.MALE, decodedCompact.gender)
    }

    @Test
    fun blePayloadEncoder_decodesLegacyPayloadWithoutGenderFlagsCleanly() {
        // Legacy format: flags = 0x01 (visible) | 0x02 (hasAge), 4-byte instance ID, 1-byte age (31), ASCII "Sam"
        val legacyPayload = byteArrayOf(
            0x03,
            0x11, 0x22, 0x33, 0x44,
            31.toByte(),
            'S'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()
        )
        val decoded = LayoverBlePayloadEncoder.decodePayload(legacyPayload)
        assertNotNull(decoded)
        assertEquals("11223344", decoded!!.instanceIdHex)
        assertEquals("Sam", decoded.displayName)
        assertEquals(31, decoded.age)
        assertNull(decoded.gender)
    }

    @Test
    fun wireFrameConnectionRequest_preservesGenderAndDecodesLegacyFrames() {
        val encoded = WireFrameCodec.encodeConnectionRequest(
            senderId = "dev_alex_99",
            displayName = "Alex",
            gender = GenderOption.FEMALE,
            age = 24
        )
        val decoded = WireFrameCodec.decodeFrame(encoded) as? WireFrame.ConnectionRequest
        assertNotNull(decoded)
        assertEquals("dev_alex_99", decoded!!.senderId)
        assertEquals("Alex", decoded.displayName)
        assertEquals(GenderOption.FEMALE, decoded.gender)
        assertEquals(24, decoded.age)

        val legacyDecoded = WireFrameCodec.decodeFrame("CONNECTION_REQUEST|dev_old|Sam") as? WireFrame.ConnectionRequest
        assertNotNull(legacyDecoded)
        assertEquals("dev_old", legacyDecoded!!.senderId)
        assertEquals("Sam", legacyDecoded.displayName)
        assertNull(legacyDecoded.gender)
        assertNull(legacyDecoded.age)
    }

    @Test
    fun unreadTracker_incrementsWhenNotViewingChatAndResetsOnOpen() {
        PeerAliasRegistry.linkAliases(listOf("dev_unread_1", "AA:BB:CC:DD:EE:01", "UNREAD1"))
        val tracker = UnreadMessageTracker()

        // Background message increments unread count
        val afterFirst = tracker.recordIncomingMessage(
            senderCandidates = listOf("AA:BB:CC:DD:EE:01"),
            messageId = "msg_1",
            isAppInForeground = false,
            activeChatPeerId = null
        )
        assertEquals(1, UnreadMessageTracker.resolveUnreadCount(afterFirst, listOf("dev_unread_1")))

        // Duplicate messageId does not double-count
        val afterDup = tracker.recordIncomingMessage(
            senderCandidates = listOf("dev_unread_1"),
            messageId = "msg_1",
            isAppInForeground = false,
            activeChatPeerId = null
        )
        assertEquals(1, UnreadMessageTracker.resolveUnreadCount(afterDup, listOf("dev_unread_1")))

        // Message while viewing another peer's chat increments unread count
        val afterSecond = tracker.recordIncomingMessage(
            senderCandidates = listOf("UNREAD1"),
            messageId = "msg_2",
            isAppInForeground = true,
            activeChatPeerId = "dev_other_peer"
        )
        assertEquals(2, UnreadMessageTracker.resolveUnreadCount(afterSecond, listOf("dev_unread_1")))

        // Message while actively viewing this peer's chat (via alias) does NOT increment
        val afterActiveView = tracker.recordIncomingMessage(
            senderCandidates = listOf("dev_unread_1"),
            messageId = "msg_3",
            isAppInForeground = true,
            activeChatPeerId = "AA:BB:CC:DD:EE:01"
        )
        assertEquals(2, UnreadMessageTracker.resolveUnreadCount(afterActiveView, listOf("dev_unread_1")))

        // Opening the peer's chat clears unread count to 0 across all aliases
        val afterMarkRead = tracker.markChatRead(listOf("AA:BB:CC:DD:EE:01"))
        assertEquals(0, UnreadMessageTracker.resolveUnreadCount(afterMarkRead, listOf("dev_unread_1")))
        assertEquals(0, UnreadMessageTracker.resolveUnreadCount(afterMarkRead, listOf("UNREAD1")))
    }

    @Test
    fun canonicalDeduplicatePeers_mergesLinkedAliasesIntoSinglePeerWithMetadata() {
        PeerAliasRegistry.linkAliases(listOf("7375B46B", "48:D1:70:49:DE:E9", "dev_b08e8828", "59:23:E1:77:B8:7C"))
        val sampleAvatar = byteArrayOf(1, 2, 3, 4)

        val scanPeer = NearbyPeer(
            id = "7375B46B",
            displayName = "Balaji",
            handle = "7375B46B",
            gender = GenderOption.MALE,
            age = 29,
            status = ConnectionStatus.REQUESTED,
            avatar = PeerAvatar(peerId = "7375B46B", initials = "B", avatarBase64OrBytes = sampleAvatar)
        )
        val gattPeer = NearbyPeer(
            id = "dev_b08e8828",
            displayName = "Layover Peer (B87C)",
            handle = "dev_b08e8828",
            gender = null,
            age = null,
            status = ConnectionStatus.ACCEPTED
        )

        val deduped = canonicalDeduplicatePeers(listOf(scanPeer, gattPeer))
        assertEquals(1, deduped.size)
        val merged = deduped.single()
        assertEquals("dev_b08e8828", merged.id)
        assertEquals("dev_b08e8828", merged.handle)
        assertEquals("Balaji", merged.displayName)
        assertEquals(ConnectionStatus.ACCEPTED, merged.status)
        assertEquals(29, merged.age)
        assertEquals(GenderOption.MALE, merged.gender)
        assertArrayEquals(sampleAvatar, merged.avatar.avatarBase64OrBytes)
    }

    @Test
    fun canonicalDeduplicatePeers_bridgesDevIdAndDerivedBleInstanceIdAutomatically() {
        val devId = "dev_auto_bridge_99"
        val derivedBleInstanceId = LayoverBlePayloadEncoder.getInstanceIdHex(devId)

        val bleScanEntry = NearbyPeer(
            id = derivedBleInstanceId,
            displayName = "Jonatha",
            handle = derivedBleInstanceId,
            gender = GenderOption.MALE,
            age = 27,
            status = ConnectionStatus.NONE
        )
        val gattRequestEntry = NearbyPeer(
            id = devId,
            displayName = "Jonathan",
            handle = devId,
            gender = null,
            age = null,
            status = ConnectionStatus.ACCEPTED
        )

        // Even without manual MAC linking (e.g., BLE RPA rotation), derived instanceId bridges both entries
        val deduped = canonicalDeduplicatePeers(listOf(bleScanEntry, gattRequestEntry))
        assertEquals(1, deduped.size)
        val merged = deduped.single()
        assertEquals(devId, merged.id)
        assertEquals("Jonathan", merged.displayName)
        assertEquals(ConnectionStatus.ACCEPTED, merged.status)
        assertEquals(27, merged.age)
        assertEquals(GenderOption.MALE, merged.gender)
    }

    @Test
    fun gattChunking_splitsLarge7684BytePayloadIntoSafeMtuChunksAndReassemblesLosslessly() {
        val payloadSize = 7_684
        val packet = ByteArray(4 + payloadSize) { idx -> (idx % 251).toByte() }

        // Negotiated MTU = 512 -> safe chunk size = 480 (strictly <= 512 ATT attribute max)
        val highMtuChunkSize = GattSessionManager.computeSafeChunkSize(512)
        assertEquals(GattSessionManager.MAX_SAFE_GATT_CHUNK_BYTES, highMtuChunkSize)

        val chunks = GattSessionManager.chunkGattPacket(packet, highMtuChunkSize)
        assertEquals(17, chunks.size) // ceil(7688 / 480) = 17
        assertTrue(chunks.all { it.size in 1..GattSessionManager.MAX_SAFE_GATT_CHUNK_BYTES })

        val reassembled = ByteArray(chunks.sumOf { it.size })
        var cursor = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, reassembled, cursor, chunk.size)
            cursor += chunk.size
        }
        assertArrayEquals(packet, reassembled)

        // Default ATT MTU = 23 -> safe chunk size = 20
        val defaultMtuChunkSize = GattSessionManager.computeSafeChunkSize(GattSessionManager.DEFAULT_ATT_MTU)
        assertEquals(20, defaultMtuChunkSize)
        val smallPacket = ByteArray(49) { idx -> idx.toByte() }
        val defaultChunks = GattSessionManager.chunkGattPacket(smallPacket, defaultMtuChunkSize)
        assertEquals(3, defaultChunks.size) // 20 + 20 + 9
        assertEquals(20, defaultChunks[0].size)
        assertEquals(20, defaultChunks[1].size)
        assertEquals(9, defaultChunks[2].size)
    }
}
