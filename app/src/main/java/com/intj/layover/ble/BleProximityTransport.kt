package com.intj.layover.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Base64
import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.crypto.SessionCryptoManager
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.diagnostics.SessionDiagnosticsTracker
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.GenderOption
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object PeerAliasRegistry {
    private val aliasGroups = CopyOnWriteArraySet<MutableSet<String>>()

    private fun isValidAlias(value: String?): Boolean {
        if (value == null) return false
        val trimmed = value.trim()
        return trimmed.isNotEmpty() &&
                !trimmed.equals("00000000", ignoreCase = true) &&
                !trimmed.equals("user", ignoreCase = true) &&
                !trimmed.equals("local", ignoreCase = true) &&
                !trimmed.equals("remote_peer", ignoreCase = true) &&
                !trimmed.equals("unknown_mac", ignoreCase = true)
    }

    fun linkAliases(rawAliases: Collection<String?>) {
        val clean = rawAliases.filter(::isValidAlias).map { it!!.trim() }.toSet()
        if (clean.isEmpty()) return

        val overlapping = aliasGroups.filter { group ->
            group.any { existing -> clean.any { candidate -> existing.equals(candidate, ignoreCase = true) } }
        }

        val merged = linkedSetOf<String>().apply {
            addAll(clean)
            overlapping.forEach { addAll(it) }
        }

        aliasGroups.removeAll(overlapping.toSet())
        aliasGroups.add(merged)
    }

    fun resolveAliases(identifier: String?): Set<String> {
        if (!isValidAlias(identifier)) return emptySet()
        val target = identifier!!.trim()
        val result = linkedSetOf<String>(target)

        aliasGroups.filter { group ->
            group.any { it.equals(target, ignoreCase = true) }
        }.forEach { group ->
            result.addAll(group)
        }

        return result
    }
}

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

    private val _incomingMessagesFlow = MutableSharedFlow<Pair<String, ChatMessage>>(extraBufferCapacity = 32)
    val incomingMessagesFlow: SharedFlow<Pair<String, ChatMessage>> = _incomingMessagesFlow.asSharedFlow()

    private val _incomingConnectionRequestFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val incomingConnectionRequestFlow: SharedFlow<Pair<String, String>> = _incomingConnectionRequestFlow.asSharedFlow()

    private val _incomingConnectionAcceptFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val incomingConnectionAcceptFlow: SharedFlow<String> = _incomingConnectionAcceptFlow.asSharedFlow()

    private val _incomingConnectionDeclineFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val incomingConnectionDeclineFlow: SharedFlow<String> = _incomingConnectionDeclineFlow.asSharedFlow()

    private val _incomingPeerBlockStateFlow = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 16)
    val incomingPeerBlockStateFlow: SharedFlow<Pair<String, Boolean>> = _incomingPeerBlockStateFlow.asSharedFlow()

    private val _incomingProfileSyncFlow = MutableSharedFlow<WireFrame.ProfileSync>(extraBufferCapacity = 16)
    val incomingProfileSyncFlow: SharedFlow<WireFrame.ProfileSync> = _incomingProfileSyncFlow.asSharedFlow()

    var onPeerProfileUpdated: ((
        peerId: String,
        displayName: String?,
        gender: GenderOption,
        age: Int?,
        avatarBytes: ByteArray?
    ) -> Unit)? = null

    fun sendProfileSync(targetPeerId: String? = null) {
        val localId = currentUserProfile.handle.ifEmpty { "user" }
        val compactBytes = currentUserProfile.compactTransferAvatarBytes ?: currentUserProfile.avatarBytes
        val frame = WireFrameCodec.encodeProfileSync(
            senderId = localId,
            displayName = currentUserProfile.displayName,
            gender = currentUserProfile.gender,
            age = currentUserProfile.age,
            compactAvatarBytes = compactBytes
        )
        if (targetPeerId != null && targetPeerId.isNotBlank()) {
            activePeerId = targetPeerId
        }
        val sent = gattSessionManager.sendFrame(frame)
        if (sent) {
            diagnosticsTracker.incrementMessagesSent()
        }
    }

    private var activePeerId: String? = null
    private var lastGattMac: String? = null

    private var lastDiscoveryStartMs = 0L
    private var lastDiscoveryVisible = !currentUserProfile.isVisibleToNearby
    private val lastRequestTimeMap = ConcurrentHashMap<String, Long>()

    private var hasSentKeyExchange = false
    private var hasReceivedKeyExchange = false
    private val pendingEncryptedRetries = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    val isE2eeEstablished: Boolean
        get() = sessionCryptoManager.isSessionKeyEstablished

    val sessionFingerprint: String?
        get() = sessionCryptoManager.sessionFingerprint

    fun isPeerIdentifierBlocked(vararg candidates: String?): Boolean {
        val expandedCandidates = linkedSetOf<String>()
        for (raw in candidates) {
            if (raw.isNullOrBlank() || raw == "00000000" || raw == "user") continue
            expandedCandidates.add(raw.trim())
            expandedCandidates.addAll(PeerAliasRegistry.resolveAliases(raw))
        }
        if (expandedCandidates.isEmpty()) return false

        val expandedBlocked = linkedSetOf<String>()
        for (b in blockedPeerIds) {
            if (b.isBlank() || b == "00000000" || b == "user") continue
            expandedBlocked.add(b.trim())
            expandedBlocked.addAll(PeerAliasRegistry.resolveAliases(b))
        }

        val matched = expandedCandidates.firstOrNull { cand ->
            expandedBlocked.any { blocked -> cand.equals(blocked, ignoreCase = true) }
        }

        if (matched != null) {
            LayoverFileLogger.log("BLOCK EVALUATION: Matched blocked alias '$matched' across candidates=$expandedCandidates")
            return true
        }
        return false
    }

    init {
        gattSessionManager.compactAvatarBytesProvider = {
            currentUserProfile.compactTransferAvatarBytes ?: currentUserProfile.avatarBytes
        }

        gattSessionManager.startServer()

        gattSessionManager.onGattReady = {
            initiateE2eeHandshake()
        }

        scope.launch {
            gattSessionManager.connectionState.collect { state ->
                LayoverFileLogger.log("GATT connection state changed: ${state.name}")
                diagnosticsTracker.updateRfcommStateLabel(state.name)
                if (state == RfcommConnectionState.DISCONNECTED || state == RfcommConnectionState.FAILED) {
                    sessionCryptoManager.clearSession()
                    hasSentKeyExchange = false
                    hasReceivedKeyExchange = false
                    diagnosticsTracker.updateKeyHandshakeComplete(false)
                    diagnosticsTracker.updateSessionFingerprint(null)
                    if (state == RfcommConnectionState.FAILED) {
                        diagnosticsTracker.updateLastErrorMessage("GATT connection failed")
                    }
                }
            }
        }

        scope.launch {
            gattSessionManager.incomingMessages.collect { rawFrame ->
                val rawSenderMac = rawFrame.senderId
                if (BluetoothAdapter.checkBluetoothAddress(rawSenderMac)) {
                    lastGattMac = rawSenderMac
                    PeerAliasRegistry.linkAliases(listOfNotNull(activePeerId, rawSenderMac))
                }

                val currentPeer = activePeerId ?: rawSenderMac
                val rawType = rawFrame.text.substringBefore("|")

                LayoverFileLogger.log("FRAME INGESTION [1/3]: rawType=$rawType, rawSender=$rawSenderMac, activePeer=$activePeerId, len=${rawFrame.text.length}")

                val frame = WireFrameCodec.decodeFrame(rawFrame.text)
                if (frame == null) {
                    LayoverFileLogger.logError("Failed to decode incoming wire frame payload.")
                    diagnosticsTracker.updateLastErrorMessage("Failed to decode wire frame")
                    return@collect
                }

                val frameSenderId = when (frame) {
                    is WireFrame.KeyExchange -> frame.senderId
                    is WireFrame.ConnectionRequest -> frame.senderId
                    is WireFrame.ConnectionAccept -> frame.senderId
                    is WireFrame.ConnectionDecline -> frame.senderId
                    is WireFrame.ProfileSync -> frame.senderId
                    is WireFrame.PeerBlockState -> frame.senderId
                    is WireFrame.ChatEncrypted -> frame.senderId
                }

                val frameDisplayName = if (frame is WireFrame.ConnectionRequest) frame.displayName else ""

                PeerAliasRegistry.linkAliases(
                    listOfNotNull(
                        activePeerId,
                        rawSenderMac,
                        lastGattMac,
                        frameSenderId,
                        frameDisplayName.ifEmpty { null }
                    )
                )

                if (frame !is WireFrame.PeerBlockState) {
                    val isRawBlocked = isPeerIdentifierBlocked(currentPeer, activePeerId, rawSenderMac, lastGattMac)
                    val isDecodedBlocked = isPeerIdentifierBlocked(
                        frameSenderId,
                        frameDisplayName,
                        activePeerId,
                        rawSenderMac,
                        lastGattMac
                    )
                    if (isRawBlocked || isDecodedBlocked) {
                        diagnosticsTracker.incrementBlockedDrops()
                        LayoverFileLogger.log("FRAME INGESTION [3/3]: DROPPED frame from blocked peer. Blocked Drops=${diagnosticsTracker.diagnosticsState.value.blockedDrops}")
                        return@collect
                    }
                }

                val isControlFrame = frame is WireFrame.KeyExchange ||
                        frame is WireFrame.ConnectionRequest ||
                        frame is WireFrame.ConnectionAccept ||
                        frame is WireFrame.ConnectionDecline ||
                        frame is WireFrame.ProfileSync ||
                        frame is WireFrame.PeerBlockState

                if (!isControlFrame && !rateLimiter.allowMessage(currentPeer)) {
                    LayoverFileLogger.log("Dropping rate-limited frame from peer: $currentPeer")
                    diagnosticsTracker.incrementRateLimitedDrops()
                    return@collect
                }

                diagnosticsTracker.incrementMessagesReceived()
                LayoverFileLogger.log("FRAME INGESTION [3/3]: PASSED frame from sender=$frameSenderId. Messages Received=${diagnosticsTracker.diagnosticsState.value.messagesReceived}")

                when (frame) {
                    is WireFrame.KeyExchange -> {
                        if (frame.senderId.isNotEmpty() && frame.senderId != "local" && frame.senderId != "user") {
                            activePeerId = frame.senderId
                        }
                        LayoverFileLogger.log("KeyExchange applied for peer=${frame.senderId}. Algorithm=${frame.algorithm}")
                        hasReceivedKeyExchange = true
                        if (sessionCryptoManager.getLocalPublicKeyBase64() == null) {
                            sessionCryptoManager.generateKeyPair()
                        }
                        val success = sessionCryptoManager.establishRemoteKey(
                            remotePublicKeyBase64 = frame.publicKeyBase64,
                            algorithmName = frame.algorithm
                        )
                        LayoverFileLogger.log("KeyExchange establishRemoteKey success=$success for peer=${frame.senderId}")

                        if (sessionCryptoManager.getLocalPublicKeyBase64() != null && success && !frame.isResponse) {
                            val localPub = sessionCryptoManager.getLocalPublicKeyBase64()!!
                            val localId = currentUserProfile.handle.ifEmpty { "local" }
                            val responsePayload = WireFrameCodec.encodeKeyExchange(
                                senderId = localId,
                                publicKeyBase64 = localPub,
                                algorithm = sessionCryptoManager.getAlgorithmName(),
                                isResponse = true
                            )
                            hasSentKeyExchange = true
                            LayoverFileLogger.log("Sending bidirectional KeyExchange response payload to peer=${frame.senderId} (algo=${sessionCryptoManager.getAlgorithmName()})...")
                            gattSessionManager.sendFrame(responsePayload)
                            gattSessionManager.flushQueue()
                        }

                        val isEstablished = sessionCryptoManager.isSessionKeyEstablished
                        diagnosticsTracker.updateKeyHandshakeComplete(isEstablished)
                        diagnosticsTracker.updateSessionFingerprint(sessionCryptoManager.sessionFingerprint)
                        if (isEstablished) {
                            LayoverFileLogger.log("SUCCESS: E2EE Key Exchange completed successfully for peer=${frame.senderId}. Fingerprint=${sessionCryptoManager.sessionFingerprint}")
                            diagnosticsTracker.updateLastErrorMessage(null)
                            flushPendingRetries()
                        } else {
                            LayoverFileLogger.logError("Key exchange establishment pending for peer=${frame.senderId}.")
                            diagnosticsTracker.updateLastErrorMessage("E2EE key exchange pending")
                        }
                    }

                    is WireFrame.ConnectionRequest -> {
                        if (frame.senderId.isNotEmpty()) {
                            activePeerId = frame.senderId
                        }
                        onPeerProfileUpdated?.invoke(
                            frame.senderId,
                            frame.displayName,
                            frame.gender,
                            frame.age,
                            null
                        )
                        LayoverFileLogger.log("Received ConnectionRequest from ${frame.senderId} (${frame.displayName}, age=${frame.age})")
                        _incomingConnectionRequestFlow.emit(Pair(frame.senderId, frame.displayName))
                    }

                    is WireFrame.ProfileSync -> {
                        if (frame.senderId.isNotEmpty()) {
                            activePeerId = frame.senderId
                        }
                        val decodedAvatar = frame.compactAvatarBase64
                            ?.takeIf { it.isNotBlank() }
                            ?.let { b64 -> runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() }
                        onPeerProfileUpdated?.invoke(
                            frame.senderId,
                            frame.displayName,
                            frame.gender,
                            frame.age,
                            decodedAvatar
                        )
                        LayoverFileLogger.log("Received ProfileSync from ${frame.senderId}: age=${frame.age}, gender=${frame.gender}")
                        _incomingProfileSyncFlow.emit(frame)
                    }

                    is WireFrame.ConnectionAccept -> {
                        if (frame.senderId.isNotEmpty()) {
                            activePeerId = frame.senderId
                        }
                        LayoverFileLogger.log("Received ConnectionAccept from ${frame.senderId}")
                        _incomingConnectionAcceptFlow.emit(frame.senderId)
                    }

                    is WireFrame.ConnectionDecline -> {
                        if (frame.senderId.isNotEmpty()) {
                            activePeerId = frame.senderId
                        }
                        LayoverFileLogger.log("Received ConnectionDecline from ${frame.senderId}")
                        _incomingConnectionDeclineFlow.emit(frame.senderId)
                    }

                    is WireFrame.PeerBlockState -> {
                        val effectivePeerId = frame.senderId.ifEmpty { currentPeer }
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        LayoverFileLogger.log("Received PeerBlockState from $effectivePeerId: blocked=${frame.blocked}")
                        _incomingPeerBlockStateFlow.emit(Pair(effectivePeerId, frame.blocked))
                    }

                    is WireFrame.ChatEncrypted -> {
                        val effectivePeerId = frame.senderId
                            .takeIf { it.isNotBlank() && it != "user" && it != "local" }
                            ?: currentPeer
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
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
                            LayoverFileLogger.log("Successfully decrypted chat message from $effectivePeerId")
                            diagnosticsTracker.updateLastErrorMessage(null)
                            val newMsg = ChatMessage(
                                id = frame.msgId,
                                senderId = effectivePeerId,
                                text = decryptedText,
                                timestampLabel = "Just now",
                                isOutgoing = false
                            )
                            _incomingMessagesFlow.emit(Pair(effectivePeerId, newMsg))
                        } else {
                            LayoverFileLogger.logError("Failed to decrypt incoming chat message from $effectivePeerId")
                            diagnosticsTracker.updateLastErrorMessage("Failed to decrypt chat message")
                        }
                    }
                }
            }
        }
    }

    fun requestAvatarFetch(device: BluetoothDevice, mapKey: String, onLoaded: (ByteArray) -> Unit) {
        if (isPeerIdentifierBlocked(mapKey, device.address)) return
        gattSessionManager.fetchAvatarFromDevice(device, onLoaded)
    }

    override fun resetDiagnosticsCounters() {
        diagnosticsTracker.resetCounters()
    }

    fun updateSafetySettings(blocked: Set<String>, muted: Set<String>) {
        blockedPeerIds = blocked
        mutedPeerIds = muted
        val activeSessionIds = setOfNotNull(activePeerId, lastGattMac)
        discoveryManager.updateProtectedIdentifiers(blocked, muted, activeSessionIds)
        LayoverFileLogger.log("SAFETY SETTINGS UPDATE: blockedPeerIds=$blockedPeerIds, activePeerId=$activePeerId")
    }

    override fun sendBlockState(peerId: String, blocked: Boolean) {
        LayoverFileLogger.log("sendBlockState to peerId=$peerId, blocked=$blocked")
        val localId = currentUserProfile.handle.ifEmpty { "user" }
        val frame = WireFrameCodec.encodePeerBlockState(
            senderId = localId,
            blocked = blocked
        )
        val sent = gattSessionManager.sendFrame(frame)
        if (sent) {
            diagnosticsTracker.incrementMessagesSent()
        }
    }

    private fun initiateE2eeHandshake() {
        if (hasSentKeyExchange && sessionCryptoManager.isSessionKeyEstablished) {
            LayoverFileLogger.log("KeyExchange already completed. Skipping duplicate send.")
            return
        }
        hasSentKeyExchange = true
        LayoverFileLogger.log("Initiating E2EE key exchange handshake over GATT...")
        val pubKeyB64 = sessionCryptoManager.getLocalPublicKeyBase64() ?: sessionCryptoManager.generateKeyPair()
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
            diagnosticsTracker.updateLastErrorMessage(null)
        } else {
            hasSentKeyExchange = false
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
        val lastTime = lastRequestTimeMap[peerId] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastTime < 800L) {
            LayoverFileLogger.log("Debouncing duplicate sendConnectionRequest for peerId=$peerId within 800ms.")
            return
        }
        lastRequestTimeMap[peerId] = now

        LayoverFileLogger.log("sendConnectionRequest to peerId=$peerId")
        if (isPeerIdentifierBlocked(peerId)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        activePeerId = peerId
        connectGattDevice(peerId)

        val localName = currentUserProfile.displayName.ifEmpty { "Layover User" }
        val reqFrame = WireFrameCodec.encodeConnectionRequest(
            senderId = currentUserProfile.handle.ifEmpty { "user" },
            displayName = localName,
            gender = currentUserProfile.gender,
            age = currentUserProfile.age
        )
        gattSessionManager.sendFrame(reqFrame)
        sendProfileSync(peerId)
    }

    override fun acceptConnectionRequest(peerId: String) {
        LayoverFileLogger.log("acceptConnectionRequest for peerId=$peerId")
        if (isPeerIdentifierBlocked(peerId)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        activePeerId = peerId
        connectGattDevice(peerId)

        val acceptFrame = WireFrameCodec.encodeConnectionAccept(
            senderId = currentUserProfile.handle.ifEmpty { "user" }
        )
        gattSessionManager.sendFrame(acceptFrame)
        sendProfileSync(peerId)
    }

    override fun declineConnectionRequest(peerId: String) {
        LayoverFileLogger.log("declineConnectionRequest for peerId=$peerId")
        val declineFrame = WireFrameCodec.encodeConnectionDecline(
            senderId = currentUserProfile.handle.ifEmpty { "user" }
        )
        gattSessionManager.sendFrame(declineFrame)
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
            lastGattMac = targetDevice.address
            PeerAliasRegistry.linkAliases(listOf(peerId, targetDevice.address))
            LayoverFileLogger.log("Connecting GATT client to discovered BluetoothDevice: address=${targetDevice.address}")
            discoveryManager.stopScanning()
            gattSessionManager.connectToDevice(targetDevice)
            return
        }

        val isMacAddress = BluetoothAdapter.checkBluetoothAddress(peerId)
        if (isMacAddress) {
            lastGattMac = peerId
            PeerAliasRegistry.linkAliases(listOf(peerId))
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

    private fun flushPendingRetries() {
        if (!sessionCryptoManager.isSessionKeyEstablished) return
        while (pendingEncryptedRetries.isNotEmpty()) {
            val item = pendingEncryptedRetries.poll() ?: break
            sendMessage(item.first, item.second)
        }
    }

    override fun sendMessage(peerId: String, text: String) {
        LayoverFileLogger.log("sendMessage to peerId=$peerId")
        if (isPeerIdentifierBlocked(peerId, activePeerId, lastGattMac)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        if (peerId.isNotEmpty()) {
            activePeerId = peerId
        }
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
            val sent = gattSessionManager.sendFrame(wirePayload)
            if (sent) {
                diagnosticsTracker.incrementMessagesSent()
                diagnosticsTracker.updateLastErrorMessage(null)
            } else {
                diagnosticsTracker.updateLastErrorMessage("Failed to send message frame")
            }
        } else {
            LayoverFileLogger.logError("Failed to encrypt text for peerId=$peerId. Queueing message & initiating E2EE key exchange...")
            pendingEncryptedRetries.add(peerId to text)
            if (gattSessionManager.connectionState.value == RfcommConnectionState.DISCONNECTED ||
                gattSessionManager.connectionState.value == RfcommConnectionState.FAILED
            ) {
                connectGattDevice(peerId)
            }
            hasSentKeyExchange = false
            initiateE2eeHandshake()
            diagnosticsTracker.updateLastErrorMessage("E2EE key session missing - re-initiating handshake")
        }
    }

    fun cleanup() {
        stopDiscovery()
        rateLimiter.clearAll()
        sessionCryptoManager.clearSession()
        hasSentKeyExchange = false
        hasReceivedKeyExchange = false
        gattSessionManager.cleanup()
    }
}
