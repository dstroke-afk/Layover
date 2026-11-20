package com.intj.layover

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
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.LayoverUiState
import com.intj.layover.navigation.LayoverNavGraph
import com.intj.layover.navigation.LayoverRoute
import com.intj.layover.ui.theme.LayoverTheme
import kotlinx.coroutines.flow.collectLatest

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

        bleStateController = BleStateController(applicationContext)
        bleDiscoveryManager = BleDiscoveryManager(applicationContext, bleStateController)
        gattSessionManager = GattSessionManager(applicationContext)
        bleTransport = BleProximityTransport(
            context = applicationContext,
            discoveryManager = bleDiscoveryManager,
            bleStateController = bleStateController,
            gattSessionManager = gattSessionManager
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

            // Sync live discovered BLE peers into uiState and preserve local connection status
            LaunchedEffect(liveDiscoveredPeers) {
                val existingMap = uiState.nearbyPeers.associateBy { it.id }
                val merged = liveDiscoveredPeers.map { discovered ->
                    val existing = existingMap[discovered.id]
                    if (existing != null && existing.status != ConnectionStatus.NONE) {
                        discovered.copy(status = existing.status)
                    } else {
                        discovered
                    }
                }
                uiState = uiState.copy(nearbyPeers = merged)
                bleTransport.diagnosticsTracker.updateDiscoveredPeersCount(merged.size)
                Log.d(TAG, "UI State updated with ${merged.size} real BLE discovered peers.")
            }

            // Collect incoming P2P GATT messages and store under both raw peerId and mapped peer entries
            LaunchedEffect(Unit) {
                bleTransport.incomingMessagesFlow.collect { (peerId, message) ->
                    val updatedMap = uiState.chatMessages.toMutableMap()
                    val list1 = updatedMap[peerId]?.toMutableList() ?: mutableListOf()
                    if (!list1.any { it.id == message.id }) {
                        list1.add(message)
                        updatedMap[peerId] = list1
                    }

                    uiState.nearbyPeers.find { it.id == peerId || it.handle == peerId }?.let { peer ->
                        val list2 = updatedMap[peer.id]?.toMutableList() ?: mutableListOf()
                        if (!list2.any { it.id == message.id }) {
                            list2.add(message)
                            updatedMap[peer.id] = list2
                        }
                    }

                    uiState = uiState.copy(chatMessages = updatedMap)
                }
            }

            // Collect incoming connection requests
            LaunchedEffect(Unit) {
                bleTransport.incomingConnectionRequestFlow.collectLatest { (peerId, displayName) ->
                    incomingRequestPeer = Pair(peerId, displayName)
                    val updatedPeers = uiState.nearbyPeers.map { p ->
                        if (p.id == peerId || p.handle == peerId) p.copy(status = ConnectionStatus.REQUESTED) else p
                    }
                    uiState = uiState.copy(nearbyPeers = updatedPeers)
                }
            }

            // Collect incoming connection accepts
            LaunchedEffect(Unit) {
                bleTransport.incomingConnectionAcceptFlow.collectLatest { peerId ->
                    acceptedRequestPeer = peerId
                    val updatedPeers = uiState.nearbyPeers.map { p ->
                        if (p.id == peerId || p.handle == peerId) p.copy(status = ConnectionStatus.ACCEPTED) else p
                    }
                    uiState = uiState.copy(nearbyPeers = updatedPeers)
                }
            }

            // Collect incoming connection declines
            LaunchedEffect(Unit) {
                bleTransport.incomingConnectionDeclineFlow.collectLatest { peerId ->
                    val updatedPeers = uiState.nearbyPeers.map { p ->
                        if (p.id == peerId || p.handle == peerId) p.copy(status = ConnectionStatus.DECLINED) else p
                    }
                    uiState = uiState.copy(nearbyPeers = updatedPeers)
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
                                val updatedPeers = uiState.nearbyPeers.map { p ->
                                    if (p.id == peerId || p.handle == peerId) p.copy(status = ConnectionStatus.ACCEPTED) else p
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

                // Incoming Connection Accepted Dialog
                acceptedRequestPeer?.let { peerId ->
                    val peer = uiState.nearbyPeers.find { it.id == peerId || it.handle == peerId }
                    val name = peer?.displayName ?: "Peer"
                    AlertDialog(
                        onDismissRequest = { acceptedRequestPeer = null },
                        title = { Text("Request Accepted") },
                        text = { Text("$name accepted your connection request!") },
                        confirmButton = {
                            Button(onClick = {
                                acceptedRequestPeer = null
                                navController.navigate(LayoverRoute.Chat.createRoute(peerId))
                            }) {
                                Text("Message")
                            }
                        },
                        dismissButton = {
                            Button(onClick = {
                                acceptedRequestPeer = null
                                val updatedPeers = uiState.nearbyPeers.map { p ->
                                    if (p.id == peerId || p.handle == peerId) p.copy(status = ConnectionStatus.ACCEPTED) else p
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
                        if (newState.userProfile.displayName != oldProfile.displayName ||
                            newState.userProfile.handle != oldProfile.handle ||
                            newState.userProfile.isVisibleToNearby != oldProfile.isVisibleToNearby) {
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
