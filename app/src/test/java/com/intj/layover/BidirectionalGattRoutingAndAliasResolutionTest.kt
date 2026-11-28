package com.intj.layover

import com.intj.layover.ble.GattOutboundRoute
import com.intj.layover.ble.GattReadinessSnapshot
import com.intj.layover.ble.GattSessionManager
import com.intj.layover.ble.LayoverBlePayloadEncoder
import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.model.ChatMessage
import com.intj.layover.navigation.resolveChatMessagesForPeer
import com.intj.layover.p2p.RfcommConnectionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BidirectionalGattRoutingAndAliasResolutionTest {

    @Before
    fun setUp() {
        PeerAliasRegistry.clear()
    }

    @After
    fun tearDown() {
        PeerAliasRegistry.clear()
    }

    @Test
    fun selectOutboundRoutePlan_dualReadyEnablesBidirectionalFallbackInBothDirections() {
        val dualReady = GattReadinessSnapshot(
            hasActiveClient = true,
            clientConnected = true,
            clientServicesDiscovered = true,
            clientRxAvailable = true,
            clientDescriptorWriteInFlight = false,
            clientNotificationsReady = true,
            hasActiveServer = true,
            serverHasSubscribedClient = true,
            connectionState = RfcommConnectionState.CONNECTED
        )

        val clientPrimary = GattSessionManager.selectOutboundRoutePlan(dualReady, preferServer = false)
        assertEquals(GattOutboundRoute.CLIENT_WRITE, clientPrimary.primary)
        assertEquals(GattOutboundRoute.SERVER_NOTIFY, clientPrimary.fallback)

        val serverPrimary = GattSessionManager.selectOutboundRoutePlan(dualReady, preferServer = true)
        assertEquals(GattOutboundRoute.SERVER_NOTIFY, serverPrimary.primary)
        assertEquals(GattOutboundRoute.CLIENT_WRITE, serverPrimary.fallback)
    }

    @Test
    fun selectOutboundRoutePlan_unconnectedStalledClientFallsBackToSubscribedServer() {
        val stalledClientWithLiveServer = GattReadinessSnapshot(
            hasActiveClient = true,
            clientConnected = false,
            clientServicesDiscovered = false,
            clientRxAvailable = false,
            clientDescriptorWriteInFlight = false,
            clientNotificationsReady = false,
            hasActiveServer = true,
            serverHasSubscribedClient = true,
            connectionState = RfcommConnectionState.CONNECTED
        )

        val plan = GattSessionManager.selectOutboundRoutePlan(stalledClientWithLiveServer)
        assertEquals(GattOutboundRoute.SERVER_NOTIFY, plan.primary)
        assertEquals(GattOutboundRoute.NONE, plan.fallback)
        assertTrue(GattSessionManager.canFlushOutboundQueue(stalledClientWithLiveServer))
    }

    @Test
    fun selectServerNotificationTargets_prioritizesMatchingAliasesAndNewestSubscriptions() {
        val subscribed = listOf(
            "48:D1:70:49:DE:E9",
            "59:23:E1:77:B8:7C",
            "6C:91:AA:12:34:56",
            "59:23:e1:77:b8:7c"
        )

        val prioritized = GattSessionManager.selectServerNotificationTargets(
            subscribedAddresses = subscribed,
            preferredAliases = setOf("dev_b08e8828", "48:d1:70:49:de:e9")
        )

        // Matching alias placed first, remaining clients ordered newest-first and deduplicated case-insensitively
        assertEquals(
            listOf("48:D1:70:49:DE:E9", "59:23:e1:77:b8:7c", "6C:91:AA:12:34:56"),
            prioritized
        )
    }

    @Test
    fun peerAliasRegistry_resolvesDevIdInstanceIdAndRotatedRpaMacsWithoutCrossPeerPollution() {
        val devIdA = "dev_b08e8828"
        val derivedInstanceA = LayoverBlePayloadEncoder.getInstanceIdHex(devIdA)
        val oldMacA = "48:D1:70:49:DE:E9"
        val rotatedMacA = "6C:91:AA:12:34:56"

        // Initial BLE scan links instanceId + old RPA MAC + display name
        PeerAliasRegistry.linkAliases(listOf(derivedInstanceA, oldMacA, "Alex"))
        // GATT frame arrives with devIdA + oldMacA
        PeerAliasRegistry.linkAliases(listOf(devIdA, oldMacA))
        // Subsequent BLE scan observes RPA MAC rotation on the same instanceId
        PeerAliasRegistry.linkAliases(listOf(derivedInstanceA, oldMacA, rotatedMacA, "Alex"))

        // Second distinct peer happens to share display name "Alex"
        val devIdB = "dev_99aa1122"
        val macB = "AA:BB:CC:DD:EE:FF"
        PeerAliasRegistry.linkAliases(listOf(devIdB, macB, "Alex"))

        assertTrue(PeerAliasRegistry.areSamePeer(devIdA, derivedInstanceA))
        assertTrue(PeerAliasRegistry.areSamePeer(devIdA, oldMacA))
        assertTrue(PeerAliasRegistry.areSamePeer(devIdA, rotatedMacA))
        assertTrue(PeerAliasRegistry.areSamePeer(oldMacA, rotatedMacA))

        val resolvedMacsA = PeerAliasRegistry.resolveMacAddresses(devIdA)
        assertTrue(resolvedMacsA.any { it.equals(oldMacA, ignoreCase = true) })
        assertTrue(resolvedMacsA.any { it.equals(rotatedMacA, ignoreCase = true) })
        assertFalse(resolvedMacsA.any { it.equals(macB, ignoreCase = true) })

        // Distinct dev_xxx peers MUST remain isolated even if they share a display name
        assertFalse(PeerAliasRegistry.areSamePeer(devIdA, devIdB))
    }

    @Test
    fun resolveChatMessagesForPeer_mergesOutgoingAndIncomingMessagesAcrossRotatedAliasesAndCasing() {
        val baseTs = 1_764_317_100_000L
        val outgoing = ChatMessage(
            id = "msg_1",
            senderId = "user",
            text = "Hey from Phone A",
            isOutgoing = true,
            timestampMillis = baseTs
        )
        val incomingFromDevId = ChatMessage(
            id = "msg_2",
            senderId = "dev_b08e8828",
            text = "Reply from Phone B",
            isOutgoing = false,
            timestampMillis = baseTs + 10_000L
        )
        val incomingFromRotatedMac = ChatMessage(
            id = "msg_3",
            senderId = "6C:91:AA:12:34:56",
            text = "Second reply after RPA rotation",
            isOutgoing = false,
            timestampMillis = baseTs + 20_000L
        )

        val chatMap = mapOf(
            "7375b46b" to listOf(outgoing),
            "dev_b08e8828" to listOf(incomingFromDevId, outgoing),
            "6C:91:AA:12:34:56" to listOf(incomingFromRotatedMac)
        )

        val merged = resolveChatMessagesForPeer(
            chatMessages = chatMap,
            candidateAliases = setOf("7375B46B", "dev_b08e8828", "6c:91:aa:12:34:56")
        )

        assertEquals(3, merged.size)
        assertEquals(listOf("msg_1", "msg_2", "msg_3"), merged.map { it.id })
    }

    @Test
    fun shouldPreferServerRoute_prefersClientWriteWhenRecentInboundArrivedOnClientLink() {
        val dualReady = GattReadinessSnapshot(
            hasActiveClient = true,
            clientConnected = true,
            clientServicesDiscovered = true,
            clientRxAvailable = true,
            clientDescriptorWriteInFlight = false,
            clientNotificationsReady = true,
            hasActiveServer = true,
            serverHasSubscribedClient = true,
            connectionState = RfcommConnectionState.CONNECTED
        )

        // Earlier server inbound (t=1000), then PHOTO_PREVIEW arrived via GATT client notify (t=5000).
        // Reply (PHOTO_DOWNLOAD_REQUEST) MUST prefer ATT-acknowledged CLIENT_WRITE, not stale SERVER_NOTIFY.
        val preferServerAfterClientInbound = GattSessionManager.shouldPreferServerRoute(
            snapshot = dualReady,
            targetAliases = setOf("AA:BB:CC:DD:EE:FF"),
            lastServerInboundMac = "AA:BB:CC:DD:EE:FF",
            lastServerInboundMs = 1_000L,
            lastClientOutboundSuccessMs = 0L,
            lastClientInboundMs = 5_000L
        )
        assertFalse(preferServerAfterClientInbound)

        // When newer inbound traffic arrived on the GATT server link for that peer, prefer server.
        val preferServerAfterNewerServerInbound = GattSessionManager.shouldPreferServerRoute(
            snapshot = dualReady,
            targetAliases = setOf("AA:BB:CC:DD:EE:FF"),
            lastServerInboundMac = "AA:BB:CC:DD:EE:FF",
            lastServerInboundMs = 8_000L,
            lastClientOutboundSuccessMs = 2_000L,
            lastClientInboundMs = 5_000L
        )
        assertTrue(preferServerAfterNewerServerInbound)
    }

    @Test
    fun reassembleIncomingChunk_selfHealsOrphanedPartialBufferWhenNewWireFrameHeaderArrives() {
        val buffer = mutableListOf<Byte>()

        // Simulate a truncated multi-chunk PHOTO_PREVIEW (declared length 4000 bytes, only 64 bytes arrived)
        val previewPrefix = "PHOTO_PREVIEW|ph_prev|dev_a|iv123|cipher_truncated_chunk_payload".toByteArray(Charsets.UTF_8)
        val truncatedPacket = java.nio.ByteBuffer.allocate(4 + previewPrefix.size)
            .putInt(4_000)
            .put(previewPrefix)
            .array()

        val (firstFrames, firstReset) = GattSessionManager.reassembleIncomingChunk(
            buffer = buffer,
            data = truncatedPacket,
            lastAppendMs = 0L,
            nowMs = 10_000L
        )
        assertTrue(firstFrames.isEmpty())
        assertEquals(null, firstReset)
        assertTrue(buffer.isNotEmpty())

        // Subsequent PHOTO_DOWNLOAD_REQUEST arrives 200ms later on the same link
        val downloadReq = "PHOTO_DOWNLOAD_REQUEST|ph_25053aa06c|dev_recv"
        val reqBytes = downloadReq.toByteArray(Charsets.UTF_8)
        val reqPacket = java.nio.ByteBuffer.allocate(4 + reqBytes.size)
            .putInt(reqBytes.size)
            .put(reqBytes)
            .array()

        assertTrue(GattSessionManager.startsWithValidWireFrameHeader(reqPacket))

        val (secondFrames, secondReset) = GattSessionManager.reassembleIncomingChunk(
            buffer = buffer,
            data = reqPacket,
            lastAppendMs = 10_000L,
            nowMs = 10_200L
        )
        assertTrue("Expected orphaned partial buffer reset", secondReset?.startsWith("orphaned_partial_buffer_") == true)
        assertEquals(listOf(downloadReq), secondFrames)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun reassembleIncomingChunk_preservesNormalMultiChunkFramesAndClearsStaleBufferAfterTimeout() {
        val buffer = mutableListOf<Byte>()
        val fullFrame = "PHOTO_PREVIEW|ph_ok|dev_a|abcd1234|${"X".repeat(300)}"
        val payload = fullFrame.toByteArray(Charsets.UTF_8)
        val packet = java.nio.ByteBuffer.allocate(4 + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()

        val chunk1 = packet.copyOfRange(0, 120)
        val chunk2 = packet.copyOfRange(120, packet.size)

        val (frames1, reset1) = GattSessionManager.reassembleIncomingChunk(
            buffer = buffer,
            data = chunk1,
            lastAppendMs = 0L,
            nowMs = 1_000L
        )
        assertTrue(frames1.isEmpty())
        assertEquals(null, reset1)

        val (frames2, reset2) = GattSessionManager.reassembleIncomingChunk(
            buffer = buffer,
            data = chunk2,
            lastAppendMs = 1_000L,
            nowMs = 1_060L
        )
        assertEquals(null, reset2)
        assertEquals(listOf(fullFrame), frames2)
        assertTrue(buffer.isEmpty())

        // Now leave garbage partial bytes and let STALE_INCOMING_BUFFER_MS expire
        buffer.addAll(byteArrayOf(0, 0, 1, 0, 10, 20, 30).toList())
        val doneFrame = "PHOTO_TRANSFER_DONE|ph_ok|dev_b|1"
        val donePayload = doneFrame.toByteArray(Charsets.UTF_8)
        val donePacket = java.nio.ByteBuffer.allocate(4 + donePayload.size)
            .putInt(donePayload.size)
            .put(donePayload)
            .array()

        val (framesAfterStale, staleReset) = GattSessionManager.reassembleIncomingChunk(
            buffer = buffer,
            data = donePacket,
            lastAppendMs = 2_000L,
            nowMs = 2_000L + GattSessionManager.STALE_INCOMING_BUFFER_MS + 500L
        )
        assertTrue("Expected stale partial buffer reset", staleReset?.startsWith("stale_partial_buffer_") == true)
        assertEquals(listOf(doneFrame), framesAfterStale)
        assertTrue(buffer.isEmpty())
    }
}
