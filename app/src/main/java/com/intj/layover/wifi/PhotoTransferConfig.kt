package com.intj.layover.wifi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared constants, 3 MB photo size validation, and compact ~120px (8–15 KB) BLE preview
 * generation for Layover's direct Sender-as-AP LocalOnlyHotspot photo transfer pipeline.
 */
object PhotoTransferConfig {
    const val MAX_PHOTO_SIZE_BYTES: Long = 3L * 1024L * 1024L // 3,145,728 bytes
    const val PREVIEW_MAX_EDGE_PX: Int = 120
    const val PREVIEW_MAX_TARGET_BYTES: Int = 15 * 1024
    const val AP_START_TIMEOUT_MS: Long = 15_000L
    const val WIFI_NETWORK_CONNECT_TIMEOUT_MS: Long = 45_000L
    const val SOCKET_CONNECT_TIMEOUT_MS: Long = 15_000L
    const val SOCKET_ACCEPT_TIMEOUT_MS: Long = 55_000L
    const val SOCKET_READ_TIMEOUT_MS: Long = 30_000L
    const val STREAM_CHUNK_SIZE_BYTES: Int = 32 * 1024
    const val PHOTO_EXCEEDS_LIMIT_MESSAGE: String = "Photo exceeds 3 MB limit"
}

sealed class PhotoSizeValidationResult {
    data class Valid(val sizeBytes: Long) : PhotoSizeValidationResult()
    data object Empty : PhotoSizeValidationResult()
    data class ExceedsLimit(
        val sizeBytes: Long,
        val maxBytes: Long = PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES,
        val errorMessage: String = PhotoTransferConfig.PHOTO_EXCEEDS_LIMIT_MESSAGE
    ) : PhotoSizeValidationResult()
}

data class LoadedPhotoPayload(
    val bytes: ByteArray,
    val mimeType: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LoadedPhotoPayload
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

sealed class PhotoLoadResult {
    data class Success(val payload: LoadedPhotoPayload) : PhotoLoadResult()
    data class Rejected(val message: String, val sizeBytes: Long? = null) : PhotoLoadResult()
}

object PhotoSizeValidator {

    fun validateSize(
        sizeBytes: Long,
        maxBytes: Long = PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES
    ): PhotoSizeValidationResult {
        return when {
            sizeBytes <= 0L -> PhotoSizeValidationResult.Empty
            sizeBytes > maxBytes -> PhotoSizeValidationResult.ExceedsLimit(
                sizeBytes = sizeBytes,
                maxBytes = maxBytes,
                errorMessage = PhotoTransferConfig.PHOTO_EXCEEDS_LIMIT_MESSAGE
            )
            else -> PhotoSizeValidationResult.Valid(sizeBytes)
        }
    }

    /**
     * Reads up to [maxBytes] from [inputStream] without lossy recompression.
     * Returns null immediately if the stream contains more than [maxBytes] bytes.
     */
    fun readBytesWithCap(
        inputStream: InputStream,
        maxBytes: Long = PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                return null
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /**
     * Resolves and validates a picked image [Uri] against the 3 MB cap BEFORE generating
     * the BLE preview or initiating transfer.
     */
    fun loadAndValidatePhotoUri(
        context: Context,
        uri: Uri,
        maxBytes: Long = PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES
    ): PhotoLoadResult {
        val resolver = context.contentResolver

        val declaredSize = queryDeclaredSizeBytes(context, uri)
        if (declaredSize != null && declaredSize > 0L) {
            when (val validation = validateSize(declaredSize, maxBytes)) {
                is PhotoSizeValidationResult.ExceedsLimit -> {
                    return PhotoLoadResult.Rejected(
                        message = validation.errorMessage,
                        sizeBytes = declaredSize
                    )
                }
                else -> Unit
            }
        }

        val bytes = try {
            resolver.openInputStream(uri)?.use { stream ->
                readBytesWithCap(stream, maxBytes)
            }
        } catch (_: Exception) {
            return PhotoLoadResult.Rejected("Unable to read selected photo")
        } ?: return PhotoLoadResult.Rejected(
            message = PhotoTransferConfig.PHOTO_EXCEEDS_LIMIT_MESSAGE,
            sizeBytes = declaredSize
        )

        return when (val finalCheck = validateSize(bytes.size.toLong(), maxBytes)) {
            is PhotoSizeValidationResult.Valid -> {
                val mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "image/jpeg"
                PhotoLoadResult.Success(LoadedPhotoPayload(bytes = bytes, mimeType = mimeType))
            }
            is PhotoSizeValidationResult.Empty -> {
                PhotoLoadResult.Rejected("Selected photo is empty")
            }
            is PhotoSizeValidationResult.ExceedsLimit -> {
                PhotoLoadResult.Rejected(finalCheck.errorMessage, finalCheck.sizeBytes)
            }
        }
    }

    private fun queryDeclaredSizeBytes(context: Context, uri: Uri): Long? {
        val resolver = context.contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val size = cursor.getLong(idx)
                        if (size > 0L) return size
                    }
                }
            }
        }
        return runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.takeIf { it > 0L }
            }
        }.getOrNull()
    }
}

/**
 * Generates a compact ~120px max-dimension JPEG thumbnail (~8–15 KB) suitable for instant
 * encrypted BLE preview delivery before the recipient taps Download.
 */
object PhotoPreviewGenerator {
    private const val MIN_EDGE_PX = 80

    fun generateCompactPreview(
        originalBytes: ByteArray,
        maxEdgePx: Int = PhotoTransferConfig.PREVIEW_MAX_EDGE_PX,
        maxBytes: Int = PhotoTransferConfig.PREVIEW_MAX_TARGET_BYTES
    ): ByteArray? {
        if (originalBytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            val longestSide = max(bounds.outWidth, bounds.outHeight)
            while (longestSide / (sampleSize * 2) >= maxEdgePx) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOpts)
                ?: return null

            try {
                var targetEdge = maxEdgePx
                var bestBytes: ByteArray? = null
                while (targetEdge >= MIN_EDGE_PX) {
                    val scaled = scaleBitmapDown(decoded, targetEdge)
                    try {
                        for (quality in listOf(82, 70, 58, 45, 32)) {
                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                            val candidate = out.toByteArray()
                            bestBytes = candidate
                            if (candidate.size <= maxBytes) {
                                return candidate
                            }
                        }
                    } finally {
                        if (scaled !== decoded) {
                            scaled.recycle()
                        }
                    }
                    targetEdge = (targetEdge * 0.8f).roundToInt()
                }
                bestBytes?.takeIf { it.size <= maxBytes }
            } finally {
                decoded.recycle()
            }
        }.getOrNull()
    }

    private fun scaleBitmapDown(source: Bitmap, maxEdgePx: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxEdgePx) return source
        val scale = maxEdgePx.toFloat() / longest.toFloat()
        val targetW = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    fun formatByteSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
