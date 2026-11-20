package com.intj.layover.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import com.intj.layover.diagnostics.LayoverFileLogger
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
    val device: BluetoothDevice,
    val lastSeenTimestamp: Long
)

class BleDiscoveryManager(
    private val context: Context,
    private val bleStateController: BleStateController
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

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
                delay(10000)
                val now = System.currentTimeMillis()
                var updated = false

                peerMap.entries.removeIf { entry ->
                    val isStale = (now - entry.value.lastSeenTimestamp) > LayoverBleConstants.PEER_STALE_TIMEOUT_MS
                    if (isStale) {
                        LayoverFileLogger.log("Pruning stale BLE peer: ${entry.key}")
                        updated = true
                    }
                    isStale
                }

                if (updated) {
                    _discoveredPeers.value = peerMap.values.map { it.peer }
                    LayoverFileLogger.log("Discovered peers list updated after pruning. Total count: ${_discoveredPeers.value.size}")
                }
            }
        }
    }

    fun getDiscoveredDevice(peerId: String): BluetoothDevice? {
        // peerId could be MAC address or instanceId key
        val record = peerMap[peerId] ?: peerMap.values.find { it.peer.id == peerId }
        return record?.device
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(userProfile: UserProfile) {
        if (!userProfile.isVisibleToNearby) {
            LayoverFileLogger.log("User opted out of visibility. Stopping BLE advertising.")
            stopAdvertising()
            return
        }

        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            LayoverFileLogger.logError("Cannot start BLE advertising: permissions missing.")
            bleStateController.updateState()
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            LayoverFileLogger.logError("Cannot start BLE advertising: Bluetooth hardware null or disabled.")
            bleStateController.updateState()
            return
        }

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            LayoverFileLogger.logError("Cannot start BLE advertising: BluetoothLeAdvertiser unavailable.")
            bleStateController.updateState()
            return
        }

        stopAdvertising()
        currentAdvertiser = advertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val payload = LayoverBlePayloadEncoder.encodePayload(
            displayName = userProfile.displayName,
            isVisible = userProfile.isVisibleToNearby
        )

        // Primary Advertising Data: 18 bytes (128-bit UUID)
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(LayoverBleConstants.LAYOVER_SERVICE_UUID)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        // Scan Response Data: 25 bytes (18-byte UUID header + 7-byte compact payload)
        val scanResponseData = AdvertiseData.Builder()
            .addServiceData(LayoverBleConstants.LAYOVER_SERVICE_UUID, payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                LayoverFileLogger.log("SUCCESS: BLE Advertising started (LOW_LATENCY, HIGH_POWER, CONNECTABLE). InstanceId=${LayoverBleConstants.LOCAL_INSTANCE_ID_HEX}")
                bleStateController.startAdvertising()
            }

            override fun onStartFailure(errorCode: Int) {
                val errReason = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_DATA_TOO_LARGE (1)"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS (2)"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "ADVERTISE_FAILED_ALREADY_STARTED (3)"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR (4)"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED (5)"
                    else -> "ErrorCode $errorCode"
                }
                LayoverFileLogger.logError("BLE Advertising failed with $errReason. Primary bytes: 18, ScanResponse bytes: 25.")
                bleStateController.stopAdvertising()
            }
        }

        advertiseCallback = callback

        try {
            LayoverFileLogger.log("Requesting BluetoothLeAdvertiser.startAdvertising(...) [Primary: 18B, ScanResponse: 25B]")
            advertiser.startAdvertising(settings, advertiseData, scanResponseData, callback)
        } catch (e: Exception) {
            LayoverFileLogger.logError("Exception starting BLE advertising", e)
            bleStateController.stopAdvertising()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val callback = advertiseCallback
        val advertiser = currentAdvertiser
        if (advertiser != null && callback != null) {
            try {
                LayoverFileLogger.log("Stopping BLE advertising...")
                advertiser.stopAdvertising(callback)
            } catch (e: Exception) {
                LayoverFileLogger.logError("Exception stopping BLE advertising", e)
            }
        }
        advertiseCallback = null
        currentAdvertiser = null
        bleStateController.stopAdvertising()
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            LayoverFileLogger.logError("Cannot start BLE scanning: missing runtime permissions.")
            bleStateController.updateState()
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            LayoverFileLogger.logError("Cannot start BLE scanning: Bluetooth adapter null or off.")
            bleStateController.updateState()
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            LayoverFileLogger.logError("Cannot start BLE scanning: BluetoothLeScanner null.")
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
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                processScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { processScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                LayoverFileLogger.logError("BLE Scanning failed with errorCode: $errorCode")
                bleStateController.stopScanning()
            }
        }

        scanCallback = callback

        try {
            LayoverFileLogger.log("Starting BluetoothLeScanner.startScan(...) with Layover UUID filter & LOW_LATENCY.")
            scanner.startScan(listOf(filter), settings, callback)
            bleStateController.startScanning()
        } catch (e: Exception) {
            LayoverFileLogger.logError("Exception starting BLE scan", e)
            bleStateController.stopScanning()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        val callback = scanCallback
        val scanner = currentScanner
        if (scanner != null && callback != null) {
            try {
                LayoverFileLogger.log("Stopping BLE scanner...")
                scanner.stopScan(callback)
            } catch (e: Exception) {
                LayoverFileLogger.logError("Exception stopping BLE scan", e)
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
        val macAddress = result.device?.address ?: return

        val serviceData = scanRecord.getServiceData(LayoverBleConstants.LAYOVER_SERVICE_UUID)
        val payload = LayoverBlePayloadEncoder.decodePayload(serviceData)

        if (payload != null && !payload.isVisible) {
            LayoverFileLogger.log("Peer $macAddress sent payload with isVisible=false. Ignoring.")
            return
        }

        val deviceName = try {
            result.device?.name ?: scanRecord.deviceName
        } catch (e: Exception) {
            null
        }

        val fallbackName = payload?.displayName?.ifEmpty { null }
            ?: deviceName?.ifEmpty { null }
            ?: "Layover Peer (${macAddress.takeLast(4)})"

        val mapKey = payload?.instanceIdHex?.ifBlank { macAddress } ?: macAddress

        val existingRecord = peerMap[mapKey]
        val currentStatus = existingRecord?.peer?.status ?: ConnectionStatus.NONE

        val updatedPeer = NearbyPeer(
            id = macAddress,
            displayName = fallbackName,
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

        peerMap[mapKey] = DiscoveredPeerRecord(
            peer = updatedPeer,
            device = result.device,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        _discoveredPeers.value = peerMap.values.map { it.peer }
        LayoverFileLogger.logThrottled("scan_$mapKey", "BLE scan result emitted: Key=$mapKey, MAC=$macAddress, Name=$fallbackName, DiscoveredTotal=${_discoveredPeers.value.size}")
    }

    fun updatePeerStatus(peerId: String, newStatus: ConnectionStatus) {
        val record = peerMap[peerId] ?: peerMap.values.find { it.peer.id == peerId }
        if (record != null) {
            val updated = record.peer.copy(status = newStatus)
            val key = peerMap.entries.find { it.value.peer.id == record.peer.id }?.key ?: peerId
            peerMap[key] = record.copy(peer = updated)
            _discoveredPeers.value = peerMap.values.map { it.peer }
        }
    }

    fun cleanup() {
        stopAdvertising()
        stopScanning()
        pruneJob?.cancel()
    }
}
