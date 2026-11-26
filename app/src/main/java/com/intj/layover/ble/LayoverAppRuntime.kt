package com.intj.layover.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.GenderOption
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.NearbyPeer
import com.intj.layover.model.UserProfile
import com.intj.layover.notifications.LayoverNotificationManager
import com.intj.layover.notifications.NotificationEligibilityEvaluator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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
    val notificationEvaluator = NotificationEligibilityEvaluator()

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
        val savedName = if (usernameAlreadySet) (prefs.getString("display_name", "") ?: "") else ""
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
        val savedVisible = usernameAlreadySet && prefs.getBoolean("is_visible", true)

        val initialProfile = UserProfile(
            displayName = savedName,
            handle = savedHandle,
            gender = savedGender,
            age = savedAge,
            avatarBytes = savedAvatarBytes,
            compactTransferAvatarBytes = savedCompactAvatarBytes,
            croppedResolutionLabel = savedResolutionLabel,
            isVisibleToNearby = savedVisible
        )

        val savedBlocked = prefs.getStringSet(KEY_BLOCKED_PEER_IDS, emptySet())?.toSet().orEmpty()
        val savedBlockedBy = prefs.getStringSet(KEY_BLOCKED_BY_PEER_IDS, emptySet())?.toSet().orEmpty()
        val savedMuted = prefs.getStringSet(KEY_MUTED_PEER_IDS, emptySet())?.toSet().orEmpty()
        acceptedPeerIds = prefs.getStringSet(KEY_ACCEPTED_PEER_IDS, emptySet())?.toSet().orEmpty()

        var transportRef: BleProximityTransport? = null
        bleDiscoveryManager = BleDiscoveryManager(
            context = appContext,
            bleStateController = bleStateController,
            onRequestAvatarFetch = { device, mapKey, onLoaded ->
                LayoverFileLogger.log("onRequestAvatarFetch invoked: mapKey=$mapKey, transportReady=${transportRef != null}")
                transportRef?.requestAvatarFetch(device, mapKey, onLoaded)
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

        _uiState = MutableStateFlow(
            LayoverUiState(
                userProfile = initialProfile,
                nearbyPeers = emptyList(),
                blockedPeerIds = savedBlocked,
                blockedByPeerIds = savedBlockedBy,
                mutedPeerIds = savedMuted,
                isScanning = true,
                transport = bleTransport,
                diagnostics = bleTransport.diagnosticsTracker.diagnosticsState.value
            )
        )
        uiState = _uiState.asStateFlow()

        LayoverFileLogger.log(
            "LayoverAppRuntime initialized: deviceId=$savedDeviceId, handle=$savedHandle, " +
                "usernameSet=$usernameAlreadySet, visible=$savedVisible, " +
                "blocked=${savedBlocked.size}, muted=${savedMuted.size}, accepted=${acceptedPeerIds.size}"
        )

        registerBluetoothReceiver()
        wireTransportCollectors()
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
        }
        LayoverFileLogger.log("LayoverAppRuntime foreground state changed: inForeground=$inForeground")
    }

    fun setActiveChatPeerId(peerId: String?) {
        val clean = peerId?.takeIf { it.isNotBlank() }
        _activeChatPeerId.value = clean
        if (clean != null) {
            LayoverNotificationManager.cancelMessageNotification(appContext, clean)
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
        val cleanedName = chosenName.trim()
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
        bleTransport.updateProfile(newState.userProfile)
        bleTransport.updateSafetySettings(
            blocked = newState.blockedPeerIds,
            muted = newState.mutedPeerIds
        )

        if (newState.blockedPeerIds != oldState.blockedPeerIds) {
            prefs.edit().putStringSet(KEY_BLOCKED_PEER_IDS, newState.blockedPeerIds.toSet()).apply()
            newState.blockedPeerIds.forEach { blockedId ->
                LayoverNotificationManager.cancelMessageNotification(appContext, blockedId)
                LayoverNotificationManager.cancelConnectionRequestNotification(appContext, blockedId)
            }
        }
        if (newState.mutedPeerIds != oldState.mutedPeerIds) {
            prefs.edit().putStringSet(KEY_MUTED_PEER_IDS, newState.mutedPeerIds.toSet()).apply()
            val newlyMuted = newState.mutedPeerIds - oldState.mutedPeerIds
            newlyMuted.forEach { mutedId ->
                LayoverNotificationManager.cancelMessageNotification(appContext, mutedId)
                LayoverNotificationManager.cancelConnectionRequestNotification(appContext, mutedId)
            }
        }
        if (newState.blockedByPeerIds != oldState.blockedByPeerIds) {
            prefs.edit().putStringSet(KEY_BLOCKED_BY_PEER_IDS, newState.blockedByPeerIds.toSet()).apply()
        }

        if (newState.userProfile != oldProfile) {
            LayoverFileLogger.log(
                "UserProfile updated: name=${newState.userProfile.displayName}, " +
                    "gender=${newState.userProfile.gender.name}, visible=${newState.userProfile.isVisibleToNearby}"
            )
            val editor = prefs.edit()
                .putString("display_name", newState.userProfile.displayName)
                .putString("handle", newState.userProfile.handle)
                .putString("gender", newState.userProfile.gender.name)
                .putBoolean("is_visible", newState.userProfile.isVisibleToNearby)
            val ageValue = newState.userProfile.age
            if (ageValue != null) {
                editor.putInt("age", ageValue)
            } else {
                editor.remove("age")
            }
            newState.userProfile.avatarBytes?.let {
                editor.putString("avatar_b64", Base64.encodeToString(it, Base64.NO_WRAP))
            }
            newState.userProfile.compactTransferAvatarBytes?.let {
                editor.putString("compact_avatar_b64", Base64.encodeToString(it, Base64.NO_WRAP))
            }
            newState.userProfile.croppedResolutionLabel?.let {
                editor.putString("cropped_resolution", it)
            }
            val avatarChanged = !oldProfile.compactTransferAvatarBytes.contentEquals(newState.userProfile.compactTransferAvatarBytes) ||
                !oldProfile.avatarBytes.contentEquals(newState.userProfile.avatarBytes)
            if (avatarChanged) {
                LayoverFileLogger.log("Avatar bytes changed; updating avatar_updated_at timestamp.")
                editor.putLong("avatar_updated_at", System.currentTimeMillis())
            }
            editor.apply()

            if (newState.userProfile.isVisibleToNearby && _hasSetUsername.value && isBluetoothEnabled()) {
                bleDiscoveryManager.startAdvertising(newState.userProfile)
            } else {
                bleDiscoveryManager.stopAdvertising()
            }
        }

        val compactBytes = newState.userProfile.effectiveCompactAvatarBytes()
        val updatedNearbyPeers = newState.nearbyPeers.map { peer ->
            val isSelf = peer.id.equals(newState.userProfile.handle, ignoreCase = true) ||
                (peer.displayName.isNotEmpty() && peer.displayName.equals(newState.userProfile.displayName, ignoreCase = true))
            if (isSelf && compactBytes != null) {
                peer.copy(avatar = peer.avatar.copy(avatarBase64OrBytes = compactBytes))
            } else {
                peer
            }
        }

        _uiState.value = newState.copy(
            nearbyPeers = updatedNearbyPeers,
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

        _uiState.update { state ->
            val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId, displayName))
            val existing = state.nearbyPeers.find { p ->
                aliases.any { a ->
                    p.id.equals(a, ignoreCase = true) ||
                        p.handle.equals(a, ignoreCase = true) ||
                        (displayName.isNotBlank() && p.displayName.equals(displayName, ignoreCase = true))
                }
            }
            val updatedPeers = if (existing == null) {
                state.nearbyPeers + NearbyPeer(
                    id = peerId,
                    displayName = displayName.ifBlank { "Layover Peer" },
                    handle = peerId,
                    status = ConnectionStatus.ACCEPTED
                )
            } else {
                state.nearbyPeers.map { p ->
                    if (p.id == existing.id) p.copy(status = ConnectionStatus.ACCEPTED) else p
                }
            }
            state.copy(nearbyPeers = updatedPeers)
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
            val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId))
            val updatedPeers = state.nearbyPeers.map { p ->
                if (aliases.any { a -> p.id.equals(a, ignoreCase = true) || p.handle.equals(a, ignoreCase = true) }) {
                    p.copy(status = ConnectionStatus.DECLINED)
                } else {
                    p
                }
            }
            state.copy(nearbyPeers = updatedPeers)
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
            val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId))
            val updatedPeers = state.nearbyPeers.map { p ->
                if (aliases.any { a -> p.id.equals(a, ignoreCase = true) || p.handle.equals(a, ignoreCase = true) }) {
                    p.copy(status = ConnectionStatus.ACCEPTED)
                } else {
                    p
                }
            }
            state.copy(nearbyPeers = updatedPeers)
        }
    }

    fun clearAcceptedRequestDialog() {
        _acceptedRequestPeer.value = null
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

    private fun findMatchingPeer(
        peers: List<NearbyPeer>,
        peerId: String,
        displayName: String = ""
    ): NearbyPeer? {
        val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId, displayName))
        return peers.find { p ->
            aliases.any { a ->
                p.id.equals(a, ignoreCase = true) ||
                    p.handle.equals(a, ignoreCase = true) ||
                    (p.displayName.isNotBlank() && p.displayName.equals(a, ignoreCase = true))
            }
        }
    }

    private fun isPeerInAcceptedSet(vararg candidates: String?): Boolean {
        val expanded = notificationEvaluator.expandIdentifiers(candidates.toList())
        if (expanded.isEmpty() || acceptedPeerIds.isEmpty()) return false
        val expandedAccepted = notificationEvaluator.expandIdentifiers(acceptedPeerIds)
        return expanded.any { cand -> expandedAccepted.any { acc -> cand.equals(acc, ignoreCase = true) } }
    }

    private fun wireTransportCollectors() {
        bleTransport.onPeerProfileUpdated = { peerId, displayName, gender, age, avatarBytes ->
            _uiState.update { state ->
                val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId, displayName))
                val updatedPeers = state.nearbyPeers.map { p ->
                    val matches = aliases.any { a ->
                        p.id.equals(a, ignoreCase = true) || p.handle.equals(a, ignoreCase = true)
                    }
                    if (matches) {
                        p.copy(
                            displayName = displayName?.takeIf { it.isNotBlank() } ?: p.displayName,
                            gender = gender,
                            age = age ?: p.age,
                            avatar = if (avatarBytes != null && avatarBytes.isNotEmpty()) {
                                p.avatar.copy(avatarBase64OrBytes = avatarBytes)
                            } else {
                                p.avatar
                            }
                        )
                    } else {
                        p
                    }
                }
                state.copy(nearbyPeers = updatedPeers)
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
                    val merged = liveDiscoveredPeers
                        .groupBy { peer ->
                            val aliases = PeerAliasRegistry.resolveAliases(peer.id) +
                                PeerAliasRegistry.resolveAliases(peer.handle) +
                                listOfNotNull(peer.id.takeIf { it.isNotBlank() }, peer.handle.takeIf { it.isNotBlank() })
                            aliases.firstOrNull { it.startsWith("dev_", ignoreCase = true) }
                                ?: aliases.firstOrNull()
                                ?: peer.displayName.ifBlank { peer.id }
                        }
                        .map { (_, group) ->
                            val discovered = group.first()
                            val discoveredAliases = PeerAliasRegistry.resolveAliases(discovered.id) +
                                PeerAliasRegistry.resolveAliases(discovered.handle) +
                                listOfNotNull(discovered.id.takeIf { it.isNotBlank() }, discovered.handle.takeIf { it.isNotBlank() })

                            val existing = existingList.find { p ->
                                val existingAliases = PeerAliasRegistry.resolveAliases(p.id) +
                                    PeerAliasRegistry.resolveAliases(p.handle) +
                                    listOfNotNull(p.id.takeIf { it.isNotBlank() }, p.handle.takeIf { it.isNotBlank() })
                                discoveredAliases.any { dAlias -> existingAliases.any { eAlias -> dAlias.equals(eAlias, ignoreCase = true) } } ||
                                    (p.displayName.isNotEmpty() && p.displayName.equals(discovered.displayName, ignoreCase = true))
                            }
                            val resolvedAvatar = when {
                                discovered.avatar.avatarBase64OrBytes != null -> discovered.avatar
                                existing != null && existing.avatar.avatarBase64OrBytes != null -> existing.avatar
                                else -> discovered.avatar
                            }
                            val resolvedAge = discovered.age ?: existing?.age
                            val resolvedStatus = when {
                                existing != null && existing.status != ConnectionStatus.NONE -> existing.status
                                discovered.status != ConnectionStatus.NONE -> discovered.status
                                isPeerInAcceptedSet(discovered.id, discovered.handle, discovered.displayName) -> ConnectionStatus.ACCEPTED
                                else -> ConnectionStatus.NONE
                            }
                            if (existing != null && resolvedStatus != ConnectionStatus.NONE) {
                                discovered.copy(
                                    id = existing.id,
                                    handle = existing.handle.ifEmpty { discovered.handle },
                                    displayName = discovered.displayName.ifBlank { existing.displayName },
                                    age = resolvedAge,
                                    status = resolvedStatus,
                                    avatar = resolvedAvatar
                                )
                            } else {
                                discovered.copy(age = resolvedAge, status = resolvedStatus, avatar = resolvedAvatar)
                            }
                        }

                    bleTransport.diagnosticsTracker.updateDiscoveredPeersCount(merged.size)
                    current.copy(nearbyPeers = merged)
                }
            }
        }

        scope.launch {
            bleTransport.incomingMessagesFlow.collect { (peerId, message) ->
                LayoverFileLogger.log("Incoming message collected in runtime: from=$peerId, msgId=${message.id}")

                var matchedPeer: NearbyPeer? = null
                _uiState.update { state ->
                    val updatedMap = state.chatMessages.toMutableMap()
                    fun addMsgToKey(key: String) {
                        if (key.isBlank()) return
                        val list = updatedMap[key]?.toMutableList() ?: mutableListOf()
                        if (!list.any { it.id == message.id }) {
                            list.add(message)
                            updatedMap[key] = list
                        }
                    }

                    addMsgToKey(peerId)
                    PeerAliasRegistry.resolveAliases(peerId).forEach { addMsgToKey(it) }
                    val peer = findMatchingPeer(state.nearbyPeers, peerId)
                    matchedPeer = peer
                    peer?.let { p ->
                        addMsgToKey(p.id)
                        if (p.handle.isNotEmpty()) addMsgToKey(p.handle)
                        if (p.displayName.isNotEmpty()) addMsgToKey(p.displayName)
                    }
                    state.copy(chatMessages = updatedMap)
                }

                val currentState = _uiState.value
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
            bleTransport.incomingConnectionRequestFlow.collect { (peerId, displayName) ->
                if (bleTransport.isPeerIdentifierBlocked(peerId, displayName)) {
                    LayoverFileLogger.log("Dropped CONNECTION_REQUEST for blocked peer: id=$peerId, name=$displayName")
                    return@collect
                }

                LayoverFileLogger.log("Incoming CONNECTION_REQUEST in runtime: id=$peerId, name=$displayName")

                var resolvedPeerId = peerId
                var resolvedName = displayName.ifBlank { "Layover Peer" }

                _uiState.update { state ->
                    val existingPeer = findMatchingPeer(state.nearbyPeers, peerId, displayName)
                    val updatedPeers = if (existingPeer == null) {
                        state.nearbyPeers + NearbyPeer(
                            id = peerId,
                            displayName = resolvedName,
                            handle = peerId,
                            status = ConnectionStatus.REQUESTED
                        )
                    } else {
                        resolvedPeerId = existingPeer.id.ifBlank { peerId }
                        resolvedName = displayName.ifBlank { existingPeer.displayName.ifBlank { "Layover Peer" } }
                        state.nearbyPeers.map { p ->
                            if (p.id == existingPeer.id) {
                                p.copy(
                                    handle = peerId.ifEmpty { p.handle },
                                    displayName = resolvedName,
                                    status = if (p.status == ConnectionStatus.ACCEPTED) ConnectionStatus.ACCEPTED else ConnectionStatus.REQUESTED
                                )
                            } else {
                                p
                            }
                        }
                    }
                    state.copy(nearbyPeers = updatedPeers)
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
                val existingPeer = findMatchingPeer(_uiState.value.nearbyPeers, peerId)
                recordAcceptedPeer(peerId, existingPeer?.id, existingPeer?.handle, existingPeer?.displayName)
                notificationEvaluator.clearRequestDedupe(peerId, existingPeer?.id, existingPeer?.displayName)
                LayoverNotificationManager.cancelConnectionRequestNotification(appContext, peerId)
                bleDiscoveryManager.updatePeerStatus(peerId, ConnectionStatus.ACCEPTED)
                _acceptedRequestPeer.value = peerId

                _uiState.update { state ->
                    val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId))
                    val updatedPeers = state.nearbyPeers.map { p ->
                        if (aliases.any { a -> p.id.equals(a, ignoreCase = true) || p.handle.equals(a, ignoreCase = true) }) {
                            p.copy(status = ConnectionStatus.ACCEPTED)
                        } else {
                            p
                        }
                    }
                    state.copy(nearbyPeers = updatedPeers)
                }
            }
        }

        scope.launch {
            bleTransport.incomingConnectionDeclineFlow.collect { peerId ->
                LayoverFileLogger.log("Incoming CONNECTION_DECLINE in runtime: peerId=$peerId")
                removeAcceptedPeer(peerId)
                notificationEvaluator.clearRequestDedupe(peerId)
                LayoverNotificationManager.cancelConnectionRequestNotification(appContext, peerId)
                bleDiscoveryManager.updatePeerStatus(peerId, ConnectionStatus.DECLINED)

                _uiState.update { state ->
                    val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId))
                    val updatedPeers = state.nearbyPeers.map { p ->
                        if (aliases.any { a -> p.id.equals(a, ignoreCase = true) || p.handle.equals(a, ignoreCase = true) }) {
                            p.copy(status = ConnectionStatus.DECLINED)
                        } else {
                            p
                        }
                    }
                    state.copy(nearbyPeers = updatedPeers)
                }
            }
        }

        scope.launch {
            bleTransport.incomingPeerBlockStateFlow.collect { (peerId, isBlockedByPeer) ->
                LayoverFileLogger.log("Incoming peer block state in runtime: peerId=$peerId, isBlockedByPeer=$isBlockedByPeer")
                val aliases = notificationEvaluator.expandIdentifiers(listOf(peerId))
                _uiState.update { state ->
                    val updatedBlockedBy = state.blockedByPeerIds.toMutableSet()
                    if (isBlockedByPeer) {
                        updatedBlockedBy.addAll(aliases)
                    } else {
                        updatedBlockedBy.removeAll { existing ->
                            aliases.any { candidate -> existing.equals(candidate, ignoreCase = true) }
                        }
                    }
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
