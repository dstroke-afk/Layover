package com.intj.layover.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

object LayoverFileLogger {
    private const val TAG = "LayoverBLE"
    private const val MAX_LOG_ENTRIES = 500
    private val logBuffer = ConcurrentLinkedQueue<String>()
    private val throttleMap = ConcurrentHashMap<String, Long>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        Log.d(TAG, message)
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] $message"
        logBuffer.add(entry)
        while (logBuffer.size > MAX_LOG_ENTRIES) {
            logBuffer.poll()
        }
    }

    fun logThrottled(key: String, message: String, windowMs: Long = 3000L) {
        val now = System.currentTimeMillis()
        val lastTime = throttleMap[key] ?: 0L
        if (now - lastTime >= windowMs) {
            throttleMap[key] = now
            log(message)
        }
    }

    fun logError(message: String, throwable: Throwable? = null) {
        val errText = if (throwable != null) "$message | ${throwable.message}" else message
        Log.e(TAG, errText, throwable)
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] ERROR: $errText"
        logBuffer.add(entry)
        while (logBuffer.size > MAX_LOG_ENTRIES) {
            logBuffer.poll()
        }
    }

    fun getLogs(): List<String> = logBuffer.toList()

    fun exportLogToDownloads(context: Context, diagnosticsSnapshot: Phase1Diagnostics? = null): Result<String> {
        return try {
            val fileName = "layover_ble_log.txt"
            val sb = StringBuilder()
            sb.appendLine("=== Layover BLE & P2P Diagnostic Logs ===")
            sb.appendLine("Export Time: ${dateFormat.format(Date())}")
            sb.appendLine("Device Android API: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            if (diagnosticsSnapshot != null) {
                sb.appendLine("--- Diagnostics Snapshot ---")
                sb.appendLine("Discovered Peers: ${diagnosticsSnapshot.discoveredPeersCount}")
                sb.appendLine("RFCOMM State: ${diagnosticsSnapshot.rfcommStateLabel}")
                sb.appendLine("E2EE Handshake: ${if (diagnosticsSnapshot.isKeyHandshakeComplete) "Complete" else "Pending"}")
                sb.appendLine("Fingerprint: ${diagnosticsSnapshot.sessionFingerprint ?: "N/A"}")
                sb.appendLine("Messages Sent: ${diagnosticsSnapshot.messagesSent}")
                sb.appendLine("Messages Received: ${diagnosticsSnapshot.messagesReceived}")
                sb.appendLine("Rate Limited Drops: ${diagnosticsSnapshot.rateLimitedDrops}")
                sb.appendLine("Blocked Drops: ${diagnosticsSnapshot.blockedDrops}")
                sb.appendLine("Last Error: ${diagnosticsSnapshot.lastErrorMessage ?: "None"}")
            }
            sb.appendLine("--- Event Buffer (${logBuffer.size} entries) ---")
            for (entry in logBuffer) {
                sb.appendLine(entry)
            }
            sb.appendLine("=== End of Log ===")

            val content = sb.toString()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return Result.failure(Exception("Failed to create MediaStore entry in Downloads"))

                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray(Charsets.UTF_8))
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                Result.success("Saved $fileName to Downloads")
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val targetFile = File(downloadsDir, fileName)
                FileOutputStream(targetFile).use { fos ->
                    fos.write(content.toByteArray(Charsets.UTF_8))
                }
                Result.success("Saved $fileName to ${targetFile.absolutePath}")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
