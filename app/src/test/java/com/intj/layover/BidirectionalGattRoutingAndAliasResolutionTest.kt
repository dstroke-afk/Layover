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
}
