package com.intj.layover.model

import com.intj.layover.diagnostics.Phase1Diagnostics

/**
 * Decoupled transport layer interface for P2P BLE / Wi-Fi Direct operations.
 */
interface ProximityTransport {
    fun startDiscovery(visible: Boolean)
    fun stopDiscovery()
    fun sendConnectionRequest(peerId: String)
    fun acceptConnectionRequest(peerId: String)
    fun sendMessage(peerId: String, text: String)
    fun resetDiagnosticsCounters() {}
}

/**
 * Default stubbed transport implementation for UI testing and previews.
 */
class NoOpProximityTransport : ProximityTransport {
    override fun startDiscovery(visible: Boolean) {}
    override fun stopDiscovery() {}
    override fun sendConnectionRequest(peerId: String) {}
    override fun acceptConnectionRequest(peerId: String) {}
    override fun sendMessage(peerId: String, text: String) {}
}

enum class ConnectionStatus {
    NONE,
    REQUESTED,
    ACCEPTED
}

data class PeerAvatar(
    val peerId: String,
    val initials: String,
    val avatarBase64OrBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PeerAvatar

        if (peerId != other.peerId) return false
        if (initials != other.initials) return false
        if (avatarBase64OrBytes != null) {
            if (other.avatarBase64OrBytes == null) return false
            if (!avatarBase64OrBytes.contentEquals(other.avatarBase64OrBytes)) return false
        } else if (other.avatarBase64OrBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + initials.hashCode()
        result = 31 * result + (avatarBase64OrBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class NearbyPeer(
    val id: String,
    val displayName: String,
    val handle: String = "",
    val status: ConnectionStatus = ConnectionStatus.NONE,
    val avatar: PeerAvatar = PeerAvatar(
        peerId = id,
        initials = displayName.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").ifEmpty { "?" }
    )
)

data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val timestampLabel: String,
    val isOutgoing: Boolean,
    val replyToText: String? = null
)

data class UserProfile(
    val displayName: String = "Balaji Eswar",
    val handle: String = "balajieswar",
    val isVisibleToNearby: Boolean = true
)

data class LayoverUiState(
    val userProfile: UserProfile = UserProfile(),
    val nearbyPeers: List<NearbyPeer> = emptyList(),
    val chatMessages: Map<String, List<ChatMessage>> = emptyMap(),
    val blockedPeerIds: Set<String> = emptySet(),
    val mutedPeerIds: Set<String> = emptySet(),
    val rateLimitedPeerIds: Set<String> = emptySet(),
    val isScanning: Boolean = false,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val transport: ProximityTransport = NoOpProximityTransport(),
    val diagnostics: Phase1Diagnostics = Phase1Diagnostics()
)

val SampleMockPeers = listOf(
    NearbyPeer(
        id = "peer_1",
        displayName = "Alex Rivera",
        handle = "arivera",
        status = ConnectionStatus.ACCEPTED
    ),
    NearbyPeer(
        id = "peer_2",
        displayName = "Sarah Chen",
        handle = "sarahc",
        status = ConnectionStatus.REQUESTED
    ),
    NearbyPeer(
        id = "peer_3",
        displayName = "David Kim",
        handle = "dkim",
        status = ConnectionStatus.NONE
    ),
    NearbyPeer(
        id = "peer_4",
        displayName = "Elena Rostova",
        handle = "elena_r",
        status = ConnectionStatus.ACCEPTED
    )
)
