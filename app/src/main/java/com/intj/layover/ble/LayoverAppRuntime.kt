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
import com.intj.layover.model.PeerAvatar
import com.intj.layover.model.UserProfile
import com.intj.layover.notifications.LayoverNotificationManager
import com.intj.layover.notifications.NotificationEligibilityEvaluator
import com.intj.layover.notifications.UnreadMessageTracker
import com.intj.layover.ui.screens.canonicalDeduplicatePeers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val unreadTracker = UnreadMessageTracker()

    private class CachedPeerProfileMeta(
        val displayName: String? = null,
        val gender: GenderOption? = null,
        val age: Int? = null,
        val avatarBytes: ByteArray? = null
    )

    private val cachedPeerMetaByAlias = ConcurrentHashMap<String, CachedPeerProfileMeta>()

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
        val sanitizedProfile = newState.userProfile.copy(
            displayName = newState.userProfile.displayName.trim().take(LayoverBleConstants.MAX_NAME_BYTE_LENGTH)
        )
        val sanitizedState = newState.copy(userProfile = sanitizedProfile)
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
            val avatarChanged = !oldProfile.compactTransferAvatarBytes.contentEquals(sanitizedProfile.compactTransferAvatarBytes) ||
                !oldProfile.avatarBytes.contentEquals(sanitizedProfile.avatarBytes)
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

        _uiState.value = sanitizedState.copy(
            nearbyPeers = canonicalDeduplicatePeers(updatedNearbyPeers),
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
                        p.copy(
                            displayName = displayName?.takeIf { it.isNotBlank() } ?: p.displayName,
                            gender = gender ?: p.gender,
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
                        val resolvedAvatar = when {
                            discovered.avatar.avatarBase64OrBytes != null -> discovered.avatar
                            existing != null && existing.avatar.avatarBase64OrBytes != null -> existing.avatar
                            cachedMeta?.avatarBytes != null -> discovered.avatar.copy(avatarBase64OrBytes = cachedMeta.avatarBytes)
                            else -> discovered.avatar
                        }
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
