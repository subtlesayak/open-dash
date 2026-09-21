package com.example.opendash

import android.app.Application
import com.example.opendash.data.VehicleStore
import com.example.opendash.util.CrashReporter

class OpenDashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)
        VehicleStore.init(this)
    }
}
