package com.intj.layover.safety

import java.util.concurrent.ConcurrentHashMap

class PeerRateLimiter(
    private val maxMessagesPerWindow: Int = 10,
    private val windowDurationMs: Long = 10_000L,
    private val minInterMessageCooldownMs: Long = 250L
) {
    private val timestampsMap = ConcurrentHashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allowMessage(peerId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val timestamps = timestampsMap.getOrPut(peerId) { ArrayDeque() }

        val cutoff = nowMs - windowDurationMs
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeFirst()
        }

        val lastTimestamp = timestamps.lastOrNull()
        if (lastTimestamp != null && (nowMs - lastTimestamp) < minInterMessageCooldownMs) {
            return false
        }

        if (timestamps.size >= maxMessagesPerWindow) {
            return false
        }

        timestamps.addLast(nowMs)
        return true
    }

    fun resetPeer(peerId: String) {
        timestampsMap.remove(peerId)
    }

    fun clearAll() {
        timestampsMap.clear()
    }
}
