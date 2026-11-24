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
import android.graphics.BitmapFactory
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
    private val bleStateController: BleStateController,
    private val onRequestAvatarFetch: ((BluetoothDevice, String, (ByteArray) -> Unit) -> Unit)? = null
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _discoveredPeers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<NearbyPeer>> = _discoveredPeers.asStateFlow()

    private val peerMap = ConcurrentHashMap<String, DiscoveredPeerRecord>()
    private val fetchedAvatarKeys = ConcurrentHashMap.newKeySet<String>()
    private val inFlightAvatarKeys = ConcurrentHashMap.newKeySet<String>()
    private val cachedAvatarsByKey = ConcurrentHashMap<String, ByteArray>()
    private var pruneJob: Job? = null

    private var protectedIdentifiers: Set<String> = emptySet()
    private var activeGattIdentifiers: Set<String> = emptySet()

    private var currentAdvertiser: BluetoothLeAdvertiser? = null
    private var currentScanner: BluetoothLeScanner? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    init {
        startPruningTask()
    }

    fun updateProtectedIdentifiers(blocked: Set<String>, muted: Set<String>, activeSessionIds: Set<String> = emptySet()) {
        protectedIdentifiers = (blocked + muted).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        activeGattIdentifiers = activeSessionIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun isPeerProtected(peer: NearbyPeer): Boolean {
        val candidates = listOfNotNull(
            peer.id.takeIf { it.isNotBlank() },
            peer.handle.takeIf { it.isNotBlank() },
            peer.displayName.takeIf { it.isNotBlank() }
        )
        val hasActiveStatus = peer.status != ConnectionStatus.NONE
        val isKnownDevId = peer.id.startsWith("dev_", ignoreCase = true) ||
                peer.handle.startsWith("dev_", ignoreCase = true) ||
                candidates.any { id -> PeerAliasRegistry.resolveAliases(id).any { it.startsWith("dev_", ignoreCase = true) } }
        val isProtectedBySafetyOrSession = candidates.any { id ->
            val expanded = PeerAliasRegistry.resolveAliases(id) + id
            expanded.any { alias ->
                protectedIdentifiers.any { it.equals(alias, ignoreCase = true) } ||
                        activeGattIdentifiers.any { it.equals(alias, ignoreCase = true) }
            }
        }
        return hasActiveStatus || isKnownDevId || isProtectedBySafetyOrSession
    }

    private fun startPruningTask() {
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                delay(10000)
                val now = System.currentTimeMillis()
                var updated = false

                peerMap.entries.removeIf { entry ->
                    val peer = entry.value.peer
                    val isStale = (now - entry.value.lastSeenTimestamp) > LayoverBleConstants.PEER_STALE_TIMEOUT_MS
                    val shouldPrune = isStale && !isPeerProtected(peer)
                    if (shouldPrune) {
                        LayoverFileLogger.log("Pruning stale BLE peer: ${entry.key}")
                        updated = true
                    }
                    shouldPrune
                }

                if (updated) {
                    _discoveredPeers.value = peerMap.values.map { it.peer }
                    LayoverFileLogger.log("Discovered peers list updated after pruning. Total count: ${_discoveredPeers.value.size}")
                }
            }
        }
    }

    fun getDiscoveredDevice(peerId: String): BluetoothDevice? {
        val aliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
        val record = aliases.firstNotNullOfOrNull { key ->
            peerMap[key] ?: peerMap.values.find {
                it.peer.id.equals(key, ignoreCase = true) ||
                        it.peer.handle.equals(key, ignoreCase = true) ||
                        it.device.address.equals(key, ignoreCase = true)
            }
        }
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
            isVisible = userProfile.isVisibleToNearby,
            handleOrDeviceId = userProfile.handle
        )

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(LayoverBleConstants.LAYOVER_SERVICE_UUID)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .addServiceData(LayoverBleConstants.LAYOVER_SERVICE_UUID, payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                val instId = LayoverBlePayloadEncoder.getInstanceIdHex(userProfile.handle)
                LayoverFileLogger.log("SUCCESS: BLE Advertising started (LOW_LATENCY, HIGH_POWER, CONNECTABLE). InstanceId=$instId")
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

        val existingRecord = peerMap[mapKey] ?: peerMap.values.find {
            it.peer.id == macAddress || it.peer.handle == mapKey || it.device.address.equals(macAddress, ignoreCase = true)
        }
        val currentStatus = existingRecord?.peer?.status ?: ConnectionStatus.NONE
        val preservedAvatarBytes = existingRecord?.peer?.avatar?.avatarBase64OrBytes
            ?: cachedAvatarsByKey[mapKey]
            ?: PeerAliasRegistry.resolveAliases(mapKey).firstNotNullOfOrNull { cachedAvatarsByKey[it] }

        val updatedPeer = NearbyPeer(
            id = mapKey,
            displayName = fallbackName,
            handle = mapKey,
            status = currentStatus,
            avatar = PeerAvatar(
                peerId = mapKey,
                initials = fallbackName.split(" ")
                    .mapNotNull { it.firstOrNull()?.toString() }
                    .take(2)
                    .joinToString("")
                    .ifEmpty { "?" },
                avatarBase64OrBytes = preservedAvatarBytes
            )
        )

        PeerAliasRegistry.linkAliases(
            listOfNotNull(
                mapKey,
                payload?.instanceIdHex,
                macAddress,
                existingRecord?.peer?.id,
                existingRecord?.peer?.handle,
                fallbackName
            )
        )

        peerMap[mapKey] = DiscoveredPeerRecord(
            peer = updatedPeer,
            device = result.device,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        _discoveredPeers.value = peerMap.values.map { it.peer }
        LayoverFileLogger.logThrottled("scan_$mapKey", "BLE scan result emitted: Key=$mapKey, MAC=$macAddress, Name=$fallbackName, DiscoveredTotal=${_discoveredPeers.value.size}")

        maybeFetchAvatar(result.device, mapKey)
    }

    private fun isValidDecodedAvatar(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    private fun maybeFetchAvatar(device: BluetoothDevice, mapKey: String) {
        val fetcher = onRequestAvatarFetch ?: run {
            LayoverFileLogger.logThrottled("no_fetcher_$mapKey", "Avatar fetcher callback is null for Key=$mapKey")
            return
        }
        val aliases = PeerAliasRegistry.resolveAliases(mapKey) + mapKey
        val alreadyFetched = aliases.any { fetchedAvatarKeys.contains(it) } || cachedAvatarsByKey.containsKey(mapKey)
        if (alreadyFetched || !inFlightAvatarKeys.add(mapKey)) return

        LayoverFileLogger.log("Scheduling avatar fetch for Key=$mapKey, MAC=${device.address}")
        fetcher(device, mapKey) { avatarBytes ->
            inFlightAvatarKeys.remove(mapKey)
            if (isValidDecodedAvatar(avatarBytes)) {
                fetchedAvatarKeys.add(mapKey)
                PeerAliasRegistry.resolveAliases(mapKey).forEach { fetchedAvatarKeys.add(it) }
                cachedAvatarsByKey[mapKey] = avatarBytes
                PeerAliasRegistry.resolveAliases(mapKey).forEach { cachedAvatarsByKey[it] = avatarBytes }

                val currentRecord = peerMap[mapKey] ?: peerMap.values.find {
                    it.peer.id.equals(mapKey, ignoreCase = true) ||
                            it.peer.handle.equals(mapKey, ignoreCase = true)
                }
                if (currentRecord != null) {
                    val targetKey = peerMap.entries.find { it.value.peer.id == currentRecord.peer.id }?.key ?: mapKey
                    val updatedPeer = currentRecord.peer.copy(
                        avatar = currentRecord.peer.avatar.copy(avatarBase64OrBytes = avatarBytes)
                    )
                    peerMap[targetKey] = currentRecord.copy(peer = updatedPeer)
                    _discoveredPeers.value = peerMap.values.map { it.peer }
                    LayoverFileLogger.log("Updated peer avatar in discovery list for Key=$mapKey (${avatarBytes.size} bytes)")
                }
            } else {
                LayoverFileLogger.log("Avatar fetch returned invalid/undecodable image (${avatarBytes.size} bytes) for Key=$mapKey; not caching.")
            }
        }
    }

    fun updatePeerStatus(peerId: String, newStatus: ConnectionStatus) {
        val aliases = PeerAliasRegistry.resolveAliases(peerId) + peerId
        val record = aliases.firstNotNullOfOrNull { key ->
            peerMap[key] ?: peerMap.values.find { it.peer.id.equals(key, ignoreCase = true) || it.peer.handle.equals(key, ignoreCase = true) }
        }
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
        fetchedAvatarKeys.clear()
        inFlightAvatarKeys.clear()
        cachedAvatarsByKey.clear()
        pruneJob?.cancel()
    }
}