package com.intj.layover.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Base64
import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.crypto.KeyEstablishStatus
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
import com.intj.layover.ui.screens.formatMessageBubbleTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object PeerAliasRegistry {
    private val aliasGroups = CopyOnWriteArraySet<MutableSet<String>>()
    private val macRegex = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    private val instanceHexRegex = Regex("^[0-9A-Fa-f]{8}$")
    private val devIdRegex = Regex("^dev_[0-9A-Za-z_-]+$", RegexOption.IGNORE_CASE)
    private val peerIdRegex = Regex("^peer_[0-9A-Za-z_-]+$", RegexOption.IGNORE_CASE)

    private fun isValidAlias(value: String?): Boolean {
        if (value == null) return false
        val trimmed = value.trim()
        return trimmed.isNotEmpty() &&
                !trimmed.equals("00000000", ignoreCase = true) &&
                !trimmed.equals("user", ignoreCase = true) &&
                !trimmed.equals("local", ignoreCase = true) &&
                !trimmed.equals("remote_peer", ignoreCase = true) &&
                !trimmed.equals("unknown_mac", ignoreCase = true) &&
                !trimmed.startsWith("Layover Peer (", ignoreCase = true)
    }

    /**
     * Returns true only for stable peer identifiers (`dev_...`, 8-hex BLE advertisement `Key`,
     * Bluetooth MAC addresses, or synthetic `peer_...` IDs), explicitly excluding raw display
     * names (`Name=...` or free-text names) so unrelated users sharing a display name never collide.
     */
    fun isStablePeerIdentifier(value: String?): Boolean {
        if (!isValidAlias(value)) return false
        val trimmed = value!!.trim()
        if (trimmed.startsWith("Name=", ignoreCase = true)) return false
        return devIdRegex.matches(trimmed) ||
                instanceHexRegex.matches(trimmed) ||
                macRegex.matches(trimmed) ||
                peerIdRegex.matches(trimmed)
    }

    private fun expandSeedWithDerivedInstanceId(raw: String): Set<String> {
        val out = linkedSetOf(raw)
        if (raw.startsWith("dev_", ignoreCase = true)) {
            val derivedInstanceHex = LayoverBlePayloadEncoder.getInstanceIdHex(raw)
            if (isValidAlias(derivedInstanceHex)) {
                out.add(derivedInstanceHex)
            }
        }
        return out
    }

    private fun extractDevIds(values: Collection<String>): Set<String> {
        return values.filter { it.startsWith("dev_", ignoreCase = true) }
            .map { it.lowercase() }
            .toSet()
    }

    private fun hasConflictingDevIds(a: Collection<String>, b: Collection<String>): Boolean {
        val devA = extractDevIds(a)
        val devB = extractDevIds(b)
        return devA.isNotEmpty() && devB.isNotEmpty() && devA.intersect(devB).isEmpty()
    }

    @Synchronized
    fun linkAliases(rawAliases: Collection<String?>) {
        val clean = linkedSetOf<String>()
        for (raw in rawAliases) {
            if (!isValidAlias(raw)) continue
            clean.addAll(expandSeedWithDerivedInstanceId(raw!!.trim()))
        }
        if (clean.isEmpty()) return

        val stableClean = clean.filter { isStablePeerIdentifier(it) }
        val overlapping = aliasGroups.filter { group ->
            if (hasConflictingDevIds(group, clean)) return@filter false
            if (stableClean.isNotEmpty()) {
                group.any { existing ->
                    isStablePeerIdentifier(existing) &&
                        stableClean.any { candidate -> existing.equals(candidate, ignoreCase = true) }
                }
            } else {
                group.any { existing -> clean.any { candidate -> existing.equals(candidate, ignoreCase = true) } }
            }
        }

        val merged = linkedSetOf<String>().apply {
            addAll(clean)
            overlapping.forEach { addAll(it) }
        }

        aliasGroups.removeAll(overlapping.toSet())
        aliasGroups.add(merged)
    }

    @Synchronized
    fun resolveAliases(identifier: String?): Set<String> {
        if (!isValidAlias(identifier)) return emptySet()
        val trimmed = identifier!!.trim()
        val seeds = expandSeedWithDerivedInstanceId(trimmed)
        val result = linkedSetOf<String>().apply { addAll(seeds) }
        val requireStableOverlap = isStablePeerIdentifier(trimmed)

        aliasGroups.filter { group ->
            if (hasConflictingDevIds(group, seeds)) return@filter false
            group.any { existing ->
                (!requireStableOverlap || isStablePeerIdentifier(existing)) &&
                    seeds.any { seed -> existing.equals(seed, ignoreCase = true) }
            }
        }.forEach { group ->
            result.addAll(group)
        }

        return result
    }

    /**
     * Resolves only stable peer identifiers (`dev_...`, BLE advertisement `Key`, MACs, `peer_...`)
     * for [identifier], strictly excluding raw display names.
     */
    @Synchronized
    fun resolveStableIdentifiers(identifier: String?): Set<String> {
        if (!isStablePeerIdentifier(identifier)) return emptySet()
        return resolveAliases(identifier)
            .filter { isStablePeerIdentifier(it) }
            .toSet()
    }

    @Synchronized
    fun resolveStableIdentifiers(identifiers: Collection<String?>): Set<String> {
        val out = linkedSetOf<String>()
        for (raw in identifiers) {
            if (!isStablePeerIdentifier(raw)) continue
            out.addAll(resolveStableIdentifiers(raw))
        }
        return out
    }

    /**
     * Matches [candidates] against [blocklist] strictly via stable identifiers (`peerId`,
     * advertisement `Key`, and rotating/known MACs). Raw display names (`Name=...` or free-text
     * names) are never used for matching, preventing false blocks across users with the same name.
     */
    @Synchronized
    fun matchesBlocklist(candidates: Collection<String?>, blocklist: Collection<String>): Boolean {
        val expandedCandidates = resolveStableIdentifiers(candidates)
        if (expandedCandidates.isEmpty()) return false
        val expandedBlocklist = resolveStableIdentifiers(blocklist)
        if (expandedBlocklist.isEmpty()) return false
        return expandedCandidates.any { cand ->
            expandedBlocklist.any { blocked -> cand.equals(blocked, ignoreCase = true) }
        }
    }

    @Synchronized
    fun resolveMacAddresses(identifier: String?): Set<String> {
        return resolveAliases(identifier)
            .filter { macRegex.matches(it) }
            .toSet()
    }

    @Synchronized
    fun areSamePeer(first: String?, second: String?): Boolean {
        if (!isValidAlias(first) || !isValidAlias(second)) return false
        if (first!!.trim().equals(second!!.trim(), ignoreCase = true)) return true
        val firstAliases = resolveAliases(first)
        val secondAliases = resolveAliases(second)
        if (hasConflictingDevIds(firstAliases, secondAliases)) return false
        val firstStable = firstAliases.filter { isStablePeerIdentifier(it) }
        val secondStable = secondAliases.filter { isStablePeerIdentifier(it) }
        if (firstStable.isNotEmpty() && secondStable.isNotEmpty()) {
            return firstStable.any { a -> secondStable.any { b -> a.equals(b, ignoreCase = true) } }
        }
        return firstAliases.any { a -> secondAliases.any { b -> a.equals(b, ignoreCase = true) } }
    }

    @Synchronized
    fun clear() {
        aliasGroups.clear()
    }
}

