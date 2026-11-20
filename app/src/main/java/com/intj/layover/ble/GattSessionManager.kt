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
import java.nio.ByteBuffer
import java.util.UUID

class GattSessionManager(
    private val context: Context
) {
    companion object {
        val LAYOVER_GATT_SERVICE_UUID: UUID = UUID.fromString("7f41a0d8-5526-4b6a-9f5b-6147e4b2a8d1")
        val LAYOVER_TX_CHAR_UUID: UUID = UUID.fromString("9a51c3e2-4b18-4d7a-8c2f-1e6b9f3a7042")
        val LAYOVER_RX_CHAR_UUID: UUID = UUID.fromString("9a51c3e2-4b18-4d7a-8c2f-1e6b9f3a7043")
        val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(RfcommConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RfcommConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<P2PMessageFrame>()
    val incomingMessages: SharedFlow<P2PMessageFrame> = _incomingMessages.asSharedFlow()

    var onGattReady: (() -> Unit)? = null

    private var gattServer: BluetoothGattServer? = null
    private var activeGattClient: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var isServerRole = false

    private val incomingBuffer = mutableListOf<Byte>()

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

        stopServer()
        setConnectionState(RfcommConnectionState.CONNECTING, "GATT Server starting...")

        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    LayoverFileLogger.log("GATT Server: Client connected: ${device?.address}")
                    connectedDevice = device
                    isServerRole = true
                    setConnectionState(RfcommConnectionState.CONNECTED)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    LayoverFileLogger.log("GATT Server: Client disconnected: ${device?.address}")
                    if (connectedDevice?.address == device?.address) {
                        connectedDevice = null
                        setConnectionState(RfcommConnectionState.DISCONNECTED)
                    }
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
                if (characteristic?.uuid?.equals(LAYOVER_TX_CHAR_UUID) == true && value != null) {
                    if (responseNeeded) {
                        device?.let { gattServer?.sendResponse(it, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
                    }
                    parseIncomingBytes(value, device?.address ?: "remote_peer")
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
                        device?.let { gattServer?.sendResponse(it, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
                    }
                    LayoverFileLogger.log("GATT Server: Client subscribed to notifications. Triggering server key exchange...")
                    scope.launch {
                        onGattReady?.invoke()
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
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )

                val rxDesc = BluetoothGattDescriptor(
                    CLIENT_CONFIG_DESCRIPTOR_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
                rxChar.addDescriptor(rxDesc)

                service.addCharacteristic(txChar)
                service.addCharacteristic(rxChar)

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
    fun stopServer() {
        try {
            gattServer?.close()
        } catch (e: Exception) {
            // Ignore
        }
        gattServer = null
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            setConnectionState(RfcommConnectionState.FAILED, "Bluetooth permissions missing for GATT client")
            return
        }

        disconnect()
        setConnectionState(RfcommConnectionState.CONNECTING, "GATT Client connecting to ${device.address}...")
        connectedDevice = device
        isServerRole = false

        var serviceDiscoveryRetryCount = 0

        val clientCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    LayoverFileLogger.log("GATT Client connected to ${device.address}. Discovering services...")
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    LayoverFileLogger.log("GATT Client disconnected from ${device.address}")
                    setConnectionState(RfcommConnectionState.DISCONNECTED)
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    LayoverFileLogger.logError("GATT Client connection error status: $status")
                    setConnectionState(RfcommConnectionState.FAILED, "GATT status $status")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                    val service = gatt.services?.find { it.uuid.equals(LAYOVER_GATT_SERVICE_UUID) } ?: gatt.getService(LAYOVER_GATT_SERVICE_UUID)
                    if (service == null) {
                        serviceDiscoveryRetryCount++
                        if (serviceDiscoveryRetryCount <= 2) {
                            LayoverFileLogger.logError("GATT Services discovered but Layover service not visible (attempt $serviceDiscoveryRetryCount). Retrying in 400ms...")
                            scope.launch {
                                delay(400)
                                gatt.discoverServices()
                            }
                            return
                        } else {
                            LayoverFileLogger.logError("GATT Services discovery failed: Layover service not found after retries.")
                            setConnectionState(RfcommConnectionState.FAILED, "Layover service not found")
                            return
                        }
                    }
                    serviceDiscoveryRetryCount = 0
                    val rxChar = service.characteristics?.find { it.uuid.equals(LAYOVER_RX_CHAR_UUID) }
                    val txChar = service.characteristics?.find { it.uuid.equals(LAYOVER_TX_CHAR_UUID) }
                    LayoverFileLogger.log("GATT Services discovered successfully. Service found: true, RX found: ${rxChar != null}, TX found: ${txChar != null}")
                    gatt.requestMtu(512)
                } else {
                    serviceDiscoveryRetryCount = 0
                    LayoverFileLogger.logError("GATT Services discovery failed with status: $status")
                    setConnectionState(RfcommConnectionState.FAILED, "Services discovery status $status")
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                LayoverFileLogger.log("GATT MTU updated to $mtu (status=$status). Enabling notifications...")
                enableNotifications(gatt)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && descriptor?.uuid?.equals(CLIENT_CONFIG_DESCRIPTOR_UUID) == true) {
                    LayoverFileLogger.log("GATT Client successfully subscribed to notifications (onDescriptorWrite SUCCESS).")
                    setConnectionState(RfcommConnectionState.CONNECTED)
                    scope.launch {
                        onGattReady?.invoke()
                    }
                } else {
                    LayoverFileLogger.logError("GATT Client failed to write descriptor: status=$status")
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid.equals(LAYOVER_RX_CHAR_UUID)) {
                    parseIncomingBytes(value, device.address)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                if (characteristic?.uuid?.equals(LAYOVER_RX_CHAR_UUID) == true) {
                    @Suppress("DEPRECATION")
                    characteristic.value?.let { parseIncomingBytes(it, device.address) }
                }
            }
        }

        try {
            bluetoothAdapter?.cancelDiscovery()
            @Suppress("DEPRECATION")
            activeGattClient = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            LayoverFileLogger.logError("Exception connecting GATT client", e)
            setConnectionState(RfcommConnectionState.FAILED, e.message)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt?) {
        val service = gatt?.services?.find { it.uuid.equals(LAYOVER_GATT_SERVICE_UUID) } ?: gatt?.getService(LAYOVER_GATT_SERVICE_UUID)
        val rxChar = service?.characteristics?.find { it.uuid.equals(LAYOVER_RX_CHAR_UUID) } ?: service?.getCharacteristic(LAYOVER_RX_CHAR_UUID)
        if (rxChar != null) {
            gatt?.setCharacteristicNotification(rxChar, true)
            val desc = rxChar.descriptors?.find { it.uuid.equals(CLIENT_CONFIG_DESCRIPTOR_UUID) } ?: rxChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt?.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    gatt?.writeDescriptor(desc)
                }
                LayoverFileLogger.log("GATT Client initiated writeDescriptor for notifications.")
            } else {
                LayoverFileLogger.logError("GATT CCCD Descriptor 0x2902 not found on RX characteristic.")
            }
        } else {
            LayoverFileLogger.logError("GATT RX characteristic not found for enabling notifications.")
        }
    }

    private fun parseIncomingBytes(data: ByteArray, remoteId: String) {
        synchronized(incomingBuffer) {
            for (b in data) {
                incomingBuffer.add(b)
            }
            while (incomingBuffer.size >= 4) {
                val lenBytes = byteArrayOf(incomingBuffer[0], incomingBuffer[1], incomingBuffer[2], incomingBuffer[3])
                val frameLen = ByteBuffer.wrap(lenBytes).int
                if (frameLen <= 0 || frameLen > 65536) {
                    incomingBuffer.clear()
                    break
                }
                if (incomingBuffer.size >= 4 + frameLen) {
                    val frameBytes = ByteArray(frameLen)
                    for (idx in 0 until frameLen) {
                        frameBytes[idx] = incomingBuffer[4 + idx]
                    }
                    val totalToRemove = 4 + frameLen
                    repeat(totalToRemove) {
                        incomingBuffer.removeAt(0)
                    }
                    val text = String(frameBytes, Charsets.UTF_8)
                    LayoverFileLogger.log("GATT frame parsed successfully: length=$frameLen")
                    scope.launch {
                        _incomingMessages.emit(P2PMessageFrame(senderId = remoteId, text = text))
                    }
                } else {
                    break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendFrame(text: String): Boolean {
        val payload = text.toByteArray(Charsets.UTF_8)
        val fullPacket = ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()

        if (isServerRole) {
            val server = gattServer ?: return false.also { LayoverFileLogger.logError("GATT Server is null when sending frame.") }
            val device = connectedDevice ?: return false.also { LayoverFileLogger.logError("GATT Server connectedDevice is null when sending frame.") }
            val service = server.getService(LAYOVER_GATT_SERVICE_UUID) ?: server.services?.find { it.uuid.equals(LAYOVER_GATT_SERVICE_UUID) }
            val rxChar = service?.getCharacteristic(LAYOVER_RX_CHAR_UUID) ?: service?.characteristics?.find { it.uuid.equals(LAYOVER_RX_CHAR_UUID) } ?: return false.also { LayoverFileLogger.logError("GATT RX characteristic null on server.") }

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, rxChar, false, fullPacket) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                rxChar.value = fullPacket
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, rxChar, false)
            }

            if (success) {
                LayoverFileLogger.log("GATT Server sent notification frame: size=${payload.size}")
            } else {
                LayoverFileLogger.logError("GATT Server failed to send notification frame")
            }
            return success
        } else {
            val client = activeGattClient ?: return false.also { LayoverFileLogger.logError("GATT Client is null when sending frame.") }
            val service = client.services?.find { it.uuid.equals(LAYOVER_GATT_SERVICE_UUID) } ?: client.getService(LAYOVER_GATT_SERVICE_UUID)
            val txChar = service?.characteristics?.find { it.uuid.equals(LAYOVER_TX_CHAR_UUID) } ?: service?.getCharacteristic(LAYOVER_TX_CHAR_UUID) ?: return false.also { LayoverFileLogger.logError("GATT TX characteristic null on client.") }

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                client.writeCharacteristic(txChar, fullPacket, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                txChar.value = fullPacket
                txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                client.writeCharacteristic(txChar)
            }

            if (success) {
                LayoverFileLogger.log("GATT Client wrote TX frame: size=${payload.size}")
            } else {
                LayoverFileLogger.logError("GATT Client failed to write TX frame")
            }
            return success
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            activeGattClient?.disconnect()
            activeGattClient?.close()
        } catch (e: Exception) {
            // Ignore
        }
        activeGattClient = null
        connectedDevice = null
        stopServer()
        _connectionState.value = RfcommConnectionState.DISCONNECTED
    }

    fun cleanup() {
        disconnect()
    }
}
