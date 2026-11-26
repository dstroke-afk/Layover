package com.intj.layover

import android.app.Application
import com.intj.layover.ble.LayoverAppRuntime
import com.intj.layover.notifications.LayoverNotificationManager

class LayoverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LayoverNotificationManager.ensureChannelsCreated(this)
        LayoverAppRuntime.getInstance(this)
    }
}
