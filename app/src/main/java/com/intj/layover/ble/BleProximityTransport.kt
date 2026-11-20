package com.intj.layover.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.ProximityTransport
import com.intj.layover.model.UserProfile
import com.intj.layover.p2p.ConnectionRole
import com.intj.layover.p2p.ConnectionTieBreaker
import com.intj.layover.p2p.RfcommSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class BleProximityTransport(
    private val context: Context,
    private val discoveryManager: BleDiscoveryManager,
    private val bleStateController: BleStateController,
    private val rfcommSessionManager: RfcommSessionManager,
    private var currentUserProfile: UserProfile = UserProfile()
) : ProximityTransport {

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _incomingMessagesFlow = MutableSharedFlow<Pair<String, ChatMessage>>()
    val incomingMessagesFlow: SharedFlow<Pair<String, ChatMessage>> = _incomingMessagesFlow.asSharedFlow()

    private var activePeerId: String? = null

    init {
        scope.launch {
            rfcommSessionManager.incomingMessages.collect { frame ->
                val peerId = activePeerId ?: frame.senderId
                val newMsg = ChatMessage(
                    id = "msg_${System.currentTimeMillis()}",
                    senderId = peerId,
                    text = frame.text,
                    timestampLabel = "Just now",
                    isOutgoing = false
                )
                _incomingMessagesFlow.emit(Pair(peerId, newMsg))
            }
        }
    }

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
        activePeerId = peerId

        val localId = currentUserProfile.handle.ifEmpty { "local" }
        val role = ConnectionTieBreaker.determineRole(localId, peerId)

        if (role == ConnectionRole.LISTENER) {
            rfcommSessionManager.startServer()
        } else {
            connectRfcommDevice(peerId)
        }
    }

    override fun acceptConnectionRequest(peerId: String) {
        discoveryManager.updatePeerStatus(peerId, ConnectionStatus.ACCEPTED)
        activePeerId = peerId

        val localId = currentUserProfile.handle.ifEmpty { "local" }
        val role = ConnectionTieBreaker.determineRole(localId, peerId)

        if (role == ConnectionRole.INITIATOR) {
            connectRfcommDevice(peerId)
        } else {
            rfcommSessionManager.startServer()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectRfcommDevice(peerId: String) {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            return
        }
        val adapter = bluetoothAdapter ?: return
        val device = try {
            adapter.getRemoteDevice(peerId)
        } catch (e: Exception) {
            null
        }

        if (device != null) {
            rfcommSessionManager.connectToDevice(device)
        } else {
            rfcommSessionManager.startServer()
        }
    }

    override fun sendMessage(peerId: String, text: String) {
        activePeerId = peerId
        rfcommSessionManager.sendFrame(text)
    }

    fun cleanup() {
        stopDiscovery()
        rfcommSessionManager.disconnect()
    }
}
