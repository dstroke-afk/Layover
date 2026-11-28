package com.intj.layover.wifi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Binary framing, E2EE BLE preview bundle encoding, and chunked AES-256-GCM socket streaming
 * for direct Sender-as-AP (and 1-on-1 Receiver-as-AP fallback) LocalOnlyHotspot photo transfers.
 */
object EncryptedPhotoStreamProtocol {
    private val MAGIC = byteArrayOf('L'.code.toByte(), 'Y'.code.toByte(), 'V'.code.toByte(), 'R'.code.toByte())
    private const val PREVIEW_BUNDLE_VERSION: Byte = 1
    const val READY_ACK_BYTE: Int = 0xAC
    const val TRANSFER_COMPLETE_ACK_BYTE: Int = 0xDA
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_TRANSFER_ID_BYTES = 128
    private const val MAX_ENCRYPTED_FRAME_BYTES = PhotoTransferConfig.STREAM_CHUNK_SIZE_BYTES + 128

    private val secureRandom = SecureRandom()

    enum class SocketRole(val wireCode: Byte) {
        SENDER(1),
        RECEIVER(2);

        companion object {
            fun fromWireCode(code: Byte): SocketRole? = entries.firstOrNull { it.wireCode == code }
        }
    }

    data class RoleHandshake(
        val role: SocketRole,
        val transferId: String
    )

    data class DecodedPreviewBundle(
        val fileSizeBytes: Long,
        val mimeType: String,
        val sha256Hex: String,
        val transferKey: SecretKey,
        val previewBytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DecodedPreviewBundle
            return fileSizeBytes == other.fileSizeBytes &&
                mimeType == other.mimeType &&
                sha256Hex == other.sha256Hex &&
                transferKey.encoded.contentEquals(other.transferKey.encoded) &&
                previewBytes.contentEquals(other.previewBytes)
        }

