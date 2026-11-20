package com.intj.layover.p2p

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.intj.layover.ble.BlePermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            _connectionState.value = RfcommConnectionState.FAILED
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = RfcommConnectionState.FAILED
            return
        }

        disconnect()
        _connectionState.value = RfcommConnectionState.CONNECTING

        scope.launch {
            try {
                val serverSocket = adapter.listenUsingRfcommWithServiceRecord("Layover", LAYOVER_RFCOMM_UUID)
                activeServerSocket = serverSocket

                val socket = serverSocket.accept()
                serverSocket.close()
                activeServerSocket = null

                handleConnectedSocket(socket)
            } catch (e: Exception) {
                if (_connectionState.value == RfcommConnectionState.CONNECTING) {
                    _connectionState.value = RfcommConnectionState.FAILED
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!BlePermissionHelper.hasAllRequiredPermissions(context)) {
            _connectionState.value = RfcommConnectionState.FAILED
            return
        }

        disconnect()
        _connectionState.value = RfcommConnectionState.CONNECTING

        scope.launch {
            try {
                val socket = device.createRfcommSocketToServiceRecord(LAYOVER_RFCOMM_UUID)
                activeClientSocket = socket
                socket.connect()
                handleConnectedSocket(socket)
            } catch (e: Exception) {
                disconnect()
                _connectionState.value = RfcommConnectionState.FAILED
            }
        }
    }

    private fun handleConnectedSocket(socket: BluetoothSocket) {
        activeClientSocket = socket
        try {
            dataOutputStream = DataOutputStream(socket.outputStream)
            dataInputStream = DataInputStream(socket.inputStream)
            _connectionState.value = RfcommConnectionState.CONNECTED

            startReadingLoop(socket.remoteDevice?.address ?: "remote_peer")
        } catch (e: Exception) {
            disconnect()
            _connectionState.value = RfcommConnectionState.FAILED
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
                        break
                    }

                    val buffer = ByteArray(frameLength)
                    dis.readFully(buffer)

                    val text = String(buffer, Charsets.UTF_8)
                    _incomingMessages.emit(
                        P2PMessageFrame(
                            senderId = remoteAddress,
                            text = text
                        )
                    )
                }
            } catch (e: IOException) {
                // Connection broken
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
            true
        } catch (e: Exception) {
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
}
