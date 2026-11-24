package com.intj.layover

import android.content.Context
import android.os.Bundle
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
import com.intj.layover.ble.LayoverBlePayloadEncoder
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.LayoverUiState
import com.intj.layover.model.NearbyPeer
import com.intj.layover.model.UserProfile
import com.intj.layover.navigation.LayoverNavGraph
import com.intj.layover.navigation.LayoverRoute
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

    private fun getCanonicalPeerKey(idOrHandle: String): String {
        if (idOrHandle.isEmpty()) return ""
        if (idOrHandle.length == 8 && idOrHandle.all { it in "0123456789ABCDEFabcdef" }) {
            return idOrHandle.uppercase()
        }
        if (idOrHandle.startsWith("dev_")) {
            return LayoverBlePayloadEncoder.getInstanceIdHex(idOrHandle)
        }
        return idOrHandle
    }

    private fun isMatchingPeer(p: NearbyPeer, queryId: String, queryName: String = ""): Boolean {
        if (queryId.isEmpty() && queryName.isEmpty()) return false
        val canonicalQuery = getCanonicalPeerKey(queryId)
        val canonicalPeerId = getCanonicalPeerKey(p.id)
        val canonicalHandle = getCanonicalPeerKey(p.handle)

        if (p.id == queryId || p.handle == queryId) return true
        if (canonicalPeerId.isNotEmpty() && (canonicalPeerId == canonicalQuery || canonicalHandle == canonicalQuery)) return true
        if (queryName.isNotEmpty() && p.displayName.isNotEmpty()) {
            if (p.displayName.equals(queryName, ignoreCase = true)) return true
            val pPrefix = p.displayName.take(4)
            val qPrefix = queryName.take(4)
            if (pPrefix.length >= 3 && qPrefix.length >= 3 &&
                (p.displayName.startsWith(qPrefix, ignoreCase = true) || queryName.startsWith(pPrefix, ignoreCase = true))
            ) return true
        }
        return false
    }

    private fun canonicalDeduplicatePeers(peers: List<NearbyPeer>): List<NearbyPeer> {
        val canonicalList = mutableListOf<NearbyPeer>()

        for (p in peers) {
            val existingIndex = canonicalList.indexOfFirst { existing ->
                isMatchingPeer(existing, p.id, p.displayName) ||
                        isMatchingPeer(existing, p.handle, p.displayName) ||
                        (existing.displayName.isNotEmpty() && p.displayName.isNotEmpty() &&
                                existing.displayName.take(4).equals(p.displayName.take(4), ignoreCase = true))
            }

            if (existingIndex == -1) {
                canonicalList.add(p)
            } else {
                val existing = canonicalList[existingIndex]
                val statusPriority = mapOf(
                    ConnectionStatus.ACCEPTED to 4,
                    ConnectionStatus.REQUESTED to 3,
                    ConnectionStatus.DECLINED to 2,
                    ConnectionStatus.NONE to 1
                )
                val bestStatus = if ((statusPriority[p.status] ?: 0) > (statusPriority[existing.status] ?: 0)) {
                    p.status
                } else {
                    existing.status
                }

                val bestId = if (p.id.startsWith("dev_")) p.id else existing.id
                val bestHandle = if (p.handle.startsWith("dev_")) p.handle else existing.handle.ifEmpty { p.handle }
                val bestName = if (p.displayName.length > existing.displayName.length) p.displayName else existing.displayName

                canonicalList[existingIndex] = existing.copy(
                    id = bestId,
                    handle = bestHandle,
                    displayName = bestName,
                    status = bestStatus
                )
            }
        }

        return canonicalList
    }

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
        val savedVisible = prefs.getBoolean("is_visible", true)
        val initialProfile = UserProfile(
            displayName = savedName,
            handle = savedHandle,
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
                        isScanning = true, // Auto-start discovery by default on launch
                        transport = bleTransport,
                        diagnostics = diagnostics
                    )
                )
            }

            var incomingRequestPeer by remember { mutableStateOf<Pair<String, String>?>(null) } // peerId, displayName
            var acceptedRequestPeer by remember { mutableStateOf<String?>(null) } // peerId

            // Sync live diagnostics state into uiState
            LaunchedEffect(diagnostics) {
                uiState = uiState.copy(diagnostics = diagnostics)
            }

            // Sync live discovered BLE peers into uiState, preserving status and deduplicating into canonical list
            LaunchedEffect(liveDiscoveredPeers) {
                val combined = uiState.nearbyPeers + liveDiscoveredPeers
                val merged = canonicalDeduplicatePeers(combined)
                uiState = uiState.copy(nearbyPeers = merged)
                bleTransport.diagnosticsTracker.updateDiscoveredPeersCount(merged.size)
                Log.d(TAG, "UI State updated with ${merged.size} deduplicated BLE discovered peers.")
            }

            // Collect incoming P2P GATT messages and store under raw peerId, handle, and display name entries
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

                    uiState.nearbyPeers.find { isMatchingPeer(it, peerId) }?.let { peer ->
                        addMsgToKey(peer.id)
                        if (peer.handle.isNotEmpty()) addMsgToKey(peer.handle)
                        if (peer.displayName.isNotEmpty()) addMsgToKey(peer.displayName)
                    }

                    uiState = uiState.copy(chatMessages = updatedMap)
                }
            }

            // Collect incoming connection requests and update matching peer in place
            LaunchedEffect(Unit) {
                bleTransport.incomingConnectionRequestFlow.collectLatest { (peerId, displayName) ->
                    val isBlocked = uiState.blockedPeerIds.contains(peerId) ||
                            uiState.blockedPeerIds.contains(displayName) ||
                            uiState.blockedPeerIds.any { blocked ->
                                isMatchingPeer(NearbyPeer(id = peerId, displayName = displayName), blocked)
                            }

                    if (isBlocked) {
                        Log.d(TAG, "Dropped CONNECTION_REQUEST popup for blocked peer: $peerId ($displayName)")
                        return@collectLatest
                    }

                    incomingRequestPeer = Pair(peerId, displayName)

                    val newRequestedPeer = NearbyPeer(
                        id = peerId,
                        displayName = displayName.ifEmpty { "Layover Peer" },
                        handle = peerId,
                        status = ConnectionStatus.REQUESTED
                    )
                    val merged = canonicalDeduplicatePeers(uiState.nearbyPeers + newRequestedPeer)
                    uiState = uiState.copy(nearbyPeers = merged)
                }
            }

            // Collect incoming connection accepts and update matching peer in place
            LaunchedEffect(Unit) {
                bleTransport.incomingConnectionAcceptFlow.collectLatest { peerId ->
                    acceptedRequestPeer = peerId
                    val newAcceptedPeer = NearbyPeer(
                        id = peerId,
                        handle = peerId,
                        status = ConnectionStatus.ACCEPTED
                    )
                    val merged = canonicalDeduplicatePeers(uiState.nearbyPeers + newAcceptedPeer)
                    uiState = uiState.copy(nearbyPeers = merged)
                }
            }

            // Collect incoming connection declines
            LaunchedEffect(Unit) {
                bleTransport.incomingConnectionDeclineFlow.collectLatest { peerId ->
                    val newDeclinedPeer = NearbyPeer(
                        id = peerId,
                        handle = peerId,
                        status = ConnectionStatus.DECLINED
                    )
                    val merged = canonicalDeduplicatePeers(uiState.nearbyPeers + newDeclinedPeer)
                    uiState = uiState.copy(nearbyPeers = merged)
                }
            }

            // Permission Launcher
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permsMap ->
                val allGranted = permsMap.values.all { it }
                bleStateController.refreshPermissionAndAdapterState()
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

            // Bind missing permissions callback to transport
            bleTransport.onMissingPermissions = {
                Log.d(TAG, "Transport requested missing permissions. Launching permission dialog...")
                permissionLauncher.launch(BlePermissionHelper.getRequiredPermissions())
            }

            // Check permissions when scanning starts / uiState changes
            LaunchedEffect(uiState.isScanning) {
                if (uiState.isScanning) {
                    if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
                        Log.d(TAG, "Requesting missing BLE runtime permissions...")
                        permissionLauncher.launch(BlePermissionHelper.getRequiredPermissions())
                    } else {
                        bleTransport.startDiscovery(uiState.userProfile.isVisibleToNearby)
                    }
                } else {
                    bleTransport.stopDiscovery()
                }
            }

            // Display error toasts if BLE state reports issues
            LaunchedEffect(bleStatus.lastError) {
                bleStatus.lastError?.let { err ->
                    bleTransport.diagnosticsTracker.updateLastErrorMessage(err)
                    if (uiState.isScanning) {
                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Lifecycle awareness
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            Log.d(TAG, "App ON_STOP: Stopping BLE discovery.")
                            bleTransport.stopDiscovery()
                        }
                        Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                            bleStateController.refreshPermissionAndAdapterState()
                            if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
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

                // Incoming Connection Request Dialog
                incomingRequestPeer?.let { (peerId, displayName) ->
                    AlertDialog(
                        onDismissRequest = { incomingRequestPeer = null },
                        title = { Text("Connection Request") },
                        text = { Text("$displayName would like to connect with you.") },
                        confirmButton = {
                            Button(onClick = {
                                incomingRequestPeer = null
                                bleTransport.acceptConnectionRequest(peerId)
                                val acceptedPeer = NearbyPeer(
                                    id = peerId,
                                    displayName = displayName,
                                    handle = peerId,
                                    status = ConnectionStatus.ACCEPTED
                                )
                                val merged = canonicalDeduplicatePeers(uiState.nearbyPeers + acceptedPeer)
                                uiState = uiState.copy(nearbyPeers = merged)
                                navController.navigate(LayoverRoute.Chat.createRoute(peerId))
                            }) {
                                Text("Accept")
                            }
                        },
                        dismissButton = {
                            Button(onClick = {
                                incomingRequestPeer = null
                                bleTransport.declineConnectionRequest(peerId)
                                val declinedPeer = NearbyPeer(
                                    id = peerId,
                                    displayName = displayName,
                                    handle = peerId,
                                    status = ConnectionStatus.DECLINED
                                )
                                val merged = canonicalDeduplicatePeers(uiState.nearbyPeers + declinedPeer)
                                uiState = uiState.copy(nearbyPeers = merged)
                            }) {
                                Text("Decline")
                            }
                        }
                    )
                }

                // Incoming Connection Accepted Dialog
                acceptedRequestPeer?.let { peerId ->
                    val peer = uiState.nearbyPeers.find { isMatchingPeer(it, peerId) }
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
                                val acceptedPeer = NearbyPeer(
                                    id = peerId,
                                    handle = peerId,
                                    status = ConnectionStatus.ACCEPTED
                                )
                                val merged = canonicalDeduplicatePeers(uiState.nearbyPeers + acceptedPeer)
                                uiState = uiState.copy(nearbyPeers = merged)
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
                            prefs.edit()
                                .putString("display_name", newState.userProfile.displayName)
                                .putString("handle", newState.userProfile.handle)
                                .putBoolean("is_visible", newState.userProfile.isVisibleToNearby)
                                .apply()

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
