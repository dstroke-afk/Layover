package com.intj.layover.model

enum class ConnectionStatus {
    NONE,
    REQUESTED,
    ACCEPTED
}

data class NearbyPeer(
    val id: String,
    val displayName: String,
    val handle: String = "",
    val status: ConnectionStatus = ConnectionStatus.NONE,
)

data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val timestampLabel: String,
    val isOutgoing: Boolean,
    val replyToText: String? = null,
)

data class UserProfile(
    val displayName: String = "Balaji Eswar",
    val handle: String = "balajieswar",
    val isVisibleToNearby: Boolean = true,
)

data class LayoverUiState(
    val userProfile: UserProfile = UserProfile(),
    val nearbyPeers: List<NearbyPeer> = listOf(
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
    ),
    val chatMessages: Map<String, List<ChatMessage>> = mapOf(
        "peer_1" to listOf(
            ChatMessage(
                id = "msg_1",
                senderId = "peer_1",
                text = "Hey! Are you also waiting for the flight to JFK?",
                timestampLabel = "10:14 AM",
                isOutgoing = false
            ),
            ChatMessage(
                id = "msg_2",
                senderId = "user",
                text = "Yes! Terminal 4, Gate B22. Flight delayed by 45 mins.",
                timestampLabel = "10:15 AM",
                isOutgoing = true,
                replyToText = "Hey! Are you also waiting for the flight to JFK?"
            ),
            ChatMessage(
                id = "msg_3",
                senderId = "peer_1",
                text = "Ah good to know! Want to grab a coffee near Gate B20?",
                timestampLabel = "10:16 AM",
                isOutgoing = false
            )
        ),
        "peer_2" to listOf(
            ChatMessage(
                id = "msg_4",
                senderId = "peer_2",
                text = "Hi Balaji, sent you a connection request!",
                timestampLabel = "09:30 AM",
                isOutgoing = false
            )
        ),
        "peer_4" to listOf(
            ChatMessage(
                id = "msg_5",
                senderId = "peer_4",
                text = "Safe travels!",
                timestampLabel = "Yesterday",
                isOutgoing = false
            )
        )
    ),
    val isScanning: Boolean = false,
)
