package com.intj.layover.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.p2p.P2PMessageFrame
import com.intj.layover.p2p.RfcommConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

data class GattReadinessSnapshot(
    val hasActiveClient: Boolean,
    val clientConnected: Boolean,
    val clientServicesDiscovered: Boolean,
    val clientRxAvailable: Boolean,
    val clientDescriptorWriteInFlight: Boolean,
    val clientNotificationsReady: Boolean,
    val hasActiveServer: Boolean,
    val serverHasSubscribedClient: Boolean,
    val connectionState: RfcommConnectionState
)

class GattSessionManager(
    private val context: Context
) {
    companion object {
        val LAYOVER_GATT_SERVICE_UUID: UUID = UUID.fromString("7f41a0d8-5526-4b6a-9f5b-6147e4b2a8d1")
        val LAYOVER_TX_CHAR_UUID: UUID = UUID.fromString("9a51c3e2-4b18-4d7a-8c2f-1e6b9f3a7042")
        val LAYOVER_RX_CHAR_UUID: UUID = UUID.fromString("9a51c3e2-4b18-4d7a-8c2f-1e6b9f3a7043")
        val LAYOVER_AVATAR_CHAR_UUID: UUID = UUID.fromString("9a51c3e2-4b18-4d7a-8c2f-1e6b9f3a7044")
        val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val AVATAR_CHUNK_SIZE = 500
        private const val MAX_DESCRIPTOR_START_RETRIES = 3
        const val DEFAULT_ATT_MTU = 23
        const val ATT_HEADER_OVERHEAD = 3
        const val MIN_SAFE_GATT_CHUNK_BYTES = 20
        const val MAX_SAFE_GATT_CHUNK_BYTES = 480
        const val MAX_OUTBOUND_FRAME_BYTES = 65_536

        fun computeSafeChunkSize(negotiatedMtu: Int): Int {
            val mtuPayload = negotiatedMtu - ATT_HEADER_OVERHEAD
            return mtuPayload.coerceIn(MIN_SAFE_GATT_CHUNK_BYTES, MAX_SAFE_GATT_CHUNK_BYTES)
        }

        fun chunkGattPacket(
            packet: ByteArray,
            maxChunkSize: Int = MAX_SAFE_GATT_CHUNK_BYTES
        ): List<ByteArray> {
            if (packet.isEmpty()) return emptyList()
            val safeChunkSize = maxChunkSize.coerceIn(MIN_SAFE_GATT_CHUNK_BYTES, MAX_SAFE_GATT_CHUNK_BYTES)
            if (packet.size <= safeChunkSize) return listOf(packet)
            val chunks = ArrayList<ByteArray>((packet.size + safeChunkSize - 1) / safeChunkSize)
            var offset = 0
            while (offset < packet.size) {
                val end = min(packet.size, offset + safeChunkSize)
                chunks.add(packet.copyOfRange(offset, end))
                offset = end
            }
            return chunks
        }

        /**
         * Strict readiness gate:
         * - When a GATT client connection is active/in-progress, NEVER flush (and never fall back to
         *   server readiness) until the client is connected, Layover service & RX characteristic are
         *   discovered, no descriptor write is in flight, and notification subscription completed.
         * - When operating solely as a GATT server, ONLY flush once a peer client has subscribed to
         *   CCCD (0x2902) notifications.
         */
        fun canFlushOutboundQueue(snapshot: GattReadinessSnapshot): Boolean {
            return if (snapshot.hasActiveClient) {
                snapshot.connectionState == RfcommConnectionState.CONNECTED &&
                    snapshot.clientConnected &&
                    snapshot.clientServicesDiscovered &&
                    snapshot.clientRxAvailable &&
                    !snapshot.clientDescriptorWriteInFlight &&
                    snapshot.clientNotificationsReady
            } else {
                snapshot.hasActiveServer &&
                    snapshot.connectionState == RfcommConnectionState.CONNECTED &&
                    snapshot.serverHasSubscribedClient
            }
        }

        /**
         * Enqueues an outbound frame while deduplicating stale queued KEY_EXCHANGE frames and
         * prioritizing the latest KEY_EXCHANGE ahead of control/encrypted payloads.
         */
        fun enqueueOutboundFrame(queue: ConcurrentLinkedDeque<String>, frame: String) {
            if (frame.startsWith("KEY_EXCHANGE|")) {
                queue.removeIf { it.startsWith("KEY_EXCHANGE|") }
                queue.addFirst(frame)
            } else {
                queue.addLast(frame)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(RfcommConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RfcommConnectionState> = _connectionState.asStateFlow()

    private val _isGattReady = MutableStateFlow(false)
    val isGattReady: StateFlow<Boolean> = _isGattReady.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<P2PMessageFrame>()
    val incomingMessages: SharedFlow<P2PMessageFrame> = _incomingMessages.asSharedFlow()

    var onGattReady: (() -> Unit)? = null
    var compactAvatarBytesProvider: (() -> ByteArray?)? = null

    private var gattServer: BluetoothGattServer? = null
    @Volatile
    private var activeGattClient: BluetoothGatt? = null
    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    private val incomingBuffers = ConcurrentHashMap<String, MutableList<Byte>>()
    private val pendingOutboundFrames = ConcurrentLinkedDeque<String>()
    private val isFlushingQueue = AtomicBoolean(false)
    private val subscribedClients = mutableSetOf<BluetoothDevice>()
    private val avatarReadBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val avatarReadOffsets = ConcurrentHashMap<String, Int>()
    private val serverClientMtuByAddress = ConcurrentHashMap<String, Int>()

    @Volatile
    private var negotiatedClientMtu: Int = DEFAULT_ATT_MTU

    @Volatile
    private var writeCompletion: CompletableDeferred<Boolean>? = null

    @Volatile
    private var isClientConnected: Boolean = false

    @Volatile
    private var isClientServicesDiscovered: Boolean = false

    @Volatile
    private var isClientRxAvailable: Boolean = false

    @Volatile
    private var isDescriptorWriteInFlight: Boolean = false

    @Volatile
    private var isClientNotificationsReady: Boolean = false

    @Volatile
    private var hasTriggeredClientGattReady: Boolean = false

    @Volatile
    private var serverHasSubscribedClient: Boolean = false

    private fun currentReadinessSnapshot(): GattReadinessSnapshot {
        return GattReadinessSnapshot(
            hasActiveClient = activeGattClient != null,
            clientConnected = isClientConnected,
            clientServicesDiscovered = isClientServicesDiscovered,
            clientRxAvailable = isClientRxAvailable,
            clientDescriptorWriteInFlight = isDescriptorWriteInFlight,
            clientNotificationsReady = isClientNotificationsReady,
            hasActiveServer = gattServer != null,
            serverHasSubscribedClient = serverHasSubscribedClient,
            connectionState = _connectionState.value
        )
    }

    fun hasReadyChannel(): Boolean = canFlushOutboundQueue(currentReadinessSnapshot())

    fun isReadyForPeer(candidateAliasesOrMacs: Set<String> = emptySet()): Boolean {
        if (!hasReadyChannel()) return false
        if (candidateAliasesOrMacs.isEmpty()) return true
        val activeMacs = buildSet {
            activeGattClient?.device?.address?.let { add(it) }
            connectedDevice?.address?.let { add(it) }
            synchronized(subscribedClients) {
                subscribedClients.forEach { add(it.address) }
            }
        }
        return activeMacs.any { active ->
            candidateAliasesOrMacs.any { cand -> active.equals(cand, ignoreCase = true) }
        }
    }

    private fun resetClientReadinessFlags() {
        isClientConnected = false
        isClientServicesDiscovered = false
        isClientRxAvailable = false
        isDescriptorWriteInFlight = false
        isClientNotificationsReady = false
        hasTriggeredClientGattReady = false
    }

    private fun setConnectionState(newState: RfcommConnectionState, errorDetail: String? = null) {
        _connectionState.value = newState
        val msg = "GATT ConnectionState: ${newState.name}" + (if (errorDetail != null) " - $errorDetail" else "")
        if (newState == RfcommConnectionState.FAILED) {
            LayoverFileLogger.logError(msg)
        } else {
            LayoverFileLogger.log(msg)
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            setConnectionState(RfcommConnectionState.FAILED, "Bluetooth permissions missing for GATT server")
            return
        }
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        if (gattServer != null) {
            LayoverFileLogger.log("GATT Server already active and hosting service.")
            return
        }

        setConnectionState(RfcommConnectionState.CONNECTING, "GATT Server starting...")

        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    LayoverFileLogger.log("GATT Server: Client connected: ${device?.address} (awaiting 0x2902 notification subscription)")
                    device?.address?.let {
                        avatarReadOffsets.remove(it)
                        incomingBuffers.remove(it)
                    }
                    // Do NOT mark ready or trigger E2EE handshake here; transient avatar probes connect
                    // only to read LAYOVER_AVATAR_CHAR_UUID and never subscribe to 0x2902 notifications.
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val addr = device?.address
                    if (addr != null) {
                        avatarReadOffsets.remove(addr)
                        serverClientMtuByAddress.remove(addr)
                        incomingBuffers.remove(addr)
                    }
                    val wasSubscribed = device != null && synchronized(subscribedClients) {
                        subscribedClients.removeAll { it.address.equals(device.address, ignoreCase = true) }
                    }
                    val remainingSubscribed = synchronized(subscribedClients) { subscribedClients.isNotEmpty() }
                    serverHasSubscribedClient = remainingSubscribed

                    if (!wasSubscribed) {
                        LayoverFileLogger.log("GATT Server: Non-subscribed client disconnected (likely avatar probe): $addr; preserving session.")
                        return
                    }

                    LayoverFileLogger.log("GATT Server: Subscribed client disconnected: $addr (remainingSubscribed=$remainingSubscribed)")
                    if (connectedDevice?.address.equals(addr, ignoreCase = true)) {
                        connectedDevice = synchronized(subscribedClients) { subscribedClients.lastOrNull() }
                            ?: activeGattClient?.device
                    }
                    if (activeGattClient == null && !remainingSubscribed) {
                        setConnectionState(RfcommConnectionState.DISCONNECTED)
                        _isGattReady.value = false
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                if (device != null && mtu > DEFAULT_ATT_MTU) {
                    serverClientMtuByAddress[device.address] = mtu
                    LayoverFileLogger.log("GATT Server: Client MTU updated for ${device.address}: mtu=$mtu")
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?
            ) {
                val targetDevice = device ?: return
                if (characteristic?.uuid?.equals(LAYOVER_AVATAR_CHAR_UUID) == true) {
                    val rawAvatar = compactAvatarBytesProvider?.invoke() ?: ByteArray(0)
                    val framedPayload = if (rawAvatar.isEmpty()) {
                        ByteBuffer.allocate(4).putInt(0).array()
                    } else {
                        ByteBuffer.allocate(4 + rawAvatar.size)
                            .putInt(rawAvatar.size)
                            .put(rawAvatar)
                            .array()
                    }

                    val trackedOffset = avatarReadOffsets[targetDevice.address]
                    val effectiveOffset = when {
                        offset > 0 -> offset
                        trackedOffset != null && trackedOffset < framedPayload.size -> trackedOffset
                        else -> 0
                    }

                    val slice = if (effectiveOffset >= framedPayload.size) {
                        avatarReadOffsets.remove(targetDevice.address)
                        ByteArray(0)
                    } else {
                        val end = min(framedPayload.size, effectiveOffset + AVATAR_CHUNK_SIZE)
                        val chunk = framedPayload.copyOfRange(effectiveOffset, end)
                        val nextPos = end
                        if (nextPos >= framedPayload.size) {
                            avatarReadOffsets.remove(targetDevice.address)
                        } else {
                            avatarReadOffsets[targetDevice.address] = nextPos
                        }
                        chunk
                    }

                    LayoverFileLogger.log("GATT Server avatar read: device=${targetDevice.address}, reqOffset=$offset, effectiveOffset=$effectiveOffset, framedTotal=${framedPayload.size}, sent=${slice.size}")
                    gattServer?.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                } else {
                    gattServer?.sendResponse(targetDevice, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                LayoverFileLogger.log("GATT Server onCharacteristicWriteRequest ENTRY: device=${device?.address}, charUUID=${characteristic?.uuid}, requestId=$requestId, offset=$offset, prepared=$preparedWrite, responseNeeded=$responseNeeded, len=${value?.size ?: 0}")
                if (value != null && (characteristic?.uuid?.equals(LAYOVER_RX_CHAR_UUID) == true || characteristic?.uuid?.equals(LAYOVER_TX_CHAR_UUID) == true)) {
                    if (responseNeeded) {
                        device?.let { gattServer?.sendResponse(it, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
                    }
                    parseIncomingBytes(value, device?.address ?: "remote_peer")
                } else {
                    if (responseNeeded) {
                        device?.let { gattServer?.sendResponse(it, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null) }
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                descriptor: BluetoothGattDescriptor?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (descriptor?.uuid?.equals(CLIENT_CONFIG_DESCRIPTOR_UUID) == true) {
                    if (responseNeeded) {
                        device?.let { gattServer?.sendResponse(it, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
                    }
                    val isDisabling = value != null && value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                    if (isDisabling) {
                        if (device != null) {
                            synchronized(subscribedClients) {
                                subscribedClients.removeAll { it.address.equals(device.address, ignoreCase = true) }
                            }
                            serverHasSubscribedClient = synchronized(subscribedClients) { subscribedClients.isNotEmpty() }
                        }
                        LayoverFileLogger.log("GATT Server: Client ${device?.address} unsubscribed from notifications.")
                        return
                    }

                    val newlySubscribed = if (device != null) {
                        synchronized(subscribedClients) {
                            val alreadyPresent = subscribedClients.any { it.address.equals(device.address, ignoreCase = true) }
                            if (!alreadyPresent) {
                                subscribedClients.add(device)
                            }
                            !alreadyPresent
                        }
                    } else {
                        false
                    }
                    if (device != null) {
                        connectedDevice = device
                    }
                    serverHasSubscribedClient = synchronized(subscribedClients) { subscribedClients.isNotEmpty() }

                    LayoverFileLogger.log("GATT Server: Client ${device?.address} subscribed to 0x2902 notifications (newlySubscribed=$newlySubscribed).")
                    if (activeGattClient == null) {
                        setConnectionState(RfcommConnectionState.CONNECTED)
                        _isGattReady.value = true
                        scope.launch {
                            delay(60)
                            if (newlySubscribed && activeGattClient == null && serverHasSubscribedClient) {
                                onGattReady?.invoke()
                            }
                            flushQueue()
                        }
                    }
                }
            }
        }

        try {
            val server = bluetoothManager?.openGattServer(context, serverCallback)
            if (server != null) {
                val service = android.bluetooth.BluetoothGattService(
                    LAYOVER_GATT_SERVICE_UUID,
                    android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
                )

                val txChar = BluetoothGattCharacteristic(
                    LAYOVER_TX_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )

                val rxChar = BluetoothGattCharacteristic(
                    LAYOVER_RX_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                )

                val avatarChar = BluetoothGattCharacteristic(
                    LAYOVER_AVATAR_CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )

                val rxDesc = BluetoothGattDescriptor(
                    CLIENT_CONFIG_DESCRIPTOR_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
                rxChar.addDescriptor(rxDesc)

                service.addCharacteristic(txChar)
                service.addCharacteristic(rxChar)
                service.addCharacteristic(avatarChar)

                server.addService(service)
                gattServer = server
                LayoverFileLogger.log("GATT Server successfully started and hosting service $LAYOVER_GATT_SERVICE_UUID")
            } else {
                setConnectionState(RfcommConnectionState.FAILED, "Failed to open GATT Server")
            }
        } catch (e: Exception) {
            LayoverFileLogger.logError("Exception starting GATT Server", e)
            setConnectionState(RfcommConnectionState.FAILED, e.message)
        }
    }

    @SuppressLint("MissingPermission")
    fun fetchAvatarFromDevice(
        device: BluetoothDevice,
        onAvatarLoaded: (ByteArray) -> Unit
    ) {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            LayoverFileLogger.logError("Avatar probe skipped: missing Bluetooth permissions.")
            onAvatarLoaded(ByteArray(0))
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            LayoverFileLogger.logError("Avatar probe skipped: Bluetooth adapter null or off.")
            onAvatarLoaded(ByteArray(0))
            return
        }

        val probeKey = "${device.address}_${System.nanoTime()}"
        val buffer = ByteArrayOutputStream()
        avatarReadBuffers[probeKey] = buffer

        var probeGatt: BluetoothGatt? = null
        var completed = false

        fun readNextChunk(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            gatt.readCharacteristic(characteristic)
        }

        fun finishAndClose(gatt: BluetoothGatt?, reason: String) {
            if (completed) return
            completed = true
            val raw = avatarReadBuffers.remove(probeKey)?.toByteArray() ?: ByteArray(0)
            val collected = if (raw.size > 4) {
                val expectedLen = ByteBuffer.wrap(raw, 0, 4).int
                val availablePayload = raw.copyOfRange(4, raw.size)
                if (expectedLen in 1..availablePayload.size) {
                    availablePayload.copyOfRange(0, expectedLen)
                } else {
                    ByteArray(0)
                }
            } else {
                ByteArray(0)
            }
            val magicHex = collected.take(4).joinToString("") { "%02X".format(it) }
            val tailHex = collected.takeLast(2).joinToString("") { "%02X".format(it) }
            LayoverFileLogger.log("Avatar probe finished (${device.address}): reason=$reason, raw=${raw.size}, avatarBytes=${collected.size}, magic=$magicHex, tail=$tailHex")
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Exception) {
            }
            scope.launch(Dispatchers.Main) {
                onAvatarLoaded(collected)
            }
        }

        val probeCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    LayoverFileLogger.log("Avatar probe connected to ${device.address}. Discovering services...")
                    scope.launch {
                        delay(120)
                        gatt?.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    finishAndClose(gatt, "connState=$newState, status=$status")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                    gatt.requestMtu(512)
                } else {
                    finishAndClose(gatt, "servicesStatus=$status")
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                val service = gatt?.services?.find { it.uuid.equals(LAYOVER_GATT_SERVICE_UUID) }
                    ?: gatt?.getService(LAYOVER_GATT_SERVICE_UUID)
                val avatarChar = service?.characteristics?.find { it.uuid.equals(LAYOVER_AVATAR_CHAR_UUID) }
                    ?: service?.getCharacteristic(LAYOVER_AVATAR_CHAR_UUID)
                if (avatarChar != null && gatt != null) {
                    LayoverFileLogger.log("Avatar probe reading characteristic from ${device.address} (mtu=$mtu)...")
                    readNextChunk(gatt, avatarChar)
                } else {
                    finishAndClose(gatt, "avatarCharMissing")
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid.equals(LAYOVER_AVATAR_CHAR_UUID)) {
                    if (value.isNotEmpty()) {
                        buffer.write(value)
                        val currentBytes = buffer.toByteArray()
                        if (currentBytes.size >= 4) {
                            val payloadLen = ByteBuffer.wrap(currentBytes, 0, 4).int
                            if (payloadLen <= 0) {
                                finishAndClose(gatt, "emptyAvatar")
                                return
                            }
                            val totalNeeded = 4 + payloadLen
                            LayoverFileLogger.log("Avatar probe progress (${device.address}): got=${currentBytes.size}/$totalNeeded")
                            if (currentBytes.size >= totalNeeded) {
                                finishAndClose(gatt, "complete")
                                return
                            }
                        }
                        readNextChunk(gatt, characteristic)
                    } else {
                        finishAndClose(gatt, "emptyChunk")
                    }
                } else {
                    finishAndClose(gatt, "readStatus=$status")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                @Suppress("DEPRECATION")
                val bytes = characteristic?.value ?: ByteArray(0)
                if (gatt != null && characteristic != null) {
                    onCharacteristicRead(gatt, characteristic, bytes, status)
                } else {
                    finishAndClose(gatt, "nullCallbackArgs")
                }
            }
        }

        try {
            LayoverFileLogger.log("Avatar probe starting connectGatt to ${device.address}...")
            probeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, probeCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, probeCallback)
            }
            scope.launch {
                delay(7000)
                finishAndClose(probeGatt, "timeout")
            }
        } catch (e: Exception) {
            LayoverFileLogger.logError("Avatar probe exception for ${device.address}", e)
            finishAndClose(probeGatt, "exception")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        synchronized(subscribedClients) { subscribedClients.clear() }
        serverHasSubscribedClient = false
        avatarReadOffsets.clear()
        _isGattReady.value = false
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            setConnectionState(RfcommConnectionState.FAILED, "Bluetooth permissions missing for GATT client")
            return
        }

        // Null activeGattClient before closing so stale callbacks from the prior GATT instance are ignored.
        val previousClient = activeGattClient
        activeGattClient = null
        resetClientReadinessFlags()
        _isGattReady.value = false
        writeCompletion?.complete(false)
        writeCompletion = null

        try {
            previousClient?.disconnect()
            previousClient?.close()
        } catch (_: Exception) {
        }

        setConnectionState(RfcommConnectionState.CONNECTING, "GATT Client connecting to ${device.address}...")
        negotiatedClientMtu = DEFAULT_ATT_MTU
        connectedDevice = device
        incomingBuffers.remove(device.address)

        var serviceDiscoveryRetryCount = 0
        var descriptorFailureRetryCount = 0

        val clientCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (gatt == null || activeGattClient !== gatt) {
                    LayoverFileLogger.log("Ignoring stale GATT client onConnectionStateChange (status=$status, newState=$newState)")
                    return
                }
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    isClientConnected = true
                    LayoverFileLogger.log("GATT Client connected to ${device.address}. Waiting 150ms settle delay before discovering services...")
                    scope.launch {
                        delay(150)
                        if (activeGattClient === gatt && isClientConnected) {
                            gatt.discoverServices()
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    LayoverFileLogger.log("GATT Client disconnected from ${device.address} (status=$status)")
                    resetClientReadinessFlags()
                    _isGattReady.value = false
                    writeCompletion?.complete(false)
                    incomingBuffers.remove(device.address)
                    activeGattClient = null
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    if (!serverHasSubscribedClient) {
                        setConnectionState(RfcommConnectionState.DISCONNECTED)
                    }
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    LayoverFileLogger.logError("GATT Client connection error status: $status")
                    resetClientReadinessFlags()
                    _isGattReady.value = false
                    writeCompletion?.complete(false)
                    incomingBuffers.remove(device.address)
                    activeGattClient = null
                    try {
                        gatt.disconnect()
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    if (!serverHasSubscribedClient) {
                        setConnectionState(RfcommConnectionState.FAILED, "GATT status $status")
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (gatt == null || activeGattClient !== gatt) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.services?.find { it.uuid.equals(LAYOVER_GATT_SERVICE_UUID) }
                        ?: gatt.getService(LAYOVER_GATT_SERVICE_UUID)
                    val rxChar = service?.characteristics?.find { it.uuid.equals(LAYOVER_RX_CHAR_UUID) }
                        ?: service?.getCharacteristic(LAYOVER_RX_CHAR_UUID)
                    val txChar = service?.characteristics?.find { it.uuid.equals(LAYOVER_TX_CHAR_UUID) }
                        ?: service?.getCharacteristic(LAYOVER_TX_CHAR_UUID)

                    if (service == null || rxChar == null) {
                        serviceDiscoveryRetryCount++
                        if (serviceDiscoveryRetryCount <= 2) {
                            LayoverFileLogger.logError(
                                "GATT Services discovered but Layover service/RX missing " +
                                    "(service=${service != null}, rx=${rxChar != null}, attempt $serviceDiscoveryRetryCount). Retrying in 250ms..."
                            )
                            if (serviceDiscoveryRetryCount == 2) {
                                runCatching {
                                    val refreshMethod = gatt.javaClass.getMethod("refresh")
                                    refreshMethod.invoke(gatt)
                                    LayoverFileLogger.log("Executed BluetoothGatt.refresh() to clear stale GATT cache.")
                                }
                            }
                            scope.launch {
                                delay(250)
                                if (activeGattClient === gatt && isClientConnected) {
                                    gatt.discoverServices()
                                }
                            }
                            return
                        } else {
                            LayoverFileLogger.logError("GATT Services discovery failed: Layover service/RX not found after retries.")
                            resetClientReadinessFlags()
                            setConnectionState(RfcommConnectionState.FAILED, "Layover service/RX not found")
                            _isGattReady.value = false
                            return
                        }
                    }

                    serviceDiscoveryRetryCount = 0
                    isClientServicesDiscovered = true
                    isClientRxAvailable = true
                    LayoverFileLogger.log(
                        "GATT Services discovered successfully. Service found: true, RX found: true, TX found: ${txChar != null}. Requesting MTU=512..."
                    )
                    val mtuRequested = gatt.requestMtu(512)
                    if (!mtuRequested) {
                        LayoverFileLogger.log("GATT Client requestMtu did not start; proceeding to enable notifications after 80ms.")
                        scope.launch {
                            delay(80)
                            if (activeGattClient === gatt && isClientConnected) {
                                enableNotifications(gatt, attempt = 1)
                            }
                        }
                    }
                } else {
                    serviceDiscoveryRetryCount = 0
                    LayoverFileLogger.logError("GATT Services discovery failed with status: $status")
                    resetClientReadinessFlags()
                    setConnectionState(RfcommConnectionState.FAILED, "Services discovery status $status")
                    _isGattReady.value = false
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                if (gatt == null || activeGattClient !== gatt) return
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > DEFAULT_ATT_MTU) {
                    negotiatedClientMtu = mtu
                }
                LayoverFileLogger.log(
                    "GATT MTU updated to $mtu (status=$status, effectiveChunk=${computeSafeChunkSize(negotiatedClientMtu)}B). " +
                        "Waiting 80ms settle delay before enabling notifications..."
                )
                scope.launch {
                    delay(80)
                    if (activeGattClient === gatt && isClientConnected && isClientServicesDiscovered) {
                        enableNotifications(gatt, attempt = 1)
                    }
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                if (gatt == null || activeGattClient !== gatt) return
                if (descriptor?.uuid?.equals(CLIENT_CONFIG_DESCRIPTOR_UUID) != true) return

                isDescriptorWriteInFlight = false
                val rxChar = resolveClientRxCharacteristic(gatt)
                isClientRxAvailable = rxChar != null

                if (status == BluetoothGatt.GATT_SUCCESS && rxChar != null) {
                    LayoverFileLogger.log("GATT Client successfully subscribed to 0x2902 notifications (onDescriptorWrite SUCCESS).")
                    isClientNotificationsReady = true
                    markClientReadyAndFlush(gatt)
                } else if (status != BluetoothGatt.GATT_SUCCESS &&
                    descriptorFailureRetryCount < 1 &&
                    isClientConnected &&
                    rxChar != null
                ) {
                    descriptorFailureRetryCount++
                    LayoverFileLogger.logError("GATT Client onDescriptorWrite failed (status=$status); retrying enableNotifications in 200ms...")
                    scope.launch {
                        delay(200)
                        if (activeGattClient === gatt && isClientConnected) {
                            enableNotifications(gatt, attempt = 1)
                        }
                    }
                } else if (rxChar != null && isClientConnected && isClientServicesDiscovered) {
                    LayoverFileLogger.logError("GATT Client failed to write descriptor (status=$status) after retry; RX confirmed non-null, marking ready for writes.")
                    isClientNotificationsReady = true
                    markClientReadyAndFlush(gatt)
                } else {
                    LayoverFileLogger.logError("GATT Client descriptor write failed and RX unavailable (status=$status); keeping GATT not ready.")
                    isClientNotificationsReady = false
                    _isGattReady.value = false
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                if (gatt == null || activeGattClient !== gatt) return
                LayoverFileLogger.log("GATT Client callback onCharacteristicWrite: UUID=${characteristic?.uuid}, status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeCompletion?.complete(true)
                } else {
                    writeCompletion?.complete(false)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (activeGattClient !== gatt) return
                LayoverFileLogger.log("GATT Client onCharacteristicChanged ENTRY: charUUID=${characteristic.uuid}, len=${value.size}")
                if (characteristic.uuid.equals(LAYOVER_RX_CHAR_UUID)) {
                    parseIncomingBytes(value, device.address)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                if (gatt == null || activeGattClient !== gatt) return
                LayoverFileLogger.log("GATT Client onCharacteristicChanged (deprecated) ENTRY: charUUID=${characteristic?.uuid}, len=${characteristic?.value?.size ?: 0}")
                if (characteristic?.uuid?.equals(LAYOVER_RX_CHAR_UUID) == true) {
                    @Suppress("DEPRECATION")
                    characteristic.value?.let { parseIncomingBytes(it, device.address) }
                }
            }
        }

        try {
            bluetoothAdapter?.cancelDiscovery()
            activeGattClient = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, clientCallback)
            }
        } catch (e: Exception) {
            LayoverFileLogger.logError("Exception connecting GATT client", e)
            resetClientReadinessFlags()
            setConnectionState(RfcommConnectionState.FAILED, e.message)
            _isGattReady.value = false
        }
    }

    private fun resolveClientRxCharacteristic(gatt: BluetoothGatt?): BluetoothGattCharacteristic? {
        val service = gatt?.services?.find { it.uuid.equals(LAYOVER_GATT_SERVICE_UUID) }
            ?: gatt?.getService(LAYOVER_GATT_SERVICE_UUID)
        return service?.characteristics?.find { it.uuid.equals(LAYOVER_RX_CHAR_UUID) }
            ?: service?.getCharacteristic(LAYOVER_RX_CHAR_UUID)
    }

    private fun markClientReadyAndFlush(gatt: BluetoothGatt) {
        if (activeGattClient !== gatt) return
        if (!isClientConnected || !isClientServicesDiscovered || !isClientRxAvailable) return
        setConnectionState(RfcommConnectionState.CONNECTED)
        _isGattReady.value = true
        val shouldTriggerReady = !hasTriggeredClientGattReady
        hasTriggeredClientGattReady = true
        scope.launch {
            delay(80)
            if (activeGattClient !== gatt || !hasReadyChannel()) return@launch
            if (shouldTriggerReady) {
                onGattReady?.invoke()
            }
            flushQueue()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt?, attempt: Int) {
        if (gatt == null || activeGattClient !== gatt || !isClientConnected) return
        val rxChar = resolveClientRxCharacteristic(gatt)
        if (rxChar == null) {
            isClientRxAvailable = false
            isDescriptorWriteInFlight = false
            isClientNotificationsReady = false
            _isGattReady.value = false
            LayoverFileLogger.logError("GATT RX characteristic not found for enabling notifications; keeping GATT not ready.")
            return
        }
        isClientRxAvailable = true
        gatt.setCharacteristicNotification(rxChar, true)

        val desc = rxChar.descriptors?.find { it.uuid.equals(CLIENT_CONFIG_DESCRIPTOR_UUID) }
            ?: rxChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
        if (desc == null) {
            isDescriptorWriteInFlight = false
            LayoverFileLogger.logError("GATT CCCD Descriptor 0x2902 not found on RX characteristic; RX confirmed non-null, marking ready for writes.")
            isClientNotificationsReady = true
            markClientReadyAndFlush(gatt)
            return
        }

        isDescriptorWriteInFlight = true
        val descStarted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(desc)
            }
        } catch (e: Exception) {
            LayoverFileLogger.logError("GATT Client writeDescriptor exception on attempt $attempt", e)
            false
        }

        if (descStarted) {
            LayoverFileLogger.log("GATT Client initiated writeDescriptor for 0x2902 notifications (attempt $attempt/$MAX_DESCRIPTOR_START_RETRIES).")
        } else if (attempt < MAX_DESCRIPTOR_START_RETRIES) {
            val retryDelayMs = 150L * attempt
            LayoverFileLogger.log("GATT Client writeDescriptor busy/not started (attempt $attempt/$MAX_DESCRIPTOR_START_RETRIES); retrying in ${retryDelayMs}ms...")
            scope.launch {
                delay(retryDelayMs)
                if (activeGattClient === gatt && isClientConnected) {
                    enableNotifications(gatt, attempt + 1)
                }
            }
        } else {
            isDescriptorWriteInFlight = false
            LayoverFileLogger.logError(
                "GATT Client writeDescriptor did not start after $MAX_DESCRIPTOR_START_RETRIES attempts; " +
                    "RX confirmed non-null, marking GATT ready for writes."
            )
            isClientNotificationsReady = true
            markClientReadyAndFlush(gatt)
        }
    }

    private fun parseIncomingBytes(data: ByteArray, remoteId: String) {
        val buffer = incomingBuffers.getOrPut(remoteId) { mutableListOf() }
        synchronized(buffer) {
            LayoverFileLogger.log("GATT reassembler ($remoteId): appending ${data.size} bytes. Current buffer size: ${buffer.size}")
            for (b in data) {
                buffer.add(b)
            }
            while (buffer.size >= 4) {
                val lenBytes = byteArrayOf(buffer[0], buffer[1], buffer[2], buffer[3])
                val frameLen = ByteBuffer.wrap(lenBytes).int
                if (frameLen <= 0 || frameLen > MAX_OUTBOUND_FRAME_BYTES) {
                    LayoverFileLogger.logError("GATT reassembler error ($remoteId): invalid frameLen=$frameLen. Clearing buffer.")
                    buffer.clear()
                    break
                }
                if (buffer.size >= 4 + frameLen) {
                    val frameBytes = ByteArray(frameLen)
                    for (idx in 0 until frameLen) {
                        frameBytes[idx] = buffer[4 + idx]
                    }
                    val totalToRemove = 4 + frameLen
                    repeat(totalToRemove) {
                        buffer.removeAt(0)
                    }
                    val text = String(frameBytes, Charsets.UTF_8)
                    val frameType = text.substringBefore("|")
                    LayoverFileLogger.log("GATT frame parsed successfully: type=$frameType, length=$frameLen")
                    scope.launch {
                        _incomingMessages.emit(P2PMessageFrame(senderId = remoteId, text = text))
                    }
                } else {
                    break
                }
            }
        }
    }

    fun flushQueue() {
        if (!hasReadyChannel()) {
            LayoverFileLogger.logThrottled(
                "flush_skip_not_ready",
                "flushQueue deferred: GATT not ready (${pendingOutboundFrames.size} queued, clientActive=${activeGattClient != null}, " +
                    "clientDiscovered=$isClientServicesDiscovered, clientRx=$isClientRxAvailable, " +
                    "descInFlight=$isDescriptorWriteInFlight, clientNotif=$isClientNotificationsReady, serverSubscribed=$serverHasSubscribedClient)."
            )
            return
        }
        if (!isFlushingQueue.compareAndSet(false, true)) return
        scope.launch {
            try {
                val retryCounts = mutableMapOf<String, Int>()
                val maxRetriesPerFrame = 8

                LayoverFileLogger.log("Flushing ${pendingOutboundFrames.size} queued outbound frames...")
                while (pendingOutboundFrames.isNotEmpty()) {
                    if (activeGattClient != null && isDescriptorWriteInFlight) {
                        LayoverFileLogger.log("flushQueue: waiting for in-flight 0x2902 descriptor write before writeCharacteristic...")
                        delay(120)
                        continue
                    }

                    if (!hasReadyChannel()) {
                        LayoverFileLogger.log("flushQueue: GATT no longer ready (${pendingOutboundFrames.size} queued). Pausing until ready callback.")
                        break
                    }

                    val frame = pendingOutboundFrames.peekFirst() ?: break
                    val success = sendFrameSuspended(frame)
                    if (success) {
                        pendingOutboundFrames.removeFirstOccurrence(frame)
                        retryCounts.remove(frame)
                        delay(60)
                    } else {
                        if (!hasReadyChannel()) {
                            LayoverFileLogger.log("flushQueue: channel became unready during write; pausing queue until reconnect.")
                            break
                        }
                        val attempts = (retryCounts[frame] ?: 0) + 1
                        retryCounts[frame] = attempts
                        if (attempts == 4 && activeGattClient != null && connectedDevice != null) {
                            val dev = connectedDevice
                            LayoverFileLogger.logError("GATT write failing repeatedly after 4 attempts; reconnecting GATT client to ${dev?.address}...")
                            if (dev != null) {
                                connectToDevice(dev)
                                break
                            }
                        }
                        if (attempts >= maxRetriesPerFrame) {
                            LayoverFileLogger.logError("GATT write exhausted $maxRetriesPerFrame retries for frame type=${frame.substringBefore("|")}. Dropping frame.")
                            pendingOutboundFrames.removeFirstOccurrence(frame)
                            retryCounts.remove(frame)
                        } else {
                            val backoffMs = (250L * attempts).coerceAtMost(1500L)
                            LayoverFileLogger.logError("GATT write deferred/failed (attempt $attempts/$maxRetriesPerFrame) for type=${frame.substringBefore("|")}. Retrying in ${backoffMs}ms...")
                            delay(backoffMs)
                        }
                    }
                }
            } finally {
                isFlushingQueue.set(false)
                if (pendingOutboundFrames.isNotEmpty() && hasReadyChannel()) {
                    flushQueue()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendFrameSuspended(text: String): Boolean {
        val payload = text.toByteArray(Charsets.UTF_8)
        val fullPacket = ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()
        val frameType = text.substringBefore("|")
        val remoteMac = connectedDevice?.address ?: activeGattClient?.device?.address ?: "unknown_mac"

        val client = activeGattClient
        if (client != null) {
            if (!canFlushOutboundQueue(currentReadinessSnapshot())) {
                LayoverFileLogger.log("GATT Client not ready for writeCharacteristic (type=$frameType); keeping frame queued.")
                return false
            }
            val rxChar = resolveClientRxCharacteristic(client)
            if (rxChar == null) {
                isClientRxAvailable = false
                _isGattReady.value = false
                LayoverFileLogger.logError("GATT RX characteristic null on client; keeping frame in queue until discovery finishes.")
                return false
            }

            val chunkSize = computeSafeChunkSize(negotiatedClientMtu)
            val chunks = chunkGattPacket(fullPacket, chunkSize)
            LayoverFileLogger.log(
                "GATT Client attempting writeCharacteristic: type=$frameType, size=${payload.size}, " +
                    "totalBytes=${fullPacket.size}, chunks=${chunks.size}, maxChunk=${chunkSize}B, " +
                    "targetCharUUID=$LAYOVER_RX_CHAR_UUID, writeType=WRITE_TYPE_DEFAULT, remoteMac=$remoteMac"
            )

            for ((chunkIndex, chunk) in chunks.withIndex()) {
                val deferred = CompletableDeferred<Boolean>()
                writeCompletion = deferred
                val initiated = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        client.writeCharacteristic(
                            rxChar,
                            chunk,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        rxChar.value = chunk
                        rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        client.writeCharacteristic(rxChar)
                    }
                } catch (e: IllegalArgumentException) {
                    if (writeCompletion === deferred) writeCompletion = null
                    LayoverFileLogger.logError(
                        "GATT Client writeCharacteristic IllegalArgumentException for type=$frameType " +
                            "(chunk ${chunkIndex + 1}/${chunks.size}, chunkLen=${chunk.size})",
                        e
                    )
                    return false
                } catch (e: Exception) {
                    if (writeCompletion === deferred) writeCompletion = null
                    LayoverFileLogger.logError(
                        "GATT Client writeCharacteristic exception for type=$frameType " +
                            "(chunk ${chunkIndex + 1}/${chunks.size}, chunkLen=${chunk.size})",
                        e
                    )
                    return false
                }

                if (!initiated) {
                    if (writeCompletion === deferred) writeCompletion = null
                    LayoverFileLogger.logError(
                        "GATT Client failed to initiate writeCharacteristic for type=$frameType " +
                            "(chunk ${chunkIndex + 1}/${chunks.size}, chunkLen=${chunk.size})"
                    )
                    return false
                }

                val chunkWritten = try {
                    withTimeout(2500) {
                        deferred.await()
                    }
                } catch (e: Exception) {
                    LayoverFileLogger.logError(
                        "GATT Client writeCharacteristic timeout waiting for onCharacteristicWrite for type=$frameType " +
                            "(chunk ${chunkIndex + 1}/${chunks.size})",
                        e
                    )
                    false
                } finally {
                    if (writeCompletion === deferred) writeCompletion = null
                }

                if (!chunkWritten) {
                    LayoverFileLogger.logError(
                        "GATT Client writeCharacteristic chunk ${chunkIndex + 1}/${chunks.size} failed for type=$frameType"
                    )
                    return false
                }
            }

            LayoverFileLogger.log("GATT Client writeCharacteristic result for type=$frameType: success=true (${chunks.size} chunk(s))")
            return true
        } else {
            val server = gattServer ?: return false.also { LayoverFileLogger.logError("GATT Server is null when sending frame.") }
            val targetDevices = synchronized(subscribedClients) { subscribedClients.toList() }
            if (targetDevices.isEmpty()) {
                serverHasSubscribedClient = false
                LayoverFileLogger.logError("GATT Server has no subscribed client when sending frame; keeping frame queued.")
                return false
            }
            val service = server.getService(LAYOVER_GATT_SERVICE_UUID) ?: server.services?.find { it.uuid.equals(LAYOVER_GATT_SERVICE_UUID) }
            val rxChar = service?.getCharacteristic(LAYOVER_RX_CHAR_UUID) ?: service?.characteristics?.find { it.uuid.equals(LAYOVER_RX_CHAR_UUID) } ?: return false.also { LayoverFileLogger.logError("GATT RX characteristic null on server.") }

            var anySuccess = false
            for (targetDevice in targetDevices) {
                val deviceMtu = serverClientMtuByAddress[targetDevice.address] ?: negotiatedClientMtu
                val chunkSize = computeSafeChunkSize(deviceMtu)
                val chunks = chunkGattPacket(fullPacket, chunkSize)
                LayoverFileLogger.log(
                    "GATT Server attempting notify: type=$frameType, size=${payload.size}, " +
                        "chunks=${chunks.size}, maxChunk=${chunkSize}B, targetCharUUID=$LAYOVER_RX_CHAR_UUID, remoteMac=${targetDevice.address}"
                )
                var allChunksSent = true
                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    val notified = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(targetDevice, rxChar, false, chunk) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            rxChar.value = chunk
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(targetDevice, rxChar, false)
                        }
                    } catch (e: IllegalArgumentException) {
                        LayoverFileLogger.logError(
                            "GATT Server notifyCharacteristicChanged IllegalArgumentException for type=$frameType " +
                                "(chunk ${chunkIndex + 1}/${chunks.size}, chunkLen=${chunk.size})",
                            e
                        )
                        false
                    } catch (e: Exception) {
                        LayoverFileLogger.logError(
                            "GATT Server notifyCharacteristicChanged exception for type=$frameType " +
                                "(chunk ${chunkIndex + 1}/${chunks.size}, chunkLen=${chunk.size})",
                            e
                        )
                        false
                    }
                    if (!notified) {
                        allChunksSent = false
                        break
                    }
                    if (chunks.size > 1 && chunkIndex < chunks.lastIndex) {
                        delay(15)
                    }
                }
                if (allChunksSent) anySuccess = true
            }
            val success = anySuccess

            if (success) {
                LayoverFileLogger.log("GATT Server notification SUCCESS: type=$frameType, size=${payload.size}")
            } else {
                LayoverFileLogger.logError("GATT Server notification FAILED: type=$frameType, size=${payload.size}")
            }
            return success
        }
    }

    @SuppressLint("MissingPermission")
    fun sendFrame(text: String): Boolean {
        val frameType = text.substringBefore("|")
        val isValidPrefix = frameType == "KEY_EXCHANGE" ||
                frameType == "CONNECTION_REQUEST" ||
                frameType == "CONNECTION_ACCEPT" ||
                frameType == "CONNECTION_DECLINE" ||
                frameType == "PROFILE_SYNC" ||
                frameType == "PEER_BLOCK_STATE" ||
                frameType == "CHAT_ENCRYPTED"

        if (!isValidPrefix) {
            LayoverFileLogger.logError("Dropping malformed/unframed outbound payload: ${text.take(30)}")
            return false
        }

        val byteSize = text.toByteArray(Charsets.UTF_8).size
        if (byteSize <= 0 || byteSize > MAX_OUTBOUND_FRAME_BYTES) {
            LayoverFileLogger.logError("Dropping oversized outbound frame: type=$frameType, bytes=$byteSize (max=$MAX_OUTBOUND_FRAME_BYTES)")
            return false
        }

        LayoverFileLogger.log("GATT sendFrame queued: type=$frameType, size=$byteSize")
        enqueueOutboundFrame(pendingOutboundFrames, text)
        if (hasReadyChannel()) {
            flushQueue()
        } else {
            LayoverFileLogger.log("GATT channel not yet ready. Enqueued outbound frame: type=$frameType")
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val clientToClose = activeGattClient
        activeGattClient = null
        resetClientReadinessFlags()
        writeCompletion?.complete(false)
        writeCompletion = null
        try {
            clientToClose?.disconnect()
            clientToClose?.close()
        } catch (_: Exception) {
        }
        connectedDevice = null
        negotiatedClientMtu = DEFAULT_ATT_MTU
        serverClientMtuByAddress.clear()
        incomingBuffers.clear()
        pendingOutboundFrames.clear()
        stopServer()
        _connectionState.value = RfcommConnectionState.DISCONNECTED
    }

    fun cleanup() {
        disconnect()
    }
}
