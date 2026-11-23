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
        LayoverFileLogger.log("DIAGNOSTICS: Discovered Peers Count = $count")
    }

    fun updateRfcommStateLabel(label: String) {
        _diagnosticsState.update { it.copy(rfcommStateLabel = label) }
        LayoverFileLogger.log("DIAGNOSTICS: RFCOMM/GATT State = $label")
    }

    fun updateKeyHandshakeComplete(complete: Boolean) {
        _diagnosticsState.update { it.copy(isKeyHandshakeComplete = complete) }
        LayoverFileLogger.log("DIAGNOSTICS: E2EE Handshake Complete = $complete")
    }

    fun updateSessionFingerprint(fingerprint: String?) {
        _diagnosticsState.update { it.copy(sessionFingerprint = fingerprint) }
        LayoverFileLogger.log("DIAGNOSTICS: Session Fingerprint = $fingerprint")
    }

    fun incrementMessagesSent() {
        _diagnosticsState.update {
            val next = it.messagesSent + 1
            LayoverFileLogger.log("DIAGNOSTICS: Messages Sent = $next")
            it.copy(messagesSent = next)
        }
    }

    fun incrementMessagesReceived() {
        _diagnosticsState.update {
            val next = it.messagesReceived + 1
            LayoverFileLogger.log("DIAGNOSTICS: Messages Received = $next")
            it.copy(messagesReceived = next)
        }
    }

    fun incrementRateLimitedDrops() {
        _diagnosticsState.update {
            val next = it.rateLimitedDrops + 1
            LayoverFileLogger.log("DIAGNOSTICS: Rate Limited Drops = $next")
            it.copy(rateLimitedDrops = next)
        }
    }

    fun incrementBlockedDrops() {
        _diagnosticsState.update {
            val next = it.blockedDrops + 1
            LayoverFileLogger.log("DIAGNOSTICS: Blocked Drops = $next")
            it.copy(blockedDrops = next)
        }
    }

    fun updateLastErrorMessage(error: String?) {
        _diagnosticsState.update { it.copy(lastErrorMessage = error) }
        if (error != null) {
            LayoverFileLogger.logError("DIAGNOSTICS: Last Error = $error")
        }
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
        LayoverFileLogger.log("DIAGNOSTICS: Counters reset.")
    }
}
