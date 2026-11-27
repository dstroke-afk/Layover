package com.intj.layover.notifications

import com.intj.layover.ble.PeerAliasRegistry

/**
 * Tracks per-peer unread incoming message counts across [PeerAliasRegistry] aliases.
 * Pure Kotlin so it can be unit-tested directly without Android framework dependencies.
 */
class UnreadMessageTracker(
    private val maxTrackedMessageIds: Int = 500
) {
    private val lock = Any()
    private val countsByCanonicalId = LinkedHashMap<String, Int>()
    private val countedMessageIds = LinkedHashSet<String>()

    companion object {
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

        fun canonicalKey(rawCandidates: Collection<String?>, fallback: String = ""): String {
            val expanded = expandIdentifiers(rawCandidates)
            return expanded.firstOrNull { it.startsWith("dev_", ignoreCase = true) }
                ?: expanded.firstOrNull()
                ?: fallback.trim()
        }

        fun resolveUnreadCount(
            unreadCountsByPeerId: Map<String, Int>,
            peerCandidates: Collection<String?>
        ): Int {
            if (unreadCountsByPeerId.isEmpty()) return 0
            val expandedPeer = expandIdentifiers(peerCandidates)
            if (expandedPeer.isEmpty()) return 0

            var total = 0
            for ((storedKey, count) in unreadCountsByPeerId) {
                if (count <= 0) continue
                val expandedKey = expandIdentifiers(listOf(storedKey))
                val matches = expandedPeer.any { peerId ->
                    expandedKey.any { key -> peerId.equals(key, ignoreCase = true) }
                }
                if (matches) {
                    total += count
                }
            }
            return total
        }
    }

    fun recordIncomingMessage(
        senderCandidates: Collection<String?>,
        messageId: String,
        isAppInForeground: Boolean,
        activeChatPeerId: String?
    ): Map<String, Int> = synchronized(lock) {
        val expandedSender = expandIdentifiers(senderCandidates)
        if (expandedSender.isEmpty()) {
            return countsByCanonicalId.toMap()
        }

        if (isAppInForeground && !activeChatPeerId.isNullOrBlank()) {
            val activeAliases = expandIdentifiers(listOf(activeChatPeerId))
            val isViewingSenderChat = expandedSender.any { sender ->
                activeAliases.any { active -> sender.equals(active, ignoreCase = true) }
            }
            if (isViewingSenderChat) {
                return countsByCanonicalId.toMap()
            }
        }

        val cleanMsgId = messageId.trim()
        if (cleanMsgId.isNotEmpty()) {
            if (countedMessageIds.contains(cleanMsgId)) {
                return countsByCanonicalId.toMap()
            }
            countedMessageIds.add(cleanMsgId)
            while (countedMessageIds.size > maxTrackedMessageIds) {
                val oldest = countedMessageIds.firstOrNull() ?: break
                countedMessageIds.remove(oldest)
            }
        }

        val canonical = canonicalKey(expandedSender)
        if (canonical.isEmpty()) {
            return countsByCanonicalId.toMap()
        }

        // Consolidate any existing entries under aliases of the same peer into the canonical key
        var existingTotal = 0
        val matchingKeys = countsByCanonicalId.keys.filter { existingKey ->
            val expandedExisting = expandIdentifiers(listOf(existingKey))
            expandedSender.any { sender ->
                expandedExisting.any { existing -> sender.equals(existing, ignoreCase = true) }
            }
        }
        matchingKeys.forEach { key ->
            existingTotal += countsByCanonicalId.remove(key) ?: 0
        }

        countsByCanonicalId[canonical] = existingTotal + 1
        countsByCanonicalId.toMap()
    }

    fun markChatRead(peerCandidates: Collection<String?>): Map<String, Int> = synchronized(lock) {
        val expandedTarget = expandIdentifiers(peerCandidates)
        if (expandedTarget.isEmpty()) {
            return countsByCanonicalId.toMap()
        }

        val keysToRemove = countsByCanonicalId.keys.filter { existingKey ->
            val expandedExisting = expandIdentifiers(listOf(existingKey))
            expandedTarget.any { target ->
                expandedExisting.any { existing -> target.equals(existing, ignoreCase = true) }
            }
        }
        keysToRemove.forEach { countsByCanonicalId.remove(it) }
        countsByCanonicalId.toMap()
    }

    fun snapshot(): Map<String, Int> = synchronized(lock) {
        countsByCanonicalId.toMap()
    }

    fun reset() = synchronized(lock) {
        countsByCanonicalId.clear()
        countedMessageIds.clear()
    }
}
