package com.intj.layover.ble

import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.ProximityTransport
import com.intj.layover.model.UserProfile

class BleProximityTransport(
    private val discoveryManager: BleDiscoveryManager,
    private val bleStateController: BleStateController,
    private var currentUserProfile: UserProfile = UserProfile()
) : ProximityTransport {

    fun updateProfile(userProfile: UserProfile) {
        currentUserProfile = userProfile
    }

    override fun startDiscovery(visible: Boolean) {
        bleStateController.updateState()
        discoveryManager.startScanning()
        if (visible) {
            discoveryManager.startAdvertising(currentUserProfile.copy(isVisibleToNearby = true))
        } else {
            discoveryManager.stopAdvertising()
        }
    }

    override fun stopDiscovery() {
        discoveryManager.stopScanning()
        discoveryManager.stopAdvertising()
    }

    override fun sendConnectionRequest(peerId: String) {
        discoveryManager.updatePeerStatus(peerId, ConnectionStatus.REQUESTED)
    }

    override fun acceptConnectionRequest(peerId: String) {
        discoveryManager.updatePeerStatus(peerId, ConnectionStatus.ACCEPTED)
    }

    override fun sendMessage(peerId: String, text: String) {
        // Message sending endpoint
    }
}
