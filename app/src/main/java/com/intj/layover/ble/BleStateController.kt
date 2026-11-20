package com.intj.layover.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BleStatusState(
    val isBluetoothSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val hasPermissions: Boolean = false,
    val isScanning: Boolean = false,
    val isAdvertising: Boolean = false,
    val lastError: String? = null,
)

class BleStateController(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _statusState = MutableStateFlow(BleStatusState())
    val statusState: StateFlow<BleStatusState> = _statusState.asStateFlow()

    init {
        updateState()
    }

    fun updateState() {
        refreshPermissionAndAdapterState()
    }

    fun refreshPermissionAndAdapterState() {
        val isSupported = bluetoothAdapter != null
        val isEnabled = bluetoothAdapter?.isEnabled == true
        val hasPerms = BlePermissionHelper.hasAllRequiredPermissions(context)

        _statusState.update { current ->
            val newError = when {
                !isSupported -> "Bluetooth hardware is not available on this device"
                !isEnabled -> "Bluetooth is turned off"
                !hasPerms -> "Required Bluetooth permissions are missing"
                else -> null
            }

            val finalError = if (hasPerms && isEnabled && current.lastError == "Required Bluetooth permissions are missing") {
                null
            } else {
                newError
            }

            current.copy(
                isBluetoothSupported = isSupported,
                isBluetoothEnabled = isEnabled,
                hasPermissions = hasPerms,
                lastError = finalError
            )
        }
    }

    fun startScanning() {
        refreshPermissionAndAdapterState()
        val (isSupported, isEnabled, hasPerms) = _statusState.value
        if (!isSupported || !isEnabled || !hasPerms) {
            _statusState.update {
                it.copy(
                    isScanning = false,
                    lastError = it.lastError ?: "Cannot start scanning due to missing prerequisites"
                )
            }
            return
        }

        _statusState.update {
            it.copy(
                isScanning = true,
                lastError = null
            )
        }
    }

    fun stopScanning() {
        _statusState.update {
            it.copy(isScanning = false)
        }
    }

    fun startAdvertising() {
        refreshPermissionAndAdapterState()
        val (isSupported, isEnabled, hasPerms) = _statusState.value
        if (!isSupported || !isEnabled || !hasPerms) {
            _statusState.update {
                it.copy(
                    isAdvertising = false,
                    lastError = it.lastError ?: "Cannot start advertising due to missing prerequisites"
                )
            }
            return
        }

        _statusState.update {
            it.copy(
                isAdvertising = true,
                lastError = null
            )
        }
    }

    fun stopAdvertising() {
        _statusState.update {
            it.copy(isAdvertising = false)
        }
    }

    fun clearError() {
        _statusState.update { it.copy(lastError = null) }
    }
}
