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
    fun declineConnectionRequest(peerId: String) {}
    fun sendBlockState(peerId: String, blocked: Boolean) {}
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
    override fun declineConnectionRequest(peerId: String) {}
    override fun sendBlockState(peerId: String, blocked: Boolean) {}
    override fun sendMessage(peerId: String, text: String) {}
}

enum class ConnectionStatus {
    NONE,
    REQUESTED,
    ACCEPTED,
    DECLINED
}

enum class GenderOption(val label: String) {
    MALE("Male"),
    FEMALE("Female");

    companion object {
        fun fromLabelOrName(raw: String?): GenderOption {
            if (raw == null) return MALE
            return entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true) || it.label.equals(raw, ignoreCase = true)
            } ?: MALE
        }
    }
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
    val gender: GenderOption? = null,
    val age: Int? = null,
    val status: ConnectionStatus = ConnectionStatus.NONE,
    val avatar: PeerAvatar = PeerAvatar(
        peerId = id,
        initials = displayName.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").ifEmpty { "?" }
    )
)

fun formatPeerNameWithBadge(
    displayName: String,
    age: Int?,
    gender: GenderOption?
): String {
    val cleanName = displayName.trim()
    val validAge = age?.takeIf { it > 0 }
    return when {
        validAge != null && gender != null -> {
            val genderShort = if (gender == GenderOption.FEMALE) "F" else "M"
            "$cleanName($validAge$genderShort)"
        }
        validAge != null -> "$cleanName($validAge)"
        else -> cleanName
    }
}

data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val timestampLabel: String = "",
    val isOutgoing: Boolean,
    val replyToText: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class UserProfile(
    val displayName: String = "",
    val handle: String = "balajieswar",
    val gender: GenderOption = GenderOption.MALE,
    val age: Int? = null,
    val avatarBytes: ByteArray? = null,
    val compactTransferAvatarBytes: ByteArray? = null,
    val croppedResolutionLabel: String? = null,
    val isVisibleToNearby: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UserProfile

        if (displayName != other.displayName) return false
        if (handle != other.handle) return false
        if (gender != other.gender) return false
        if (age != other.age) return false
        if (isVisibleToNearby != other.isVisibleToNearby) return false
        if (croppedResolutionLabel != other.croppedResolutionLabel) return false
        if (avatarBytes != null) {
            if (other.avatarBytes == null) return false
            if (!avatarBytes.contentEquals(other.avatarBytes)) return false
        } else if (other.avatarBytes != null) return false
        if (compactTransferAvatarBytes != null) {
            if (other.compactTransferAvatarBytes == null) return false
            if (!compactTransferAvatarBytes.contentEquals(other.compactTransferAvatarBytes)) return false
        } else if (other.compactTransferAvatarBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + handle.hashCode()
        result = 31 * result + gender.hashCode()
        result = 31 * result + (age ?: 0)
        result = 31 * result + isVisibleToNearby.hashCode()
        result = 31 * result + (croppedResolutionLabel?.hashCode() ?: 0)
        result = 31 * result + (avatarBytes?.contentHashCode() ?: 0)
        result = 31 * result + (compactTransferAvatarBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class LayoverUiState(
    val userProfile: UserProfile = UserProfile(),
    val nearbyPeers: List<NearbyPeer> = emptyList(),
    val chatMessages: Map<String, List<ChatMessage>> = emptyMap(),
    val unreadCountsByPeerId: Map<String, Int> = emptyMap(),
    val blockedPeerIds: Set<String> = emptySet(),
    val blockedByPeerIds: Set<String> = emptySet(),
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
        gender = GenderOption.MALE,
        age = 28,
        status = ConnectionStatus.ACCEPTED
    ),
    NearbyPeer(
        id = "peer_2",
        displayName = "Sarah Chen",
        gender = GenderOption.FEMALE,
        age = 26,
        status = ConnectionStatus.REQUESTED
    ),
    NearbyPeer(
        id = "peer_3",
        displayName = "David Kim",
        gender = GenderOption.MALE,
        age = 31,
        status = ConnectionStatus.NONE
    ),
    NearbyPeer(
        id = "peer_4",
        displayName = "Elena Rostova",
        gender = GenderOption.FEMALE,
        age = 29,
        status = ConnectionStatus.ACCEPTED
    )
)
