package com.intj.layover.wifi

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey

/**
 * Temporary direct 1-on-1 TCP socket server hosted over a LocalOnlyHotspot:
 * - Primary path (Sender-as-AP): accepts the Receiver socket, streams encrypted photo chunks,
 *   and waits for the receiver's completion ACK before closing the socket/hotspot.
 * - Optional 1-on-1 role-inversion fallback (Receiver-as-AP): accepts the Sender socket,
 *   receives/decrypts/verifies the photo stream, and sends the completion ACK.
 *
 * Always closes both the peer socket and backing [ServerSocket] immediately on completion,
 * error, cancellation, or timeout.
 */
class DirectPhotoSocketServer(
    bindAddress: InetAddress? = null,
    private val acceptTimeoutMs: Int = PhotoTransferConfig.SOCKET_ACCEPT_TIMEOUT_MS.toInt(),
    private val socketReadTimeoutMs: Int = PhotoTransferConfig.SOCKET_READ_TIMEOUT_MS.toInt()
) : Closeable {

    private val closed = AtomicBoolean(false)
    private val activePeerSocket = AtomicReference<Socket?>(null)

    private val serverSocket: ServerSocket = ServerSocket().apply {
        reuseAddress = true
        if (bindAddress != null) {
            bind(InetSocketAddress(bindAddress, 0), 2)
        } else {
            bind(InetSocketAddress(0), 2)
        }
    }

    val localPort: Int
        get() = serverSocket.localPort

    val isClosed: Boolean
        get() = closed.get() || serverSocket.isClosed

    @Throws(IOException::class)
    fun serveAsSenderHost(
        transferId: String,
        plaintextBytes: ByteArray,
        transferKey: SecretKey,
        onProgress: (Float) -> Unit = {}
    ) {
        serverSocket.soTimeout = acceptTimeoutMs
        var peerSocket: Socket? = null
        try {
            peerSocket = serverSocket.accept()
            activePeerSocket.set(peerSocket)
            peerSocket.soTimeout = socketReadTimeoutMs
            peerSocket.tcpNoDelay = true

            val handshake = EncryptedPhotoStreamProtocol.readRoleHandshake(peerSocket.getInputStream())
            if (handshake.role != EncryptedPhotoStreamProtocol.SocketRole.RECEIVER ||
                handshake.transferId != transferId
            ) {
                throw IOException("Unexpected peer handshake: $handshake (expected RECEIVER/$transferId)")
            }

            EncryptedPhotoStreamProtocol.writeReadyAck(peerSocket.getOutputStream())
            EncryptedPhotoStreamProtocol.writeEncryptedPhotoStream(
                plaintextBytes = plaintextBytes,
                transferId = transferId,
                key = transferKey,
                outputStream = peerSocket.getOutputStream(),
                onProgress = onProgress
            )
            // Keep socket and LocalOnlyHotspot alive until receiver finishes draining TCP buffers
            // and verifies SHA-256 (preventing ECONNABORTED on receiver).
            peerSocket.soTimeout = 10_000
            EncryptedPhotoStreamProtocol.awaitTransferCompleteAck(peerSocket.getInputStream())
        } finally {
            closePeerSocket(peerSocket)
            close()
        }
    }

    @Throws(IOException::class)
    fun serveAsReceiverHost(
        transferId: String,
        expectedPlaintextSize: Long,
        expectedSha256Hex: String?,
        transferKey: SecretKey,
        onProgress: (Float) -> Unit = {}
    ): ByteArray {
        serverSocket.soTimeout = acceptTimeoutMs
        var peerSocket: Socket? = null
        try {
            peerSocket = serverSocket.accept()
            activePeerSocket.set(peerSocket)
            peerSocket.soTimeout = socketReadTimeoutMs
            peerSocket.tcpNoDelay = true

            val handshake = EncryptedPhotoStreamProtocol.readRoleHandshake(peerSocket.getInputStream())
            if (handshake.role != EncryptedPhotoStreamProtocol.SocketRole.SENDER ||
                handshake.transferId != transferId
            ) {
                throw IOException("Unexpected peer handshake: $handshake (expected SENDER/$transferId)")
            }

            EncryptedPhotoStreamProtocol.writeReadyAck(peerSocket.getOutputStream())
            val decrypted = EncryptedPhotoStreamProtocol.readEncryptedPhotoStream(
                expectedPlaintextSize = expectedPlaintextSize,
                expectedSha256Hex = expectedSha256Hex,
                transferId = transferId,
                key = transferKey,
                inputStream = peerSocket.getInputStream(),
                onProgress = onProgress
            )
            runCatching {
                EncryptedPhotoStreamProtocol.writeTransferCompleteAck(peerSocket.getOutputStream())
            }
            return decrypted
        } finally {
            closePeerSocket(peerSocket)
            close()
        }
    }

    private fun closePeerSocket(socket: Socket?) {
        if (socket != null) {
            activePeerSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activePeerSocket.getAndSet(null)?.let { runCatching { it.close() } }
        runCatching { serverSocket.close() }
    }
}
