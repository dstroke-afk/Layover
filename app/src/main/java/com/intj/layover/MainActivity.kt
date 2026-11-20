package com.intj.layover

import android.os.Bundle
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
import com.intj.layover.model.LayoverUiState
import com.intj.layover.navigation.LayoverNavGraph
import com.intj.layover.ui.theme.LayoverTheme

class MainActivity : ComponentActivity() {

    private lateinit var bleStateController: BleStateController
    private lateinit var bleDiscoveryManager: BleDiscoveryManager
    private lateinit var bleTransport: BleProximityTransport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bleStateController = BleStateController(applicationContext)
        bleDiscoveryManager = BleDiscoveryManager(applicationContext, bleStateController)
        bleTransport = BleProximityTransport(bleDiscoveryManager, bleStateController)

        setContent {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val bleStatus by bleStateController.statusState.collectAsState()
            val liveDiscoveredPeers by bleDiscoveryManager.discoveredPeers.collectAsState()

            var uiState by remember {
                mutableStateOf(
                    LayoverUiState(transport = bleTransport)
                )
            }

            // Sync live discovered BLE peers into uiState when scanned
            LaunchedEffect(liveDiscoveredPeers) {
                if (liveDiscoveredPeers.isNotEmpty()) {
                    val combinedPeers = (liveDiscoveredPeers + uiState.nearbyPeers).distinctBy { it.id }
                    uiState = uiState.copy(nearbyPeers = combinedPeers)
                }
            }

            // Permission Launcher
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permsMap ->
                val allGranted = permsMap.values.all { it }
                bleStateController.updateState()
                if (allGranted && uiState.isScanning) {
                    bleTransport.startDiscovery(uiState.userProfile.isVisibleToNearby)
                } else if (!allGranted) {
                    Toast.makeText(
                        context,
                        "Bluetooth permissions are required for nearby scanning",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // Check permissions when scanning starts
            LaunchedEffect(uiState.isScanning) {
                if (uiState.isScanning) {
                    if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
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
                    if (uiState.isScanning) {
                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Lifecycle awareness: stop scanning & advertising on pause/stop
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            bleTransport.stopDiscovery()
                        }
                        Lifecycle.Event.ON_START -> {
                            bleStateController.updateState()
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    bleDiscoveryManager.cleanup()
                }
            }

            LayoverTheme {
                LayoverNavGraph(
                    uiState = uiState,
                    onUpdateUiState = { newState ->
                        bleTransport.updateProfile(newState.userProfile)
                        uiState = newState.copy(transport = bleTransport)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleDiscoveryManager.cleanup()
    }
}
