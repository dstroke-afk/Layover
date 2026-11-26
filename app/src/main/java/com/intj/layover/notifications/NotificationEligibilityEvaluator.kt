package com.intj.layover.notifications

import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.model.ConnectionStatus
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

enum class NotificationDecisionReason {
    ALLOW,
    SETUP_INCOMPLETE,
    BLUETOOTH_OFF,
    PERMISSION_MISSING,
    PEER_BLOCKED,
    PEER_BLOCKED_BY_REMOTE,
    PEER_MUTED,
    PEER_NOT_CONNECTED,
    ACTIVE_CHAT_FOREGROUND,
    APP_IN_FOREGROUND,
    DUPLICATE_MESSAGE,
    DUPLICATE_REQUEST_BURST
}

data class NotificationDecision(
    val shouldNotify: Boolean,
    val reason: NotificationDecisionReason
)

/**
 * Pure, testable decision engine for Layover message and connection request notifications.
 */
class NotificationEligibilityEvaluator(
    private val requestBurstCooldownMs: Long = 15_000L,
    private val maxTrackedMessageIds: Int = 500
) {
    private val notifiedMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val lastRequestNotificationMsByKey = ConcurrentHashMap<String, Long>()

    fun expandIdentifiers(rawCandidates: Collection<String?>): Set<String> {
        val expanded = linkedSetOf<String>()
        for (raw in rawCandidates) {
            if (raw.isNullOrBlank()) continue
            val trimmed = raw.trim()
            if (trimmed.equals("00000000", ignoreCase = true) ||
                trimmed.equals("user", ignoreCase = true) ||
                trimmed.equals("local", ignoreCase = true)
            ) {
                continue
            }
            expanded.add(trimmed)
            expanded.addAll(PeerAliasRegistry.resolveAliases(trimmed))
        }
        return expanded
    }

    private fun intersectsIgnoringCase(candidates: Set<String>, targetSet: Set<String>): Boolean {
        if (candidates.isEmpty() || targetSet.isEmpty()) return false
        val expandedTargets = expandIdentifiers(targetSet)
        return candidates.any { candidate ->
            expandedTargets.any { target -> candidate.equals(target, ignoreCase = true) }
        }
    }

    fun canonicalKey(rawCandidates: Collection<String?>, fallback: String): String {
        val expanded = expandIdentifiers(rawCandidates)
        return expanded.firstOrNull { it.startsWith("dev_", ignoreCase = true) }
            ?: expanded.firstOrNull()
            ?: fallback.trim().ifEmpty { "unknown_peer" }
    }

    fun evaluateIncomingMessage(
        isSetupComplete: Boolean,
        localDisplayName: String,
        isBluetoothEnabled: Boolean,
        hasNotificationPermission: Boolean,
        senderCandidates: Collection<String?>,
        messageId: String,
        peerStatus: ConnectionStatus?,
        acceptedPeerIds: Set<String>,
        blockedPeerIds: Set<String>,
        blockedByPeerIds: Set<String>,
        mutedPeerIds: Set<String>,
        isAppInForeground: Boolean,
        activeChatPeerId: String?
    ): NotificationDecision {
        if (!isSetupComplete || localDisplayName.isBlank()) {
            return NotificationDecision(false, NotificationDecisionReason.SETUP_INCOMPLETE)
        }
        if (!isBluetoothEnabled) {
            return NotificationDecision(false, NotificationDecisionReason.BLUETOOTH_OFF)
        }
        if (!hasNotificationPermission) {
            return NotificationDecision(false, NotificationDecisionReason.PERMISSION_MISSING)
        }

        val expandedSender = expandIdentifiers(senderCandidates)
        if (intersectsIgnoringCase(expandedSender, blockedPeerIds)) {
            return NotificationDecision(false, NotificationDecisionReason.PEER_BLOCKED)
        }
        if (intersectsIgnoringCase(expandedSender, blockedByPeerIds)) {
            return NotificationDecision(false, NotificationDecisionReason.PEER_BLOCKED_BY_REMOTE)
        }
        if (intersectsIgnoringCase(expandedSender, mutedPeerIds)) {
            return NotificationDecision(false, NotificationDecisionReason.PEER_MUTED)
        }

        if (peerStatus == ConnectionStatus.DECLINED) {
            return NotificationDecision(false, NotificationDecisionReason.PEER_NOT_CONNECTED)
        }

        val isConnectedPeer = peerStatus == ConnectionStatus.ACCEPTED ||
            intersectsIgnoringCase(expandedSender, acceptedPeerIds)
        if (!isConnectedPeer) {
            return NotificationDecision(false, NotificationDecisionReason.PEER_NOT_CONNECTED)
        }

        if (isAppInForeground && !activeChatPeerId.isNullOrBlank()) {
            val activeAliases = expandIdentifiers(listOf(activeChatPeerId))
            val viewingSameChat = expandedSender.any { sender ->
                activeAliases.any { active -> sender.equals(active, ignoreCase = true) }
            }
            if (viewingSameChat) {
                return NotificationDecision(false, NotificationDecisionReason.ACTIVE_CHAT_FOREGROUND)
            }
        }

        val cleanMsgId = messageId.trim()
        if (cleanMsgId.isNotEmpty()) {
            synchronized(notifiedMessageIds) {
                if (notifiedMessageIds.contains(cleanMsgId)) {
                    return NotificationDecision(false, NotificationDecisionReason.DUPLICATE_MESSAGE)
                }
                notifiedMessageIds.add(cleanMsgId)
                while (notifiedMessageIds.size > maxTrackedMessageIds) {
                    val oldest = notifiedMessageIds.firstOrNull() ?: break
                    notifiedMessageIds.remove(oldest)
                }
            }
        }

        return NotificationDecision(true, NotificationDecisionReason.ALLOW)
    }

    fun evaluateConnectionRequest(
        isSetupComplete: Boolean,
        localDisplayName: String,
        isBluetoothEnabled: Boolean,
        hasNotificationPermission: Boolean,
        senderCandidates: Collection<String?>,
        blockedPeerIds: Set<String>,
        blockedByPeerIds: Set<String>,
        mutedPeerIds: Set<String>,
        isAppInForeground: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): NotificationDecision {
        if (!isSetupComplete || localDisplayName.isBlank()) {
            return NotificationDecision(false, NotificationDecisionReason.SETUP_INCOMPLETE)
        }
        if (!isBluetoothEnabled) {
            return NotificationDecision(false, NotificationDecisionReason.BLUETOOTH_OFF)
        }
        if (!hasNotificationPermission) {
            return NotificationDecision(false, NotificationDecisionReason.PERMISSION_MISSING)
        }

        val expandedSender = expandIdentifiers(senderCandidates)
        if (intersectsIgnoringCase(expandedSender, blockedPeerIds)) {
            return NotificationDecision(false, NotificationDecisionReason.PEER_BLOCKED)
        }
        if (intersectsIgnoringCase(expandedSender, blockedByPeerIds)) {
            return NotificationDecision(false, NotificationDecisionReason.PEER_BLOCKED_BY_REMOTE)
        }
        if (intersectsIgnoringCase(expandedSender, mutedPeerIds)) {
            return NotificationDecision(false, NotificationDecisionReason.PEER_MUTED)
        }

        if (isAppInForeground) {
            return NotificationDecision(false, NotificationDecisionReason.APP_IN_FOREGROUND)
        }

        val key = canonicalKey(senderCandidates, fallback = "unknown_requester").lowercase()
        val lastNotified = lastRequestNotificationMsByKey[key]
        if (lastNotified != null && (nowMs - lastNotified) < requestBurstCooldownMs) {
            return NotificationDecision(false, NotificationDecisionReason.DUPLICATE_REQUEST_BURST)
        }

        lastRequestNotificationMsByKey[key] = nowMs
        return NotificationDecision(true, NotificationDecisionReason.ALLOW)
    }

    fun clearRequestDedupe(vararg candidates: String?) {
        val expanded = expandIdentifiers(candidates.toList())
        if (expanded.isEmpty()) return
        val canonical = canonicalKey(candidates.toList(), fallback = "").lowercase()
        if (canonical.isNotEmpty()) {
            lastRequestNotificationMsByKey.remove(canonical)
        }
        expanded.forEach { alias ->
            lastRequestNotificationMsByKey.remove(alias.lowercase())
        }
    }

    fun reset() {
        notifiedMessageIds.clear()
        lastRequestNotificationMsByKey.clear()
    }
}
