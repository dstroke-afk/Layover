package com.intj.layover.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.intj.layover.model.ConnectionStatus
import com.intj.layover.model.NearbyPeer
import com.intj.layover.model.PeerAvatar
import com.intj.layover.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredPeerRecord(
    val peer: NearbyPeer,
    val lastSeenTimestamp: Long
)

class BleDiscoveryManager(
    private val context: Context,
    private val bleStateController: BleStateController
) {
    companion object {
        private const val TAG = "LayoverBLE"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _discoveredPeers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<NearbyPeer>> = _discoveredPeers.asStateFlow()

    private val peerMap = ConcurrentHashMap<String, DiscoveredPeerRecord>()
    private var pruneJob: Job? = null

    private var currentAdvertiser: BluetoothLeAdvertiser? = null
    private var currentScanner: BluetoothLeScanner? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    init {
        startPruningTask()
    }

    private fun startPruningTask() {
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                delay(5000)
                val now = System.currentTimeMillis()
                var updated = false

                peerMap.entries.removeIf { entry ->
                    val isStale = (now - entry.value.lastSeenTimestamp) > LayoverBleConstants.PEER_STALE_TIMEOUT_MS
                    if (isStale) {
                        Log.d(TAG, "Pruning stale BLE peer: ${entry.key}")
                        updated = true
                    }
                    isStale
                }

                if (updated) {
                    _discoveredPeers.value = peerMap.values.map { it.peer }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(userProfile: UserProfile) {
        if (!userProfile.isVisibleToNearby) {
            Log.d(TAG, "User opted out of visibility. Stopping BLE advertising.")
            stopAdvertising()
            return
        }

        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            Log.e(TAG, "Cannot start BLE advertising: permissions missing.")
            bleStateController.updateState()
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Cannot start BLE advertising: Bluetooth hardware null or disabled.")
            bleStateController.updateState()
            return
        }

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "Cannot start BLE advertising: BluetoothLeAdvertiser unavailable.")
            bleStateController.updateState()
            return
        }

        stopAdvertising()
        currentAdvertiser = advertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val payload = LayoverBlePayloadEncoder.encodePayload(
            peerId = userProfile.handle.ifEmpty { "user" },
            displayName = userProfile.displayName,
            isVisible = userProfile.isVisibleToNearby
        )

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(LayoverBleConstants.LAYOVER_SERVICE_UUID)
            .addServiceData(LayoverBleConstants.LAYOVER_SERVICE_UUID, payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "BLE Advertising started successfully.")
                bleStateController.startAdvertising()
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "BLE Advertising failed with errorCode: $errorCode")
                bleStateController.stopAdvertising()
            }
        }

        advertiseCallback = callback

        try {
            Log.d(TAG, "Requesting BluetoothLeAdvertiser.startAdvertising(...)")
            advertiser.startAdvertising(settings, advertiseData, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE advertising", e)
            bleStateController.stopAdvertising()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val callback = advertiseCallback
        val advertiser = currentAdvertiser
        if (advertiser != null && callback != null) {
            try {
                Log.d(TAG, "Stopping BLE advertising...")
                advertiser.stopAdvertising(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping BLE advertising", e)
            }
        }
        advertiseCallback = null
        currentAdvertiser = null
        bleStateController.stopAdvertising()
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            Log.e(TAG, "Cannot start BLE scanning: missing runtime permissions.")
            bleStateController.updateState()
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Cannot start BLE scanning: Bluetooth adapter null or off.")
            bleStateController.updateState()
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "Cannot start BLE scanning: BluetoothLeScanner null.")
            bleStateController.updateState()
            return
        }

        stopScanning()
        currentScanner = scanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(LayoverBleConstants.LAYOVER_SERVICE_UUID)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                processScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { processScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE Scanning failed with errorCode: $errorCode")
                bleStateController.stopScanning()
            }
        }

        scanCallback = callback

        try {
            Log.d(TAG, "Starting BluetoothLeScanner.startScan(...) with Layover UUID filter.")
            scanner.startScan(listOf(filter), settings, callback)
            bleStateController.startScanning()
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scan", e)
            bleStateController.stopScanning()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        val callback = scanCallback
        val scanner = currentScanner
        if (scanner != null && callback != null) {
            try {
                Log.d(TAG, "Stopping BLE scanner...")
                scanner.stopScan(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping BLE scan", e)
            }
        }
        scanCallback = null
        currentScanner = null
        bleStateController.stopScanning()
    }

    @SuppressLint("MissingPermission")
    private fun processScanResult(result: ScanResult?) {
        if (result == null) return
        val scanRecord = result.scanRecord ?: return
        val serviceData = scanRecord.getServiceData(LayoverBleConstants.LAYOVER_SERVICE_UUID)
        val payload = LayoverBlePayloadEncoder.decodePayload(serviceData)

        val macAddress = result.device?.address ?: payload?.peerId ?: return

        // Device name fallback logic
        val deviceName = try {
            result.device?.name ?: scanRecord.deviceName
        } catch (e: Exception) {
            null
        }

        val fallbackName = payload?.displayName
            ?: deviceName
            ?: "Nearby Node ${macAddress.takeLast(4)}"

        if (payload != null && !payload.isVisible) {
            Log.d(TAG, "Peer $macAddress sent payload with isVisible=false. Skipping.")
            return
        }

        Log.d(TAG, "Discovered BLE peer: address=$macAddress, name=$fallbackName")

        val existingRecord = peerMap[macAddress]
        val currentStatus = existingRecord?.peer?.status ?: ConnectionStatus.NONE

        val updatedPeer = NearbyPeer(
            id = macAddress,
            displayName = fallbackName,
            handle = payload?.peerId ?: macAddress.takeLast(6),
            status = currentStatus,
            avatar = PeerAvatar(
                peerId = macAddress,
                initials = fallbackName.split(" ")
                    .mapNotNull { it.firstOrNull()?.toString() }
                    .take(2)
                    .joinToString("")
                    .ifEmpty { "?" }
            )
        )

        peerMap[macAddress] = DiscoveredPeerRecord(
            peer = updatedPeer,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        _discoveredPeers.value = peerMap.values.map { it.peer }
    }

    fun updatePeerStatus(peerId: String, newStatus: ConnectionStatus) {
        val record = peerMap[peerId]
        if (record != null) {
            val updated = record.peer.copy(status = newStatus)
            peerMap[peerId] = record.copy(peer = updated)
            _discoveredPeers.value = peerMap.values.map { it.peer }
        }
    }

    fun cleanup() {
        stopAdvertising()
        stopScanning()
        pruneJob?.cancel()
    }
}
