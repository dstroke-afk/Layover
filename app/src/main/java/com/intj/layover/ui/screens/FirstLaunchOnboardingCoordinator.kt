package com.intj.layover.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.intj.layover.ble.BlePermissionHelper
import com.intj.layover.model.GenderOption

private enum class LaunchPermissionStage {
    IDLE,
    REQUESTING_BLE,
    REQUESTING_WIFI,
    COMPLETED
}

@Composable
fun FirstLaunchOnboardingCoordinator(
    hasCompletedInitialPermissionFlow: Boolean,
    isProfileConfigured: Boolean,
    onInitialPermissionFlowCompleted: (bleGranted: Boolean, wifiGranted: Boolean) -> Unit,
    onConfirmUsername: (username: String, gender: GenderOption) -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf(LaunchPermissionStage.IDLE) }
    var bleGranted by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }

    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { wifiGrantResults ->
        val allWifiGranted = wifiGrantResults.values.all { it }
        stage = LaunchPermissionStage.COMPLETED
        onInitialPermissionFlowCompleted(bleGranted, allWifiGranted)
        if (!isProfileConfigured) {
            showProfileDialog = true
        }
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { bleGrantResults ->
        bleGranted = bleGrantResults.values.all { it }
        stage = LaunchPermissionStage.REQUESTING_WIFI
        wifiPermissionLauncher.launch(BlePermissionHelper.getWifiHotspotPermissions())
    }

    LaunchedEffect(hasCompletedInitialPermissionFlow) {
        if (!hasCompletedInitialPermissionFlow && stage == LaunchPermissionStage.IDLE) {
            bleGranted = BlePermissionHelper.hasAllRequiredPermissions(context)
            stage = LaunchPermissionStage.REQUESTING_BLE
            blePermissionLauncher.launch(BlePermissionHelper.getRequiredPermissions())
        }
    }

    content()

    if (showProfileDialog && !isProfileConfigured) {
        OneTimeUsernameDialog(
            onConfirmUsername = { chosenName, chosenGender, chosenAge ->
                val cleanedName = chosenName.trim()
                showProfileDialog = false
            }
        )
    }
}
