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
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.LayoverUiState
import com.intj.layover.navigation.LayoverNavGraph
import com.intj.layover.p2p.RfcommSessionManager
import com.intj.layover.ui.theme.LayoverTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "LayoverBLE"
    }

    private lateinit var bleStateController: BleStateController
    private lateinit var bleDiscoveryManager: BleDiscoveryManager
    private lateinit var rfcommSessionManager: RfcommSessionManager
    private lateinit var bleTransport: BleProximityTransport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bleStateController = BleStateController(applicationContext)
        bleDiscoveryManager = BleDiscoveryManager(applicationContext, bleStateController)
        rfcommSessionManager = RfcommSessionManager(applicationContext)
        bleTransport = BleProximityTransport(
            context = applicationContext,
            discoveryManager = bleDiscoveryManager,
            bleStateController = bleStateController,
            rfcommSessionManager = rfcommSessionManager
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

            // Sync live diagnostics state into uiState
            LaunchedEffect(diagnostics) {
                uiState = uiState.copy(diagnostics = diagnostics)
            }

            // Sync live discovered BLE peers into uiState and preserve local connection status (REQUESTED / ACCEPTED)
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

            // Collect incoming P2P RFCOMM messages and append to chat state
            LaunchedEffect(Unit) {
                bleTransport.incomingMessagesFlow.collect { (peerId, message) ->
                    val updatedMap = uiState.chatMessages.toMutableMap()
                    val currentList = updatedMap[peerId]?.toMutableList() ?: mutableListOf()
                    currentList.add(message)
                    updatedMap[peerId] = currentList
                    uiState = uiState.copy(chatMessages = updatedMap)
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

            // Lifecycle awareness: check permissions on ON_START / ON_RESUME, stop scanning on ON_STOP
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
                LayoverNavGraph(
                    uiState = uiState,
                    onUpdateUiState = { newState ->
                        bleTransport.updateProfile(newState.userProfile)
                        bleTransport.updateSafetySettings(
                            blocked = newState.blockedPeerIds,
                            muted = newState.mutedPeerIds
                        )
                        uiState = newState.copy(
                            transport = bleTransport,
                            diagnostics = bleTransport.diagnosticsTracker.diagnosticsState.value
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleTransport.cleanup()
    }
}
