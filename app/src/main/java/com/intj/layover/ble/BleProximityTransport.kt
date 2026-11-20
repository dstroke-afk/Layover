package com.intj.layover.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Base64
import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.crypto.SessionCryptoManager
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.ProximityTransport
import com.intj.layover.model.UserProfile
import com.intj.layover.p2p.ConnectionRole
import com.intj.layover.p2p.ConnectionTieBreaker
import com.intj.layover.p2p.RfcommConnectionState
import com.intj.layover.p2p.RfcommSessionManager
import com.intj.layover.p2p.WireFrame
import com.intj.layover.p2p.WireFrameCodec
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

    private val sessionCryptoManager = SessionCryptoManager()

    private val _incomingMessagesFlow = MutableSharedFlow<Pair<String, ChatMessage>>()
    val incomingMessagesFlow: SharedFlow<Pair<String, ChatMessage>> = _incomingMessagesFlow.asSharedFlow()

    private var activePeerId: String? = null

    val isE2eeEstablished: Boolean
        get() = sessionCryptoManager.isSessionKeyEstablished

    val sessionFingerprint: String?
        get() = sessionCryptoManager.sessionFingerprint

    init {
        // Handle RFCOMM connection lifecycle and E2EE key exchange
        scope.launch {
            rfcommSessionManager.connectionState.collect { state ->
                if (state == RfcommConnectionState.CONNECTED) {
                    initiateE2eeHandshake()
                } else if (state == RfcommConnectionState.DISCONNECTED || state == RfcommConnectionState.FAILED) {
                    sessionCryptoManager.clearSession()
                }
            }
        }

        // Handle incoming wire frames
        scope.launch {
            rfcommSessionManager.incomingMessages.collect { rawFrame ->
                val frame = WireFrameCodec.decodeFrame(rawFrame.text) ?: return@collect
                val currentPeer = activePeerId ?: rawFrame.senderId

                when (frame) {
                    is WireFrame.KeyExchange -> {
                        sessionCryptoManager.establishRemoteKey(
                            remotePublicKeyBase64 = frame.publicKeyBase64,
                            algorithmName = frame.algorithm
                        )
                    }

                    is WireFrame.ChatEncrypted -> {
                        val iv = try {
                            Base64.decode(frame.ivBase64, Base64.NO_WRAP)
                        } catch (e: Exception) {
                            return@collect
                        }
                        val cipherBytes = try {
                            Base64.decode(frame.ciphertextBase64, Base64.NO_WRAP)
                        } catch (e: Exception) {
                            return@collect
                        }

                        val decryptedText = sessionCryptoManager.decryptText(
                            EncryptedPayload(iv = iv, ciphertext = cipherBytes)
                        )

                        if (decryptedText != null) {
                            val newMsg = ChatMessage(
                                id = frame.msgId,
                                senderId = currentPeer,
                                text = decryptedText,
                                timestampLabel = "Just now",
                                isOutgoing = false
                            )
                            _incomingMessagesFlow.emit(Pair(currentPeer, newMsg))
                        }
                    }
                }
            }
        }
    }

    private fun initiateE2eeHandshake() {
        val pubKeyB64 = sessionCryptoManager.generateKeyPair()
        val algo = sessionCryptoManager.getAlgorithmName()
        val localId = currentUserProfile.handle.ifEmpty { "local" }

        val keyExchangePayload = WireFrameCodec.encodeKeyExchange(
            senderId = localId,
            publicKeyBase64 = pubKeyB64,
            algorithm = algo
        )
        rfcommSessionManager.sendFrame(keyExchangePayload)
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
        val localId = currentUserProfile.handle.ifEmpty { "user" }
        val msgId = "msg_${System.currentTimeMillis()}"

        val encryptedPayload = sessionCryptoManager.encryptText(text)
        if (encryptedPayload != null) {
            val wirePayload = WireFrameCodec.encodeChatEncrypted(
                msgId = msgId,
                senderId = localId,
                timestamp = System.currentTimeMillis(),
                payload = encryptedPayload
            )
            rfcommSessionManager.sendFrame(wirePayload)
        } else {
            // Unencrypted fallback if session key not established yet
            rfcommSessionManager.sendFrame(text)
        }
    }

    fun cleanup() {
        stopDiscovery()
        sessionCryptoManager.clearSession()
        rfcommSessionManager.disconnect()
    }
}
