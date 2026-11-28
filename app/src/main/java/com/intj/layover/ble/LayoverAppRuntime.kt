package com.intj.layover.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.model.ChatMessage
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.GenderOption
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.MessageDeliveryStatus
import com.intj.layover.model.NearbyPeer
import com.intj.layover.model.PeerAvatar
import com.intj.layover.model.PeerDpDownloadState
import com.intj.layover.model.PhotoAttachment
import com.intj.layover.model.PhotoTransferState
import com.intj.layover.model.UserProfile
import com.intj.layover.notifications.LayoverNotificationManager
import com.intj.layover.notifications.NotificationEligibilityEvaluator
import com.intj.layover.notifications.UnreadMessageTracker
import com.intj.layover.p2p.WireFrame
import com.intj.layover.ui.screens.canonicalDeduplicatePeers
import com.intj.layover.ui.screens.formatMessageBubbleTimestamp
import com.intj.layover.wifi.PeerDpCacheStore
import com.intj.layover.wifi.WifiPhotoTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-scoped BLE, chat state, and notification runtime shared between
 * [com.intj.layover.MainActivity] and [BleProximityService] so incoming messages
 * and connection requests keep working when the app is minimized or closed.
 */
class LayoverAppRuntime private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "LayoverBLE"
        const val PREFS_NAME = "layover_user_profile"
        private const val KEY_BLOCKED_PEER_IDS = "blocked_peer_ids"
        private const val KEY_BLOCKED_BY_PEER_IDS = "blocked_by_peer_ids"
        private const val KEY_MUTED_PEER_IDS = "muted_peer_ids"
        private const val KEY_ACCEPTED_PEER_IDS = "accepted_peer_ids"
        private const val KEY_CHAT_HISTORY = "chat_history_v1"
        private const val MAX_PERSISTED_MESSAGES_PER_PEER = 200

        internal fun serializeChatHistory(chatMessages: Map<String, List<ChatMessage>>): String {
            val encoder = java.util.Base64.getEncoder()
            fun encode(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

            val lines = ArrayList<String>()
            for ((peerKey, messages) in chatMessages) {
                if (peerKey.isBlank() || messages.isEmpty()) continue
                val trimmed = if (messages.size > MAX_PERSISTED_MESSAGES_PER_PEER) {
                    messages.takeLast(MAX_PERSISTED_MESSAGES_PER_PEER)
                } else {
                    messages
                }
                for (msg in trimmed) {
                    val ts = if (msg.timestampMillis > 0L) msg.timestampMillis else System.currentTimeMillis()
                    val replyEncoded = msg.replyToText?.let { encode(it) } ?: ""
                    val outgoingFlag = if (msg.isOutgoing) "1" else "0"
                    val base = "${encode(peerKey)}|${encode(msg.id)}|${encode(msg.senderId)}|" +
                        "$ts|$outgoingFlag|${encode(msg.text)}|$replyEncoded"
                    val photo = msg.photoAttachment
                    val photoPart = if (photo != null) {
                        val pathEncoded = photo.localFilePath?.let { encode(it) } ?: ""
                        "${encode(photo.transferId)}|${photo.fileSizeBytes}|$pathEncoded|${photo.state.name}|${encode(photo.mimeType)}"
                    } else {
                        "||||"
                    }
                    val deliveredAtPart = msg.deliveredAtMillis?.toString() ?: ""
                    val seenAtPart = msg.seenAtMillis?.toString() ?: ""
                    lines.add("$base|$photoPart|${msg.deliveryStatus.name}|$deliveredAtPart|$seenAtPart")
                }
            }
            return lines.joinToString("\n")
        }

        internal fun deserializeChatHistory(
            serialized: String?,
            fallbackNowMillis: Long = System.currentTimeMillis()
        ): Map<String, List<ChatMessage>> {
            if (serialized.isNullOrBlank()) return emptyMap()
            val decoder = java.util.Base64.getDecoder()
            fun decode(value: String): String = String(decoder.decode(value), Charsets.UTF_8)

            val result = LinkedHashMap<String, MutableList<ChatMessage>>()
            for (line in serialized.lineSequence()) {
                if (line.isBlank()) continue
                val parts = line.split("|")
                if (parts.size < 6) continue
                runCatching {
                    val peerKey = decode(parts[0])
                    val msgId = decode(parts[1])
                    val senderId = decode(parts[2])
                    val parsedTs = parts[3].toLongOrNull() ?: 0L
                    val effectiveTs = if (parsedTs > 0L) parsedTs else fallbackNowMillis
                    val isOutgoing = parts[4] == "1"
                    val text = decode(parts[5])
                    val replyTo = parts.getOrNull(6)?.takeIf { it.isNotEmpty() }?.let { decode(it) }
                    val photoAttachment = if (parts.size >= 12 && parts[7].isNotEmpty()) {
                        val transferId = decode(parts[7])
                        val fileSize = parts[8].toLongOrNull() ?: 0L
                        val localPath = parts[9].takeIf { it.isNotEmpty() }?.let { decode(it) }
                        val parsedState = runCatching { PhotoTransferState.valueOf(parts[10]) }
                            .getOrDefault(PhotoTransferState.AVAILABLE)
                        val mime = parts[11].takeIf { it.isNotEmpty() }?.let { decode(it) } ?: "image/jpeg"
                        val restoredState = if (parsedState == PhotoTransferState.COMPLETED && localPath != null) {
                            PhotoTransferState.COMPLETED
                        } else if (parsedState == PhotoTransferState.DOWNLOADING ||
                            parsedState == PhotoTransferState.CONNECTING_WIFI ||
                            parsedState == PhotoTransferState.QUEUED
                        ) {
                            PhotoTransferState.AVAILABLE
                        } else {
                            parsedState
                        }
                        PhotoAttachment(
                            transferId = transferId,
                            fileSizeBytes = fileSize,
                            mimeType = mime,
                            localFilePath = localPath,
                            state = restoredState,
                            progress = if (restoredState == PhotoTransferState.COMPLETED) 1f else 0f
                        )
                    } else {
                        null
                    }
                    val defaultStatus = if (isOutgoing) MessageDeliveryStatus.SENT else MessageDeliveryStatus.DELIVERED
                    val deliveryStatus = parts.getOrNull(12)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { runCatching { MessageDeliveryStatus.valueOf(it) }.getOrNull() }
                        ?: defaultStatus
                    val deliveredAtMillis = parts.getOrNull(13)?.takeIf { it.isNotBlank() }?.toLongOrNull()
                    val seenAtMillis = parts.getOrNull(14)?.takeIf { it.isNotBlank() }?.toLongOrNull()
                    if (peerKey.isNotBlank() && msgId.isNotBlank()) {
                        val list = result.getOrPut(peerKey) { mutableListOf() }
                        if (list.none { it.id == msgId }) {
                            list.add(
                                ChatMessage(
                                    id = msgId,
                                    senderId = senderId,
                                    text = text,
                                    timestampLabel = formatMessageBubbleTimestamp(effectiveTs),
                                    isOutgoing = isOutgoing,
                                    replyToText = replyTo,
                                    timestampMillis = effectiveTs,
                                    photoAttachment = photoAttachment,
                                    deliveryStatus = deliveryStatus,
                                    deliveredAtMillis = deliveredAtMillis,
                                    seenAtMillis = seenAtMillis
                                )
                            )
                        }
                    }
                }
            }
            return result
        }

        internal data class MessageDeletionResult(
            val updatedChatMessages: Map<String, List<ChatMessage>>,
            val removedMessages: List<ChatMessage>,
            val orphanedPhotoFilePaths: Set<String>
        )

        /**
         * Removes [msgId] from matching peer alias buckets (or all buckets when [peerAliases] is
         * empty) and computes any photo local file paths that are no longer referenced by any
         * remaining message across the entire history.
         */
        internal fun removeMessageFromHistory(
            chatMessages: Map<String, List<ChatMessage>>,
            msgId: String,
            peerAliases: Collection<String> = emptyList()
        ): MessageDeletionResult {
            if (msgId.isBlank() || chatMessages.isEmpty()) {
                return MessageDeletionResult(chatMessages, emptyList(), emptySet())
            }
            val cleanAliases = peerAliases
                .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                .toSet()

            val removedByKey = LinkedHashMap<String, ChatMessage>()
            val updatedMap = LinkedHashMap<String, List<ChatMessage>>(chatMessages.size)

            for ((key, list) in chatMessages) {
                val keyMatchesPeer = cleanAliases.isEmpty() ||
                    cleanAliases.any { alias -> key.equals(alias, ignoreCase = true) }
                if (!keyMatchesPeer) {
                    // Check if any message with this msgId in this bucket has a matching senderId alias
                    val hasSenderMatch = list.any { msg ->
                        msg.id == msgId && cleanAliases.any { alias -> msg.senderId.equals(alias, ignoreCase = true) }
                    }
                    if (!hasSenderMatch) {
                        updatedMap[key] = list
                        continue
                    }
                }

                val remaining = ArrayList<ChatMessage>(list.size)
                for (msg in list) {
                    if (msg.id == msgId) {
                        removedByKey.putIfAbsent(msg.id + "|" + (msg.photoAttachment?.localFilePath ?: ""), msg)
                    } else {
                        remaining.add(msg)
                    }
                }
                if (remaining.isNotEmpty()) {
                    updatedMap[key] = remaining
                }
            }

            if (removedByKey.isEmpty()) {
                return MessageDeletionResult(chatMessages, emptyList(), emptySet())
            }

            val removedList = removedByKey.values.toList()
            val candidatePaths = removedList
                .mapNotNull { it.photoAttachment?.localFilePath?.takeIf { path -> path.isNotBlank() } }
                .toSet()
            val stillReferencedPaths = updatedMap.values
                .asSequence()
                .flatten()
                .mapNotNull { it.photoAttachment?.localFilePath?.takeIf { path -> path.isNotBlank() } }
                .toSet()
            val orphanedPaths = candidatePaths - stillReferencedPaths

            return MessageDeletionResult(
                updatedChatMessages = updatedMap,
                removedMessages = removedList,
                orphanedPhotoFilePaths = orphanedPaths
            )
        }

        internal fun deliveryStatusRank(status: MessageDeliveryStatus): Int = when (status) {
            MessageDeliveryStatus.SENDING -> 0
            MessageDeliveryStatus.SENT -> 1
            MessageDeliveryStatus.DELIVERED -> 2
            MessageDeliveryStatus.SEEN -> 3
        }

        /**
         * Monotonically advances outgoing message delivery ticks (`SENDING -> SENT -> DELIVERED -> SEEN`)
         * across all peer alias buckets, preserving timestamps and never regressing an already-higher tick.
         */
        internal fun applyMessageDeliveryStatusUpdate(
            chatMessages: Map<String, List<ChatMessage>>,
            msgIds: Collection<String>,
            newStatus: MessageDeliveryStatus,
            eventTimestampMillis: Long = System.currentTimeMillis()
        ): Pair<Map<String, List<ChatMessage>>, Boolean> {
            val targetIds = msgIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (targetIds.isEmpty() || chatMessages.isEmpty()) return chatMessages to false

            val newRank = deliveryStatusRank(newStatus)
            var modified = false
            val updated = chatMessages.mapValues { (_, list) ->
                list.map { msg ->
                    if (msg.isOutgoing && msg.id in targetIds) {
                        val currentRank = deliveryStatusRank(msg.deliveryStatus)
                        if (newRank > currentRank) {
                            modified = true
                            val nextDeliveredAt = when (newStatus) {
                                MessageDeliveryStatus.DELIVERED -> msg.deliveredAtMillis ?: eventTimestampMillis
                                MessageDeliveryStatus.SEEN -> msg.deliveredAtMillis ?: eventTimestampMillis
                                else -> msg.deliveredAtMillis
                            }
                            val nextSeenAt = if (newStatus == MessageDeliveryStatus.SEEN) {
                                msg.seenAtMillis ?: eventTimestampMillis
                            } else {
                                msg.seenAtMillis
                            }
                            msg.copy(
                                deliveryStatus = newStatus,
                                deliveredAtMillis = nextDeliveredAt,
                                seenAtMillis = nextSeenAt
                            )
                        } else {
                            msg
                        }
                    } else {
                        msg
                    }
                }
            }
            return (if (modified) updated else chatMessages) to modified
        }

        /**
         * Updates `blockedByPeerIds` using ONLY stable peer identifiers (`dev_...`, BLE advertisement
         * `Key`, known/rotating MACs, `peer_...`), stripping raw display names so unrelated peers
         * sharing a display name are never marked as blocked.
         */
        internal fun applyPeerBlockStateUpdate(
            currentBlockedByPeerIds: Set<String>,
            peerId: String,
            additionalStableIds: Collection<String?> = emptyList(),
            isBlockedByPeer: Boolean
        ): Set<String> {
            val stableTargets = PeerAliasRegistry.resolveStableIdentifiers(
                buildList {
                    add(peerId)
                    addAll(additionalStableIds)
                }
            )
            val sanitizedCurrent = currentBlockedByPeerIds
                .filter { PeerAliasRegistry.isStablePeerIdentifier(it) }
                .toMutableSet()
            if (stableTargets.isEmpty()) return sanitizedCurrent

            if (isBlockedByPeer) {
                sanitizedCurrent.addAll(stableTargets)
            } else {
                sanitizedCurrent.removeAll { existing ->
                    val expandedExisting = PeerAliasRegistry.resolveStableIdentifiers(existing)
                    stableTargets.any { target ->
                        existing.equals(target, ignoreCase = true) ||
                            expandedExisting.any { it.equals(target, ignoreCase = true) }
                    }
                }
            }
            return sanitizedCurrent
        }

        @Volatile
        private var instance: LayoverAppRuntime? = null

        fun getInstance(context: Context): LayoverAppRuntime {
            return instance ?: synchronized(this) {
                instance ?: LayoverAppRuntime(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val bleStateController = BleStateController(appContext)
    val gattSessionManager = GattSessionManager(appContext)
    val bleDiscoveryManager: BleDiscoveryManager
    val bleTransport: BleProximityTransport
    val peerDpCacheStore = PeerDpCacheStore(appContext)
    val wifiPhotoTransferManager: WifiPhotoTransferManager
    val notificationEvaluator = NotificationEligibilityEvaluator()
    val unreadTracker = UnreadMessageTracker()

    private class CachedPeerProfileMeta(
        val displayName: String? = null,
        val gender: GenderOption? = null,
        val age: Int? = null,
        val avatarBytes: ByteArray? = null
    )

    private val cachedPeerMetaByAlias = ConcurrentHashMap<String, CachedPeerProfileMeta>()
    private val sentSeenAckMsgIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private val _hasSetUsername: MutableStateFlow<Boolean>
    val hasSetUsername: StateFlow<Boolean>

    private val _uiState: MutableStateFlow<LayoverUiState>
    val uiState: StateFlow<LayoverUiState>

    private val _incomingRequestPeer = MutableStateFlow<Pair<String, String>?>(null)
    val incomingRequestPeer: StateFlow<Pair<String, String>?> = _incomingRequestPeer.asStateFlow()

    private val _acceptedRequestPeer = MutableStateFlow<String?>(null)
    val acceptedRequestPeer: StateFlow<String?> = _acceptedRequestPeer.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(false)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private val _activeChatPeerId = MutableStateFlow<String?>(null)
    val activeChatPeerId: StateFlow<String?> = _activeChatPeerId.asStateFlow()

    private val _isRuntimeReady = MutableStateFlow(false)
    val isRuntimeReady: StateFlow<Boolean> = _isRuntimeReady.asStateFlow()

    @Volatile
    private var acceptedPeerIds: Set<String> = emptySet()

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            LayoverFileLogger.log("BluetoothAdapter.ACTION_STATE_CHANGED received: state=$state")
            bleStateController.refreshPermissionAndAdapterState()

            when (state) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    LayoverFileLogger.log("Bluetooth turned off; stopping BLE discovery, GATT, and background service.")
                    bleTransport.stopDiscovery()
                    gattSessionManager.disconnect()
                    BleProximityService.stop(appContext)
                }
                BluetoothAdapter.STATE_ON -> {
                    LayoverFileLogger.log("Bluetooth turned on; re-evaluating BLE discovery and background service.")
                    if (isBackgroundListeningEligible()) {
                        if (_isAppInForeground.value) {
                            BleProximityService.syncServiceState(appContext)
                        }
                        startDiscoveryIfEligible()
                    }
                }
            }
        }
    }

    init {
        LayoverNotificationManager.ensureChannelsCreated(appContext)

        val savedDeviceId = prefs.getString("device_id", null) ?: run {
            val newId = "dev_" + UUID.randomUUID().toString().take(8)
            prefs.edit().putString("device_id", newId).apply()
            LayoverFileLogger.log("Generated new deviceId=$newId")
            newId
        }
        val usernameAlreadySet = prefs.getBoolean("username_set", false)
        val rawSavedName = if (usernameAlreadySet) (prefs.getString("display_name", "") ?: "") else ""
        val savedName = rawSavedName.trim().take(LayoverBleConstants.MAX_NAME_BYTE_LENGTH)
        if (usernameAlreadySet && savedName != rawSavedName) {
            prefs.edit().putString("display_name", savedName).apply()
        }
        val savedHandle = prefs.getString("handle", savedDeviceId) ?: savedDeviceId
        val savedGender = GenderOption.fromLabelOrName(prefs.getString("gender", GenderOption.MALE.name))
        val savedAge = if (prefs.contains("age")) prefs.getInt("age", -1).takeIf { it > 0 } else null
        val savedAvatarBytes = prefs.getString("avatar_b64", null)?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        }
        val savedCompactAvatarBytes = prefs.getString("compact_avatar_b64", null)?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        }
        val savedResolutionLabel = prefs.getString("cropped_resolution", null)
        val rawSavedFullResAvatarPath = prefs.getString("full_res_avatar_path", null)
        val hasValidCroppedFullRes = PeerDpCacheStore.isCroppedOwnFullResPath(rawSavedFullResAvatarPath)
        if (!rawSavedFullResAvatarPath.isNullOrBlank() && !hasValidCroppedFullRes) {
            prefs.edit()
                .remove("full_res_avatar_path")
                .remove("full_res_avatar_sha256")
                .apply()
        }
        val savedFullResAvatarPath = if (hasValidCroppedFullRes) rawSavedFullResAvatarPath else null
        val savedFullResAvatarSha256 = if (hasValidCroppedFullRes) {
            prefs.getString("full_res_avatar_sha256", null)
        } else {
            null
        }
        val savedVisible = usernameAlreadySet && prefs.getBoolean("is_visible", true)

        val initialProfile = UserProfile(
            displayName = savedName,
            handle = savedHandle,
            gender = savedGender,
            age = savedAge,
            avatarBytes = savedAvatarBytes,
            compactTransferAvatarBytes = savedCompactAvatarBytes,
            croppedResolutionLabel = savedResolutionLabel,
            fullResAvatarFilePath = savedFullResAvatarPath,
            fullResAvatarSha256 = savedFullResAvatarSha256,
            isVisibleToNearby = savedVisible
        )

        val savedBlocked = prefs.getStringSet(KEY_BLOCKED_PEER_IDS, emptySet())
            ?.filter { PeerAliasRegistry.isStablePeerIdentifier(it) }
            ?.toSet()
            .orEmpty()
        val savedBlockedBy = prefs.getStringSet(KEY_BLOCKED_BY_PEER_IDS, emptySet())
            ?.filter { PeerAliasRegistry.isStablePeerIdentifier(it) }
            ?.toSet()
            .orEmpty()
        val savedMuted = prefs.getStringSet(KEY_MUTED_PEER_IDS, emptySet())?.toSet().orEmpty()
        acceptedPeerIds = prefs.getStringSet(KEY_ACCEPTED_PEER_IDS, emptySet())?.toSet().orEmpty()

        var transportRef: BleProximityTransport? = null
        bleDiscoveryManager = BleDiscoveryManager(
            context = appContext,
            bleStateController = bleStateController,
            onRequestAvatarFetch = { device, mapKey, onLoaded ->
                LayoverFileLogger.log("onRequestAvatarFetch invoked: mapKey=$mapKey, transportReady=${transportRef != null}")
                transportRef?.requestAvatarFetch(device, mapKey, onLoaded)
            },
            isChatSessionActiveOrConnecting = { device, mapKey ->
                gattSessionManager.isSessionActiveOrConnectingFor(device.address, mapKey)
            }
        )
        bleTransport = BleProximityTransport(
            context = appContext,
            discoveryManager = bleDiscoveryManager,
            bleStateController = bleStateController,
            gattSessionManager = gattSessionManager,
            currentUserProfile = initialProfile
        ).also {
            transportRef = it
            it.updateSafetySettings(blocked = savedBlocked, muted = savedMuted)
        }

        _hasSetUsername = MutableStateFlow(usernameAlreadySet)
        hasSetUsername = _hasSetUsername.asStateFlow()

        val savedChatHistory = deserializeChatHistory(prefs.getString(KEY_CHAT_HISTORY, null))

        _uiState = MutableStateFlow(
            LayoverUiState(
                userProfile = initialProfile,
                nearbyPeers = emptyList(),
                chatMessages = savedChatHistory,
                blockedPeerIds = savedBlocked,
                blockedByPeerIds = savedBlockedBy,
                mutedPeerIds = savedMuted,
                isScanning = true,
                transport = bleTransport,
                diagnostics = bleTransport.diagnosticsTracker.diagnosticsState.value
            )
        )
        uiState = _uiState.asStateFlow()

        wifiPhotoTransferManager = WifiPhotoTransferManager(
            context = appContext,
            localPeerIdProvider = { _uiState.value.userProfile.handle.ifBlank { savedDeviceId } },
            sendBleFrame = { targetPeerId, rawWireFrame ->
                bleTransport.sendPhotoWireFrame(targetPeerId, rawWireFrame)
            },
            sendBleFrameWithRoute = { targetPeerId, rawWireFrame, routePreference ->
                bleTransport.sendPhotoWireFrame(targetPeerId, rawWireFrame, routePreference)
            },
            ensureEncryptedChannelReady = { peerId ->
                bleTransport.ensureEncryptedChannelReady(peerId)
            },
            refreshPeerChannelForRetry = { peerId ->
                bleTransport.refreshPeerGattChannelForRetry(peerId)
            },
            encryptBytesForPeer = { peerId, plaintextBytes ->
                bleTransport.encryptKeyBytesForPeer(peerId, plaintextBytes)
            },
            decryptBytesFromPeer = { peerId, payload ->
                bleTransport.decryptKeyBytesFromPeer(peerId, payload)
            },
            onOutgoingPhotoCreated = { peerId, message ->
                appendPhotoMessageToState(peerId, message, isIncoming = false)
            },
            onIncomingPhotoPreview = { peerId, message ->
                appendPhotoMessageToState(peerId, message, isIncoming = true)
            },
            onPhotoStateUpdated = { transferId, state, progress, localFilePath, statusDetail ->
                updatePhotoAttachmentState(transferId, state, progress, localFilePath, statusDetail)
            },
            onPhotoValidationError = { peerId, errorMessage ->
                setPhotoErrorForPeer(peerId, errorMessage)
            },
            onClearPhotoError = { peerId ->
                clearPhotoErrorForPeer(peerId)
            },
            peerDpCacheStore = peerDpCacheStore,
            ownFullResAvatarProvider = {
                val currentProfile = _uiState.value.userProfile
                peerDpCacheStore.loadOwnFullResAvatar(
                    fullResFilePath = currentProfile.fullResAvatarFilePath,
                    fallbackAvatarBytes = currentProfile.avatarBytes,
                    fallbackCompactBytes = currentProfile.compactTransferAvatarBytes
                )
            },
            onPeerDpStateUpdated = { peerId, avatarDigest, dpState ->
                updatePeerDpDownloadState(peerId, avatarDigest, dpState)
            }
        )

        bleTransport.onSendPhotoRequested = { peerId, uriString ->
            runCatching { Uri.parse(uriString) }
                .onSuccess { uri -> wifiPhotoTransferManager.sendPhotoFromUri(peerId, uri) }
                .onFailure { setPhotoErrorForPeer(peerId, "Unable to read selected photo") }
        }
        bleTransport.onDownloadPhotoRequested = { peerId, transferId ->
            wifiPhotoTransferManager.requestDownload(peerId, transferId)
        }
        bleTransport.onDownloadPeerDpRequested = { peerId, avatarDigest ->
            val matchedPeer = findMatchingPeer(_uiState.value.nearbyPeers, peerId)
            val stableSeeds = listOfNotNull(peerId, matchedPeer?.id, matchedPeer?.handle)
                .filter { it.isNotBlank() }
            val expandedAliases = (
                PeerAliasRegistry.resolveStableIdentifiers(stableSeeds) + stableSeeds
            ).filter { it.isNotBlank() }.toSet()
            val targetPeerId = matchedPeer?.id?.takeIf { it.isNotBlank() } ?: peerId
            wifiPhotoTransferManager.requestPeerFullResDp(
                peerId = targetPeerId,
                peerAliases = expandedAliases,
                avatarDigest = avatarDigest
            )
        }
        bleTransport.onCancelPhotoRequested = { transferId ->
            wifiPhotoTransferManager.cancelTransfer(transferId)
        }
        bleTransport.onDeleteMessageForMeRequested = { peerId, msgId ->
            deleteMessage(peerId = peerId, msgId = msgId, deleteForEveryone = false)
        }
        bleTransport.onDeleteMessageForEveryoneRequested = { peerId, msgId ->
            deleteMessage(peerId = peerId, msgId = msgId, deleteForEveryone = true)
        }
        bleTransport.onIncomingPhotoWireFrame = { senderPeerId, frame ->
            when (frame) {
                is WireFrame.PhotoPreview -> wifiPhotoTransferManager.handleIncomingPhotoPreview(senderPeerId, frame)
                is WireFrame.PhotoDownloadRequest -> wifiPhotoTransferManager.handleIncomingPhotoDownloadRequest(senderPeerId, frame)
                is WireFrame.WifiApReady -> wifiPhotoTransferManager.handleIncomingWifiApReady(senderPeerId, frame)
                is WireFrame.WifiApFallback -> wifiPhotoTransferManager.handleIncomingWifiApFallback(senderPeerId, frame)
                is WireFrame.PhotoTransferDone -> wifiPhotoTransferManager.handleIncomingPhotoTransferDone(senderPeerId, frame)
                else -> Unit
            }
        }

        LayoverFileLogger.log(
            "LayoverAppRuntime initialized: deviceId=$savedDeviceId, handle=$savedHandle, " +
                "usernameSet=$usernameAlreadySet, visible=$savedVisible, " +
                "blocked=${savedBlocked.size}, muted=${savedMuted.size}, accepted=${acceptedPeerIds.size}"
        )

        registerBluetoothReceiver()
        wireTransportCollectors()
        _isRuntimeReady.value = true
    }

    private fun registerBluetoothReceiver() {
        runCatching {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            ContextCompat.registerReceiver(
                appContext,
                bluetoothStateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure { err ->
            LayoverFileLogger.logError("Failed to register Bluetooth state receiver", err)
        }
    }

    private fun UserProfile.effectiveCompactAvatarBytes(): ByteArray? =
        compactTransferAvatarBytes ?: avatarBytes

    fun isBluetoothEnabled(): Boolean {
        return bluetoothManager?.adapter?.isEnabled == true
    }

    fun isSetupComplete(): Boolean {
        return _hasSetUsername.value && _uiState.value.userProfile.displayName.isNotBlank()
    }

    fun isBackgroundListeningEligible(): Boolean {
        return isSetupComplete() &&
            isBluetoothEnabled() &&
            BlePermissionHelper.hasAllRequiredPermissions(appContext) &&
            _uiState.value.isScanning
    }

    fun setAppInForeground(inForeground: Boolean) {
        _isAppInForeground.value = inForeground
        if (!inForeground) {
            _activeChatPeerId.value = null
        } else {
            _activeChatPeerId.value?.let { activePeer ->
                sendPendingSeenAcksForChat(activePeer)
            }
        }
        LayoverFileLogger.log("LayoverAppRuntime foreground state changed: inForeground=$inForeground")
    }

    fun setActiveChatPeerId(peerId: String?) {
        val clean = peerId?.takeIf { it.isNotBlank() }
        _activeChatPeerId.value = clean
        if (clean != null) {
            LayoverNotificationManager.cancelMessageNotification(appContext, clean)
            val matchedPeer = findMatchingPeer(_uiState.value.nearbyPeers, clean)
            val updatedUnread = unreadTracker.markChatRead(
                listOfNotNull(clean, matchedPeer?.id, matchedPeer?.handle, matchedPeer?.displayName)
            )
            _uiState.update { it.copy(unreadCountsByPeerId = updatedUnread) }
            if (_isAppInForeground.value) {
                sendPendingSeenAcksForChat(clean)
            }
        }
    }

    private fun isPeerCurrentlyViewedInChat(peerId: String, matchedPeer: NearbyPeer?): Boolean {
        if (!_isAppInForeground.value) return false
        val activeChat = _activeChatPeerId.value ?: return false
        val activeStable = PeerAliasRegistry.resolveStableIdentifiers(activeChat)
        val peerStable = PeerAliasRegistry.resolveStableIdentifiers(
            listOfNotNull(peerId, matchedPeer?.id, matchedPeer?.handle)
        )
        if (activeStable.isNotEmpty() && peerStable.isNotEmpty()) {
            return activeStable.any { a -> peerStable.any { b -> a.equals(b, ignoreCase = true) } }
        }
        return activeChat.equals(peerId, ignoreCase = true) ||
            (matchedPeer != null && activeChat.equals(matchedPeer.id, ignoreCase = true))
    }

    private fun sendPendingSeenAcksForChat(peerId: String) {
        val state = _uiState.value
        val matchedPeer = findMatchingPeer(state.nearbyPeers, peerId)
        val stableIds = PeerAliasRegistry.resolveStableIdentifiers(
            listOfNotNull(peerId, matchedPeer?.id, matchedPeer?.handle)
        )
        if (PeerAliasRegistry.matchesBlocklist(stableIds, state.blockedPeerIds) ||
            PeerAliasRegistry.matchesBlocklist(stableIds, state.blockedByPeerIds)
        ) {
            return
        }
        val chatAliases = buildSet {
            add(peerId)
            addAll(stableIds)
            matchedPeer?.id?.takeIf { it.isNotBlank() }?.let { add(it) }
            matchedPeer?.handle?.takeIf { it.isNotBlank() }?.let { add(it) }
            matchedPeer?.displayName?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val unseenIncomingIds = LinkedHashSet<String>()
        for ((key, list) in state.chatMessages) {
            if (chatAliases.any { alias -> key.equals(alias, ignoreCase = true) }) {
                for (msg in list) {
                    if (!msg.isOutgoing && !sentSeenAckMsgIds.contains(msg.id)) {
                        unseenIncomingIds.add(msg.id)
                    }
                }
            }
        }
        if (unseenIncomingIds.isEmpty()) return

        val batch = unseenIncomingIds.toList().takeLast(25)
        synchronized(sentSeenAckMsgIds) {
            sentSeenAckMsgIds.addAll(batch)
            while (sentSeenAckMsgIds.size > 1_000) {
                val oldest = sentSeenAckMsgIds.firstOrNull() ?: break
                sentSeenAckMsgIds.remove(oldest)
            }
        }
        val targetPeerId = matchedPeer?.id?.takeIf { it.isNotBlank() } ?: peerId
        bleTransport.sendMessageSeenAck(targetPeerId, batch)
    }

    private fun advanceOutgoingMessageDeliveryStatus(
        msgIds: Collection<String>,
        newStatus: MessageDeliveryStatus,
        eventTimestampMillis: Long = System.currentTimeMillis()
    ) {
        if (msgIds.isEmpty()) return
        _uiState.update { state ->
            val (updatedMap, modified) = applyMessageDeliveryStatusUpdate(
                chatMessages = state.chatMessages,
                msgIds = msgIds,
                newStatus = newStatus,
                eventTimestampMillis = eventTimestampMillis
            )
            if (modified) {
                persistChatHistory(updatedMap)
                state.copy(chatMessages = updatedMap)
            } else {
                state
            }
        }
    }

    fun startDiscoveryIfEligible() {
        if (!isSetupComplete()) return
        if (!BlePermissionHelper.hasAllRequiredPermissions(appContext)) return
        if (!isBluetoothEnabled()) return
        if (!_uiState.value.isScanning) {
            bleTransport.stopDiscovery()
            return
        }
        val visible = _uiState.value.userProfile.isVisibleToNearby && _hasSetUsername.value
        bleTransport.startDiscovery(visible)
    }

    fun confirmInitialProfile(chosenName: String, chosenGender: GenderOption, chosenAge: Int) {
        val cleanedName = chosenName.trim().take(LayoverBleConstants.MAX_NAME_BYTE_LENGTH)
        val visiblePref = prefs.getBoolean("is_visible", true)
        val updatedProfile = _uiState.value.userProfile.copy(
            displayName = cleanedName,
            gender = chosenGender,
            age = chosenAge,
            isVisibleToNearby = visiblePref
        )
        prefs.edit()
            .putString("display_name", cleanedName)
            .putString("gender", chosenGender.name)
            .putInt("age", chosenAge)
            .putBoolean("username_set", true)
            .putBoolean("initial_permission_flow_done", true)
            .apply()

        LayoverFileLogger.log("Confirmed profile: name=$cleanedName, gender=${chosenGender.name}, age=$chosenAge, visible=$visiblePref")
        Log.d(TAG, "Confirmed profile: name=$cleanedName, gender=${chosenGender.name}, age=$chosenAge")

        bleTransport.updateProfile(updatedProfile)
        _hasSetUsername.value = true
        _uiState.update { it.copy(userProfile = updatedProfile) }

        if (isBackgroundListeningEligible()) {
            BleProximityService.syncServiceState(appContext)
            if (updatedProfile.isVisibleToNearby) {
                bleDiscoveryManager.startAdvertising(updatedProfile)
            }
            bleTransport.startDiscovery(updatedProfile.isVisibleToNearby)
        }
    }

    fun updateUiState(newState: LayoverUiState) {
        val oldState = _uiState.value
        val oldProfile = oldState.userProfile
        var resolvedFullResPath = newState.userProfile.fullResAvatarFilePath
        var resolvedFullResSha = newState.userProfile.fullResAvatarSha256
        newState.userProfile.pendingFullResAvatarBytes?.let { pendingBytes ->
            val saved = peerDpCacheStore.saveOwnFullResAvatar(pendingBytes)
            if (saved != null) {
                resolvedFullResPath = saved.first.absolutePath
                resolvedFullResSha = saved.second
                LayoverFileLogger.log(
                    "Saved own full-res avatar to disk: bytes=${pendingBytes.size}, sha256=${saved.second.take(12)}, path=${saved.first.absolutePath}"
                )
            } else {
                LayoverFileLogger.logError("Failed to save own full-res avatar (bytes=${pendingBytes.size})")
            }
        }
        val sanitizedProfile = newState.userProfile.copy(
            displayName = newState.userProfile.displayName.trim().take(LayoverBleConstants.MAX_NAME_BYTE_LENGTH),
            fullResAvatarFilePath = resolvedFullResPath,
            fullResAvatarSha256 = resolvedFullResSha,
            pendingFullResAvatarBytes = null
        )
        val sanitizedBlocked = newState.blockedPeerIds
            .filter { PeerAliasRegistry.isStablePeerIdentifier(it) }
            .toSet()
        val sanitizedBlockedBy = newState.blockedByPeerIds
            .filter { PeerAliasRegistry.isStablePeerIdentifier(it) }
            .toSet()
        val sanitizedState = newState.copy(
            userProfile = sanitizedProfile,
            blockedPeerIds = sanitizedBlocked,
            blockedByPeerIds = sanitizedBlockedBy
        )
        bleTransport.updateProfile(sanitizedProfile)
        bleTransport.updateSafetySettings(
            blocked = sanitizedState.blockedPeerIds,
            muted = sanitizedState.mutedPeerIds
        )

        if (sanitizedState.blockedPeerIds != oldState.blockedPeerIds) {
            prefs.edit().putStringSet(KEY_BLOCKED_PEER_IDS, sanitizedState.blockedPeerIds.toSet()).apply()
            sanitizedState.blockedPeerIds.forEach { blockedId ->
                LayoverNotificationManager.cancelMessageNotification(appContext, blockedId)
                LayoverNotificationManager.cancelConnectionRequestNotification(appContext, blockedId)
            }
        }
        if (sanitizedState.mutedPeerIds != oldState.mutedPeerIds) {
            prefs.edit().putStringSet(KEY_MUTED_PEER_IDS, sanitizedState.mutedPeerIds.toSet()).apply()
            val newlyMuted = sanitizedState.mutedPeerIds - oldState.mutedPeerIds
            newlyMuted.forEach { mutedId ->
                LayoverNotificationManager.cancelMessageNotification(appContext, mutedId)
                LayoverNotificationManager.cancelConnectionRequestNotification(appContext, mutedId)
            }
        }
        if (sanitizedState.blockedByPeerIds != oldState.blockedByPeerIds) {
            prefs.edit().putStringSet(KEY_BLOCKED_BY_PEER_IDS, sanitizedState.blockedByPeerIds.toSet()).apply()
        }

        if (sanitizedProfile != oldProfile) {
            LayoverFileLogger.log(
                "UserProfile updated: name=${sanitizedProfile.displayName}, " +
                    "gender=${sanitizedProfile.gender.name}, visible=${sanitizedProfile.isVisibleToNearby}"
            )
            val editor = prefs.edit()
                .putString("display_name", sanitizedProfile.displayName)
                .putString("handle", sanitizedProfile.handle)
                .putString("gender", sanitizedProfile.gender.name)
                .putBoolean("is_visible", sanitizedProfile.isVisibleToNearby)
            val ageValue = sanitizedProfile.age
            if (ageValue != null) {
                editor.putInt("age", ageValue)
            } else {
                editor.remove("age")
            }
            sanitizedProfile.avatarBytes?.let {
                editor.putString("avatar_b64", Base64.encodeToString(it, Base64.NO_WRAP))
            }
            sanitizedProfile.compactTransferAvatarBytes?.let {
                editor.putString("compact_avatar_b64", Base64.encodeToString(it, Base64.NO_WRAP))
            }
            sanitizedProfile.croppedResolutionLabel?.let {
                editor.putString("cropped_resolution", it)
            }
            sanitizedProfile.fullResAvatarFilePath?.let {
                editor.putString("full_res_avatar_path", it)
            }
            sanitizedProfile.fullResAvatarSha256?.let {
                editor.putString("full_res_avatar_sha256", it)
            }
            val avatarChanged = !oldProfile.compactTransferAvatarBytes.contentEquals(sanitizedProfile.compactTransferAvatarBytes) ||
                !oldProfile.avatarBytes.contentEquals(sanitizedProfile.avatarBytes) ||
                oldProfile.fullResAvatarSha256 != sanitizedProfile.fullResAvatarSha256
            if (avatarChanged) {
                LayoverFileLogger.log("Avatar bytes changed; updating avatar_updated_at timestamp.")
                editor.putLong("avatar_updated_at", System.currentTimeMillis())
            }
            editor.apply()

            if (sanitizedProfile.isVisibleToNearby && _hasSetUsername.value && isBluetoothEnabled()) {
                bleDiscoveryManager.startAdvertising(sanitizedProfile)
            } else {
                bleDiscoveryManager.stopAdvertising()
            }
        }

        val compactBytes = sanitizedProfile.effectiveCompactAvatarBytes()
        val updatedNearbyPeers = sanitizedState.nearbyPeers.map { peer ->
            val isSelf = peer.id.equals(sanitizedProfile.handle, ignoreCase = true) ||
                (peer.displayName.isNotEmpty() && peer.displayName.equals(sanitizedProfile.displayName, ignoreCase = true))
            if (isSelf && compactBytes != null) {
                peer.copy(avatar = peer.avatar.copy(avatarBase64OrBytes = compactBytes))
            } else {
                peer
            }
        }

        val sanitizedChatMessages = sanitizedState.chatMessages.mapValues { (_, list) ->
            list.map { msg ->
                if (msg.timestampMillis <= 0L) {
                    val safeTs = System.currentTimeMillis()
                    msg.copy(
                        timestampMillis = safeTs,
                        timestampLabel = formatMessageBubbleTimestamp(safeTs)
                    )
                } else {
                    msg
                }
            }
        }

        if (sanitizedChatMessages != oldState.chatMessages) {
            persistChatHistory(sanitizedChatMessages)
        }

        _uiState.value = sanitizedState.copy(
            nearbyPeers = canonicalDeduplicatePeers(updatedNearbyPeers),
            chatMessages = sanitizedChatMessages,
            unreadCountsByPeerId = unreadTracker.snapshot(),
            transport = bleTransport,
            diagnostics = bleTransport.diagnosticsTracker.diagnosticsState.value
        )

        if (newState.isScanning != oldState.isScanning) {
            if (newState.isScanning) {
                if (isBackgroundListeningEligible()) {
                    BleProximityService.syncServiceState(appContext)
                    startDiscoveryIfEligible()
                }
            } else {
                bleTransport.stopDiscovery()
                BleProximityService.stop(appContext)
            }
        }
    }

    fun acceptIncomingRequest(peerId: String, displayName: String) {
        LayoverFileLogger.log("Accepted connection request from peerId=$peerId ($displayName)")
        _incomingRequestPeer.value = null
        notificationEvaluator.clearRequestDedupe(peerId, displayName)
        LayoverNotificationManager.cancelConnectionRequestNotification(appContext, peerId)
        recordAcceptedPeer(peerId, displayName)
        bleTransport.acceptConnectionRequest(peerId)
        bleDiscoveryManager.updatePeerStatus(peerId, ConnectionStatus.ACCEPTED)

        val cachedMeta = lookupCachedPeerMeta(peerId, displayName)
        _uiState.update { state ->
            val matchedPeers = findAllMatchingPeers(state.nearbyPeers, peerId, displayName)
            PeerAliasRegistry.linkAliases(
                listOfNotNull(peerId, displayName.ifBlank { null }) +
                    matchedPeers.flatMap { listOfNotNull(it.id, it.handle.ifEmpty { null }, it.displayName.ifEmpty { null }) }
            )
            val updatedPeers = if (matchedPeers.isEmpty()) {
                val resolvedName = displayName.ifBlank { cachedMeta?.displayName ?: "Layover Peer" }
                state.nearbyPeers + NearbyPeer(
                    id = peerId,
                    displayName = resolvedName,
                    handle = peerId,
                    gender = cachedMeta?.gender,
                    age = cachedMeta?.age,
                    status = ConnectionStatus.ACCEPTED,
                    avatar = PeerAvatar(
                        peerId = peerId,
                        initials = resolvedName.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").ifEmpty { "?" },
                        avatarBase64OrBytes = cachedMeta?.avatarBytes
                    )
                )
            } else {
                val matchedIds = matchedPeers.map { it.id }.toSet()
                state.nearbyPeers.map { p ->
                    if (p.id in matchedIds) {
                        p.copy(
                            gender = p.gender ?: cachedMeta?.gender,
                            age = p.age ?: cachedMeta?.age,
                            status = ConnectionStatus.ACCEPTED,
                            avatar = if (p.avatar.avatarBase64OrBytes == null && cachedMeta?.avatarBytes != null) {
                                p.avatar.copy(avatarBase64OrBytes = cachedMeta.avatarBytes)
                            } else {
                                p.avatar
                            }
                        )
                    } else {
                        p
                    }
                }
            }
            state.copy(nearbyPeers = canonicalDeduplicatePeers(updatedPeers))
        }
    }

    fun declineIncomingRequest(peerId: String) {
        LayoverFileLogger.log("Declined connection request from peerId=$peerId")
        _incomingRequestPeer.value = null
        notificationEvaluator.clearRequestDedupe(peerId)
        LayoverNotificationManager.cancelConnectionRequestNotification(appContext, peerId)
        removeAcceptedPeer(peerId)
        bleTransport.declineConnectionRequest(peerId)
        bleDiscoveryManager.updatePeerStatus(peerId, ConnectionStatus.DECLINED)

        _uiState.update { state ->
            val matchedPeers = findAllMatchingPeers(state.nearbyPeers, peerId)
            PeerAliasRegistry.linkAliases(
                listOf(peerId) + matchedPeers.flatMap { listOfNotNull(it.id, it.handle.ifEmpty { null }, it.displayName.ifEmpty { null }) }
            )
            val matchedIds = matchedPeers.map { it.id }.toSet()
            val updatedPeers = state.nearbyPeers.map { p ->
                if (p.id in matchedIds) p.copy(status = ConnectionStatus.DECLINED) else p
            }
            state.copy(nearbyPeers = canonicalDeduplicatePeers(updatedPeers))
        }
    }

    fun dismissIncomingRequest() {
        val current = _incomingRequestPeer.value
        _incomingRequestPeer.value = null
        if (current != null) {
            notificationEvaluator.clearRequestDedupe(current.first, current.second)
            LayoverNotificationManager.cancelConnectionRequestNotification(appContext, current.first)
        }
    }

    fun dismissAcceptedRequest(peerId: String) {
        _acceptedRequestPeer.value = null
        recordAcceptedPeer(peerId)
        _uiState.update { state ->
            val matchedPeers = findAllMatchingPeers(state.nearbyPeers, peerId)
            PeerAliasRegistry.linkAliases(
                listOf(peerId) + matchedPeers.flatMap { listOfNotNull(it.id, it.handle.ifEmpty { null }, it.displayName.ifEmpty { null }) }
            )
            val matchedIds = matchedPeers.map { it.id }.toSet()
            val updatedPeers = state.nearbyPeers.map { p ->
                if (p.id in matchedIds) p.copy(status = ConnectionStatus.ACCEPTED) else p
            }
            state.copy(nearbyPeers = canonicalDeduplicatePeers(updatedPeers))
        }
    }

    fun clearAcceptedRequestDialog() {
        _acceptedRequestPeer.value = null
    }

    private fun persistChatHistory(chatMessages: Map<String, List<ChatMessage>>) {
        runCatching {
            val encoded = serializeChatHistory(chatMessages)
            prefs.edit().putString(KEY_CHAT_HISTORY, encoded).apply()
        }.onFailure { err ->
            LayoverFileLogger.logError("Failed to persist chat history", err)
        }
    }

    /**
     * WhatsApp-style message deletion:
     * - [deleteForEveryone] == false ("Delete for me"): removes locally from state & persisted
     *   history, cancels any active transfer, and deletes unreferenced local media.
     * - [deleteForEveryone] == true ("Delete for everyone"): performs local removal + cleanup
     *   AND sends an encrypted `DELETE_MESSAGE(msgId)` BLE control frame to the peer.
     */
    fun deleteMessage(peerId: String, msgId: String, deleteForEveryone: Boolean) {
        if (msgId.isBlank()) return
        val matchedPeer = findMatchingPeer(_uiState.value.nearbyPeers, peerId)
        val targetPeerId = matchedPeer?.id?.takeIf { it.isNotBlank() } ?: peerId
        val peerAliases = buildSet {
            addAll(notificationEvaluator.expandIdentifiers(listOfNotNull(peerId, matchedPeer?.id, matchedPeer?.handle, matchedPeer?.displayName)))
            listOfNotNull(peerId, matchedPeer?.id, matchedPeer?.handle, matchedPeer?.displayName)
                .filter { it.isNotBlank() }
                .forEach { add(it.trim()) }
        }

        val deletionResult = applyMessageDeletionToState(msgId = msgId, peerAliases = peerAliases)
        LayoverFileLogger.log(
            "Local message delete (deleteForEveryone=$deleteForEveryone): peerId=$targetPeerId, msgId=$msgId, " +
                "removed=${deletionResult.removedMessages.size}, orphanedFiles=${deletionResult.orphanedPhotoFilePaths.size}"
        )

        if (deleteForEveryone && targetPeerId.isNotBlank()) {
            bleTransport.sendDeleteMessage(targetPeerId, msgId)
        }
    }

    private fun handleIncomingRemoteMessageDeletion(peerId: String, msgId: String) {
        if (msgId.isBlank()) return
        val matchedPeer = findMatchingPeer(_uiState.value.nearbyPeers, peerId)
        val peerAliases = buildSet {
            addAll(notificationEvaluator.expandIdentifiers(listOfNotNull(peerId, matchedPeer?.id, matchedPeer?.handle, matchedPeer?.displayName)))
            listOfNotNull(peerId, matchedPeer?.id, matchedPeer?.handle, matchedPeer?.displayName)
                .filter { it.isNotBlank() }
                .forEach { add(it.trim()) }
        }
        val deletionResult = applyMessageDeletionToState(msgId = msgId, peerAliases = peerAliases)
        LayoverFileLogger.log(
            "Applied remote DELETE_MESSAGE from peerId=$peerId: msgId=$msgId, " +
                "removed=${deletionResult.removedMessages.size}, orphanedFiles=${deletionResult.orphanedPhotoFilePaths.size}"
        )
    }

    private fun applyMessageDeletionToState(
        msgId: String,
        peerAliases: Set<String>
    ): MessageDeletionResult {
        var result = MessageDeletionResult(_uiState.value.chatMessages, emptyList(), emptySet())
        _uiState.update { state ->
            val computed = removeMessageFromHistory(
                chatMessages = state.chatMessages,
                msgId = msgId,
                peerAliases = peerAliases
            )
            result = computed
            if (computed.removedMessages.isNotEmpty()) {
                persistChatHistory(computed.updatedChatMessages)
                state.copy(chatMessages = computed.updatedChatMessages)
            } else {
                state
            }
        }

        // Cancel any active/queued photo transfer tied to the deleted message
        result.removedMessages
            .mapNotNull { it.photoAttachment?.transferId }
            .distinct()
            .forEach { transferId ->
                wifiPhotoTransferManager.cancelTransfer(transferId)
            }

        // Delete unreferenced photo files inside our private layover_photos cache directory
        val allowedPhotosDir = runCatching { File(appContext.filesDir, "layover_photos").canonicalFile }.getOrNull()
        result.orphanedPhotoFilePaths.forEach { path ->
            runCatching {
                val targetFile = File(path).canonicalFile
                val insideAppPhotosDir = allowedPhotosDir != null &&
                    targetFile.path.startsWith(allowedPhotosDir.path + File.separator)
                if (insideAppPhotosDir && targetFile.exists()) {
                    val deleted = targetFile.delete()
                    LayoverFileLogger.log("Cleaned up orphaned photo file for deleted message $msgId: deleted=$deleted, path=$path")
                }
            }.onFailure { err ->
                LayoverFileLogger.logError("Failed to delete orphaned photo file $path", err)
            }
        }

        return result
    }

    private fun setPhotoErrorForPeer(peerId: String, message: String) {
        _uiState.update { state ->
            val updated = state.photoErrorsByPeerId.toMutableMap()
            val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId)) + peerId
            aliases.filter { it.isNotBlank() }.forEach { alias ->
                updated[alias] = message
            }
            state.copy(photoErrorsByPeerId = updated)
        }
    }

    private fun clearPhotoErrorForPeer(peerId: String) {
        _uiState.update { state ->
            val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId)) + peerId
            val updated = state.photoErrorsByPeerId.filterKeys { existingKey ->
                aliases.none { alias -> existingKey.equals(alias, ignoreCase = true) }
            }
            state.copy(photoErrorsByPeerId = updated)
        }
    }

    private fun appendPhotoMessageToState(peerId: String, message: ChatMessage, isIncoming: Boolean) {
        var matchedPeer: NearbyPeer? = null
        _uiState.update { state ->
            val updatedMap = state.chatMessages.toMutableMap()
            val targetKeys = LinkedHashSet<String>()
            fun collectKey(key: String?) {
                if (!key.isNullOrBlank()) targetKeys.add(key.trim())
            }
            collectKey(peerId)
            PeerAliasRegistry.resolveAliases(peerId).forEach { collectKey(it) }
            val peer = findMatchingPeer(state.nearbyPeers, peerId)
            matchedPeer = peer
            peer?.let { p ->
                collectKey(p.id)
                collectKey(p.handle)
                collectKey(p.displayName)
                PeerAliasRegistry.resolveAliases(p.id).forEach { collectKey(it) }
            }
            updatedMap.keys.filter { existingKey ->
                targetKeys.any { target -> existingKey.equals(target, ignoreCase = true) }
            }.forEach { targetKeys.add(it) }

            var changed = false
            for (key in targetKeys) {
                val list = updatedMap[key]?.toMutableList() ?: mutableListOf()
                if (list.none { it.id == message.id }) {
                    list.add(message)
                    updatedMap[key] = list
                    changed = true
                }
            }
            if (changed) {
                persistChatHistory(updatedMap)
            }

            val updatedUnread = if (isIncoming) {
                val unreadCandidates = listOfNotNull(
                    peerId,
                    message.senderId,
                    peer?.id,
                    peer?.handle,
                    peer?.displayName
                )
                unreadTracker.recordIncomingMessage(
                    senderCandidates = unreadCandidates,
                    messageId = message.id,
                    isAppInForeground = _isAppInForeground.value,
                    activeChatPeerId = _activeChatPeerId.value
                )
            } else {
                state.unreadCountsByPeerId
            }

            state.copy(
                chatMessages = updatedMap,
                unreadCountsByPeerId = updatedUnread
            )
        }

        if (isIncoming) {
            val currentState = _uiState.value
            val routePeerId = matchedPeer?.id?.takeIf { it.isNotBlank() } ?: peerId
            val stableIds = PeerAliasRegistry.resolveStableIdentifiers(
                listOfNotNull(peerId, matchedPeer?.id, matchedPeer?.handle)
            )
            val notBlocked = !PeerAliasRegistry.matchesBlocklist(stableIds, currentState.blockedPeerIds) &&
                !PeerAliasRegistry.matchesBlocklist(stableIds, currentState.blockedByPeerIds)
            if (notBlocked) {
                bleTransport.sendMessageDeliveredAck(routePeerId, listOf(message.id))
                if (isPeerCurrentlyViewedInChat(peerId, matchedPeer) && sentSeenAckMsgIds.add(message.id)) {
                    bleTransport.sendMessageSeenAck(routePeerId, listOf(message.id))
                }
            }
            val senderCandidates = listOfNotNull(
                peerId,
                message.senderId,
                matchedPeer?.id,
                matchedPeer?.handle,
                matchedPeer?.displayName
            )
            val decision = notificationEvaluator.evaluateIncomingMessage(
                isSetupComplete = _hasSetUsername.value,
                localDisplayName = currentState.userProfile.displayName,
                isBluetoothEnabled = isBluetoothEnabled(),
                hasNotificationPermission = LayoverNotificationManager.hasNotificationPermission(appContext),
                senderCandidates = senderCandidates,
                messageId = message.id,
                peerStatus = matchedPeer?.status,
                acceptedPeerIds = acceptedPeerIds,
                blockedPeerIds = currentState.blockedPeerIds,
                blockedByPeerIds = currentState.blockedByPeerIds,
                mutedPeerIds = currentState.mutedPeerIds,
                isAppInForeground = _isAppInForeground.value,
                activeChatPeerId = _activeChatPeerId.value
            )
            if (decision.shouldNotify) {
                val routePeerId = matchedPeer?.id?.takeIf { it.isNotBlank() } ?: peerId
                val senderDisplayName = matchedPeer?.displayName?.takeIf { it.isNotBlank() }
                    ?: if (routePeerId.isNotBlank()) "Peer (${routePeerId.takeLast(4)})" else "Nearby Peer"
                LayoverNotificationManager.showIncomingMessageNotification(
                    context = appContext,
                    peerId = routePeerId,
                    senderName = senderDisplayName,
                    messageText = "Sent a photo"
                )
            }
        }
    }

    private fun updatePhotoAttachmentState(
        transferId: String,
        newState: PhotoTransferState,
        progress: Float,
        localFilePath: String?,
        statusDetail: String?
    ) {
        _uiState.update { state ->
            var modified = false
            val updatedMap = state.chatMessages.mapValues { (_, list) ->
                list.map { msg ->
                    val att = msg.photoAttachment
                    if (att != null && att.transferId == transferId) {
                        modified = true
                        msg.copy(
                            photoAttachment = att.copy(
                                state = newState,
                                progress = progress.coerceIn(0f, 1f),
                                localFilePath = localFilePath ?: att.localFilePath,
                                statusDetail = statusDetail
                            )
                        )
                    } else {
                        msg
                    }
                }
            }
            if (modified && newState == PhotoTransferState.COMPLETED) {
                persistChatHistory(updatedMap)
            }
            if (modified) state.copy(chatMessages = updatedMap) else state
        }
    }

    private fun hydrateCroppedFullResAvatar(
        avatar: PeerAvatar,
        peerIds: Collection<String?>,
        downloadsByKey: Map<String, PeerDpDownloadState>
    ): PeerAvatar {
        val currentDigest = PeerDpCacheStore.computeAvatarDigest(avatar.avatarBase64OrBytes)
        if (currentDigest.isNullOrBlank()) {
            return if (avatar.croppedFullResPath != null || avatar.croppedFullResDigest != null) {
                avatar.copy(croppedFullResPath = null, croppedFullResDigest = null)
            } else {
                avatar
            }
        }
        val stableSeeds = peerIds.mapNotNull { it?.takeIf { s -> s.isNotBlank() } }
        val aliases = (PeerAliasRegistry.resolveStableIdentifiers(stableSeeds) + stableSeeds)
            .filter { it.isNotBlank() }
            .toSet()

        val statePath = aliases.firstNotNullOfOrNull { alias ->
            downloadsByKey["${alias.lowercase()}|$currentDigest"]
                ?.takeIf { it.state == PhotoTransferState.COMPLETED }
                ?.localFilePath
        }?.takeIf { PeerDpCacheStore.isCroppedPeerDpPath(it) && java.io.File(it).exists() }

        val existingPath = avatar.croppedFullResPath
            ?.takeIf {
                avatar.croppedFullResDigest == currentDigest &&
                    PeerDpCacheStore.isCroppedPeerDpPath(it) &&
                    java.io.File(it).exists()
            }

        val resolvedPath = statePath
            ?: existingPath
            ?: peerDpCacheStore.getCachedPeerDpFile(aliases, currentDigest)?.absolutePath

        return if (resolvedPath != null) {
            if (avatar.croppedFullResPath == resolvedPath && avatar.croppedFullResDigest == currentDigest) {
                avatar
            } else {
                avatar.copy(
                    croppedFullResPath = resolvedPath,
                    croppedFullResDigest = currentDigest
                )
            }
        } else if (avatar.croppedFullResPath != null || avatar.croppedFullResDigest != null) {
            avatar.copy(croppedFullResPath = null, croppedFullResDigest = null)
        } else {
            avatar
        }
    }

    private fun updatePeerDpDownloadState(
        peerId: String,
        avatarDigest: String,
        dpState: PeerDpDownloadState
    ) {
        _uiState.update { state ->
            val updatedDownloads = state.peerDpDownloadsByKey.toMutableMap()
            val matchedPeers = findAllMatchingPeers(state.nearbyPeers, peerId, "")
            val stableSeeds = buildList {
                add(peerId)
                matchedPeers.forEach { peer ->
                    if (peer.id.isNotBlank()) add(peer.id)
                    if (peer.handle.isNotBlank()) add(peer.handle)
                }
            }.filter { it.isNotBlank() }
            val aliases = (
                PeerAliasRegistry.resolveStableIdentifiers(stableSeeds) + stableSeeds
            ).filter { it.isNotBlank() }.toSet()
            aliases.forEach { alias ->
                updatedDownloads["${alias.lowercase()}|$avatarDigest"] = dpState
            }

            val completedPath = dpState.localFilePath
                ?.takeIf {
                    dpState.state == PhotoTransferState.COMPLETED &&
                        PeerDpCacheStore.isCroppedPeerDpPath(it) &&
                        java.io.File(it).exists()
                }
            val updatedNearbyPeers = if (completedPath != null) {
                state.nearbyPeers.map { peer ->
                    val peerMatches = aliases.any { alias ->
                        peer.id.equals(alias, ignoreCase = true) ||
                            peer.handle.equals(alias, ignoreCase = true)
                    }
                    if (peerMatches) {
                        val peerDigest = PeerDpCacheStore.computeAvatarDigest(peer.avatar.avatarBase64OrBytes)
                        if (peerDigest == null || peerDigest == avatarDigest) {
                            peer.copy(
                                avatar = peer.avatar.copy(
                                    croppedFullResPath = completedPath,
                                    croppedFullResDigest = avatarDigest
                                )
                            )
                        } else {
                            peer
                        }
                    } else {
                        peer
                    }
                }
            } else {
                state.nearbyPeers
            }

            state.copy(
                peerDpDownloadsByKey = updatedDownloads,
                nearbyPeers = updatedNearbyPeers
            )
        }
    }

    fun surfacePendingConnectionRequestFromNotification(peerId: String, displayName: String) {
        if (peerId.isBlank()) return
        LayoverNotificationManager.cancelConnectionRequestNotification(appContext, peerId)
        notificationEvaluator.clearRequestDedupe(peerId, displayName)
        if (bleTransport.isPeerIdentifierBlocked(peerId, displayName)) return

        val state = _uiState.value
        val existingPeer = findMatchingPeer(state.nearbyPeers, peerId, displayName)
        if (existingPeer?.status == ConnectionStatus.ACCEPTED) return

        val resolvedName = existingPeer?.displayName?.takeIf { it.isNotBlank() }
            ?: displayName.ifBlank { "Layover Peer" }
        _incomingRequestPeer.value = Pair(existingPeer?.id ?: peerId, resolvedName)
    }

    private fun recordAcceptedPeer(vararg identifiers: String?) {
        val expanded = notificationEvaluator.expandIdentifiers(identifiers.toList())
        if (expanded.isEmpty()) return
        val updated = acceptedPeerIds + expanded
        acceptedPeerIds = updated
        prefs.edit().putStringSet(KEY_ACCEPTED_PEER_IDS, updated).apply()
    }

    private fun removeAcceptedPeer(vararg identifiers: String?) {
        val expanded = notificationEvaluator.expandIdentifiers(identifiers.toList())
        if (expanded.isEmpty()) return
        val updated = acceptedPeerIds.filterNot { existing ->
            expanded.any { candidate -> existing.equals(candidate, ignoreCase = true) }
        }.toSet()
        acceptedPeerIds = updated
        prefs.edit().putStringSet(KEY_ACCEPTED_PEER_IDS, updated).apply()
    }

    private fun findAllMatchingPeers(
        peers: List<NearbyPeer>,
        peerId: String,
        displayName: String = ""
    ): List<NearbyPeer> {
        val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId, displayName))
        val cleanName = displayName.trim().takeUnless { it.isEmpty() || it.startsWith("Layover Peer (", ignoreCase = true) }
        return peers.filter { p ->
            val peerAliases = notificationEvaluator.expandIdentifiers(listOf(p.id, p.handle))
            val aliasOverlap = aliases.any { a ->
                peerAliases.any { pa -> pa.equals(a, ignoreCase = true) }
            }
            val nameMatch = cleanName != null &&
                p.displayName.isNotBlank() &&
                !p.displayName.startsWith("Layover Peer (", ignoreCase = true) &&
                p.displayName.equals(cleanName, ignoreCase = true)
            aliasOverlap || nameMatch
        }
    }

    private fun findMatchingPeer(
        peers: List<NearbyPeer>,
        peerId: String,
        displayName: String = ""
    ): NearbyPeer? {
        return findAllMatchingPeers(peers, peerId, displayName).firstOrNull()
    }

    private fun isPeerInAcceptedSet(vararg candidates: String?): Boolean {
        val expanded = notificationEvaluator.expandIdentifiers(candidates.toList())
        if (expanded.isEmpty() || acceptedPeerIds.isEmpty()) return false
        val expandedAccepted = notificationEvaluator.expandIdentifiers(acceptedPeerIds)
        return expanded.any { cand -> expandedAccepted.any { acc -> cand.equals(acc, ignoreCase = true) } }
    }

    private fun lookupCachedPeerMeta(vararg identifiers: String?): CachedPeerProfileMeta? {
        val aliases = notificationEvaluator.expandIdentifiers(identifiers.toList())
        return aliases.firstNotNullOfOrNull { cachedPeerMetaByAlias[it.lowercase()] }
    }

    private fun storeCachedPeerMeta(
        identifiers: Collection<String?>,
        displayName: String?,
        gender: GenderOption?,
        age: Int?,
        avatarBytes: ByteArray?
    ) {
        val aliases = notificationEvaluator.expandIdentifiers(identifiers)
        if (aliases.isEmpty()) return
        val existing = aliases.firstNotNullOfOrNull { cachedPeerMetaByAlias[it.lowercase()] }
        val merged = CachedPeerProfileMeta(
            displayName = displayName?.takeIf { it.isNotBlank() } ?: existing?.displayName,
            gender = gender ?: existing?.gender,
            age = age?.takeIf { it > 0 } ?: existing?.age,
            avatarBytes = avatarBytes?.takeIf { it.isNotEmpty() } ?: existing?.avatarBytes
        )
        aliases.forEach { alias ->
            cachedPeerMetaByAlias[alias.lowercase()] = merged
        }
    }

    private fun wireTransportCollectors() {
        bleTransport.onPeerProfileUpdated = { peerId, displayName, gender, age, avatarBytes ->
            PeerAliasRegistry.linkAliases(listOfNotNull(peerId, displayName?.ifBlank { null }))
            storeCachedPeerMeta(
                identifiers = listOf(peerId, displayName),
                displayName = displayName,
                gender = gender,
                age = age,
                avatarBytes = avatarBytes
            )
            _uiState.update { state ->
                val matchedPeers = findAllMatchingPeers(state.nearbyPeers, peerId, displayName ?: "")
                PeerAliasRegistry.linkAliases(
                    listOfNotNull(peerId, displayName?.ifBlank { null }) +
                        matchedPeers.flatMap { listOfNotNull(it.id, it.handle.ifEmpty { null }, it.displayName.ifEmpty { null }) }
                )
                val matchedIds = matchedPeers.map { it.id }.toSet()
                val updatedPeers = state.nearbyPeers.map { p ->
                    if (p.id in matchedIds) {
                        val baseAvatar = if (avatarBytes != null && avatarBytes.isNotEmpty()) {
                            p.avatar.copy(avatarBase64OrBytes = avatarBytes)
                        } else {
                            p.avatar
                        }
                        val hydratedAvatar = hydrateCroppedFullResAvatar(
                            avatar = baseAvatar,
                            peerIds = listOf(peerId, p.id, p.handle),
                            downloadsByKey = state.peerDpDownloadsByKey
                        )
                        p.copy(
                            displayName = displayName?.takeIf { it.isNotBlank() } ?: p.displayName,
                            gender = gender ?: p.gender,
                            age = age ?: p.age,
                            avatar = hydratedAvatar
                        )
                    } else {
                        p
                    }
                }
                state.copy(nearbyPeers = canonicalDeduplicatePeers(updatedPeers))
            }
        }

        scope.launch {
            bleTransport.diagnosticsTracker.diagnosticsState.collect { diagnostics ->
                _uiState.update { it.copy(diagnostics = diagnostics) }
            }
        }

        scope.launch {
            bleDiscoveryManager.discoveredPeers.collect { liveDiscoveredPeers ->
                _uiState.update { current ->
                    val existingList = current.nearbyPeers
                    val mergedLive = canonicalDeduplicatePeers(liveDiscoveredPeers).map { discovered ->
                        val existing = findMatchingPeer(existingList, discovered.id, discovered.displayName)
                            ?: findMatchingPeer(existingList, discovered.handle, discovered.displayName)
                        if (existing != null) {
                            PeerAliasRegistry.linkAliases(
                                listOfNotNull(
                                    discovered.id,
                                    discovered.handle.ifEmpty { null },
                                    discovered.displayName.ifEmpty { null },
                                    existing.id,
                                    existing.handle.ifEmpty { null },
                                    existing.displayName.ifEmpty { null }
                                )
                            )
                        }
                        val cachedMeta = lookupCachedPeerMeta(
                            discovered.id,
                            discovered.handle,
                            discovered.displayName,
                            existing?.id,
                            existing?.handle
                        )
                        val baseResolvedAvatar = when {
                            discovered.avatar.avatarBase64OrBytes != null -> discovered.avatar
                            existing != null && existing.avatar.avatarBase64OrBytes != null -> existing.avatar
                            cachedMeta?.avatarBytes != null -> discovered.avatar.copy(avatarBase64OrBytes = cachedMeta.avatarBytes)
                            else -> discovered.avatar
                        }
                        val resolvedAvatar = hydrateCroppedFullResAvatar(
                            avatar = baseResolvedAvatar,
                            peerIds = listOf(discovered.id, discovered.handle, existing?.id, existing?.handle),
                            downloadsByKey = current.peerDpDownloadsByKey
                        )
                        val resolvedAge = discovered.age ?: existing?.age ?: cachedMeta?.age
                        val resolvedGender = discovered.gender ?: existing?.gender ?: cachedMeta?.gender
                        val existingName = existing?.displayName?.takeIf { it.isNotBlank() }
                        val cachedName = cachedMeta?.displayName?.takeIf { it.isNotBlank() }
                        val discoveredName = discovered.displayName.takeIf { it.isNotBlank() }
                        val resolvedDisplayName = when {
                            cachedName != null && discoveredName != null &&
                                cachedName.length > discoveredName.length &&
                                cachedName.startsWith(discoveredName, ignoreCase = true) -> cachedName
                            existingName != null && discoveredName != null &&
                                existingName.length > discoveredName.length &&
                                existingName.startsWith(discoveredName, ignoreCase = true) -> existingName
                            discoveredName != null && !discoveredName.startsWith("Layover Peer (", ignoreCase = true) -> discoveredName
                            existingName != null -> existingName
                            cachedName != null -> cachedName
                            else -> discovered.displayName
                        }
                        val resolvedStatus = when {
                            existing != null && existing.status != ConnectionStatus.NONE -> existing.status
                            discovered.status != ConnectionStatus.NONE -> discovered.status
                            isPeerInAcceptedSet(discovered.id, discovered.handle, resolvedDisplayName) -> ConnectionStatus.ACCEPTED
                            else -> ConnectionStatus.NONE
                        }
                        if (existing != null && resolvedStatus != ConnectionStatus.NONE) {
                            discovered.copy(
                                id = existing.id,
                                handle = existing.handle.ifEmpty { discovered.handle },
                                displayName = resolvedDisplayName,
                                gender = resolvedGender,
                                age = resolvedAge,
                                status = resolvedStatus,
                                avatar = resolvedAvatar
                            )
                        } else {
                            discovered.copy(
                                displayName = resolvedDisplayName,
                                gender = resolvedGender,
                                age = resolvedAge,
                                status = resolvedStatus,
                                avatar = resolvedAvatar
                            )
                        }
                    }

                    val retainedActivePeers = existingList.filter { existing ->
                        existing.status == ConnectionStatus.ACCEPTED || existing.status == ConnectionStatus.REQUESTED
                    }
                    val merged = canonicalDeduplicatePeers(mergedLive + retainedActivePeers)
                    bleTransport.diagnosticsTracker.updateDiscoveredPeersCount(merged.size)
                    current.copy(nearbyPeers = merged)
                }
            }
        }

        scope.launch {
            bleTransport.incomingMessagesFlow.collect { (peerId, message) ->
                LayoverFileLogger.log("Incoming message collected in runtime: from=$peerId, msgId=${message.id}")

                var matchedPeer: NearbyPeer? = null
                var updatedKeysCount = 0
                _uiState.update { state ->
                    val updatedMap = state.chatMessages.toMutableMap()
                    val targetKeys = LinkedHashSet<String>()
                    fun collectKey(key: String?) {
                        if (!key.isNullOrBlank()) targetKeys.add(key.trim())
                    }

                    collectKey(peerId)
                    PeerAliasRegistry.resolveAliases(peerId).forEach { collectKey(it) }
                    val peer = findMatchingPeer(state.nearbyPeers, peerId)
                    matchedPeer = peer
                    peer?.let { p ->
                        collectKey(p.id)
                        collectKey(p.handle)
                        collectKey(p.displayName)
                        PeerAliasRegistry.resolveAliases(p.id).forEach { collectKey(it) }
                    }

                    // Also include any existing map keys that match case-insensitively across aliases
                    val existingMatchingKeys = updatedMap.keys.filter { existingKey ->
                        targetKeys.any { target -> existingKey.equals(target, ignoreCase = true) }
                    }
                    targetKeys.addAll(existingMatchingKeys)

                    val safeMessage = if (message.timestampMillis <= 0L) {
                        val safeTs = System.currentTimeMillis()
                        message.copy(
                            timestampMillis = safeTs,
                            timestampLabel = formatMessageBubbleTimestamp(safeTs)
                        )
                    } else {
                        message
                    }

                    for (key in targetKeys) {
                        val list = updatedMap[key]?.toMutableList() ?: mutableListOf()
                        if (!list.any { it.id == safeMessage.id }) {
                            list.add(safeMessage)
                            updatedMap[key] = list
                            updatedKeysCount++
                        }
                    }

                    if (updatedKeysCount > 0) {
                        persistChatHistory(updatedMap)
                    }

                    val unreadCandidates = listOfNotNull(
                        peerId,
                        message.senderId,
                        peer?.id,
                        peer?.handle,
                        peer?.displayName
                    )
                    val updatedUnread = if (!message.isOutgoing) {
                        unreadTracker.recordIncomingMessage(
                            senderCandidates = unreadCandidates,
                            messageId = message.id,
                            isAppInForeground = _isAppInForeground.value,
                            activeChatPeerId = _activeChatPeerId.value
                        )
                    } else {
                        state.unreadCountsByPeerId
                    }
                    state.copy(
                        chatMessages = updatedMap,
                        unreadCountsByPeerId = updatedUnread
                    )
                }

                LayoverFileLogger.log(
                    "Runtime UI state updated for incoming CHAT_ENCRYPTED: from=$peerId, " +
                        "matchedPeerId=${matchedPeer?.id}, msgId=${message.id}, keysUpdated=$updatedKeysCount"
                )

                val currentState = _uiState.value
                val routePeerIdForAck = matchedPeer?.id?.takeIf { it.isNotBlank() } ?: peerId
                val ackStableIds = PeerAliasRegistry.resolveStableIdentifiers(
                    listOfNotNull(peerId, message.senderId, matchedPeer?.id, matchedPeer?.handle)
                )
                val peerNotBlocked = !PeerAliasRegistry.matchesBlocklist(ackStableIds, currentState.blockedPeerIds) &&
                    !PeerAliasRegistry.matchesBlocklist(ackStableIds, currentState.blockedByPeerIds)
                if (!message.isOutgoing && peerNotBlocked &&
                    isPeerCurrentlyViewedInChat(peerId, matchedPeer) &&
                    sentSeenAckMsgIds.add(message.id)
                ) {
                    bleTransport.sendMessageSeenAck(routePeerIdForAck, listOf(message.id))
                }

                val senderCandidates = listOfNotNull(
                    peerId,
                    message.senderId,
                    matchedPeer?.id,
                    matchedPeer?.handle,
                    matchedPeer?.displayName
                )
                val decision = notificationEvaluator.evaluateIncomingMessage(
                    isSetupComplete = _hasSetUsername.value,
                    localDisplayName = currentState.userProfile.displayName,
                    isBluetoothEnabled = isBluetoothEnabled(),
                    hasNotificationPermission = LayoverNotificationManager.hasNotificationPermission(appContext),
                    senderCandidates = senderCandidates,
                    messageId = message.id,
                    peerStatus = matchedPeer?.status,
                    acceptedPeerIds = acceptedPeerIds,
                    blockedPeerIds = currentState.blockedPeerIds,
                    blockedByPeerIds = currentState.blockedByPeerIds,
                    mutedPeerIds = currentState.mutedPeerIds,
                    isAppInForeground = _isAppInForeground.value,
                    activeChatPeerId = _activeChatPeerId.value
                )

                LayoverFileLogger.log(
                    "Incoming message notification decision: shouldNotify=${decision.shouldNotify}, " +
                        "reason=${decision.reason}, peerId=$peerId, msgId=${message.id}, " +
                        "foreground=${_isAppInForeground.value}, activeChat=${_activeChatPeerId.value}"
                )

                if (decision.shouldNotify) {
                    val routePeerId = matchedPeer?.id?.takeIf { it.isNotBlank() } ?: peerId
                    val senderDisplayName = matchedPeer?.displayName?.takeIf { it.isNotBlank() }
                        ?: if (routePeerId.isNotBlank()) "Peer (${routePeerId.takeLast(4)})" else "Nearby Peer"
                    LayoverNotificationManager.showIncomingMessageNotification(
                        context = appContext,
                        peerId = routePeerId,
                        senderName = senderDisplayName,
                        messageText = message.text
                    )
                }
            }
        }

        scope.launch {
            bleTransport.incomingMessageDeletesFlow.collect { (peerId, msgId) ->
                LayoverFileLogger.log("Incoming DELETE_MESSAGE collected in runtime: from=$peerId, msgId=$msgId")
                handleIncomingRemoteMessageDeletion(peerId = peerId, msgId = msgId)
            }
        }

        scope.launch {
            bleTransport.incomingConnectionRequestFlow.collect { (peerId, displayName) ->
                if (bleTransport.isPeerIdentifierBlocked(peerId, displayName)) {
                    LayoverFileLogger.log("Dropped CONNECTION_REQUEST for blocked peer: id=$peerId, name=$displayName")
                    return@collect
                }

                LayoverFileLogger.log("Incoming CONNECTION_REQUEST in runtime: id=$peerId, name=$displayName")

                var resolvedPeerId = peerId
                var resolvedName = displayName.ifBlank { "Layover Peer" }
                val cachedMeta = lookupCachedPeerMeta(peerId, displayName)

                _uiState.update { state ->
                    val matchedPeers = findAllMatchingPeers(state.nearbyPeers, peerId, displayName)
                    PeerAliasRegistry.linkAliases(
                        listOfNotNull(peerId, displayName.ifBlank { null }) +
                            matchedPeers.flatMap { listOfNotNull(it.id, it.handle.ifEmpty { null }, it.displayName.ifEmpty { null }) }
                    )
                    val updatedPeers = if (matchedPeers.isEmpty()) {
                        state.nearbyPeers + NearbyPeer(
                            id = peerId,
                            displayName = resolvedName,
                            handle = peerId,
                            gender = cachedMeta?.gender,
                            age = cachedMeta?.age,
                            status = ConnectionStatus.REQUESTED,
                            avatar = PeerAvatar(
                                peerId = peerId,
                                initials = resolvedName.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").ifEmpty { "?" },
                                avatarBase64OrBytes = cachedMeta?.avatarBytes
                            )
                        )
                    } else {
                        val primaryMatch = matchedPeers.first()
                        resolvedPeerId = if (peerId.startsWith("dev_", ignoreCase = true)) {
                            peerId
                        } else {
                            primaryMatch.id.ifBlank { peerId }
                        }
                        resolvedName = displayName.ifBlank { primaryMatch.displayName.ifBlank { "Layover Peer" } }
                        val matchedIds = matchedPeers.map { it.id }.toSet()
                        state.nearbyPeers.map { p ->
                            if (p.id in matchedIds) {
                                p.copy(
                                    handle = peerId.ifEmpty { p.handle },
                                    displayName = resolvedName,
                                    gender = cachedMeta?.gender ?: p.gender,
                                    age = cachedMeta?.age ?: p.age,
                                    status = if (p.status == ConnectionStatus.ACCEPTED) ConnectionStatus.ACCEPTED else ConnectionStatus.REQUESTED,
                                    avatar = if (p.avatar.avatarBase64OrBytes == null && cachedMeta?.avatarBytes != null) {
                                        p.avatar.copy(avatarBase64OrBytes = cachedMeta.avatarBytes)
                                    } else {
                                        p.avatar
                                    }
                                )
                            } else {
                                p
                            }
                        }
                    }
                    state.copy(nearbyPeers = canonicalDeduplicatePeers(updatedPeers))
                }

                _incomingRequestPeer.value = Pair(resolvedPeerId, resolvedName)

                val currentState = _uiState.value
                val decision = notificationEvaluator.evaluateConnectionRequest(
                    isSetupComplete = _hasSetUsername.value,
                    localDisplayName = currentState.userProfile.displayName,
                    isBluetoothEnabled = isBluetoothEnabled(),
                    hasNotificationPermission = LayoverNotificationManager.hasNotificationPermission(appContext),
                    senderCandidates = listOf(peerId, resolvedPeerId, displayName, resolvedName),
                    blockedPeerIds = currentState.blockedPeerIds,
                    blockedByPeerIds = currentState.blockedByPeerIds,
                    mutedPeerIds = currentState.mutedPeerIds,
                    isAppInForeground = _isAppInForeground.value
                )

                LayoverFileLogger.log(
                    "Incoming connection request notification decision: shouldNotify=${decision.shouldNotify}, " +
                        "reason=${decision.reason}, peerId=$resolvedPeerId, name=$resolvedName, " +
                        "foreground=${_isAppInForeground.value}"
                )

                if (decision.shouldNotify) {
                    LayoverNotificationManager.showConnectionRequestNotification(
                        context = appContext,
                        peerId = resolvedPeerId,
                        requesterName = resolvedName
                    )
                }
            }
        }

        scope.launch {
            bleTransport.incomingConnectionAcceptFlow.collect { peerId ->
                LayoverFileLogger.log("Incoming CONNECTION_ACCEPT in runtime: peerId=$peerId")
                val existingPeers = findAllMatchingPeers(_uiState.value.nearbyPeers, peerId)
                PeerAliasRegistry.linkAliases(
                    listOf(peerId) + existingPeers.flatMap { listOfNotNull(it.id, it.handle.ifEmpty { null }, it.displayName.ifEmpty { null }) }
                )
                val existingPeer = existingPeers.firstOrNull()
                recordAcceptedPeer(peerId, existingPeer?.id, existingPeer?.handle, existingPeer?.displayName)
                notificationEvaluator.clearRequestDedupe(peerId, existingPeer?.id, existingPeer?.displayName)
                LayoverNotificationManager.cancelConnectionRequestNotification(appContext, peerId)
                bleDiscoveryManager.updatePeerStatus(peerId, ConnectionStatus.ACCEPTED)
                _acceptedRequestPeer.value = peerId

                _uiState.update { state ->
                    val matchedIds = findAllMatchingPeers(state.nearbyPeers, peerId).map { it.id }.toSet()
                    val updatedPeers = state.nearbyPeers.map { p ->
                        if (p.id in matchedIds) p.copy(status = ConnectionStatus.ACCEPTED) else p
                    }
                    state.copy(nearbyPeers = canonicalDeduplicatePeers(updatedPeers))
                }
            }
        }

        scope.launch {
            bleTransport.incomingConnectionDeclineFlow.collect { peerId ->
                LayoverFileLogger.log("Incoming CONNECTION_DECLINE in runtime: peerId=$peerId")
                val existingPeers = findAllMatchingPeers(_uiState.value.nearbyPeers, peerId)
                PeerAliasRegistry.linkAliases(
                    listOf(peerId) + existingPeers.flatMap { listOfNotNull(it.id, it.handle.ifEmpty { null }, it.displayName.ifEmpty { null }) }
                )
                removeAcceptedPeer(peerId)
                notificationEvaluator.clearRequestDedupe(peerId)
                LayoverNotificationManager.cancelConnectionRequestNotification(appContext, peerId)
                bleDiscoveryManager.updatePeerStatus(peerId, ConnectionStatus.DECLINED)

                _uiState.update { state ->
                    val matchedIds = findAllMatchingPeers(state.nearbyPeers, peerId).map { it.id }.toSet()
                    val updatedPeers = state.nearbyPeers.map { p ->
                        if (p.id in matchedIds) p.copy(status = ConnectionStatus.DECLINED) else p
                    }
                    state.copy(nearbyPeers = canonicalDeduplicatePeers(updatedPeers))
                }
            }
        }

        scope.launch {
            bleTransport.outgoingMessageSentFlow.collect { msgId ->
                LayoverFileLogger.log("Outgoing GATT frame confirmed SENT: msgId=$msgId")
                advanceOutgoingMessageDeliveryStatus(
                    msgIds = listOf(msgId),
                    newStatus = MessageDeliveryStatus.SENT
                )
            }
        }

        scope.launch {
            bleTransport.incomingMessageDeliveredFlow.collect { (peerId, msgIds) ->
                LayoverFileLogger.log("Incoming MESSAGE_DELIVERED in runtime: peerId=$peerId, msgIds=$msgIds")
                advanceOutgoingMessageDeliveryStatus(
                    msgIds = msgIds,
                    newStatus = MessageDeliveryStatus.DELIVERED
                )
            }
        }

        scope.launch {
            bleTransport.incomingMessageSeenFlow.collect { (peerId, msgIds) ->
                LayoverFileLogger.log("Incoming MESSAGE_SEEN in runtime: peerId=$peerId, msgIds=$msgIds")
                advanceOutgoingMessageDeliveryStatus(
                    msgIds = msgIds,
                    newStatus = MessageDeliveryStatus.SEEN
                )
            }
        }

        scope.launch {
            bleTransport.incomingPeerBlockStateFlow.collect { (peerId, isBlockedByPeer) ->
                LayoverFileLogger.log("Incoming peer block state in runtime: peerId=$peerId, isBlockedByPeer=$isBlockedByPeer")
                _uiState.update { state ->
                    val matchedPeers = findAllMatchingPeers(state.nearbyPeers, peerId)
                    val matchedStableIds = matchedPeers.flatMap { listOfNotNull(it.id, it.handle.ifEmpty { null }) }
                    PeerAliasRegistry.linkAliases((listOf(peerId) + matchedStableIds).filter { PeerAliasRegistry.isStablePeerIdentifier(it) })
                    val updatedBlockedBy = applyPeerBlockStateUpdate(
                        currentBlockedByPeerIds = state.blockedByPeerIds,
                        peerId = peerId,
                        additionalStableIds = matchedStableIds,
                        isBlockedByPeer = isBlockedByPeer
                    )
                    prefs.edit().putStringSet(KEY_BLOCKED_BY_PEER_IDS, updatedBlockedBy).apply()
                    state.copy(blockedByPeerIds = updatedBlockedBy)
                }
                if (isBlockedByPeer) {
                    LayoverNotificationManager.cancelMessageNotification(appContext, peerId)
                    LayoverNotificationManager.cancelConnectionRequestNotification(appContext, peerId)
                }
            }
        }
    }
}
