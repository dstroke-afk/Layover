package com.intj.layover.notifications

import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationEligibilityEvaluatorTest {

    private lateinit var evaluator: NotificationEligibilityEvaluator

    @Before
    fun setUp() {
        evaluator = NotificationEligibilityEvaluator(requestBurstCooldownMs = 15_000L)
    }

    @Test
    fun incomingMessage_whenMinimizedFromConnectedPeer_allowsNotification() {
        val decision = evaluator.evaluateIncomingMessage(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_peer01", "Alex"),
            messageId = "msg_1",
            peerStatus = ConnectionStatus.ACCEPTED,
            acceptedPeerIds = setOf("dev_peer01"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            activeChatPeerId = null
        )

        assertTrue(decision.shouldNotify)
        assertEquals(NotificationDecisionReason.ALLOW, decision.reason)
    }

    @Test
    fun incomingMessage_whenForegroundOnDifferentChat_allowsNotification() {
        val decision = evaluator.evaluateIncomingMessage(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_peer01", "Alex"),
            messageId = "msg_2",
            peerStatus = ConnectionStatus.ACCEPTED,
            acceptedPeerIds = setOf("dev_peer01"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = true,
            activeChatPeerId = "dev_peer02"
        )

        assertTrue(decision.shouldNotify)
        assertEquals(NotificationDecisionReason.ALLOW, decision.reason)
    }

    @Test
    fun incomingMessage_whenActivelyViewingSameChatViaAlias_suppressesNotification() {
        PeerAliasRegistry.linkAliases(listOf("dev_alias_a", "AA:BB:CC:DD:EE:01", "Alice"))

        val decision = evaluator.evaluateIncomingMessage(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("AA:BB:CC:DD:EE:01"),
            messageId = "msg_3",
            peerStatus = ConnectionStatus.ACCEPTED,
            acceptedPeerIds = setOf("dev_alias_a"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = true,
            activeChatPeerId = "dev_alias_a"
        )

        assertFalse(decision.shouldNotify)
        assertEquals(NotificationDecisionReason.ACTIVE_CHAT_FOREGROUND, decision.reason)
    }

    @Test
    fun incomingMessage_whenPeerNotAccepted_suppressesNotification() {
        val decision = evaluator.evaluateIncomingMessage(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_stranger"),
            messageId = "msg_4",
            peerStatus = ConnectionStatus.REQUESTED,
            acceptedPeerIds = emptySet(),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            activeChatPeerId = null
        )

        assertFalse(decision.shouldNotify)
        assertEquals(NotificationDecisionReason.PEER_NOT_CONNECTED, decision.reason)
    }

    @Test
    fun incomingMessage_duplicateMessageId_suppressesSecondNotification() {
        val first = evaluator.evaluateIncomingMessage(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_peer01"),
            messageId = "msg_dup_1",
            peerStatus = ConnectionStatus.ACCEPTED,
            acceptedPeerIds = setOf("dev_peer01"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            activeChatPeerId = null
        )
        val second = evaluator.evaluateIncomingMessage(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_peer01"),
            messageId = "msg_dup_1",
            peerStatus = ConnectionStatus.ACCEPTED,
            acceptedPeerIds = setOf("dev_peer01"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            activeChatPeerId = null
        )

        assertTrue(first.shouldNotify)
        assertFalse(second.shouldNotify)
        assertEquals(NotificationDecisionReason.DUPLICATE_MESSAGE, second.reason)
    }

    @Test
    fun incomingMessage_whenSetupIncompleteOrBluetoothOffOrBlockedOrMuted_suppresses() {
        val setupIncomplete = evaluator.evaluateIncomingMessage(
            isSetupComplete = false,
            localDisplayName = "",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_peer01"),
            messageId = "msg_s1",
            peerStatus = ConnectionStatus.ACCEPTED,
            acceptedPeerIds = setOf("dev_peer01"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            activeChatPeerId = null
        )
        assertEquals(NotificationDecisionReason.SETUP_INCOMPLETE, setupIncomplete.reason)

        val btOff = evaluator.evaluateIncomingMessage(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = false,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_peer01"),
            messageId = "msg_s2",
            peerStatus = ConnectionStatus.ACCEPTED,
            acceptedPeerIds = setOf("dev_peer01"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            activeChatPeerId = null
        )
        assertEquals(NotificationDecisionReason.BLUETOOTH_OFF, btOff.reason)

        val blocked = evaluator.evaluateIncomingMessage(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_peer_blk"),
            messageId = "msg_s3",
            peerStatus = ConnectionStatus.ACCEPTED,
            acceptedPeerIds = setOf("dev_peer_blk"),
            blockedPeerIds = setOf("dev_peer_blk"),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            activeChatPeerId = null
        )
        assertEquals(NotificationDecisionReason.PEER_BLOCKED, blocked.reason)

        val blockedByRemote = evaluator.evaluateIncomingMessage(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_peer_blk_by"),
            messageId = "msg_s4",
            peerStatus = ConnectionStatus.ACCEPTED,
            acceptedPeerIds = setOf("dev_peer_blk_by"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = setOf("dev_peer_blk_by"),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            activeChatPeerId = null
        )
        assertEquals(NotificationDecisionReason.PEER_BLOCKED_BY_REMOTE, blockedByRemote.reason)

        val muted = evaluator.evaluateIncomingMessage(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_peer_muted"),
            messageId = "msg_s5",
            peerStatus = ConnectionStatus.ACCEPTED,
            acceptedPeerIds = setOf("dev_peer_muted"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = setOf("dev_peer_muted"),
            isAppInForeground = false,
            activeChatPeerId = null
        )
        assertEquals(NotificationDecisionReason.PEER_MUTED, muted.reason)
    }

    @Test
    fun connectionRequest_whenBackgrounded_notifiesAndDeduplicatesBurst() {
        val first = evaluator.evaluateConnectionRequest(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_req01", "Sarah"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            nowMs = 1_000L
        )
        val burstDuplicate = evaluator.evaluateConnectionRequest(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_req01", "Sarah"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            nowMs = 5_000L
        )
        val afterCooldown = evaluator.evaluateConnectionRequest(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_req01", "Sarah"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            nowMs = 20_000L
        )

        assertTrue(first.shouldNotify)
        assertFalse(burstDuplicate.shouldNotify)
        assertEquals(NotificationDecisionReason.DUPLICATE_REQUEST_BURST, burstDuplicate.reason)
        assertTrue(afterCooldown.shouldNotify)
    }

    @Test
    fun connectionRequest_whenInForegroundOrSetupIncompleteOrBluetoothOff_suppresses() {
        val foreground = evaluator.evaluateConnectionRequest(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_req02"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = true,
            nowMs = 1_000L
        )
        assertEquals(NotificationDecisionReason.APP_IN_FOREGROUND, foreground.reason)

        val setupIncomplete = evaluator.evaluateConnectionRequest(
            isSetupComplete = false,
            localDisplayName = "Balaji",
            isBluetoothEnabled = true,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_req02"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            nowMs = 1_000L
        )
        assertEquals(NotificationDecisionReason.SETUP_INCOMPLETE, setupIncomplete.reason)

        val btOff = evaluator.evaluateConnectionRequest(
            isSetupComplete = true,
            localDisplayName = "Balaji",
            isBluetoothEnabled = false,
            hasNotificationPermission = true,
            senderCandidates = listOf("dev_req02"),
            blockedPeerIds = emptySet(),
            blockedByPeerIds = emptySet(),
            mutedPeerIds = emptySet(),
            isAppInForeground = false,
            nowMs = 1_000L
        )
        assertEquals(NotificationDecisionReason.BLUETOOTH_OFF, btOff.reason)
    }
}
