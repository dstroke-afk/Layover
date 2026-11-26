package com.intj.layover.ble

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.intj.layover.diagnostics.LayoverFileLogger
import com.intj.layover.notifications.LayoverNotificationManager

/**
 * Foreground service (`connectedDevice`) that keeps Layover's BLE GATT server,
 * discovery, and advertising alive when the app is minimized or swiped away,
 * provided initial setup is complete and Bluetooth is enabled.
 */
class BleProximityService : Service() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        fun syncServiceState(context: Context) {
            val appContext = context.applicationContext
            val runtime = LayoverAppRuntime.getInstance(appContext)
            if (runtime.isBackgroundListeningEligible()) {
                start(appContext)
            } else {
                stop(appContext)
            }
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            val runtime = LayoverAppRuntime.getInstance(appContext)
            if (!runtime.isBackgroundListeningEligible()) {
                LayoverFileLogger.log("BleProximityService.start skipped: not eligible (setup/BT/BLE perms/scanning).")
                return
            }
            val intent = Intent(appContext, BleProximityService::class.java)
            runCatching {
                ContextCompat.startForegroundService(appContext, intent)
                LayoverFileLogger.log("BleProximityService startForegroundService requested.")
            }.onFailure { err ->
                LayoverFileLogger.logError("Failed to start BleProximityService", err)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(Intent(appContext, BleProximityService::class.java))
            }.onFailure { err ->
                LayoverFileLogger.logError("Failed to stop BleProximityService", err)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        LayoverNotificationManager.ensureChannelsCreated(this)
        LayoverAppRuntime.getInstance(applicationContext)
        LayoverFileLogger.log("BleProximityService.onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = LayoverAppRuntime.getInstance(applicationContext)

        if (!BlePermissionHelper.hasAllRequiredPermissions(this)) {
            LayoverFileLogger.logError("BleProximityService.onStartCommand: missing BLE permissions; stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = LayoverNotificationManager.buildForegroundServiceNotification(this)
        val startedForeground = runCatching {
            startForeground(
                LayoverNotificationManager.NOTIFICATION_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            true
        }.onFailure { err ->
            LayoverFileLogger.logError("BleProximityService failed to enter foreground state", err)
        }.getOrDefault(false)

        if (!startedForeground) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!runtime.isBackgroundListeningEligible()) {
            LayoverFileLogger.log("BleProximityService.onStartCommand: prerequisites not met (setup or Bluetooth off); stopping.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        runtime.startDiscoveryIfEligible()
        LayoverFileLogger.log("BleProximityService active in foreground (START_STICKY).")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val runtime = LayoverAppRuntime.getInstance(applicationContext)
        runtime.setAppInForeground(false)
        if (runtime.isBackgroundListeningEligible()) {
            LayoverFileLogger.log("BleProximityService.onTaskRemoved: app swiped away; keeping BLE service & GATT alive.")
            runtime.startDiscoveryIfEligible()
        } else {
            LayoverFileLogger.log("BleProximityService.onTaskRemoved: not eligible for background listening; stopping.")
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        LayoverFileLogger.log("BleProximityService.onDestroy")
        val runtime = LayoverAppRuntime.getInstance(applicationContext)
        if (!runtime.isAppInForeground.value || !runtime.isBluetoothEnabled()) {
            runtime.bleTransport.stopDiscovery()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
