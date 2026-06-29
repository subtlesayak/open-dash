package com.example.opendash

import android.app.Application
import com.example.opendash.data.VehicleStore
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import com.example.opendash.media.MediaInfoProvider
import com.example.opendash.media.OpenDashNotificationListener
import com.example.opendash.util.CrashReporter

class OpenDashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)
        VehicleStore.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && MediaInfoProvider.isAccessGranted(this)) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(this, OpenDashNotificationListener::class.java),
                )
            }
        }
    }
}
