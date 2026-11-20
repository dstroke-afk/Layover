package com.intj.layover.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Base64
import android.util.Log
import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.crypto.SessionCryptoManager
import com.intj.layover.diagnostics.SessionDiagnosticsTracker
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
import com.intj.layover.safety.PeerRateLimiter
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

    companion object {
        private const val TAG = "LayoverBLE"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val sessionCryptoManager = SessionCryptoManager()
    private val rateLimiter = PeerRateLimiter()

    val diagnosticsTracker = SessionDiagnosticsTracker()

    private var blockedPeerIds: Set<String> = emptySet()
    private var mutedPeerIds: Set<String> = emptySet()

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
                Log.d(TAG, "RFCOMM state changed: ${state.name}")
                diagnosticsTracker.updateRfcommStateLabel(state.name)
                if (state == RfcommConnectionState.CONNECTED) {
                    initiateE2eeHandshake()
                } else if (state == RfcommConnectionState.DISCONNECTED || state == RfcommConnectionState.FAILED) {
                    sessionCryptoManager.clearSession()
                    diagnosticsTracker.updateKeyHandshakeComplete(false)
                    diagnosticsTracker.updateSessionFingerprint(null)
                }
            }
        }

        // Handle incoming wire frames with safety, block, and rate-limiting enforcement
        scope.launch {
            rfcommSessionManager.incomingMessages.collect { rawFrame ->
                val currentPeer = activePeerId ?: rawFrame.senderId

                // Block enforcement
                if (blockedPeerIds.contains(currentPeer)) {
                    Log.d(TAG, "Dropping frame from blocked peer: $currentPeer")
                    diagnosticsTracker.incrementBlockedDrops()
                    rfcommSessionManager.disconnect()
                    sessionCryptoManager.clearSession()
                    diagnosticsTracker.updateKeyHandshakeComplete(false)
                    diagnosticsTracker.updateSessionFingerprint(null)
                    return@collect
                }

                // Rate limiting enforcement
                if (!rateLimiter.allowMessage(currentPeer)) {
                    Log.d(TAG, "Dropping rate-limited frame from peer: $currentPeer")
                    diagnosticsTracker.incrementRateLimitedDrops()
                    return@collect
                }

                val frame = WireFrameCodec.decodeFrame(rawFrame.text)
                if (frame == null) {
                    Log.e(TAG, "Failed to decode incoming wire frame payload.")
                    diagnosticsTracker.updateLastErrorMessage("Failed to decode wire frame")
                    return@collect
                }

                when (frame) {
                    is WireFrame.KeyExchange -> {
                        Log.d(TAG, "Received KeyExchange frame from ${frame.senderId}. Algorithm=${frame.algorithm}")
                        val success = sessionCryptoManager.establishRemoteKey(
                            remotePublicKeyBase64 = frame.publicKeyBase64,
                            algorithmName = frame.algorithm
                        )
                        val isEstablished = sessionCryptoManager.isSessionKeyEstablished
                        diagnosticsTracker.updateKeyHandshakeComplete(isEstablished)
                        diagnosticsTracker.updateSessionFingerprint(sessionCryptoManager.sessionFingerprint)
                        if (!success) {
                            Log.e(TAG, "Key exchange establishment failed.")
                            diagnosticsTracker.updateLastErrorMessage("E2EE key exchange failed")
                        }
                    }

                    is WireFrame.ChatEncrypted -> {
                        val iv = try {
                            Base64.decode(frame.ivBase64, Base64.NO_WRAP)
                        } catch (e: Exception) {
                            diagnosticsTracker.updateLastErrorMessage("Invalid IV Base64")
                            return@collect
                        }
                        val cipherBytes = try {
                            Base64.decode(frame.ciphertextBase64, Base64.NO_WRAP)
                        } catch (e: Exception) {
                            diagnosticsTracker.updateLastErrorMessage("Invalid Ciphertext Base64")
                            return@collect
                        }

                        val decryptedText = sessionCryptoManager.decryptText(
                            EncryptedPayload(iv = iv, ciphertext = cipherBytes)
                        )

                        if (decryptedText != null) {
                            Log.d(TAG, "Successfully decrypted chat message from $currentPeer")
                            diagnosticsTracker.incrementMessagesReceived()
                            val newMsg = ChatMessage(
                                id = frame.msgId,
                                senderId = currentPeer,
                                text = decryptedText,
                                timestampLabel = "Just now",
                                isOutgoing = false
                            )
                            _incomingMessagesFlow.emit(Pair(currentPeer, newMsg))
                        } else {
                            Log.e(TAG, "Failed to decrypt incoming chat message from $currentPeer")
                            diagnosticsTracker.updateLastErrorMessage("Failed to decrypt chat message")
                        }
                    }
                }
            }
        }
    }

    override fun resetDiagnosticsCounters() {
        diagnosticsTracker.resetCounters()
    }

    fun updateSafetySettings(blocked: Set<String>, muted: Set<String>) {
        blockedPeerIds = blocked
        mutedPeerIds = muted
        if (activePeerId != null && blockedPeerIds.contains(activePeerId)) {
            rfcommSessionManager.disconnect()
            sessionCryptoManager.clearSession()
        }
    }

    private fun initiateE2eeHandshake() {
        Log.d(TAG, "Initiating E2EE key exchange handshake...")
        val pubKeyB64 = sessionCryptoManager.generateKeyPair()
        val algo = sessionCryptoManager.getAlgorithmName()
        val localId = currentUserProfile.handle.ifEmpty { "local" }

        val keyExchangePayload = WireFrameCodec.encodeKeyExchange(
            senderId = localId,
            publicKeyBase64 = pubKeyB64,
            algorithm = algo
        )
        val sent = rfcommSessionManager.sendFrame(keyExchangePayload)
        if (sent) {
            diagnosticsTracker.incrementMessagesSent()
        } else {
            diagnosticsTracker.updateLastErrorMessage("Failed to send key exchange frame")
        }
    }

    fun updateProfile(userProfile: UserProfile) {
        currentUserProfile = userProfile
    }

    override fun startDiscovery(visible: Boolean) {
        Log.d(TAG, "startDiscovery requested. visible=$visible")
        bleStateController.updateState()
        val hasPerms = BlePermissionHelper.hasAllRequiredPermissions(context)
        val isEnabled = bluetoothAdapter?.isEnabled == true

        if (!hasPerms || !isEnabled) {
            val err = if (!hasPerms) {
                "Bluetooth permissions are required for nearby discovery"
            } else {
                "Bluetooth is turned off"
            }
            Log.e(TAG, "startDiscovery prerequisite check failed: $err")
            diagnosticsTracker.updateLastErrorMessage(err)
            return
        }

        // Scanner starts whenever discovery is requested (even if visible == false)
        discoveryManager.startScanning()

        // Advertiser starts only when visible == true
        if (visible) {
            discoveryManager.startAdvertising(currentUserProfile.copy(isVisibleToNearby = true))
        } else {
            discoveryManager.stopAdvertising()
        }
    }

    override fun stopDiscovery() {
        Log.d(TAG, "stopDiscovery requested.")
        discoveryManager.stopScanning()
        discoveryManager.stopAdvertising()
    }

    override fun sendConnectionRequest(peerId: String) {
        Log.d(TAG, "sendConnectionRequest to peerId=$peerId")
        if (blockedPeerIds.contains(peerId)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        discoveryManager.updatePeerStatus(peerId, ConnectionStatus.REQUESTED)
        activePeerId = peerId

        val localId = currentUserProfile.handle.ifEmpty { "local" }
        val role = ConnectionTieBreaker.determineRole(localId, peerId)
        Log.d(TAG, "Tie-breaker role determined for request: $role")

        if (role == ConnectionRole.LISTENER) {
            rfcommSessionManager.startServer()
        } else {
            connectRfcommDevice(peerId)
        }
    }

    override fun acceptConnectionRequest(peerId: String) {
        Log.d(TAG, "acceptConnectionRequest for peerId=$peerId")
        if (blockedPeerIds.contains(peerId)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        discoveryManager.updatePeerStatus(peerId, ConnectionStatus.ACCEPTED)
        activePeerId = peerId

        val localId = currentUserProfile.handle.ifEmpty { "local" }
        val role = ConnectionTieBreaker.determineRole(localId, peerId)
        Log.d(TAG, "Tie-breaker role determined for accept: $role")

        if (role == ConnectionRole.INITIATOR) {
            connectRfcommDevice(peerId)
        } else {
            rfcommSessionManager.startServer()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectRfcommDevice(peerId: String) {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            Log.e(TAG, "Cannot connect RFCOMM device: permissions missing.")
            return
        }
        val adapter = bluetoothAdapter ?: return
        val device = try {
            adapter.getRemoteDevice(peerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote device for MAC/peerId=$peerId", e)
            null
        }

        if (device != null) {
            rfcommSessionManager.connectToDevice(device)
        } else {
            rfcommSessionManager.startServer()
        }
    }

    override fun sendMessage(peerId: String, text: String) {
        Log.d(TAG, "sendMessage to peerId=$peerId")
        if (blockedPeerIds.contains(peerId)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        activePeerId = peerId
        val localId = currentUserProfile.handle.ifEmpty { "user" }
        val msgId = "msg_${System.currentTimeMillis()}"

        val encryptedPayload = sessionCryptoManager.encryptText(text)
        val sent = if (encryptedPayload != null) {
            val wirePayload = WireFrameCodec.encodeChatEncrypted(
                msgId = msgId,
                senderId = localId,
                timestamp = System.currentTimeMillis(),
                payload = encryptedPayload
            )
            rfcommSessionManager.sendFrame(wirePayload)
        } else {
            rfcommSessionManager.sendFrame(text)
        }

        if (sent) {
            diagnosticsTracker.incrementMessagesSent()
        } else {
            diagnosticsTracker.updateLastErrorMessage("Failed to send message frame")
        }
    }

    fun cleanup() {
        stopDiscovery()
        rateLimiter.clearAll()
        sessionCryptoManager.clearSession()
        rfcommSessionManager.disconnect()
    }
}
