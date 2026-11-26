package com.intj.layover.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.intj.layover.MainActivity
import com.intj.layover.R
import com.intj.layover.ble.BlePermissionHelper
import com.intj.layover.ble.PeerAliasRegistry
import com.intj.layover.diagnostics.LayoverFileLogger

sealed class NotificationDeepLink {
    data class OpenChat(val peerId: String) : NotificationDeepLink()
    data class OpenConnectionRequest(val peerId: String, val displayName: String) : NotificationDeepLink()
}

object LayoverNotificationManager {
    const val CHANNEL_ID_MESSAGES = "layover_messages"
    const val CHANNEL_ID_REQUESTS = "layover_requests"
    const val CHANNEL_ID_SERVICE = "layover_background_service"

    const val NOTIFICATION_ID_SERVICE = 1001
    private const val NOTIFICATION_ID_MESSAGE_BASE = 20_000
    private const val NOTIFICATION_ID_REQUEST_BASE = 60_000

    const val EXTRA_NOTIFICATION_TYPE = "com.intj.layover.extra.NOTIFICATION_TYPE"
    const val EXTRA_PEER_ID = "com.intj.layover.extra.PEER_ID"
    const val EXTRA_PEER_NAME = "com.intj.layover.extra.PEER_NAME"

    const val TYPE_MESSAGE = "message"
    const val TYPE_CONNECTION_REQUEST = "connection_request"

    fun ensureChannelsCreated(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val messagesChannel = NotificationChannel(
            CHANNEL_ID_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming encrypted chat messages from connected peers"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
        }

        val requestsChannel = NotificationChannel(
            CHANNEL_ID_REQUESTS,
            "Connection Requests",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming connection requests from nearby Layover peers"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
        }

        val serviceChannel = NotificationChannel(
            CHANNEL_ID_SERVICE,
            "Nearby Connection Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Layover listening over Bluetooth for incoming messages and connection requests"
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }

        manager.createNotificationChannels(
            listOf(messagesChannel, requestsChannel, serviceChannel)
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return BlePermissionHelper.hasNotificationPermission(context)
    }

    fun parseDeepLink(intent: Intent?): NotificationDeepLink? {
        if (intent == null) return null
        val type = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE)?.trim().orEmpty()
        val peerId = intent.getStringExtra(EXTRA_PEER_ID)?.trim().orEmpty()
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME)?.trim().orEmpty()

        return when (type) {
            TYPE_MESSAGE -> peerId.takeIf { it.isNotEmpty() }?.let { NotificationDeepLink.OpenChat(it) }
            TYPE_CONNECTION_REQUEST -> NotificationDeepLink.OpenConnectionRequest(
                peerId = peerId,
                displayName = peerName.ifEmpty {
                    if (peerId.isNotEmpty()) "Peer (${peerId.takeLast(4)})" else "Nearby Peer"
                }
            )
            else -> null
        }
    }

    fun clearDeepLinkExtras(intent: Intent?) {
        intent?.removeExtra(EXTRA_NOTIFICATION_TYPE)
        intent?.removeExtra(EXTRA_PEER_ID)
        intent?.removeExtra(EXTRA_PEER_NAME)
    }

    private fun canonicalNotificationKey(peerId: String): String {
        val aliases = PeerAliasRegistry.resolveAliases(peerId)
        return (aliases.firstOrNull { it.startsWith("dev_", ignoreCase = true) }
            ?: aliases.firstOrNull()
            ?: peerId.trim().ifEmpty { "default" }).lowercase()
    }

    private fun messageNotificationId(peerId: String): Int {
        return NOTIFICATION_ID_MESSAGE_BASE + (canonicalNotificationKey(peerId).hashCode() and 0x7FFF)
    }

    private fun requestNotificationId(peerId: String): Int {
        return NOTIFICATION_ID_REQUEST_BASE + (canonicalNotificationKey(peerId).hashCode() and 0x7FFF)
    }

    fun buildForegroundServiceNotification(context: Context): Notification {
        ensureChannelsCreated(context)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_SERVICE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_layover)
            .setContentTitle("Layover is active nearby")
            .setContentText("Listening over Bluetooth for messages and connection requests")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun showIncomingMessageNotification(
        context: Context,
        peerId: String,
        senderName: String,
        messageText: String
    ) {
        if (!hasNotificationPermission(context)) {
            LayoverFileLogger.log("Skipping message notification: POST_NOTIFICATIONS not granted.")
            return
        }
        ensureChannelsCreated(context)

        val notificationId = messageNotificationId(peerId)
        val openChatIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.intj.layover.action.OPEN_CHAT_${canonicalNotificationKey(peerId)}"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NOTIFICATION_TYPE, TYPE_MESSAGE)
            putExtra(EXTRA_PEER_ID, peerId)
            putExtra(EXTRA_PEER_NAME, senderName)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = senderName.ifBlank {
            if (peerId.isNotBlank()) "Peer (${peerId.takeLast(4)})" else "Nearby Peer"
        }
        val preview = messageText.ifBlank { "New encrypted message" }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_layover)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            LayoverFileLogger.log("Posted message notification: id=$notificationId, peerId=$peerId, title=$title")
        }.onFailure { err ->
            LayoverFileLogger.logError("Failed to post message notification for peerId=$peerId", err)
        }
    }

    @SuppressLint("MissingPermission")
    fun showConnectionRequestNotification(
        context: Context,
        peerId: String,
        requesterName: String
    ) {
        if (!hasNotificationPermission(context)) {
            LayoverFileLogger.log("Skipping connection request notification: POST_NOTIFICATIONS not granted.")
            return
        }
        ensureChannelsCreated(context)

        val notificationId = requestNotificationId(peerId)
        val resolvedName = requesterName.ifBlank {
            if (peerId.isNotBlank()) "Peer (${peerId.takeLast(4)})" else "Nearby Peer"
        }
        val openRequestIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.intj.layover.action.OPEN_REQUEST_${canonicalNotificationKey(peerId)}"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NOTIFICATION_TYPE, TYPE_CONNECTION_REQUEST)
            putExtra(EXTRA_PEER_ID, peerId)
            putExtra(EXTRA_PEER_NAME, resolvedName)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openRequestIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = "$resolvedName sent a connection request"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_layover)
            .setContentTitle("Connection Request")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            LayoverFileLogger.log("Posted connection request notification: id=$notificationId, peerId=$peerId, requester=$resolvedName")
        }.onFailure { err ->
            LayoverFileLogger.logError("Failed to post connection request notification for peerId=$peerId", err)
        }
    }

    fun cancelMessageNotification(context: Context, peerId: String) {
        if (peerId.isBlank()) return
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(messageNotificationId(peerId))
        PeerAliasRegistry.resolveAliases(peerId).forEach { alias ->
            manager.cancel(NOTIFICATION_ID_MESSAGE_BASE + (alias.lowercase().hashCode() and 0x7FFF))
        }
    }

    fun cancelConnectionRequestNotification(context: Context, peerId: String) {
        if (peerId.isBlank()) return
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(requestNotificationId(peerId))
        PeerAliasRegistry.resolveAliases(peerId).forEach { alias ->
            manager.cancel(NOTIFICATION_ID_REQUEST_BASE + (alias.lowercase().hashCode() and 0x7FFF))
        }
    }
}
