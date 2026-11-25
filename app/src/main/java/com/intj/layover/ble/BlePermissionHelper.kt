package com.intj.layover.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BlePermissionHelper {

    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }

    fun getWifiHotspotPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }

    fun getAllLaunchPermissions(): Array<String> {
        return (getRequiredPermissions().toList() + getWifiHotspotPermissions().toList())
            .distinct()
            .toTypedArray()
    }

    fun hasAllRequiredPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasWifiHotspotPermissions(context: Context): Boolean {
        return getWifiHotspotPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasAllLaunchPermissions(context: Context): Boolean {
        return hasAllRequiredPermissions(context) && hasWifiHotspotPermissions(context)
    }
}