        override fun hashCode(): Int {
            var result = fileSizeBytes.hashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + sha256Hex.hashCode()
            result = 31 * result + transferKey.encoded.contentHashCode()
            result = 31 * result + previewBytes.contentHashCode()
            return result
        }
    }

    fun generateTransferKey(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256, secureRandom)
        return kg.generateKey()
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Packs photo metadata, per-transfer AES-256 key, SHA-256 digest, and compact JPEG thumbnail
     * into a binary payload ready to be encrypted end-to-end inside a BLE `PHOTO_PREVIEW` frame.
     */
    fun encodePreviewBundle(
        fileSizeBytes: Long,
        mimeType: String,
        sha256Hex: String,
        transferKey: SecretKey,
        previewBytes: ByteArray
    ): ByteArray {
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        val shaBytes = sha256Hex.toByteArray(Charsets.UTF_8)
        val keyBytes = transferKey.encoded
        val out = ByteArrayOutputStream(64 + mimeBytes.size + shaBytes.size + keyBytes.size + previewBytes.size)
        val dos = DataOutputStream(out)
        dos.writeByte(PREVIEW_BUNDLE_VERSION.toInt())
        dos.writeLong(fileSizeBytes)
        dos.writeShort(mimeBytes.size)
        dos.write(mimeBytes)
        dos.writeShort(shaBytes.size)
        dos.write(shaBytes)
        dos.writeShort(keyBytes.size)
        dos.write(keyBytes)
        dos.writeInt(previewBytes.size)
        dos.write(previewBytes)
        dos.flush()
        return out.toByteArray()
    }

    fun decodePreviewBundle(bundleBytes: ByteArray): DecodedPreviewBundle? {
        return runCatching {
            val dis = DataInputStream(ByteArrayInputStream(bundleBytes))
            val version = dis.readByte()
            if (version != PREVIEW_BUNDLE_VERSION) return null
            val fileSize = dis.readLong()
            val mimeLen = dis.readUnsignedShort()
            if (mimeLen > 128) return null
            val mimeBytes = ByteArray(mimeLen)
            dis.readFully(mimeBytes)
            val shaLen = dis.readUnsignedShort()
            if (shaLen > 128) return null
            val shaBytes = ByteArray(shaLen)
            dis.readFully(shaBytes)
            val keyLen = dis.readUnsignedShort()
            if (keyLen != 32) return null
            val keyBytes = ByteArray(keyLen)
            dis.readFully(keyBytes)
            val previewLen = dis.readInt()
            if (previewLen < 0 || previewLen > PhotoTransferConfig.PREVIEW_MAX_TARGET_BYTES * 2) return null
            val previewBytes = ByteArray(previewLen)
            dis.readFully(previewBytes)
            DecodedPreviewBundle(
                fileSizeBytes = fileSize,
                mimeType = String(mimeBytes, Charsets.UTF_8).ifBlank { "image/jpeg" },
                sha256Hex = String(shaBytes, Charsets.UTF_8),
                transferKey = SecretKeySpec(keyBytes, "AES"),
                previewBytes = previewBytes
            )
        }.getOrNull()
    }

    @Throws(IOException::class)
    fun writeRoleHandshake(outputStream: OutputStream, role: SocketRole, transferId: String) {
        val idBytes = transferId.toByteArray(Charsets.UTF_8)
        if (idBytes.isEmpty() || idBytes.size > MAX_TRANSFER_ID_BYTES) {
            throw IOException("Invalid transferId length: ${idBytes.size}")
        }
        val dos = DataOutputStream(outputStream)
        dos.write(MAGIC)
        dos.writeByte(role.wireCode.toInt())
        dos.writeShort(idBytes.size)
        dos.write(idBytes)
        dos.flush()
    }

    @Throws(IOException::class)
    fun readRoleHandshake(inputStream: InputStream): RoleHandshake {
        val dis = DataInputStream(inputStream)
        val magicBuf = ByteArray(MAGIC.size)
        dis.readFully(magicBuf)
        if (!magicBuf.contentEquals(MAGIC)) {
            throw IOException("Invalid Layover photo socket magic header")
        }
        val roleCode = dis.readByte()
        val role = SocketRole.fromWireCode(roleCode)
            ?: throw IOException("Unknown socket role code: $roleCode")
        val idLen = dis.readUnsignedShort()
        if (idLen == 0 || idLen > MAX_TRANSFER_ID_BYTES) {
            throw IOException("Invalid transferId length in handshake: $idLen")
        }
        val idBytes = ByteArray(idLen)
        dis.readFully(idBytes)
        return RoleHandshake(role = role, transferId = String(idBytes, Charsets.UTF_8))
    }

    @Throws(IOException::class)
    fun writeReadyAck(outputStream: OutputStream) {
        outputStream.write(READY_ACK_BYTE)
        outputStream.flush()
    }

    @Throws(IOException::class)
    fun readReadyAck(inputStream: InputStream) {
        val ack = inputStream.read()
        if (ack != READY_ACK_BYTE) {
            throw IOException("Expected ready ACK ($READY_ACK_BYTE), got $ack")
        }
    }

    /**
     * Written by the receiver after decrypting and verifying SHA-256 of the entire stream so
     * the sender keeps the TCP socket and LocalOnlyHotspot alive until every byte is drained.
     */
    @Throws(IOException::class)
    fun writeTransferCompleteAck(outputStream: OutputStream) {
        outputStream.write(TRANSFER_COMPLETE_ACK_BYTE)
        outputStream.flush()
    }

    /**
     * Waits for the receiver's [TRANSFER_COMPLETE_ACK_BYTE] after [writeEncryptedPhotoStream]
     * flushes the EOF terminator. Returns true if the explicit ACK was received.
     */
    fun awaitTransferCompleteAck(inputStream: InputStream): Boolean {
        return runCatching {
            inputStream.read() == TRANSFER_COMPLETE_ACK_BYTE
        }.getOrDefault(false)
    }

    private fun buildChunkAad(transferId: String, chunkIndex: Int, isFinal: Boolean): ByteArray {
        return "$transferId|$chunkIndex|${if (isFinal) 1 else 0}".toByteArray(Charsets.UTF_8)
    }

    /**
     * Encrypts [plaintextBytes] into authenticated AES-256-GCM chunks and streams them to [outputStream].
     * Enforces [PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES] before transmitting.
     */
    @Throws(IOException::class)
    fun writeEncryptedPhotoStream(
        plaintextBytes: ByteArray,
        transferId: String,
        key: SecretKey,
        outputStream: OutputStream,
        chunkSizeBytes: Int = PhotoTransferConfig.STREAM_CHUNK_SIZE_BYTES,
        onProgress: (Float) -> Unit = {}
    ) {
        if (plaintextBytes.isEmpty()) {
            throw IOException("Cannot stream empty photo payload")
        }
        if (plaintextBytes.size.toLong() > PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES) {
            throw IOException(PhotoTransferConfig.PHOTO_EXCEEDS_LIMIT_MESSAGE)
        }

        val safeChunkSize = chunkSizeBytes.coerceIn(4096, PhotoTransferConfig.STREAM_CHUNK_SIZE_BYTES)
        val dos = DataOutputStream(outputStream)
        var offset = 0
        var chunkIndex = 0
        val total = plaintextBytes.size

        while (offset < total) {
            val end = min(total, offset + safeChunkSize)
            val isFinal = end >= total
            val chunkPlain = plaintextBytes.copyOfRange(offset, end)

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(buildChunkAad(transferId, chunkIndex, isFinal))
            val encryptedChunk = cipher.doFinal(chunkPlain)

            val frameBodyLen = iv.size + encryptedChunk.size
            dos.writeInt(frameBodyLen)
            dos.write(iv)
            dos.write(encryptedChunk)

            offset = end
            chunkIndex++
            onProgress((offset.toFloat() / total.toFloat()).coerceIn(0f, 1f))
        }

        dos.writeInt(0)
        dos.flush()
    }

    /**
     * Reads authenticated AES-256-GCM chunks from [inputStream], verifying every chunk's GCM tag,
     * sequence index, final-chunk flag, 3 MB maximum cap, exact byte size, and SHA-256 integrity.
     */
    @Throws(IOException::class)
    fun readEncryptedPhotoStream(
        expectedPlaintextSize: Long,
        expectedSha256Hex: String?,
        transferId: String,
        key: SecretKey,
        inputStream: InputStream,
        onProgress: (Float) -> Unit = {}
    ): ByteArray {
        if (expectedPlaintextSize > PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES) {
            throw IOException(PhotoTransferConfig.PHOTO_EXCEEDS_LIMIT_MESSAGE)
        }

        val dis = DataInputStream(inputStream)
        val initialCapacity = expectedPlaintextSize.coerceIn(1024L, PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES).toInt()
        val plaintextOut = ByteArrayOutputStream(initialCapacity)

        var currentFrameLen = try {
            dis.readInt()
        } catch (e: EOFException) {
            throw IOException("Encrypted photo stream closed unexpectedly before first frame", e)
        }

        var chunkIndex = 0
        var totalPlaintextBytes = 0L

        while (currentFrameLen > 0) {
            if (currentFrameLen < GCM_IV_LENGTH_BYTES + 16 || currentFrameLen > MAX_ENCRYPTED_FRAME_BYTES) {
                throw IOException("Invalid encrypted frame size: $currentFrameLen")
            }

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            dis.readFully(iv)
            val cipherBytes = ByteArray(currentFrameLen - GCM_IV_LENGTH_BYTES)
            dis.readFully(cipherBytes)

            val nextFrameLen = try {
                dis.readInt()
            } catch (e: EOFException) {
                throw IOException("Encrypted photo stream truncated before EOF terminator", e)
            }
            val isFinal = nextFrameLen == 0

            val decrypted = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(buildChunkAad(transferId, chunkIndex, isFinal))
                cipher.doFinal(cipherBytes)
            } catch (e: Exception) {
                throw IOException("AES-GCM authentication failed on chunk $chunkIndex", e)
            }

            totalPlaintextBytes += decrypted.size
            if (totalPlaintextBytes > PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES) {
                throw IOException(PhotoTransferConfig.PHOTO_EXCEEDS_LIMIT_MESSAGE)
            }
            plaintextOut.write(decrypted)

            if (expectedPlaintextSize > 0L) {
                onProgress((totalPlaintextBytes.toFloat() / expectedPlaintextSize.toFloat()).coerceIn(0f, 1f))
            }

            chunkIndex++
            currentFrameLen = nextFrameLen
        }

        if (currentFrameLen < 0) {
            throw IOException("Malformed negative frame length: $currentFrameLen")
        }

        val fullPlaintext = plaintextOut.toByteArray()
        if (fullPlaintext.isEmpty()) {
            throw IOException("Received empty photo stream")
        }
        if (expectedPlaintextSize > 0L && fullPlaintext.size.toLong() != expectedPlaintextSize) {
            throw IOException(
                "Received photo size (${fullPlaintext.size}) does not match expected ($expectedPlaintextSize)"
            )
        }
        if (!expectedSha256Hex.isNullOrBlank()) {
            val actualHash = sha256Hex(fullPlaintext)
            if (!actualHash.equals(expectedSha256Hex.trim(), ignoreCase = true)) {
                throw IOException("SHA-256 integrity mismatch on decrypted photo payload")
            }
        }
        onProgress(1f)
        return fullPlaintext
    }
}
