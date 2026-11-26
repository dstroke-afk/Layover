package com.intj.layover

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.intj.layover.ble.BleDiscoveryManager
import com.intj.layover.ble.BlePermissionHelper
import com.intj.layover.ble.BleProximityTransport
import com.intj.layover.ble.BleStateController
import com.intj.layover.ble.GattSessionManager
import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.GenderOption
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.NearbyPeer
import com.intj.layover.model.UserProfile
import com.intj.layover.navigation.LayoverNavGraph
import com.intj.layover.navigation.LayoverRoute
import com.intj.layover.ui.screens.OneTimeUsernameDialog
import com.intj.layover.ui.theme.LayoverTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "LayoverBLE"
    }

    private lateinit var bleStateController: BleStateController
    private lateinit var bleDiscoveryManager: BleDiscoveryManager
    private lateinit var gattSessionManager: GattSessionManager
    private lateinit var bleTransport: BleProximityTransport

    private fun UserProfile.effectiveCompactAvatarBytes(): ByteArray? =
        compactTransferAvatarBytes ?: avatarBytes

    private fun openAppPermissionSettings(context: Context) {
        val missing = BlePermissionHelper.getRequiredPermissions().joinToString()
        LayoverFileLogger.log("Opening Settings.ACTION_APPLICATION_DETAILS_SETTINGS for package=${context.packageName}; blePerms=$missing")
        Log.d(TAG, "Opening app permission settings for package=${context.packageName}")
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LayoverFileLogger.log("MainActivity.onCreate: initializing profile prefs and BLE stack.")
        Log.d(TAG, "MainActivity.onCreate called; savedInstanceStateNull=${savedInstanceState == null}")

        val prefs = applicationContext.getSharedPreferences("layover_user_profile", Context.MODE_PRIVATE)
        val savedDeviceId = prefs.getString("device_id", null) ?: run {
            val newId = "dev_" + UUID.randomUUID().toString().take(8)
            prefs.edit().putString("device_id", newId).apply()
            LayoverFileLogger.log("Generated new deviceId=$newId")
            Log.d(TAG, "Generated new deviceId=$newId")
            newId
        }
        val usernameAlreadySet = prefs.getBoolean("username_set", false)
        val initialPermissionFlowDone = prefs.getBoolean("initial_permission_flow_done", false)
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

        LayoverFileLogger.log(
            "Loaded profile: deviceId=$savedDeviceId, handle=$savedHandle, usernameSet=$usernameAlreadySet, " +
                "initialPermissionFlowDone=$initialPermissionFlowDone, visible=$savedVisible, " +
                "hasAvatar=${savedAvatarBytes != null}, hasCompactAvatar=${savedCompactAvatarBytes != null}"
        )
        Log.d(
            TAG,
            "Loaded profile: deviceId=$savedDeviceId, usernameSet=$usernameAlreadySet, " +
                "initialPermissionFlowDone=$initialPermissionFlowDone, visible=$savedVisible"
        )

        var transportRef: BleProximityTransport? = null

        bleStateController = BleStateController(applicationContext)
        bleDiscoveryManager = BleDiscoveryManager(
            context = applicationContext,
            bleStateController = bleStateController,
            onRequestAvatarFetch = { device, mapKey, onLoaded ->
                LayoverFileLogger.log("onRequestAvatarFetch invoked: mapKey=$mapKey, transportReady=${transportRef != null}")
                Log.d(TAG, "onRequestAvatarFetch invoked: mapKey=$mapKey")
                transportRef?.requestAvatarFetch(device, mapKey, onLoaded)
            }
        )
        gattSessionManager = GattSessionManager(applicationContext)
        bleTransport = BleProximityTransport(
            context = applicationContext,
            discoveryManager = bleDiscoveryManager,
            bleStateController = bleStateController,
            gattSessionManager = gattSessionManager,
            currentUserProfile = initialProfile
        ).also {
            transportRef = it
        }

        setContent {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val bleStatus by bleStateController.statusState.collectAsState()
            val liveDiscoveredPeers by bleDiscoveryManager.discoveredPeers.collectAsState()
            val diagnostics by bleTransport.diagnosticsTracker.diagnosticsState.collectAsState()

            var uiState by remember {
                mutableStateOf(
                    LayoverUiState(
                        userProfile = initialProfile,
                        nearbyPeers = emptyList(),
                        isScanning = true,
                        transport = bleTransport,
                        diagnostics = diagnostics
                    )
                )
            }

            var incomingRequestPeer by remember { mutableStateOf<Pair<String, String>?>(null) }
            var acceptedRequestPeer by remember { mutableStateOf<String?>(null) }
            var hasBlePermissions by remember {
                mutableStateOf(BlePermissionHelper.hasAllRequiredPermissions(context))
            }
            var blePermissionDeniedOrDismissed by remember {
                mutableStateOf(
                    initialPermissionFlowDone && !BlePermissionHelper.hasAllRequiredPermissions(context)
                )
            }
            var hasCompletedInitialPermissionFlow by remember {
                mutableStateOf(initialPermissionFlowDone)
            }
            var hasSetUsername by remember {
                mutableStateOf(usernameAlreadySet)
            }
            var showUsernameAfterPermissions by remember {
                mutableStateOf(false)
            }
            var blePromptLaunchedInSession by remember {
                mutableStateOf(false)
            }
            var isBlePermissionPromptVisible by remember {
                mutableStateOf(false)
            }

            LaunchedEffect(diagnostics) {
                uiState = uiState.copy(diagnostics = diagnostics)
            }

            LaunchedEffect(liveDiscoveredPeers) {
                val existingList = uiState.nearbyPeers
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
                        if (existing != null && existing.status != ConnectionStatus.NONE) {
                            discovered.copy(
                                id = existing.id,
                                handle = existing.handle.ifEmpty { discovered.handle },
                                displayName = discovered.displayName.ifBlank { existing.displayName },
                                age = resolvedAge,
                                status = existing.status,
                                avatar = resolvedAvatar
                            )
                        } else {
                            discovered.copy(age = resolvedAge, avatar = resolvedAvatar)
                        }
                    }
                uiState = uiState.copy(nearbyPeers = merged)
                bleTransport.diagnosticsTracker.updateDiscoveredPeersCount(merged.size)
                merged.forEach { p ->
                    LayoverFileLogger.log("UI Peer state: id=${p.id}, name=${p.displayName}, avatarBytes=${p.avatar.avatarBase64OrBytes?.size ?: 0}")
                }
                Log.d(TAG, "UI State updated with ${merged.size} deduplicated BLE discovered peers.")
            }

            LaunchedEffect(Unit) {
                bleTransport.incomingMessagesFlow.collect { (peerId, message) ->
                    LayoverFileLogger.log("Incoming message collected: from=$peerId, msgId=${message.id}")
                    val updatedMap = uiState.chatMessages.toMutableMap()

                    fun addMsgToKey(key: String) {
                        val list = updatedMap[key]?.toMutableList() ?: mutableListOf()
                        if (!list.any { it.id == message.id }) {
                            list.add(message)
                            updatedMap[key] = list
                        }
                    }

                    addMsgToKey(peerId)

                    val aliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
                    uiState.nearbyPeers.find { peer ->
                        aliases.any { a ->
                            peer.id.equals(a, ignoreCase = true) ||
                                    peer.handle.equals(a, ignoreCase = true) ||
                                    peer.displayName.equals(a, ignoreCase = true)
                        }
                    }?.let { peer ->
                        addMsgToKey(peer.id)
                        if (peer.handle.isNotEmpty()) addMsgToKey(peer.handle)
                        if (peer.displayName.isNotEmpty()) addMsgToKey(peer.displayName)
                    }

                    uiState = uiState.copy(chatMessages = updatedMap)
                }
            }

            LaunchedEffect(Unit) {
                bleTransport.incomingConnectionRequestFlow.collectLatest { (peerId, displayName) ->
                    if (bleTransport.isPeerIdentifierBlocked(peerId, displayName)) {
                        LayoverFileLogger.log("Dropped CONNECTION_REQUEST for blocked peer: id=$peerId, name=$displayName")
                        Log.d(TAG, "Dropped CONNECTION_REQUEST popup for blocked peer: $peerId ($displayName)")
                        return@collectLatest
                    }

                    LayoverFileLogger.log("Incoming CONNECTION_REQUEST: id=$peerId, name=$displayName")
                    incomingRequestPeer = Pair(peerId, displayName)

                    val aliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
                    val existingPeer = uiState.nearbyPeers.find { p ->
                        aliases.any { a ->
                            p.id.equals(a, ignoreCase = true) ||
                                    p.handle.equals(a, ignoreCase = true) ||
                                    (displayName.isNotEmpty() && p.displayName.equals(displayName, ignoreCase = true))
                        }
                    }

                    val updatedPeers = if (existingPeer == null) {
                        uiState.nearbyPeers + NearbyPeer(
                            id = peerId,
                            displayName = displayName.ifEmpty { "Layover Peer" },
                            handle = peerId,
                            status = ConnectionStatus.REQUESTED
                        )
                    } else {
                        uiState.nearbyPeers.map { p ->
                            if (p.id == existingPeer.id) {
                                p.copy(
                                    handle = peerId.ifEmpty { p.handle },
                                    displayName = displayName.ifEmpty { p.displayName },
                                    status = ConnectionStatus.REQUESTED
                                )
                            } else {
                                p
                            }
                        }
                    }
                    uiState = uiState.copy(nearbyPeers = updatedPeers)
                }
            }

            LaunchedEffect(Unit) {
                bleTransport.incomingConnectionAcceptFlow.collectLatest { peerId ->
                    LayoverFileLogger.log("Incoming CONNECTION_ACCEPT: peerId=$peerId")
                    acceptedRequestPeer = peerId
                    val aliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
                    val existingPeer = uiState.nearbyPeers.find { p ->
                        aliases.any { a -> p.id.equals(a, ignoreCase = true) || p.handle.equals(a, ignoreCase = true) }
                    }

                    val updatedPeers = uiState.nearbyPeers.map { p ->
                        if (p.id == peerId || p.handle == peerId || (existingPeer != null && p.id == existingPeer.id)) {
                            p.copy(status = ConnectionStatus.ACCEPTED)
                        } else {
                            p
                        }
                    }
                    uiState = uiState.copy(nearbyPeers = updatedPeers)
                }
            }

            LaunchedEffect(Unit) {
                bleTransport.incomingConnectionDeclineFlow.collectLatest { peerId ->
                    LayoverFileLogger.log("Incoming CONNECTION_DECLINE: peerId=$peerId")
                    val aliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
                    val updatedPeers = uiState.nearbyPeers.map { p ->
                        if (aliases.any { a -> p.id.equals(a, ignoreCase = true) || p.handle.equals(a, ignoreCase = true) }) {
                            p.copy(status = ConnectionStatus.DECLINED)
                        } else {
                            p
                        }
                    }
                    uiState = uiState.copy(nearbyPeers = updatedPeers)
                }
            }

            LaunchedEffect(Unit) {
                bleTransport.incomingPeerBlockStateFlow.collectLatest { event: Pair<String, Boolean> ->
                    val peerId = event.first
                    val isBlockedByPeer = event.second
                    LayoverFileLogger.log("Incoming peer block state: peerId=$peerId, isBlockedByPeer=$isBlockedByPeer")
                    val aliases = (PeerAliasRegistry.resolveAliases(peerId) + peerId)
                        .filter { alias: String -> alias.isNotBlank() && alias != "00000000" && alias != "user" }
                        .toSet()
                    val updatedBlockedBy = uiState.blockedByPeerIds.toMutableSet()
                    if (isBlockedByPeer) {
                        updatedBlockedBy.addAll(aliases)
                    } else {
                        updatedBlockedBy.removeAll { existing: String ->
                            aliases.any { candidate: String -> existing.equals(candidate, ignoreCase = true) }
                        }
                    }
                    uiState = uiState.copy(blockedByPeerIds = updatedBlockedBy)
                }
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permsMap ->
                isBlePermissionPromptVisible = false
                val actualBleGranted = BlePermissionHelper.hasAllRequiredPermissions(context)
                val allBleGranted = if (permsMap.isEmpty()) actualBleGranted else (permsMap.values.all { it } || actualBleGranted)
                bleStateController.refreshPermissionAndAdapterState()
                hasBlePermissions = allBleGranted

                LayoverFileLogger.log(
                    "BLE permission result: mapEmpty=${permsMap.isEmpty()}, actualBleGranted=$actualBleGranted, " +
                        "allBleGranted=$allBleGranted, hasBlePermissions=$hasBlePermissions, details=$permsMap"
                )
                Log.d(TAG, "BLE permission result: allBleGranted=$allBleGranted, details=$permsMap")

                if (permsMap.isEmpty() && !actualBleGranted) {
                    LayoverFileLogger.log("Ignoring empty BLE permission map while actualBleGranted=false (activity recreate during prompt).")
                    return@rememberLauncherForActivityResult
                }

                hasCompletedInitialPermissionFlow = true
                prefs.edit()
                    .putBoolean("initial_permission_flow_done", true)
                    .putBoolean("ble_permission_prompt_open", false)
                    .apply()

                if (allBleGranted) {
                    blePermissionDeniedOrDismissed = false
                    prefs.edit().putBoolean("ble_permission_denied_or_dismissed", false).apply()
                    Log.d(TAG, "BLE permissions granted via system dialog.")
                    bleTransport.startDiscovery(uiState.userProfile.isVisibleToNearby && hasSetUsername)
                } else {
                    blePermissionDeniedOrDismissed = true
                    prefs.edit().putBoolean("ble_permission_denied_or_dismissed", true).apply()
                    Log.w(TAG, "BLE permissions denied or dismissed by user; showing persistent card on People Around.")
                }

                if (!hasSetUsername) {
                    showUsernameAfterPermissions = true
                }
            }

            // Keep missing-permission transport callback non-looping: rely on first-launch flow + persistent card in PeopleAroundScreen
            bleTransport.onMissingPermissions = {
                val bleGrantedNow = BlePermissionHelper.hasAllRequiredPermissions(context)
                hasBlePermissions = bleGrantedNow
                LayoverFileLogger.log("Transport reported missing BLE permissions (bleGrantedNow=$bleGrantedNow); avoiding automatic popup loop.")
                Log.d(TAG, "Transport reported missing BLE permissions; avoiding automatic popup loop.")
            }

            LaunchedEffect(uiState.isScanning, hasCompletedInitialPermissionFlow, hasBlePermissions) {
                if (!hasCompletedInitialPermissionFlow) {
                    LayoverFileLogger.log(
                        "Skipping discovery effect while initial permission flow active: done=$hasCompletedInitialPermissionFlow"
                    )
                    return@LaunchedEffect
                }
                if (uiState.isScanning) {
                    val bleGrantedNow = BlePermissionHelper.hasAllRequiredPermissions(context)
                    hasBlePermissions = bleGrantedNow
                    if (!bleGrantedNow) {
                        LayoverFileLogger.log("BLE permissions missing after initial flow; keeping discovery idle without looping popups.")
                        Log.d(TAG, "BLE permissions missing; skipping automatic popup loop.")
                    } else {
                        blePermissionDeniedOrDismissed = false
                        LayoverFileLogger.log("Discovery active; starting BLE transport (visible=${uiState.userProfile.isVisibleToNearby && hasSetUsername}).")
                        bleTransport.startDiscovery(uiState.userProfile.isVisibleToNearby && hasSetUsername)
                    }
                } else {
                    LayoverFileLogger.log("isScanning=false; stopping BLE discovery.")
                    bleTransport.stopDiscovery()
                }
            }

            LaunchedEffect(bleStatus.lastError) {
                bleStatus.lastError?.let { err ->
                    LayoverFileLogger.log("bleStatus.lastError observed: $err")
                    bleTransport.diagnosticsTracker.updateLastErrorMessage(err)
                    if (uiState.isScanning && hasBlePermissions) {
                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            LayoverFileLogger.log("Lifecycle ON_STOP: stopping BLE discovery.")
                            Log.d(TAG, "App ON_STOP: Stopping BLE discovery.")
                            bleTransport.stopDiscovery()
                        }
                        Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                            bleStateController.refreshPermissionAndAdapterState()
                            val bleGrantedNow = BlePermissionHelper.hasAllRequiredPermissions(context)
                            hasBlePermissions = bleGrantedNow
                            if (bleGrantedNow) {
                                blePermissionDeniedOrDismissed = false
                            }

                            LayoverFileLogger.log(
                                "Lifecycle $event: bleGranted=$bleGrantedNow, " +
                                    "initialFlowDone=$hasCompletedInitialPermissionFlow, usernameSet=$hasSetUsername, " +
                                    "blePromptLaunched=$blePromptLaunchedInSession, deniedOrDismissed=$blePermissionDeniedOrDismissed"
                            )

                            if (!hasCompletedInitialPermissionFlow && !blePromptLaunchedInSession) {
                                blePromptLaunchedInSession = true
                                isBlePermissionPromptVisible = true
                                val blePerms = BlePermissionHelper.getRequiredPermissions()
                                prefs.edit().putBoolean("ble_permission_prompt_open", true).apply()
                                LayoverFileLogger.log("Starting first-launch BLE permission prompt (${blePerms.joinToString()})")
                                Log.d(TAG, "Lifecycle $event: Launching first-launch BLE permission prompt once.")
                                permissionLauncher.launch(blePerms)
                            } else if (bleGrantedNow) {
                                bleTransport.startDiscovery(uiState.userProfile.isVisibleToNearby && hasSetUsername)
                            }
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    LayoverFileLogger.log("MainActivity DisposableEffect disposed; cleaning up BLE transport.")
                    bleTransport.cleanup()
                }
            }

            LayoverTheme {
                val navController = androidx.navigation.compose.rememberNavController()

                val shouldShowUsernameDialog = !hasSetUsername &&
                    (hasCompletedInitialPermissionFlow || showUsernameAfterPermissions)

                if (shouldShowUsernameDialog) {
                    LayoverFileLogger.log(
                        "Showing OneTimeUsernameDialog (username_set=false, initialFlowDone=$hasCompletedInitialPermissionFlow)"
                    )
                    OneTimeUsernameDialog(
                        onConfirmUsername = { chosenName, chosenGender, chosenAge ->
                            val cleanedName = chosenName.trim()
                            val visiblePref = prefs.getBoolean("is_visible", true)
                            val updatedProfile = uiState.userProfile.copy(
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
                            uiState = uiState.copy(userProfile = updatedProfile)
                            hasSetUsername = true
                            showUsernameAfterPermissions = false
                            if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
                                LayoverFileLogger.log("Profile saved and BLE permissions missing; relying on PeopleAroundScreen recovery card.")
                            } else if (updatedProfile.isVisibleToNearby) {
                                LayoverFileLogger.log("Starting BLE advertising and discovery after profile setup.")
                                bleDiscoveryManager.startAdvertising(updatedProfile)
                                bleTransport.startDiscovery(true)
                            }
                        }
                    )
                }

                incomingRequestPeer?.let { (peerId, displayName) ->
                    AlertDialog(
                        onDismissRequest = {
                            LayoverFileLogger.log("Incoming connection request dismissed for peerId=$peerId")
                            incomingRequestPeer = null
                        },
                        title = { Text("Connection Request") },
                        text = { Text("$displayName would like to connect with you.") },
                        confirmButton = {
                            Button(onClick = {
                                LayoverFileLogger.log("Accepted connection request from peerId=$peerId ($displayName)")
                                incomingRequestPeer = null
                                bleTransport.acceptConnectionRequest(peerId)
                                val updatedPeers = uiState.nearbyPeers.map { p ->
                                    if (p.id == peerId || p.handle == peerId || p.displayName.equals(displayName, ignoreCase = true)) {
                                        p.copy(status = ConnectionStatus.ACCEPTED)
                                    } else {
                                        p
                                    }
                                }
                                uiState = uiState.copy(nearbyPeers = updatedPeers)
                                navController.navigate(LayoverRoute.Chat.createRoute(peerId))
                            }) {
                                Text("Accept")
                            }
                        },
                        dismissButton = {
                            Button(onClick = {
                                LayoverFileLogger.log("Declined connection request from peerId=$peerId ($displayName)")
                                incomingRequestPeer = null
                                bleTransport.declineConnectionRequest(peerId)
                                val updatedPeers = uiState.nearbyPeers.map { p ->
                                    if (p.id == peerId || p.handle == peerId) p.copy(status = ConnectionStatus.DECLINED) else p
                                }
                                uiState = uiState.copy(nearbyPeers = updatedPeers)
                            }) {
                                Text("Decline")
                            }
                        }
                    )
                }

                acceptedRequestPeer?.let { peerId ->
                    val aliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
                    val peer = uiState.nearbyPeers.find { p ->
                        aliases.any { a -> p.id.equals(a, ignoreCase = true) || p.handle.equals(a, ignoreCase = true) }
                    }
                    val name = peer?.displayName ?: "Peer"
                    AlertDialog(
                        onDismissRequest = { acceptedRequestPeer = null },
                        title = { Text("Request Accepted") },
                        text = { Text("$name accepted your connection request!") },
                        confirmButton = {
                            Button(onClick = {
                                LayoverFileLogger.log("Navigating to chat with accepted peerId=$peerId (routeId=${peer?.id ?: peerId})")
                                acceptedRequestPeer = null
                                val targetRouteId = peer?.id ?: peerId
                                navController.navigate(LayoverRoute.Chat.createRoute(targetRouteId))
                            }) {
                                Text("Message")
                            }
                        },
                        dismissButton = {
                            Button(onClick = {
                                acceptedRequestPeer = null
                                val updatedPeers = uiState.nearbyPeers.map { p ->
                                    if (p.id == peerId || p.handle == peerId || (peer != null && p.id == peer.id)) p.copy(status = ConnectionStatus.ACCEPTED) else p
                                }
                                uiState = uiState.copy(nearbyPeers = updatedPeers)
                            }) {
                                Text("Close")
                            }
                        }
                    )
                }

                LayoverNavGraph(
                    uiState = uiState,
                    onUpdateUiState = { newState ->
                        val oldProfile = uiState.userProfile
                        bleTransport.updateProfile(newState.userProfile)
                        bleTransport.updateSafetySettings(
                            blocked = newState.blockedPeerIds,
                            muted = newState.mutedPeerIds
                        )
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

                            if (newState.userProfile.isVisibleToNearby && hasSetUsername) {
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

                        uiState = newState.copy(
                            nearbyPeers = updatedNearbyPeers,
                            transport = bleTransport,
                            diagnostics = bleTransport.diagnosticsTracker.diagnosticsState.value
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                    navController = navController
                )
            }
        }
    }

    override fun onDestroy() {
        LayoverFileLogger.log("MainActivity.onDestroy called.")
        Log.d(TAG, "MainActivity.onDestroy called.")
        super.onDestroy()
        bleTransport.cleanup()
    }
}
