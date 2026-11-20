package com.intj.layover.p2p

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.intj.layover.ble.BlePermissionHelper
import com.intj.layover.diagnostics.LayoverFileLogger
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

enum class RfcommConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

data class P2PMessageFrame(
    val senderId: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class RfcommSessionManager(
    private val context: Context
) {
    companion object {
        val LAYOVER_RFCOMM_UUID: UUID = UUID.fromString("9a51c3e2-4b18-4d7a-8c2f-1e6b9f3a7042")
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(RfcommConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RfcommConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<P2PMessageFrame>()
    val incomingMessages: SharedFlow<P2PMessageFrame> = _incomingMessages.asSharedFlow()

    private var activeServerSocket: BluetoothServerSocket? = null
    private var activeClientSocket: BluetoothSocket? = null
    private var dataOutputStream: DataOutputStream? = null
    private var dataInputStream: DataInputStream? = null
    private var readJob: Job? = null
    private var serverLoopJob: Job? = null

    private fun setConnectionState(newState: RfcommConnectionState, errorDetail: String? = null) {
        _connectionState.value = newState
        val msg = "RFCOMM ConnectionState: ${newState.name}" + (if (errorDetail != null) " - $errorDetail" else "")
        if (newState == RfcommConnectionState.FAILED) {
            LayoverFileLogger.logError(msg)
        } else {
            LayoverFileLogger.log(msg)
        }
    }

    fun startServerLoop() {
        serverLoopJob?.cancel()
        serverLoopJob = scope.launch {
            while (isActive) {
                runServerListenerOnce()
                delay(2000) // Brief delay before restarting listener if closed
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runServerListenerOnce() {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        try {
            LayoverFileLogger.log("RFCOMM Server listener starting on UUID $LAYOVER_RFCOMM_UUID...")
            val serverSocket = adapter.listenUsingRfcommWithServiceRecord("Layover", LAYOVER_RFCOMM_UUID)
            activeServerSocket = serverSocket

            val socket = serverSocket.accept()
            LayoverFileLogger.log("RFCOMM Server accepted incoming socket from device ${socket.remoteDevice?.address}")
            try {
                serverSocket.close()
            } catch (e: Exception) {
                // Ignore
            }
            activeServerSocket = null

            handleConnectedSocket(socket)
        } catch (e: Exception) {
            LayoverFileLogger.logError("RFCOMM Server listener exception: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            setConnectionState(RfcommConnectionState.FAILED, "Bluetooth permissions missing")
            return
        }

        disconnect()
        setConnectionState(RfcommConnectionState.CONNECTING, "Connecting to device MAC ${device.address}")

        scope.launch {
            var connected = false
            try {
                LayoverFileLogger.log("RFCOMM client cancelling discovery before connect...")
                bluetoothAdapter?.cancelDiscovery()
                delay(150)

                LayoverFileLogger.log("RFCOMM client attempting secure connect to ${device.address}...")
                val socket = device.createRfcommSocketToServiceRecord(LAYOVER_RFCOMM_UUID)
                activeClientSocket = socket
                socket.connect()

                LayoverFileLogger.log("RFCOMM Client SECURE socket connected successfully to ${device.address}")
                connected = true
                handleConnectedSocket(socket)
            } catch (e: Exception) {
                LayoverFileLogger.logError("RFCOMM secure connect failed to ${device.address}: ${e.message}. Retrying insecure fallback after 400ms...", e)
                try {
                    activeClientSocket?.close()
                } catch (ex: Exception) {
                    // Ignore
                }
                activeClientSocket = null

                delay(400)

                try {
                    LayoverFileLogger.log("RFCOMM client attempting INSECURE connect fallback to ${device.address}...")
                    val insecureSocket = device.createInsecureRfcommSocketToServiceRecord(LAYOVER_RFCOMM_UUID)
                    activeClientSocket = insecureSocket
                    insecureSocket.connect()

                    LayoverFileLogger.log("RFCOMM Client INSECURE socket connected successfully to ${device.address}")
                    connected = true
                    handleConnectedSocket(insecureSocket)
                } catch (ex: Exception) {
                    LayoverFileLogger.logError("RFCOMM INSECURE connect fallback failed to ${device.address}: ${ex.message}", ex)
                    disconnect()
                    setConnectionState(RfcommConnectionState.FAILED, ex.message)
                }
            }
        }
    }

    private fun handleConnectedSocket(socket: BluetoothSocket) {
        activeClientSocket = socket
        try {
            dataOutputStream = DataOutputStream(socket.outputStream)
            dataInputStream = DataInputStream(socket.inputStream)
            setConnectionState(RfcommConnectionState.CONNECTED)
            LayoverFileLogger.log("RFCOMM Session CONNECTED. Data streams initialized.")

            startReadingLoop(socket.remoteDevice?.address ?: "remote_peer")
        } catch (e: Exception) {
            LayoverFileLogger.logError("Failed to initialize DataStreams on RFCOMM socket", e)
            disconnect()
            setConnectionState(RfcommConnectionState.FAILED, e.message)
        }
    }

    private fun startReadingLoop(remoteAddress: String) {
        readJob?.cancel()
        readJob = scope.launch {
            val dis = dataInputStream ?: return@launch
            try {
                while (isActive && _connectionState.value == RfcommConnectionState.CONNECTED) {
                    val frameLength = dis.readInt()
                    if (frameLength <= 0 || frameLength > 65536) {
                        LayoverFileLogger.logError("Invalid RFCOMM frame length: $frameLength. Terminating loop.")
                        break
                    }

                    val buffer = ByteArray(frameLength)
                    dis.readFully(buffer)

                    val text = String(buffer, Charsets.UTF_8)
                    LayoverFileLogger.log("RFCOMM frame received: length=$frameLength")
                    _incomingMessages.emit(
                        P2PMessageFrame(
                            senderId = remoteAddress,
                            text = text
                        )
                    )
                }
            } catch (e: IOException) {
                LayoverFileLogger.log("RFCOMM reading loop disconnected (IOException): ${e.message}")
            } finally {
                disconnect()
            }
        }
    }

    fun sendFrame(text: String): Boolean {
        val dos = dataOutputStream ?: return false
        return try {
            val payload = text.toByteArray(Charsets.UTF_8)
            dos.writeInt(payload.size)
            dos.write(payload)
            dos.flush()
            LayoverFileLogger.log("RFCOMM frame sent successfully: size=${payload.size}")
            true
        } catch (e: Exception) {
            LayoverFileLogger.logError("RFCOMM sendFrame failed", e)
            disconnect()
            false
        }
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null

        try {
            dataOutputStream?.close()
        } catch (e: Exception) {
            // Ignore
        }
        dataOutputStream = null

        try {
            dataInputStream?.close()
        } catch (e: Exception) {
            // Ignore
        }
        dataInputStream = null

        try {
            activeClientSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        activeClientSocket = null

        try {
            activeServerSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        activeServerSocket = null

        _connectionState.value = RfcommConnectionState.DISCONNECTED
    }

    fun cleanup() {
        serverLoopJob?.cancel()
        disconnect()
    }
}
