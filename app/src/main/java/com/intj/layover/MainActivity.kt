package com.intj.layover

import android.content.Context
import android.os.Bundle
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
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.GenderOption
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.NearbyPeer
import com.intj.layover.model.UserProfile
import com.intj.layover.navigation.LayoverNavGraph
import com.intj.layover.navigation.LayoverRoute
import com.intj.layover.ui.screens.OneTimeUsernameDialog
import com.intj.layover.ui.theme.LayoverTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = applicationContext.getSharedPreferences("layover_user_profile", Context.MODE_PRIVATE)
        val savedDeviceId = prefs.getString("device_id", null) ?: run {
            val newId = "dev_" + UUID.randomUUID().toString().take(8)
            prefs.edit().putString("device_id", newId).apply()
            newId
        }
        val savedName = prefs.getString("display_name", "Balaji Eswar") ?: "Balaji Eswar"
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
        val savedVisible = prefs.getBoolean("is_visible", true)
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

        bleStateController = BleStateController(applicationContext)
        bleDiscoveryManager = BleDiscoveryManager(applicationContext, bleStateController)
        gattSessionManager = GattSessionManager(applicationContext)
        bleTransport = BleProximityTransport(
            context = applicationContext,
            discoveryManager = bleDiscoveryManager,
            bleStateController = bleStateController,
            gattSessionManager = gattSessionManager,
            currentUserProfile = initialProfile
        )

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
            var hasPermissions by remember {
                mutableStateOf(BlePermissionHelper.hasAllRequiredPermissions(context))
            }
            var hasSetUsername by remember {
                mutableStateOf(prefs.getBoolean("username_set", false))
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
                        if (existing != null && existing.status != ConnectionStatus.NONE) {
                            discovered.copy(
                                id = existing.id,
                                handle = existing.handle.ifEmpty { discovered.handle },
                                displayName = discovered.displayName.ifBlank { existing.displayName },
                                status = existing.status,
                                avatar = if (discovered.avatar.avatarBase64OrBytes != null) discovered.avatar else existing.avatar
                            )
                        } else {
                            discovered
                        }
                    }
                uiState = uiState.copy(nearbyPeers = merged)
                bleTransport.diagnosticsTracker.updateDiscoveredPeersCount(merged.size)
                Log.d(TAG, "UI State updated with ${merged.size} deduplicated BLE discovered peers.")
            }

            LaunchedEffect(Unit) {
                bleTransport.incomingMessagesFlow.collect { (peerId, message) ->
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
                        Log.d(TAG, "Dropped CONNECTION_REQUEST popup for blocked peer: $peerId ($displayName)")
                        return@collectLatest
                    }

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
                val allGranted = permsMap.values.all { it }
                bleStateController.refreshPermissionAndAdapterState()
                hasPermissions = allGranted || BlePermissionHelper.hasAllRequiredPermissions(context)
                if (allGranted) {
                    Log.d(TAG, "Permissions granted via system dialog.")
                    bleTransport.startDiscovery(uiState.userProfile.isVisibleToNearby)
                } else {
                    Log.e(TAG, "Permissions rejected by user.")
                    Toast.makeText(
                        context,
                        "Bluetooth permissions are required for nearby scanning",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            bleTransport.onMissingPermissions = {
                Log.d(TAG, "Transport requested missing permissions. Launching permission dialog...")
                permissionLauncher.launch(BlePermissionHelper.getRequiredPermissions())
            }

            LaunchedEffect(uiState.isScanning) {
                if (uiState.isScanning) {
                    if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
                        hasPermissions = false
                        Log.d(TAG, "Requesting missing BLE runtime permissions...")
                        permissionLauncher.launch(BlePermissionHelper.getRequiredPermissions())
                    } else {
                        hasPermissions = true
                        bleTransport.startDiscovery(uiState.userProfile.isVisibleToNearby)
                    }
                } else {
                    bleTransport.stopDiscovery()
                }
            }

            LaunchedEffect(bleStatus.lastError) {
                bleStatus.lastError?.let { err ->
                    bleTransport.diagnosticsTracker.updateLastErrorMessage(err)
                    if (uiState.isScanning) {
                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            Log.d(TAG, "App ON_STOP: Stopping BLE discovery.")
                            bleTransport.stopDiscovery()
                        }
                        Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                            bleStateController.refreshPermissionAndAdapterState()
                            val granted = BlePermissionHelper.hasAllRequiredPermissions(context)
                            hasPermissions = granted
                            if (!granted) {
                                Log.d(TAG, "Lifecycle $event: Missing permissions detected. Launching prompt.")
                                permissionLauncher.launch(BlePermissionHelper.getRequiredPermissions())
                            } else {
                                bleTransport.startDiscovery(uiState.userProfile.isVisibleToNearby)
                            }
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    bleTransport.cleanup()
                }
            }

            LayoverTheme {
                val navController = androidx.navigation.compose.rememberNavController()

                if (hasPermissions && !hasSetUsername) {
                    OneTimeUsernameDialog(
                        onConfirmUsername = { chosenName, chosenGender ->
                            val cleanedName = chosenName.trim()
                            val updatedProfile = uiState.userProfile.copy(
                                displayName = cleanedName,
                                gender = chosenGender
                            )
                            prefs.edit()
                                .putString("display_name", cleanedName)
                                .putString("gender", chosenGender.name)
                                .putBoolean("username_set", true)
                                .apply()
                            bleTransport.updateProfile(updatedProfile)
                            uiState = uiState.copy(userProfile = updatedProfile)
                            hasSetUsername = true
                            if (updatedProfile.isVisibleToNearby) {
                                bleDiscoveryManager.startAdvertising(updatedProfile)
                                bleTransport.startDiscovery(true)
                            }
                        }
                    )
                }

                incomingRequestPeer?.let { (peerId, displayName) ->
                    AlertDialog(
                        onDismissRequest = { incomingRequestPeer = null },
                        title = { Text("Connection Request") },
                        text = { Text("$displayName would like to connect with you.") },
                        confirmButton = {
                            Button(onClick = {
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
                            editor.apply()

                            if (newState.userProfile.isVisibleToNearby) {
                                bleDiscoveryManager.startAdvertising(newState.userProfile)
                            } else {
                                bleDiscoveryManager.stopAdvertising()
                            }
                        }
                        uiState = newState.copy(
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
        super.onDestroy()
        bleTransport.cleanup()
    }
}