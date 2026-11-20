package com.intj.layover.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Phase1Diagnostics(
    val discoveredPeersCount: Int = 0,
    val rfcommStateLabel: String = "DISCONNECTED",
    val isKeyHandshakeComplete: Boolean = false,
    val sessionFingerprint: String? = null,
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    val rateLimitedDrops: Int = 0,
    val blockedDrops: Int = 0,
    val lastErrorMessage: String? = null
)

class SessionDiagnosticsTracker {

    private val _diagnosticsState = MutableStateFlow(Phase1Diagnostics())
    val diagnosticsState: StateFlow<Phase1Diagnostics> = _diagnosticsState.asStateFlow()

    fun updateDiscoveredPeersCount(count: Int) {
        _diagnosticsState.update { it.copy(discoveredPeersCount = count) }
    }

    fun updateRfcommStateLabel(label: String) {
        _diagnosticsState.update { it.copy(rfcommStateLabel = label) }
    }

    fun updateKeyHandshakeComplete(complete: Boolean) {
        _diagnosticsState.update { it.copy(isKeyHandshakeComplete = complete) }
    }

    fun updateSessionFingerprint(fingerprint: String?) {
        _diagnosticsState.update { it.copy(sessionFingerprint = fingerprint) }
    }

    fun incrementMessagesSent() {
        _diagnosticsState.update { it.copy(messagesSent = it.messagesSent + 1) }
    }

    fun incrementMessagesReceived() {
        _diagnosticsState.update { it.copy(messagesReceived = it.messagesReceived + 1) }
    }

    fun incrementRateLimitedDrops() {
        _diagnosticsState.update { it.copy(rateLimitedDrops = it.rateLimitedDrops + 1) }
    }

    fun incrementBlockedDrops() {
        _diagnosticsState.update { it.copy(blockedDrops = it.blockedDrops + 1) }
    }

    fun updateLastErrorMessage(error: String?) {
        _diagnosticsState.update { it.copy(lastErrorMessage = error) }
    }

    fun resetCounters() {
        _diagnosticsState.update {
            it.copy(
                messagesSent = 0,
                messagesReceived = 0,
                rateLimitedDrops = 0,
                blockedDrops = 0
            )
        }
    }
}
