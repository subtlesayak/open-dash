package com.example.opendash.util

import android.util.Log
import com.example.opendash.BuildConfig

object DebugLog {
    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) runCatching { Log.d(tag, message()) }
    }

    fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) runCatching { Log.i(tag, message()) }
    }

    fun w(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) runCatching { Log.w(tag, message()) }
    }

    fun e(tag: String, message: () -> String, error: Throwable? = null) {
        if (BuildConfig.DEBUG) runCatching {
            if (error == null) Log.e(tag, message()) else Log.e(tag, message(), error)
        }
    }
}