class BleProximityTransport(
    private val context: Context,
    private val discoveryManager: BleDiscoveryManager,
    private val bleStateController: BleStateController,
    private val gattSessionManager: GattSessionManager,
    private var currentUserProfile: UserProfile = UserProfile()
) : ProximityTransport {

    companion object {
        private const val HANDSHAKE_DEBOUNCE_MS = 3_000L
        private const val DECRYPT_RECOVERY_COOLDOWN_MS = 5_000L
        private const val MAX_CONSECUTIVE_DECRYPT_RECOVERIES = 3
        private const val RESET_RESPONSE_DEBOUNCE_MS = 2_000L
    }

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

    private val _incomingMessageDeletesFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 32)
    val incomingMessageDeletesFlow: SharedFlow<Pair<String, String>> = _incomingMessageDeletesFlow.asSharedFlow()

    private val _outgoingMessageSentFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val outgoingMessageSentFlow: SharedFlow<String> = _outgoingMessageSentFlow.asSharedFlow()

    private val _incomingMessageDeliveredFlow = MutableSharedFlow<Pair<String, List<String>>>(extraBufferCapacity = 64)
    val incomingMessageDeliveredFlow: SharedFlow<Pair<String, List<String>>> = _incomingMessageDeliveredFlow.asSharedFlow()

    private val _incomingMessageSeenFlow = MutableSharedFlow<Pair<String, List<String>>>(extraBufferCapacity = 64)
    val incomingMessageSeenFlow: SharedFlow<Pair<String, List<String>>> = _incomingMessageSeenFlow.asSharedFlow()

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
        gender: GenderOption?,
        age: Int?,
        avatarBytes: ByteArray?
    ) -> Unit)? = null

    var onIncomingPhotoWireFrame: ((senderPeerId: String, frame: WireFrame) -> Unit)? = null
    var onSendPhotoRequested: ((peerId: String, uriString: String) -> Unit)? = null
    var onDownloadPhotoRequested: ((peerId: String, transferId: String) -> Unit)? = null
    var onDownloadPeerDpRequested: ((peerId: String, avatarDigest: String) -> Unit)? = null
    var onCancelPhotoRequested: ((transferId: String) -> Unit)? = null
    var onDeleteMessageForMeRequested: ((peerId: String, msgId: String) -> Unit)? = null
    var onDeleteMessageForEveryoneRequested: ((peerId: String, msgId: String) -> Unit)? = null

    private val _e2eeSessionReadyFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun isEncryptedChannelReadyForPeer(peerId: String): Boolean {
        val peerAliases = collectPeerAliases(peerId, activePeerId, lastGattMac)
        val channelReady = gattSessionManager.isReadyForPeer(peerAliases) || gattSessionManager.hasReadyChannel()
        if (!channelReady) return false
        return sessionCryptoManager.hasSessionForPeer(peerAliases) &&
            !sessionCryptoManager.needsReconnectConfirmation(peerAliases)
    }

    /**
     * Ensures both the GATT transport link and E2EE key exchange are established for [peerId]
     * before encrypting/sending photo frames, initiating connection and handshake automatically
     * instead of requiring a manual text message first.
     */
    suspend fun ensureEncryptedChannelReady(
        peerId: String,
        timeoutMs: Long = 12_000L
    ): Boolean {
        if (isPeerIdentifierBlocked(peerId, activePeerId, lastGattMac)) {
            diagnosticsTracker.incrementBlockedDrops()
            return false
        }
        if (peerId.isNotBlank()) {
            activePeerId = peerId
            linkOutboundPeerAliases(peerId)
        }
        if (isEncryptedChannelReadyForPeer(peerId)) {
            return true
        }

        val initialAliases = collectPeerAliases(peerId, activePeerId, lastGattMac)
        val initialChannelReady = gattSessionManager.isReadyForPeer(initialAliases) || gattSessionManager.hasReadyChannel()
        val initialHasSession = sessionCryptoManager.hasSessionForPeer(initialAliases)
        val initialNeedsConfirm = sessionCryptoManager.needsReconnectConfirmation(initialAliases)

        LayoverFileLogger.log(
            "ensureEncryptedChannelReady: establishing GATT/E2EE for peerId=$peerId " +
                "(channelReady=$initialChannelReady, hasSession=$initialHasSession, needsConfirm=$initialNeedsConfirm, " +
                "clientConnecting=${gattSessionManager.isClientConnecting()})"
        )

        if (!initialChannelReady && !gattSessionManager.isClientConnecting() && peerId.isNotBlank()) {
            connectGattDevice(peerId)
        } else if (initialChannelReady) {
            if (!initialHasSession) {
                synchronized(handshakeLock) { hasSentKeyExchange = false }
            }
            initiateE2eeHandshake(isReset = false)
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastConnectAttemptMs = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            if (isEncryptedChannelReadyForPeer(peerId)) {
                LayoverFileLogger.log("ensureEncryptedChannelReady: GATT & E2EE ready for peerId=$peerId")
                return true
            }

            val peerAliases = collectPeerAliases(peerId, activePeerId, lastGattMac)
            val channelReady = gattSessionManager.isReadyForPeer(peerAliases) || gattSessionManager.hasReadyChannel()
            if (channelReady) {
                if (!sessionCryptoManager.hasSessionForPeer(peerAliases) ||
                    sessionCryptoManager.needsReconnectConfirmation(peerAliases)
                ) {
                    initiateE2eeHandshake(isReset = false)
                }
            } else if (!gattSessionManager.isClientConnecting() &&
                peerId.isNotBlank() &&
                System.currentTimeMillis() - lastConnectAttemptMs >= 2_500L
            ) {
                lastConnectAttemptMs = System.currentTimeMillis()
                connectGattDevice(peerId)
            }

            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            withTimeoutOrNull(minOf(250L, remaining)) {
                _e2eeSessionReadyFlow.first()
            }
        }

        val ready = isEncryptedChannelReadyForPeer(peerId)
        if (!ready) {
            LayoverFileLogger.logError(
                "ensureEncryptedChannelReady: timed out after ${timeoutMs}ms for peerId=$peerId " +
                    "(channelReady=${gattSessionManager.hasReadyChannel()}, e2eeEstablished=$isE2eeEstablished)"
            )
        }
        return ready
    }

    fun encryptKeyBytesForPeer(peerId: String, keyBytes: ByteArray): EncryptedPayload? {
        if (peerId.isNotBlank()) {
            linkOutboundPeerAliases(peerId)
        }
        val aliases = collectPeerAliases(peerId, activePeerId, lastGattMac)
        return sessionCryptoManager.encryptBytes(keyBytes, aliases)
    }

    fun decryptKeyBytesFromPeer(peerId: String, payload: EncryptedPayload): ByteArray? {
        if (peerId.isNotBlank()) {
            linkOutboundPeerAliases(peerId)
        }
        val aliases = collectPeerAliases(peerId, activePeerId, lastGattMac)
        return sessionCryptoManager.decryptBytes(payload, aliases)
    }

    /**
     * Proactively refreshes the GATT client link to [peerId] when a critical photo control frame
     * (such as `PHOTO_DOWNLOAD_REQUEST`) received no response across existing routes.
     */
    suspend fun refreshPeerGattChannelForRetry(
        peerId: String,
        timeoutMs: Long = 5_000L
    ): Boolean {
        if (isPeerIdentifierBlocked(peerId, activePeerId, lastGattMac)) {
            diagnosticsTracker.incrementBlockedDrops()
            return false
        }
        if (peerId.isNotBlank()) {
            activePeerId = peerId
            linkOutboundPeerAliases(peerId)
        }
        val peerAliases = collectPeerAliases(peerId, activePeerId, lastGattMac)
        val plan = gattSessionManager.inspectOutboundRoutePlan(peerAliases)
        LayoverFileLogger.log(
            "refreshPeerGattChannelForRetry: peerId=$peerId, currentPrimary=${plan.primary}, currentFallback=${plan.fallback}"
        )
        if (peerId.isNotBlank() && plan.primary != GattOutboundRoute.CLIENT_WRITE && !gattSessionManager.isClientConnecting()) {
            // If we only had a GATT Server route (or no route), open a fresh GATT Client connection
            // so retries can also deliver via ATT-acknowledged CLIENT_WRITE.
            connectGattDevice(peerId)
        } else if (peerId.isNotBlank() && plan.primary == GattOutboundRoute.CLIENT_WRITE && plan.fallback == GattOutboundRoute.NONE) {
            handleGattReconnectRequest(lastGattMac, "photo_download_request_retry")
        }
        return ensureEncryptedChannelReady(peerId, timeoutMs = timeoutMs)
    }

    fun sendPhotoWireFrame(
        targetPeerId: String,
        rawWireFrame: String,
        routePreference: GattRoutePreference = GattRoutePreference.AUTO
    ): Boolean {
        if (isPeerIdentifierBlocked(targetPeerId, activePeerId, lastGattMac)) {
            diagnosticsTracker.incrementBlockedDrops()
            return false
        }
        if (targetPeerId.isNotBlank()) {
            activePeerId = targetPeerId
            linkOutboundPeerAliases(targetPeerId)
        }
        val peerAliases = collectPeerAliases(targetPeerId, activePeerId, lastGattMac)
        val channelReady = gattSessionManager.isReadyForPeer(peerAliases) || gattSessionManager.hasReadyChannel()
        val routePlan = gattSessionManager.inspectOutboundRoutePlan(peerAliases, routePreference)
        val frameType = rawWireFrame.substringBefore("|")
        LayoverFileLogger.log(
            "sendPhotoWireFrame: type=$frameType, targetPeerId=$targetPeerId, pref=$routePreference, " +
                "primary=${routePlan.primary}, fallback=${routePlan.fallback}, channelReady=$channelReady"
        )
        if (!channelReady && !gattSessionManager.isClientConnecting() && targetPeerId.isNotBlank()) {
            connectGattDevice(targetPeerId)
        }
        val sent = gattSessionManager.sendFrame(
            text = rawWireFrame,
            targetAliases = peerAliases,
            routePreference = routePreference
        )
        if (sent) {
            diagnosticsTracker.incrementMessagesSent()
            diagnosticsTracker.updateLastErrorMessage(null)
        } else {
            LayoverFileLogger.logError("sendPhotoWireFrame failed to enqueue/send frame: type=$frameType, targetPeerId=$targetPeerId")
            diagnosticsTracker.updateLastErrorMessage("Failed to send $frameType frame")
        }
        return sent
    }

    override fun sendPhoto(peerId: String, uriString: String) {
        onSendPhotoRequested?.invoke(peerId, uriString)
    }

    override fun downloadPhoto(peerId: String, transferId: String) {
        onDownloadPhotoRequested?.invoke(peerId, transferId)
    }

    override fun downloadPeerProfilePicture(peerId: String, avatarDigest: String) {
        onDownloadPeerDpRequested?.invoke(peerId, avatarDigest)
    }

    override fun cancelPhotoTransfer(transferId: String) {
        onCancelPhotoRequested?.invoke(transferId)
    }

    override fun deleteMessageForMe(peerId: String, msgId: String) {
        onDeleteMessageForMeRequested?.invoke(peerId, msgId)
    }

    override fun deleteMessageForEveryone(peerId: String, msgId: String) {
        onDeleteMessageForEveryoneRequested?.invoke(peerId, msgId)
    }

    fun sendProfileSync(targetPeerId: String? = null) {
        val localId = currentUserProfile.handle.ifEmpty { "user" }
        // Avatar bytes are already fetched via dedicated chunked LAYOVER_AVATAR_CHAR_UUID reads;
        // omit multi-KB avatar blobs here so PROFILE_SYNC stays compact on the control channel.
        val frame = WireFrameCodec.encodeProfileSync(
            senderId = localId,
            displayName = currentUserProfile.displayName,
            gender = currentUserProfile.gender,
            age = currentUserProfile.age,
            compactAvatarBytes = null
        )
        if (targetPeerId != null && targetPeerId.isNotBlank()) {
            activePeerId = targetPeerId
        }
        val targetAliases = collectPeerAliases(targetPeerId, activePeerId, lastGattMac)
        val sent = gattSessionManager.sendFrame(frame, targetAliases)
        if (sent) {
            diagnosticsTracker.incrementMessagesSent()
        }
    }

    @Volatile
    private var activePeerId: String? = null
    @Volatile
    private var lastGattMac: String? = null

    private var lastDiscoveryStartMs = 0L
    private var lastDiscoveryVisible = !currentUserProfile.isVisibleToNearby
    private val lastRequestTimeMap = ConcurrentHashMap<String, Long>()

    private val handshakeLock = Any()
    private var hasSentKeyExchange = false
    private var lastRespondedRemotePublicKey: String? = null
    private var lastResetResponseTimeMs: Long = 0L
    private var lastKeyExchangeSentMs: Long = 0L
    private var awaitingResetResponse: Boolean = false
    private val lastDecryptRecoveryTimeMs = ConcurrentHashMap<String, Long>()
    private val consecutiveDecryptRecoveries = ConcurrentHashMap<String, Int>()
    private val pendingEncryptedRetries = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, String, String>>()
    private val pendingEncryptedDeleteRetries = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
    private val pendingDeliveredAckRetries = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, List<String>>>()
    private val pendingSeenAckRetries = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, List<String>>>()

    val isE2eeEstablished: Boolean
        get() = sessionCryptoManager.isSessionKeyEstablished

    val sessionFingerprint: String?
        get() = sessionCryptoManager.sessionFingerprint

    private fun isDistinctPeerDevId(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val aClean = a.trim()
        val bClean = b.trim()
        return aClean.startsWith("dev_", ignoreCase = true) &&
            bClean.startsWith("dev_", ignoreCase = true) &&
            !aClean.equals(bClean, ignoreCase = true)
    }

    private fun collectPeerAliases(vararg seeds: String?): Set<String> {
        val out = LinkedHashSet<String>()
        for (raw in seeds) {
            if (raw.isNullOrBlank() || raw == "00000000" || raw == "user" || raw == "local") continue
            val trimmed = raw.trim()
            if (PeerAliasRegistry.isStablePeerIdentifier(trimmed)) {
                out.addAll(PeerAliasRegistry.resolveStableIdentifiers(trimmed))
            } else {
                out.add(trimmed)
                out.addAll(PeerAliasRegistry.resolveAliases(trimmed))
            }
        }
        return out
    }

    private fun canonicalRecoveryKey(peerId: String): String {
        val aliases = collectPeerAliases(peerId)
        return aliases.firstOrNull { it.startsWith("dev_", ignoreCase = true) }?.lowercase()
            ?: peerId.trim().lowercase()
    }

    fun isPeerIdentifierBlocked(vararg candidates: String?): Boolean {
        val matched = PeerAliasRegistry.matchesBlocklist(candidates.toList(), blockedPeerIds)
        if (matched) {
            val stableCandidates = PeerAliasRegistry.resolveStableIdentifiers(candidates.toList())
            LayoverFileLogger.log("BLOCK EVALUATION: Matched blocked stable alias across candidates=$stableCandidates")
        }
        return matched
    }

    init {
        gattSessionManager.compactAvatarBytesProvider = {
            currentUserProfile.compactTransferAvatarBytes ?: currentUserProfile.avatarBytes
        }

        gattSessionManager.onOutboundFrameTransmitted = { rawFrame ->
            when (val decoded = WireFrameCodec.decodeFrame(rawFrame)) {
                is WireFrame.ChatEncrypted -> {
                    _outgoingMessageSentFlow.tryEmit(decoded.msgId)
                }
                is WireFrame.PhotoPreview -> {
                    if (!decoded.transferId.startsWith("dp_", ignoreCase = true)) {
                        _outgoingMessageSentFlow.tryEmit("photo_${decoded.transferId}")
                    }
                }
                else -> Unit
            }
        }

        gattSessionManager.startServer()

        gattSessionManager.onGattReady = {
            initiateE2eeHandshake(isReset = false)
            scope.launch {
                delay(500)
                resumeScanningIfEligible()
            }
        }

        gattSessionManager.onReconnectRequested = { failedMac, reason ->
            handleGattReconnectRequest(failedMac, reason)
        }

        scope.launch {
            gattSessionManager.connectionState.collect { state ->
                LayoverFileLogger.log("GATT connection state changed: ${state.name}")
                diagnosticsTracker.updateRfcommStateLabel(state.name)
                if (state == RfcommConnectionState.DISCONNECTED || state == RfcommConnectionState.FAILED) {
                    // Preserve local key pair and cached peer session across transient BLE disconnects
                    // so reconnects reuse the exact same key & fingerprint unless the peer actually restarted.
                    sessionCryptoManager.markPeerDisconnected(collectPeerAliases(activePeerId, lastGattMac))
                    synchronized(handshakeLock) {
                        hasSentKeyExchange = false
                        lastRespondedRemotePublicKey = null
                        lastKeyExchangeSentMs = 0L
                        awaitingResetResponse = false
                    }
                    diagnosticsTracker.updateKeyHandshakeComplete(false)
                    if (state == RfcommConnectionState.FAILED) {
                        diagnosticsTracker.updateLastErrorMessage("GATT connection failed")
                    }
                    resumeScanningIfEligible()
                }
            }
        }

        scope.launch {
            gattSessionManager.incomingMessages.collect { rawFrame ->
                val rawSenderMac = rawFrame.senderId
                val rawType = rawFrame.text.substringBefore("|")

                if (rawType != "KEY_EXCHANGE") {
                    LayoverFileLogger.log("FRAME INGESTION [1/3]: rawType=$rawType, rawSender=$rawSenderMac, activePeer=$activePeerId, len=${rawFrame.text.length}")
                }

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
                    is WireFrame.DeleteMessage -> frame.senderId
                    is WireFrame.MessageDelivered -> frame.senderId
                    is WireFrame.MessageSeen -> frame.senderId
                    is WireFrame.PhotoPreview -> frame.senderId
                    is WireFrame.PhotoDownloadRequest -> frame.senderId
                    is WireFrame.WifiApReady -> frame.senderId
                    is WireFrame.WifiApFallback -> frame.senderId
                    is WireFrame.PhotoTransferDone -> frame.senderId
                }

                val frameDisplayName = when (frame) {
                    is WireFrame.ConnectionRequest -> frame.displayName
                    is WireFrame.ProfileSync -> frame.displayName
                    else -> ""
                }

                val activeMatchesSender = !isDistinctPeerDevId(activePeerId, frameSenderId)
                val matchedDiscovered = discoveryManager.findDiscoveredPeer(frameSenderId)
                    ?: discoveryManager.findDiscoveredPeer(rawSenderMac)
                    ?: activePeerId?.takeIf { activeMatchesSender }?.let { discoveryManager.findDiscoveredPeer(it) }
                val latestDiscoveredMac = discoveryManager.getDiscoveredDevice(frameSenderId)?.address
                    ?: matchedDiscovered?.id?.let { discoveryManager.getDiscoveredDevice(it)?.address }

                if (BluetoothAdapter.checkBluetoothAddress(rawSenderMac)) {
                    lastGattMac = rawSenderMac
                }

                val aliasesToLink = buildList {
                    add(rawSenderMac)
                    add(frameSenderId)
                    latestDiscoveredMac?.let { add(it) }
                    matchedDiscovered?.id?.let { add(it) }
                    matchedDiscovered?.handle?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    if (activeMatchesSender && activePeerId != null) {
                        add(activePeerId)
                    }
                    if (activeMatchesSender && lastGattMac != null) {
                        add(lastGattMac)
                    }
                }.filter { PeerAliasRegistry.isStablePeerIdentifier(it) }
                PeerAliasRegistry.linkAliases(aliasesToLink)

                val effectivePeerId = frameSenderId
                    .takeIf { it.isNotBlank() && it != "user" && it != "local" }
                    ?: activePeerId?.takeIf { activeMatchesSender }
                    ?: rawSenderMac

                if (frame !is WireFrame.PeerBlockState) {
                    val isBlocked = isPeerIdentifierBlocked(
                        effectivePeerId,
                        frameSenderId,
                        rawSenderMac,
                        lastGattMac.takeIf { activeMatchesSender }
                    )
                    if (isBlocked) {
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
                        frame is WireFrame.PeerBlockState ||
                        frame is WireFrame.DeleteMessage ||
                        frame is WireFrame.MessageDelivered ||
                        frame is WireFrame.MessageSeen ||
                        frame is WireFrame.PhotoDownloadRequest ||
                        frame is WireFrame.WifiApReady ||
                        frame is WireFrame.WifiApFallback ||
                        frame is WireFrame.PhotoTransferDone

                if (!isControlFrame && !rateLimiter.allowMessage(effectivePeerId)) {
                    LayoverFileLogger.log("Dropping rate-limited frame from peer: $effectivePeerId")
                    diagnosticsTracker.incrementRateLimitedDrops()
                    return@collect
                }

                diagnosticsTracker.incrementMessagesReceived()
                if (frame !is WireFrame.KeyExchange) {
                    LayoverFileLogger.log("FRAME INGESTION [3/3]: PASSED frame from sender=$frameSenderId. Messages Received=${diagnosticsTracker.diagnosticsState.value.messagesReceived}")
                }

                when (frame) {
                    is WireFrame.KeyExchange -> {
                        handleIncomingKeyExchange(
                            frame = frame,
                            effectivePeerId = effectivePeerId,
                            rawSenderMac = rawSenderMac
                        )
                    }

                    is WireFrame.ConnectionRequest -> {
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        onPeerProfileUpdated?.invoke(
                            effectivePeerId,
                            frame.displayName,
                            frame.gender,
                            frame.age,
                            null
                        )
                        LayoverFileLogger.log("Received ConnectionRequest from $effectivePeerId (${frame.displayName}, age=${frame.age})")
                        _incomingConnectionRequestFlow.emit(Pair(effectivePeerId, frame.displayName))
                    }

                    is WireFrame.ProfileSync -> {
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        val decodedAvatar = frame.compactAvatarBase64
                            ?.takeIf { it.isNotBlank() }
                            ?.let { b64 -> runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() }
                        onPeerProfileUpdated?.invoke(
                            effectivePeerId,
                            frame.displayName,
                            frame.gender,
                            frame.age,
                            decodedAvatar
                        )
                        LayoverFileLogger.log("Received ProfileSync from $effectivePeerId: age=${frame.age}, gender=${frame.gender}")
                        _incomingProfileSyncFlow.emit(frame)
                    }

                    is WireFrame.ConnectionAccept -> {
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        LayoverFileLogger.log("Received ConnectionAccept from $effectivePeerId")
                        _incomingConnectionAcceptFlow.emit(effectivePeerId)
                    }

                    is WireFrame.ConnectionDecline -> {
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        LayoverFileLogger.log("Received ConnectionDecline from $effectivePeerId")
                        _incomingConnectionDeclineFlow.emit(effectivePeerId)
                    }

                    is WireFrame.PeerBlockState -> {
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        LayoverFileLogger.log("Received PeerBlockState from $effectivePeerId: blocked=${frame.blocked}")
                        _incomingPeerBlockStateFlow.emit(Pair(effectivePeerId, frame.blocked))
                    }

                    is WireFrame.ChatEncrypted -> {
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        val iv = try {
                            Base64.decode(frame.ivBase64.trim(), Base64.NO_WRAP)
                        } catch (e: Exception) {
                            diagnosticsTracker.updateLastErrorMessage("Invalid IV Base64")
                            return@collect
                        }
                        val cipherBytes = try {
                            Base64.decode(frame.ciphertextBase64.trim(), Base64.NO_WRAP)
                        } catch (e: Exception) {
                            diagnosticsTracker.updateLastErrorMessage("Invalid Ciphertext Base64")
                            return@collect
                        }

                        val peerAliases = collectPeerAliases(effectivePeerId, frame.senderId, rawSenderMac, lastGattMac)
                        val decryptedText = sessionCryptoManager.decryptText(
                            payload = EncryptedPayload(iv = iv, ciphertext = cipherBytes),
                            peerCandidates = peerAliases
                        )

                        if (decryptedText != null) {
                            consecutiveDecryptRecoveries.remove(canonicalRecoveryKey(effectivePeerId))
                            LayoverFileLogger.log(
                                "DISPATCH CHAT_ENCRYPTED -> UI/flow: msgId=${frame.msgId}, effectivePeerId=$effectivePeerId, " +
                                    "frameSenderId=${frame.senderId}, rawSenderMac=$rawSenderMac, aliases=$peerAliases"
                            )
                            diagnosticsTracker.updateLastErrorMessage(null)
                            val arrivalMillis = System.currentTimeMillis()
                            val newMsg = ChatMessage(
                                id = frame.msgId,
                                senderId = effectivePeerId,
                                text = decryptedText,
                                timestampLabel = formatMessageBubbleTimestamp(arrivalMillis),
                                isOutgoing = false,
                                timestampMillis = arrivalMillis
                            )
                            _incomingMessagesFlow.emit(Pair(effectivePeerId, newMsg))
                            sendMessageDeliveredAck(effectivePeerId, listOf(frame.msgId))
                        } else {
                            val currentFp = sessionCryptoManager.getSessionFingerprintForPeer(peerAliases)
                                ?: sessionCryptoManager.sessionFingerprint
                            val established = sessionCryptoManager.hasSessionForPeer(peerAliases)
                            LayoverFileLogger.logError(
                                "Failed to decrypt incoming chat message from $effectivePeerId " +
                                    "(msgId=${frame.msgId}, established=$established, fingerprint=${currentFp ?: "N/A"}, " +
                                    "algo=${sessionCryptoManager.getAlgorithmName()}, gattReady=${gattSessionManager.hasReadyChannel()})"
                            )
                            diagnosticsTracker.updateLastErrorMessage("Failed to decrypt chat message")
                            triggerDecryptRecoveryRehandshake(effectivePeerId, peerAliases)
                        }
                    }

                    is WireFrame.DeleteMessage -> {
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        val iv = try {
                            Base64.decode(frame.ivBase64.trim(), Base64.NO_WRAP)
                        } catch (_: Exception) {
                            diagnosticsTracker.updateLastErrorMessage("Invalid DELETE_MESSAGE IV Base64")
                            return@collect
                        }
                        val cipherBytes = try {
                            Base64.decode(frame.ciphertextBase64.trim(), Base64.NO_WRAP)
                        } catch (_: Exception) {
                            diagnosticsTracker.updateLastErrorMessage("Invalid DELETE_MESSAGE Ciphertext Base64")
                            return@collect
                        }
                        val peerAliases = collectPeerAliases(effectivePeerId, frame.senderId, rawSenderMac, lastGattMac)
                        val decryptedMsgId = sessionCryptoManager.decryptText(
                            payload = EncryptedPayload(iv = iv, ciphertext = cipherBytes),
                            peerCandidates = peerAliases
                        )?.trim()
                        if (!decryptedMsgId.isNullOrEmpty()) {
                            consecutiveDecryptRecoveries.remove(canonicalRecoveryKey(effectivePeerId))
                            LayoverFileLogger.log(
                                "DISPATCH DELETE_MESSAGE -> runtime: msgId=$decryptedMsgId, effectivePeerId=$effectivePeerId"
                            )
                            _incomingMessageDeletesFlow.emit(Pair(effectivePeerId, decryptedMsgId))
                        } else {
                            LayoverFileLogger.logError(
                                "Failed to decrypt incoming DELETE_MESSAGE from $effectivePeerId (aliases=$peerAliases)"
                            )
                            triggerDecryptRecoveryRehandshake(effectivePeerId, peerAliases)
                        }
                    }

                    is WireFrame.MessageDelivered -> {
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        val iv = runCatching { Base64.decode(frame.ivBase64.trim(), Base64.NO_WRAP) }.getOrNull() ?: return@collect
                        val cipherBytes = runCatching { Base64.decode(frame.ciphertextBase64.trim(), Base64.NO_WRAP) }.getOrNull() ?: return@collect
                        val peerAliases = collectPeerAliases(effectivePeerId, frame.senderId, rawSenderMac, lastGattMac)
                        val decryptedPayload = sessionCryptoManager.decryptText(
                            payload = EncryptedPayload(iv = iv, ciphertext = cipherBytes),
                            peerCandidates = peerAliases
                        )
                        val msgIds = decryptedPayload
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty()
                        if (msgIds.isNotEmpty()) {
                            LayoverFileLogger.log("DISPATCH MESSAGE_DELIVERED -> runtime: peerId=$effectivePeerId, msgIds=$msgIds")
                            _incomingMessageDeliveredFlow.emit(Pair(effectivePeerId, msgIds))
                        }
                    }

                    is WireFrame.MessageSeen -> {
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        val iv = runCatching { Base64.decode(frame.ivBase64.trim(), Base64.NO_WRAP) }.getOrNull() ?: return@collect
                        val cipherBytes = runCatching { Base64.decode(frame.ciphertextBase64.trim(), Base64.NO_WRAP) }.getOrNull() ?: return@collect
                        val peerAliases = collectPeerAliases(effectivePeerId, frame.senderId, rawSenderMac, lastGattMac)
                        val decryptedPayload = sessionCryptoManager.decryptText(
                            payload = EncryptedPayload(iv = iv, ciphertext = cipherBytes),
                            peerCandidates = peerAliases
                        )
                        val msgIds = decryptedPayload
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty()
                        if (msgIds.isNotEmpty()) {
                            LayoverFileLogger.log("DISPATCH MESSAGE_SEEN -> runtime: peerId=$effectivePeerId, msgIds=$msgIds")
                            _incomingMessageSeenFlow.emit(Pair(effectivePeerId, msgIds))
                        }
                    }

                    is WireFrame.PhotoPreview,
                    is WireFrame.PhotoDownloadRequest,
                    is WireFrame.WifiApReady,
                    is WireFrame.WifiApFallback,
                    is WireFrame.PhotoTransferDone -> {
                        if (effectivePeerId.isNotEmpty()) {
                            activePeerId = effectivePeerId
                        }
                        onIncomingPhotoWireFrame?.invoke(effectivePeerId, frame)
                    }
                }
            }
        }
    }

    private fun handleIncomingKeyExchange(
        frame: WireFrame.KeyExchange,
        effectivePeerId: String,
        rawSenderMac: String
    ) {
        if (effectivePeerId.isNotEmpty()) {
            activePeerId = effectivePeerId
        }
        val peerAliases = collectPeerAliases(effectivePeerId, frame.senderId, rawSenderMac, lastGattMac)

        val allowOverwrite = synchronized(handshakeLock) {
            when {
                frame.isReset && !frame.isResponse -> true
                frame.isReset && awaitingResetResponse -> true
                else -> false
            }
        }

        // Reject stray isResponse=true frames when we already have an established session unless
        // it matches the same key or we explicitly requested a reset.
        val hasExistingSession = sessionCryptoManager.hasSessionForPeer(peerAliases)
        if (frame.isResponse && !allowOverwrite && hasExistingSession && !sessionCryptoManager.needsReconnectConfirmation(peerAliases)) {
            val outcome = sessionCryptoManager.establishRemoteKeyForPeer(
                peerId = effectivePeerId,
                peerAliases = peerAliases,
                remotePublicKeyBase64 = frame.publicKeyBase64,
                algorithmName = frame.algorithm,
                allowOverwrite = false
            )
            if (outcome.status == KeyEstablishStatus.ALREADY_ESTABLISHED_SAME_KEY) {
                diagnosticsTracker.updateKeyHandshakeComplete(true)
                diagnosticsTracker.updateSessionFingerprint(outcome.fingerprint)
                diagnosticsTracker.updateLastErrorMessage(null)
                _e2eeSessionReadyFlow.tryEmit(effectivePeerId)
                flushPendingRetries()
            } else {
                LayoverFileLogger.log(
                    "Ignoring out-of-order KeyExchange response from $effectivePeerId " +
                        "(status=${outcome.status}, keeping fingerprint=${outcome.fingerprint})"
                )
            }
            return
        }

        val outcome = sessionCryptoManager.establishRemoteKeyForPeer(
            peerId = effectivePeerId,
            peerAliases = peerAliases,
            remotePublicKeyBase64 = frame.publicKeyBase64,
            algorithmName = frame.algorithm,
            allowOverwrite = allowOverwrite
        )

        when (outcome.status) {
            KeyEstablishStatus.ALGORITHM_UNSUPPORTED -> {
                // Remote sent X25519 to a device that only supports EC; reply with our EC key
                // (isResponse=false) so remote adopts EC and completes the handshake.
                val fallbackPub = outcome.localPublicKeyBase64 ?: sessionCryptoManager.ensureLocalKeyPair("EC")
                val fallbackAlgo = outcome.localAlgorithm ?: "EC"
                val shouldSendFallback = synchronized(handshakeLock) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeyExchangeSentMs >= 1_500L) {
                        hasSentKeyExchange = true
                        lastKeyExchangeSentMs = now
                        true
                    } else {
                        false
                    }
                }
                if (shouldSendFallback) {
                    val localId = currentUserProfile.handle.ifEmpty { "local" }
                    val fallbackFrame = WireFrameCodec.encodeKeyExchange(
                        senderId = localId,
                        publicKeyBase64 = fallbackPub,
                        algorithm = fallbackAlgo,
                        isResponse = false,
                        isReset = frame.isReset
                    )
                    LayoverFileLogger.log("Remote requested unsupported ${frame.algorithm}; sending $fallbackAlgo KeyExchange fallback to $effectivePeerId")
                    gattSessionManager.sendFrame(fallbackFrame)
                }
                diagnosticsTracker.updateKeyHandshakeComplete(false)
                return
            }

            KeyEstablishStatus.ALREADY_ESTABLISHED_SAME_KEY -> {
                synchronized(handshakeLock) {
                    if (frame.isReset && frame.isResponse) {
                        awaitingResetResponse = false
                    }
                }
                diagnosticsTracker.updateKeyHandshakeComplete(true)
                diagnosticsTracker.updateSessionFingerprint(outcome.fingerprint)
                diagnosticsTracker.updateLastErrorMessage(null)

                if (!frame.isResponse) {
                    sendKeyExchangeResponseIfNeeded(
                        peerId = effectivePeerId,
                        remotePublicKeyBase64 = frame.publicKeyBase64,
                        localPub = outcome.localPublicKeyBase64,
                        localAlgo = outcome.localAlgorithm,
                        isReset = frame.isReset
                    )
                } else {
                    LayoverFileLogger.logThrottled(
                        "key_exchange_dup_$effectivePeerId",
                        "Confirmed existing E2EE session with $effectivePeerId (fingerprint=${outcome.fingerprint}); ignoring duplicate response."
                    )
                }
                _e2eeSessionReadyFlow.tryEmit(effectivePeerId)
                flushPendingRetries()
                return
            }

            KeyEstablishStatus.IGNORED_EXISTING_SESSION,
            KeyEstablishStatus.IGNORED_STALE_KEY -> {
                LayoverFileLogger.log(
                    "Preserved existing E2EE session for $effectivePeerId (status=${outcome.status}, " +
                        "fingerprint=${outcome.fingerprint}, incomingIsResponse=${frame.isResponse}, incomingIsReset=${frame.isReset})"
                )
                return
            }

            KeyEstablishStatus.ESTABLISHED -> {
                synchronized(handshakeLock) {
                    if (frame.isReset && frame.isResponse) {
                        awaitingResetResponse = false
                    }
                }
                consecutiveDecryptRecoveries.remove(canonicalRecoveryKey(effectivePeerId))
                LayoverFileLogger.log(
                    "SUCCESS: E2EE Key Exchange established for peer=$effectivePeerId " +
                        "(algo=${outcome.localAlgorithm}, isResponse=${frame.isResponse}, isReset=${frame.isReset}). " +
                        "Fingerprint=${outcome.fingerprint}"
                )
                diagnosticsTracker.updateKeyHandshakeComplete(true)
                diagnosticsTracker.updateSessionFingerprint(outcome.fingerprint)
                diagnosticsTracker.updateLastErrorMessage(null)

                if (!frame.isResponse) {
                    sendKeyExchangeResponseIfNeeded(
                        peerId = effectivePeerId,
                        remotePublicKeyBase64 = frame.publicKeyBase64,
                        localPub = outcome.localPublicKeyBase64,
                        localAlgo = outcome.localAlgorithm,
                        isReset = frame.isReset
                    )
                }
                _e2eeSessionReadyFlow.tryEmit(effectivePeerId)
                flushPendingRetries()
                return
            }

            KeyEstablishStatus.FAILED -> {
                LayoverFileLogger.logError("Key exchange failed for peer=$effectivePeerId (algo=${frame.algorithm})")
                diagnosticsTracker.updateLastErrorMessage("E2EE key exchange failed")
            }
        }
    }

    private fun sendKeyExchangeResponseIfNeeded(
        peerId: String,
        remotePublicKeyBase64: String,
        localPub: String?,
        localAlgo: String?,
        isReset: Boolean
    ) {
        val pub = localPub ?: sessionCryptoManager.getLocalPublicKeyBase64() ?: return
        val algo = localAlgo ?: sessionCryptoManager.getAlgorithmName()
        val now = System.currentTimeMillis()
        val shouldRespond = synchronized(handshakeLock) {
            val alreadyRespondedSame = lastRespondedRemotePublicKey == remotePublicKeyBase64
            val allowResetReply = isReset && (now - lastResetResponseTimeMs) >= RESET_RESPONSE_DEBOUNCE_MS
            if (!alreadyRespondedSame || allowResetReply) {
                hasSentKeyExchange = true
                lastRespondedRemotePublicKey = remotePublicKeyBase64
                lastKeyExchangeSentMs = now
                if (isReset) {
                    lastResetResponseTimeMs = now
                }
                true
            } else {
                false
            }
        }
        if (!shouldRespond) return

        val localId = currentUserProfile.handle.ifEmpty { "local" }
        val responsePayload = WireFrameCodec.encodeKeyExchange(
            senderId = localId,
            publicKeyBase64 = pub,
            algorithm = algo,
            isResponse = true,
            isReset = isReset
        )
        LayoverFileLogger.log("Sending KeyExchange response to peer=$peerId (algo=$algo, isReset=$isReset)")
        gattSessionManager.sendFrame(responsePayload, collectPeerAliases(peerId, activePeerId, lastGattMac))
    }

    private fun triggerDecryptRecoveryRehandshake(peerId: String, peerAliases: Set<String>) {
        val recoveryKey = canonicalRecoveryKey(peerId)
        val now = System.currentTimeMillis()
        val attempts = synchronized(handshakeLock) {
            val lastMs = lastDecryptRecoveryTimeMs[recoveryKey] ?: 0L
            val count = consecutiveDecryptRecoveries[recoveryKey] ?: 0
            if (now - lastMs < DECRYPT_RECOVERY_COOLDOWN_MS) {
                LayoverFileLogger.log("Decrypt recovery re-handshake throttled for peer=$peerId (cooldown active).")
                return
            }
            if (count >= MAX_CONSECUTIVE_DECRYPT_RECOVERIES) {
                LayoverFileLogger.logError(
                    "Decrypt recovery re-handshake cap ($MAX_CONSECUTIVE_DECRYPT_RECOVERIES) reached for peer=$peerId; " +
                        "avoiding infinite re-handshake loop."
                )
                return
            }
            val nextCount = count + 1
            lastDecryptRecoveryTimeMs[recoveryKey] = now
            consecutiveDecryptRecoveries[recoveryKey] = nextCount
            hasSentKeyExchange = false
            lastRespondedRemotePublicKey = null
            awaitingResetResponse = true
            nextCount
        }

        LayoverFileLogger.log(
            "Clearing stale E2EE session and initiating controlled recovery re-handshake " +
                "(attempt $attempts/$MAX_CONSECUTIVE_DECRYPT_RECOVERIES) for peer=$peerId"
        )
        sessionCryptoManager.clearPeerSession(peerAliases)
        diagnosticsTracker.updateKeyHandshakeComplete(false)
        diagnosticsTracker.updateSessionFingerprint(null)

        if (gattSessionManager.hasReadyChannel()) {
            initiateE2eeHandshake(isReset = true)
        } else {
            connectGattDevice(peerId)
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
        if (peerId.isBlank()) return
        linkOutboundPeerAliases(peerId)
        val peerAliases = collectPeerAliases(peerId, activePeerId, lastGattMac)
        val channelReady = gattSessionManager.isReadyForPeer(peerAliases) || gattSessionManager.hasReadyChannel()
        LayoverFileLogger.log(
            "sendBlockState to peerId=$peerId, blocked=$blocked, channelReady=$channelReady, aliases=$peerAliases"
        )
        if (!channelReady && !gattSessionManager.isClientConnecting()) {
            connectGattDevice(peerId)
        }
        val localId = currentUserProfile.handle.ifEmpty { "user" }
        val frame = WireFrameCodec.encodePeerBlockState(
            senderId = localId,
            blocked = blocked
        )
        val sent = gattSessionManager.sendFrame(
            text = frame,
            targetAliases = peerAliases,
            routePreference = GattRoutePreference.BOTH_IF_AVAILABLE
        )
        if (sent) {
            diagnosticsTracker.incrementMessagesSent()
        }
    }

    private fun initiateE2eeHandshake(isReset: Boolean = false) {
        if (!gattSessionManager.hasReadyChannel()) {
            LayoverFileLogger.logThrottled(
                "key_exchange_skip_not_ready",
                "Skipping initiateE2eeHandshake(isReset=$isReset): GATT channel not yet ready."
            )
            return
        }

        val now = System.currentTimeMillis()
        val activeAliases = collectPeerAliases(activePeerId, lastGattMac)
        val alreadyConfirmed = !isReset &&
            sessionCryptoManager.hasSessionForPeer(activeAliases) &&
            !sessionCryptoManager.needsReconnectConfirmation(activeAliases)

        val shouldSend = synchronized(handshakeLock) {
            if (alreadyConfirmed && hasSentKeyExchange) {
                LayoverFileLogger.logThrottled(
                    "key_exchange_skip_established",
                    "KeyExchange already confirmed for active peer. Skipping duplicate send."
                )
                return
            }
            if (!isReset && hasSentKeyExchange && (now - lastKeyExchangeSentMs) < HANDSHAKE_DEBOUNCE_MS) {
                LayoverFileLogger.logThrottled(
                    "key_exchange_skip_debounce",
                    "KeyExchange recently sent; debouncing duplicate initiateE2eeHandshake."
                )
                return
            }
            hasSentKeyExchange = true
            lastKeyExchangeSentMs = now
            if (isReset) {
                awaitingResetResponse = true
            }
            true
        }
        if (!shouldSend) return

        LayoverFileLogger.log("Initiating E2EE key exchange handshake over GATT (isReset=$isReset)...")
        val pubKeyB64 = sessionCryptoManager.ensureLocalKeyPair()
        val algo = sessionCryptoManager.getAlgorithmName()
        val localId = currentUserProfile.handle.ifEmpty { "local" }

        val keyExchangePayload = WireFrameCodec.encodeKeyExchange(
            senderId = localId,
            publicKeyBase64 = pubKeyB64,
            algorithm = algo,
            isResponse = false,
            isReset = isReset
        )
        val sent = gattSessionManager.sendFrame(keyExchangePayload, activeAliases)
        if (sent) {
            diagnosticsTracker.incrementMessagesSent()
            if (!isReset) {
                diagnosticsTracker.updateLastErrorMessage(null)
            }
        } else {
            synchronized(handshakeLock) {
                hasSentKeyExchange = false
                lastKeyExchangeSentMs = 0L
                if (isReset) awaitingResetResponse = false
            }
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

    private fun linkOutboundPeerAliases(peerId: String) {
        val discovered = discoveryManager.findDiscoveredPeer(peerId)
        val deviceMac = discoveryManager.getDiscoveredDevice(peerId)?.address
        val activeMatchesTarget = !isDistinctPeerDevId(activePeerId, peerId)
        PeerAliasRegistry.linkAliases(
            buildList {
                add(peerId)
                deviceMac?.let { add(it) }
                discovered?.id?.let { add(it) }
                discovered?.handle?.takeIf { it.isNotEmpty() }?.let { add(it) }
                if (activeMatchesTarget && activePeerId != null) add(activePeerId)
                if (activeMatchesTarget && lastGattMac != null) add(lastGattMac)
            }.filter { PeerAliasRegistry.isStablePeerIdentifier(it) }
        )
    }

    override fun sendConnectionRequest(peerId: String) {
        linkOutboundPeerAliases(peerId)
        val now = System.currentTimeMillis()
        val peerAliases = collectPeerAliases(peerId)
        val recentRequestExists = peerAliases.any { alias ->
            val lastTime = lastRequestTimeMap[alias.lowercase()] ?: 0L
            (now - lastTime) < 800L
        }
        if (recentRequestExists) {
            LayoverFileLogger.log("Debouncing duplicate sendConnectionRequest for peerId=$peerId within 800ms.")
            return
        }
        peerAliases.forEach { alias ->
            lastRequestTimeMap[alias.lowercase()] = now
        }

        LayoverFileLogger.log("sendConnectionRequest to peerId=$peerId")
        if (isPeerIdentifierBlocked(peerId)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        activePeerId = peerId

        if (gattSessionManager.isReadyForPeer(peerAliases)) {
            LayoverFileLogger.log("Reusing active ready GATT channel for sendConnectionRequest to peerId=$peerId")
            if (!sessionCryptoManager.hasSessionForPeer(peerAliases)) {
                initiateE2eeHandshake(isReset = false)
            }
        } else {
            connectGattDevice(peerId)
        }

        val localName = currentUserProfile.displayName.ifEmpty { "Layover User" }
        val reqFrame = WireFrameCodec.encodeConnectionRequest(
            senderId = currentUserProfile.handle.ifEmpty { "user" },
            displayName = localName,
            gender = currentUserProfile.gender,
            age = currentUserProfile.age
        )
        gattSessionManager.sendFrame(reqFrame, peerAliases)
        sendProfileSync(peerId)
    }

    override fun acceptConnectionRequest(peerId: String) {
        linkOutboundPeerAliases(peerId)
        LayoverFileLogger.log("acceptConnectionRequest for peerId=$peerId")
        if (isPeerIdentifierBlocked(peerId)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        activePeerId = peerId

        val peerAliases = collectPeerAliases(peerId, lastGattMac)
        if (gattSessionManager.isReadyForPeer(peerAliases)) {
            // Reuse the existing GATT server/client channel instead of opening a competing reverse GATT client.
            LayoverFileLogger.log("Reusing active ready GATT channel for acceptConnectionRequest to peerId=$peerId")
            if (!sessionCryptoManager.hasSessionForPeer(peerAliases)) {
                initiateE2eeHandshake(isReset = false)
            }
        } else {
            connectGattDevice(peerId)
        }

        val acceptFrame = WireFrameCodec.encodeConnectionAccept(
            senderId = currentUserProfile.handle.ifEmpty { "user" }
        )
        gattSessionManager.sendFrame(acceptFrame, peerAliases)
        sendProfileSync(peerId)
    }

    override fun declineConnectionRequest(peerId: String) {
        linkOutboundPeerAliases(peerId)
        LayoverFileLogger.log("declineConnectionRequest for peerId=$peerId")
        val declineFrame = WireFrameCodec.encodeConnectionDecline(
            senderId = currentUserProfile.handle.ifEmpty { "user" }
        )
        gattSessionManager.sendFrame(declineFrame, collectPeerAliases(peerId, activePeerId, lastGattMac))
    }

    @SuppressLint("MissingPermission")
    private fun resumeScanningIfEligible() {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) return
        if (bluetoothAdapter?.isEnabled != true) return
        if (!bleStateController.statusState.value.isScanning) {
            LayoverFileLogger.log("Resuming BLE scanning to track peer RPA MAC rotations.")
            discoveryManager.startScanning()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleGattReconnectRequest(failedMac: String?, reason: String): Boolean {
        val targetPeerId = activePeerId ?: failedMac
        LayoverFileLogger.log(
            "GATT reconnect/fallback requested: targetPeerId=$targetPeerId, failedMac=$failedMac, reason=$reason"
        )
        if (targetPeerId.isNullOrBlank()) {
            resumeScanningIfEligible()
            return false
        }
        if (!BlePermissionHelper.hasAllRequiredPermissions(context) || bluetoothAdapter?.isEnabled != true) {
            return false
        }

        val freshDevice = discoveryManager.getDiscoveredDevice(targetPeerId)
        if (freshDevice != null) {
            val rotated = failedMac != null && !freshDevice.address.equals(failedMac, ignoreCase = true)
            LayoverFileLogger.log(
                "GATT reconnect resolved peer device: targetPeerId=$targetPeerId, mac=${freshDevice.address}, " +
                    "rotatedFromFailedMac=$rotated (failedMac=$failedMac)"
            )
            lastGattMac = freshDevice.address
            PeerAliasRegistry.linkAliases(listOfNotNull(targetPeerId, failedMac, freshDevice.address))
            discoveryManager.stopScanning()
            gattSessionManager.connectToDevice(freshDevice)
            return true
        }

        LayoverFileLogger.log("GATT reconnect: no cached BluetoothDevice for $targetPeerId; resuming BLE scan for fresh RPA MAC.")
        resumeScanningIfEligible()
        return false
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

        val resolvedMac = PeerAliasRegistry.resolveMacAddresses(peerId).lastOrNull()
            ?: peerId.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
        if (resolvedMac != null) {
            lastGattMac = resolvedMac
            PeerAliasRegistry.linkAliases(listOf(peerId, resolvedMac))
            val device = try {
                adapter.getRemoteDevice(resolvedMac)
            } catch (e: Exception) {
                LayoverFileLogger.logError("Failed to get remote device for MAC=$resolvedMac", e)
                null
            }

            if (device != null) {
                LayoverFileLogger.log("Connecting GATT client to resolved alias MAC=$resolvedMac for peerId=$peerId")
                discoveryManager.stopScanning()
                gattSessionManager.connectToDevice(device)
            } else {
                resumeScanningIfEligible()
                gattSessionManager.startServer()
            }
        } else {
            LayoverFileLogger.log("PeerId $peerId has no known Bluetooth MAC yet. Resuming scan & ensuring GATT server is listening.")
            resumeScanningIfEligible()
            gattSessionManager.startServer()
        }
    }

    private fun flushPendingRetries() {
        val activeAliases = collectPeerAliases(activePeerId, lastGattMac)
        if (!sessionCryptoManager.hasSessionForPeer(activeAliases)) return
        if (!gattSessionManager.hasReadyChannel()) return
        val remaining = mutableListOf<Triple<String, String, String>>()
        while (pendingEncryptedRetries.isNotEmpty()) {
            val item = pendingEncryptedRetries.poll() ?: break
            val itemAliases = collectPeerAliases(item.first, activePeerId, lastGattMac)
            if (sessionCryptoManager.hasSessionForPeer(itemAliases) &&
                !sessionCryptoManager.needsReconnectConfirmation(itemAliases)
            ) {
                sendMessage(item.first, item.second, item.third)
            } else {
                remaining.add(item)
            }
        }
        remaining.forEach { pendingEncryptedRetries.add(it) }

        val remainingDeletes = mutableListOf<Pair<String, String>>()
        while (pendingEncryptedDeleteRetries.isNotEmpty()) {
            val item = pendingEncryptedDeleteRetries.poll() ?: break
            val itemAliases = collectPeerAliases(item.first, activePeerId, lastGattMac)
            if (sessionCryptoManager.hasSessionForPeer(itemAliases) &&
                !sessionCryptoManager.needsReconnectConfirmation(itemAliases)
            ) {
                sendDeleteMessage(item.first, item.second)
            } else {
                remainingDeletes.add(item)
            }
        }
        remainingDeletes.forEach { pendingEncryptedDeleteRetries.add(it) }

        val remainingDeliveredAcks = mutableListOf<Pair<String, List<String>>>()
        while (pendingDeliveredAckRetries.isNotEmpty()) {
            val item = pendingDeliveredAckRetries.poll() ?: break
            val itemAliases = collectPeerAliases(item.first, activePeerId, lastGattMac)
            if (sessionCryptoManager.hasSessionForPeer(itemAliases) &&
                !sessionCryptoManager.needsReconnectConfirmation(itemAliases)
            ) {
                sendMessageDeliveredAck(item.first, item.second)
            } else {
                remainingDeliveredAcks.add(item)
            }
        }
        remainingDeliveredAcks.forEach { pendingDeliveredAckRetries.add(it) }

        val remainingSeenAcks = mutableListOf<Pair<String, List<String>>>()
        while (pendingSeenAckRetries.isNotEmpty()) {
            val item = pendingSeenAckRetries.poll() ?: break
            val itemAliases = collectPeerAliases(item.first, activePeerId, lastGattMac)
            if (sessionCryptoManager.hasSessionForPeer(itemAliases) &&
                !sessionCryptoManager.needsReconnectConfirmation(itemAliases)
            ) {
                sendMessageSeenAck(item.first, item.second)
            } else {
                remainingSeenAcks.add(item)
            }
        }
        remainingSeenAcks.forEach { pendingSeenAckRetries.add(it) }
    }

    fun sendMessageDeliveredAck(peerId: String, msgIds: List<String>): Boolean {
        return sendEncryptedMessageAck(peerId = peerId, msgIds = msgIds, isSeen = false)
    }

    fun sendMessageSeenAck(peerId: String, msgIds: List<String>): Boolean {
        return sendEncryptedMessageAck(peerId = peerId, msgIds = msgIds, isSeen = true)
    }

    private fun sendEncryptedMessageAck(
        peerId: String,
        msgIds: List<String>,
        isSeen: Boolean
    ): Boolean {
        val cleanIds = msgIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (peerId.isBlank() || cleanIds.isEmpty()) return false
        if (isPeerIdentifierBlocked(peerId, activePeerId, lastGattMac)) {
            return false
        }
        linkOutboundPeerAliases(peerId)
        val peerAliases = collectPeerAliases(peerId, activePeerId, lastGattMac)
        val channelReady = gattSessionManager.isReadyForPeer(peerAliases) || gattSessionManager.hasReadyChannel()
        val hasSession = sessionCryptoManager.hasSessionForPeer(peerAliases) &&
            !sessionCryptoManager.needsReconnectConfirmation(peerAliases)

        if (!channelReady || !hasSession) {
            if (isSeen) {
                pendingSeenAckRetries.add(peerId to cleanIds)
            } else {
                pendingDeliveredAckRetries.add(peerId to cleanIds)
            }
            return false
        }

        val localId = currentUserProfile.handle.ifEmpty { "user" }
        val payloadText = cleanIds.joinToString(",")
        val encrypted = sessionCryptoManager.encryptText(payloadText, peerAliases) ?: return false
        val wireFrame = if (isSeen) {
            WireFrameCodec.encodeMessageSeen(senderId = localId, encryptedMsgIdsPayload = encrypted)
        } else {
            WireFrameCodec.encodeMessageDelivered(senderId = localId, encryptedMsgIdsPayload = encrypted)
        }
        val sent = gattSessionManager.sendFrame(
            text = wireFrame,
            targetAliases = peerAliases,
            routePreference = GattRoutePreference.AUTO
        )
        LayoverFileLogger.log(
            "Sent encrypted ${if (isSeen) "MESSAGE_SEEN" else "MESSAGE_DELIVERED"} ACK -> peerId=$peerId, msgIds=$cleanIds, sent=$sent"
        )
        return sent
    }

    fun sendDeleteMessage(peerId: String, msgId: String): Boolean {
        if (msgId.isBlank()) return false
        if (isPeerIdentifierBlocked(peerId, activePeerId, lastGattMac)) {
            diagnosticsTracker.incrementBlockedDrops()
            return false
        }
        if (peerId.isNotEmpty()) {
            activePeerId = peerId
        }
        linkOutboundPeerAliases(peerId)
        val peerAliases = collectPeerAliases(peerId, activePeerId, lastGattMac)
        val readyForPeer = gattSessionManager.isReadyForPeer(peerAliases)
        val channelReady = readyForPeer || gattSessionManager.hasReadyChannel()
        val needsConfirm = sessionCryptoManager.needsReconnectConfirmation(peerAliases)

        if (!channelReady || needsConfirm) {
            LayoverFileLogger.log(
                "Deferring encrypted DELETE_MESSAGE(msgId=$msgId) for peerId=$peerId until GATT & E2EE confirmed " +
                    "(channelReady=$channelReady, needsConfirm=$needsConfirm)."
            )
            pendingEncryptedDeleteRetries.add(peerId to msgId)
            if (!channelReady && !gattSessionManager.isClientConnecting()) {
                connectGattDevice(peerId)
            } else if (channelReady) {
                initiateE2eeHandshake(isReset = false)
            }
            return false
        }

        val localId = currentUserProfile.handle.ifEmpty { "user" }
        val encryptedPayload = sessionCryptoManager.encryptText(msgId, peerAliases)
        return if (encryptedPayload != null) {
            val wirePayload = WireFrameCodec.encodeDeleteMessage(
                senderId = localId,
                encryptedMsgIdPayload = encryptedPayload
            )
            val sent = gattSessionManager.sendFrame(
                text = wirePayload,
                targetAliases = peerAliases,
                routePreference = GattRoutePreference.BOTH_IF_AVAILABLE
            )
            LayoverFileLogger.log("Sent encrypted DELETE_MESSAGE(msgId=$msgId) -> peerId=$peerId, sent=$sent")
            if (sent) {
                diagnosticsTracker.incrementMessagesSent()
            }
            sent
        } else {
            LayoverFileLogger.logError("Failed to encrypt DELETE_MESSAGE(msgId=$msgId) for peerId=$peerId; queueing retry.")
            pendingEncryptedDeleteRetries.add(peerId to msgId)
            if (gattSessionManager.hasReadyChannel()) {
                synchronized(handshakeLock) { hasSentKeyExchange = false }
                initiateE2eeHandshake(isReset = false)
            } else if (!gattSessionManager.isClientConnecting()) {
                connectGattDevice(peerId)
            }
            false
        }
    }

    override fun sendMessage(peerId: String, text: String, msgId: String) {
        if (isPeerIdentifierBlocked(peerId, activePeerId, lastGattMac)) {
            diagnosticsTracker.incrementBlockedDrops()
            return
        }
        if (peerId.isNotEmpty()) {
            activePeerId = peerId
        }
        linkOutboundPeerAliases(peerId)
        val peerAliases = collectPeerAliases(peerId, activePeerId, lastGattMac)
        val readyForPeer = gattSessionManager.isReadyForPeer(peerAliases)
        val channelReady = readyForPeer || gattSessionManager.hasReadyChannel()
        val needsConfirm = sessionCryptoManager.needsReconnectConfirmation(peerAliases)
        val routePlan = gattSessionManager.inspectOutboundRoutePlan(peerAliases)

        LayoverFileLogger.log(
            "sendMessage routing for peerId=$peerId: msgId=$msgId, primary=${routePlan.primary}, fallback=${routePlan.fallback}, " +
                "channelReady=$channelReady, readyForPeer=$readyForPeer, needsConfirm=$needsConfirm, " +
                "clientConnecting=${gattSessionManager.isClientConnecting()}, aliases=$peerAliases"
        )

        if (!channelReady || needsConfirm) {
            LayoverFileLogger.log(
                "Deferring encrypted chat message for peerId=$peerId until GATT & E2EE session confirmed " +
                    "(channelReady=$channelReady, needsConfirm=$needsConfirm)."
            )
            pendingEncryptedRetries.add(Triple(peerId, text, msgId))
            if (!channelReady && !gattSessionManager.isClientConnecting()) {
                connectGattDevice(peerId)
            } else if (channelReady) {
                initiateE2eeHandshake(isReset = false)
            }
            return
        }

        val localId = currentUserProfile.handle.ifEmpty { "user" }
        val resolvedMsgId = msgId.ifBlank { "msg_${System.currentTimeMillis()}" }

        val encryptedPayload = sessionCryptoManager.encryptText(text, peerAliases)
        if (encryptedPayload != null) {
            val wirePayload = WireFrameCodec.encodeChatEncrypted(
                msgId = resolvedMsgId,
                senderId = localId,
                timestamp = System.currentTimeMillis(),
                payload = encryptedPayload
            )
            val sent = gattSessionManager.sendFrame(wirePayload, targetAliases = peerAliases)
            if (sent) {
                diagnosticsTracker.incrementMessagesSent()
                diagnosticsTracker.updateLastErrorMessage(null)
            } else {
                diagnosticsTracker.updateLastErrorMessage("Failed to send message frame")
            }
        } else {
            LayoverFileLogger.logError("Failed to encrypt text for peerId=$peerId. Queueing message & initiating E2EE key exchange...")
            pendingEncryptedRetries.add(Triple(peerId, text, resolvedMsgId))
            if (gattSessionManager.hasReadyChannel()) {
                synchronized(handshakeLock) { hasSentKeyExchange = false }
                initiateE2eeHandshake(isReset = false)
            } else if (!gattSessionManager.isClientConnecting()) {
                connectGattDevice(peerId)
            }
            diagnosticsTracker.updateLastErrorMessage("E2EE key session missing - re-initiating handshake")
        }
    }

    fun cleanup() {
        stopDiscovery()
        rateLimiter.clearAll()
        sessionCryptoManager.clearSession()
        synchronized(handshakeLock) {
            hasSentKeyExchange = false
            lastRespondedRemotePublicKey = null
            lastResetResponseTimeMs = 0L
            lastKeyExchangeSentMs = 0L
            awaitingResetResponse = false
        }
        lastDecryptRecoveryTimeMs.clear()
        consecutiveDecryptRecoveries.clear()
        pendingEncryptedRetries.clear()
        pendingEncryptedDeleteRetries.clear()
        pendingDeliveredAckRetries.clear()
        pendingSeenAckRetries.clear()
        gattSessionManager.cleanup()
    }
}
