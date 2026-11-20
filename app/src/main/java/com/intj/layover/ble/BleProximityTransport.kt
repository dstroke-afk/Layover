package com.intj.layover.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Base64
import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.crypto.SessionCryptoManager
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.diagnostics.SessionDiagnosticsTracker
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.ProximityTransport
import com.intj.layover.model.UserProfile
import com.intj.layover.p2p.RfcommConnectionState
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
    private val gattSessionManager: GattSessionManager,
    private var currentUserProfile: UserProfile = UserProfile()
) : ProximityTransport {

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val sessionCryptoManager = SessionCryptoManager()
    private val rateLimiter = PeerRateLimiter()

    val diagnosticsTracker = SessionDiagnosticsTracker()

    var onMissingPermissions: (() -> Unit)? = null

    private var blockedPeerIds: Set<String> = emptySet()
    private var mutedPeerIds: Set<String> = emptySet()

    private val _incomingMessagesFlow = MutableSharedFlow<Pair<String, ChatMessage>>()
    val incomingMessagesFlow: SharedFlow<Pair<String, ChatMessage>> = _incomingMessagesFlow.asSharedFlow()

    private var activePeerId: String? = null

    // Debounce tracking for startDiscovery
    private var lastDiscoveryStartMs = 0L
    private var lastDiscoveryVisible = !currentUserProfile.isVisibleToNearby

    val isE2eeEstablished: Boolean
        get() = sessionCryptoManager.isSessionKeyEstablished

    val sessionFingerprint: String?
        get() = sessionCryptoManager.sessionFingerprint

    init {
        // Start GATT Server listener
        gattSessionManager.startServer()

        // Handle GATT connection lifecycle and E2EE key exchange
        scope.launch {
            gattSessionManager.connectionState.collect { state ->
                LayoverFileLogger.log("GATT connection state changed: ${state.name}")
                diagnosticsTracker.updateRfcommStateLabel(state.name)
                if (state == RfcommConnectionState.CONNECTED) {
                    initiateE2eeHandshake()
                } else if (state == RfcommConnectionState.DISCONNECTED || state == RfcommConnectionState.FAILED) {
                    sessionCryptoManager.clearSession()
                    diagnosticsTracker.updateKeyHandshakeComplete(false)
                    diagnosticsTracker.updateSessionFingerprint(null)
                    if (state == RfcommConnectionState.FAILED) {
                        diagnosticsTracker.updateLastErrorMessage("GATT connection failed")
                    }
                }
            }
        }

        // Handle incoming wire frames with safety, block, and rate-limiting enforcement
        scope.launch {
            gattSessionManager.incomingMessages.collect { rawFrame ->
                val currentPeer = activePeerId ?: rawFrame.senderId

                // Block enforcement
                if (blockedPeerIds.contains(currentPeer)) {
                    LayoverFileLogger.log("Dropping frame from blocked peer: $currentPeer")
                    diagnosticsTracker.incrementBlockedDrops()
                    gattSessionManager.disconnect()
                    sessionCryptoManager.clearSession()
                    diagnosticsTracker.updateKeyHandshakeComplete(false)
                    diagnosticsTracker.updateSessionFingerprint(null)
                    return@collect
                }

                // Rate limiting enforcement
                if (!rateLimiter.allowMessage(currentPeer)) {
                    LayoverFileLogger.log("Dropping rate-limited frame from peer: $currentPeer")
                    diagnosticsTracker.incrementRateLimitedDrops()
                    return@collect
                }

                val frame = WireFrameCodec.decodeFrame(rawFrame.text)
                if (frame == null) {
                    LayoverFileLogger.logError("Failed to decode incoming wire frame payload.")
                    diagnosticsTracker.updateLastErrorMessage("Failed to decode wire frame")
                    return@collect
                }

                when (frame) {
                    is WireFrame.KeyExchange -> {
                        LayoverFileLogger.log("Received KeyExchange frame from ${frame.senderId}. Algorithm=${frame.algorithm}")
                        val success = sessionCryptoManager.establishRemoteKey(
                            remotePublicKeyBase64 = frame.publicKeyBase64,
                            algorithmName = frame.algorithm
                        )
                        val isEstablished = sessionCryptoManager.isSessionKeyEstablished
                        diagnosticsTracker.updateKeyHandshakeComplete(isEstablished)
                        diagnosticsTracker.updateSessionFingerprint(sessionCryptoManager.sessionFingerprint)
                        if (!success) {
                            LayoverFileLogger.logError("Key exchange establishment failed.")
                            diagnosticsTracker.updateLastErrorMessage("E2EE key exchange failed")
                        } else {
                            LayoverFileLogger.log("SUCCESS: E2EE Key Exchange completed via BLE GATT.")
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
                            LayoverFileLogger.log("Successfully decrypted chat message from $currentPeer")
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
                            LayoverFileLogger.logError("Failed to decrypt incoming chat message from $currentPeer")
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
            gattSessionManager.disconnect()
            sessionCryptoManager.clearSession()
        }
    }

    private fun initiateE2eeHandshake() {
        LayoverFileLogger.log("Initiating E2EE key exchange handshake over GATT...")
        val pubKeyB64 = sessionCryptoManager.generateKeyPair()
        val algo = sessionCryptoManager.getAlgorithmName()
        val localId = currentUserProfile.handle.ifEmpty { "local" }

        val keyExchangePayload = WireFrameCodec.encodeKeyExchange(
            senderId = localId,
            publicKeyBase64 = pubKeyB64,
            algorithm = algo
        )
        val sent = gattSessionManager.sendFrame(keyExchangePayload)
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
        val now = System.currentTimeMillis()
        if (now - lastDiscoveryStartMs < 1000L && lastDiscoveryVisible == visible) {
            LayoverFileLogger.log("Debouncing duplicate startDiscovery(visible=$visible) call within 1s.")
            return
        }
        lastDiscoveryStartMs = now
        lastDiscoveryVisible = visible

        LayoverFileLogger.log("startDiscovery requested. visible=$visible")
        bleStateController.refreshPermissionAndAdapterState()
        val hasPerms = BlePermissionHelper.hasAllRequiredPermissions(context)
        val isEnabled = bluetoothAdapter?.isEnabled == true

        if (!hasPerms) {
            val err = "Required Bluetooth permissions are missing"
            LayoverFileLogger.logError("startDiscovery failed: $err")
            diagnosticsTracker.updateLastErrorMessage(err)
            onMissingPermissions?.invoke()
            return
        }

        if (!isEnabled) {
            val err = "Bluetooth is turned off"
            LayoverFileLogger.logError("startDiscovery failed: $err")
            diagnosticsTracker.updateLastErrorMessage(err)
            return
        }

        discoveryManager.startScanning()
        gattSessionManager.startServer()

        if (visible) {
            discoveryManager.startAdvertising(currentUserProfile.copy(isVisibleToNearby = true))
        } else {
            discoveryManager.stopAdvertising()
        }
    }

    override fun stopDiscovery() {
        LayoverFileLogger.log("stopDiscovery requested.")
        discoveryManager.stopScanning()
        discoveryManager.stopAdvertising()
    }

    override fun sendConnectionRequest(peerId: String) {
        LayoverFileLogger.log("sendConnectionRequest to peerId=$peerId")
        if (blockedPeerIds.contains(peerId)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        discoveryManager.updatePeerStatus(peerId, ConnectionStatus.REQUESTED)
        activePeerId = peerId

        connectGattDevice(peerId)
    }

    override fun acceptConnectionRequest(peerId: String) {
        LayoverFileLogger.log("acceptConnectionRequest for peerId=$peerId")
        if (blockedPeerIds.contains(peerId)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        discoveryManager.updatePeerStatus(peerId, ConnectionStatus.ACCEPTED)
        activePeerId = peerId

        connectGattDevice(peerId)
    }

    @SuppressLint("MissingPermission")
    private fun connectGattDevice(peerId: String) {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            LayoverFileLogger.logError("Cannot connect GATT device: permissions missing.")
            return
        }
        val adapter = bluetoothAdapter ?: return

        val targetDevice = discoveryManager.getDiscoveredDevice(peerId)
        if (targetDevice != null) {
            LayoverFileLogger.log("Connecting GATT client to discovered BluetoothDevice: address=${targetDevice.address}")
            discoveryManager.stopScanning()
            gattSessionManager.connectToDevice(targetDevice)
            return
        }

        val isMacAddress = BluetoothAdapter.checkBluetoothAddress(peerId)
        if (isMacAddress) {
            val device = try {
                adapter.getRemoteDevice(peerId)
            } catch (e: Exception) {
                LayoverFileLogger.logError("Failed to get remote device for MAC=$peerId", e)
                null
            }

            if (device != null) {
                discoveryManager.stopScanning()
                gattSessionManager.connectToDevice(device)
            } else {
                gattSessionManager.startServer()
            }
        } else {
            LayoverFileLogger.log("PeerId $peerId is not a Bluetooth MAC address. Ensuring GATT server is listening.")
            gattSessionManager.startServer()
        }
    }

    override fun sendMessage(peerId: String, text: String) {
        LayoverFileLogger.log("sendMessage to peerId=$peerId")
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
            gattSessionManager.sendFrame(wirePayload)
        } else {
            gattSessionManager.sendFrame(text)
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
        gattSessionManager.cleanup()
    }
}
