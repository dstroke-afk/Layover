package com.intj.layover.p2p

enum class ConnectionRole {
    INITIATOR, // Acts as Bluetooth Socket Client
    LISTENER    // Acts as Bluetooth Server Socket Listener
}

object ConnectionTieBreaker {

    fun determineRole(localNodeId: String, remoteNodeId: String): ConnectionRole {
        val normLocal = localNodeId.trim().lowercase()
        val normRemote = remoteNodeId.trim().lowercase()

        val cmp = normLocal.compareTo(normRemote)
        return if (cmp >= 0) {
            ConnectionRole.INITIATOR
        } else {
            ConnectionRole.LISTENER
        }
    }
}
