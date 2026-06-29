package com.example.opendash.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashStreamQuality(
    val label: String,
    val summary: String,
    val detail: String,
    val movingFps: Int,
    val idleFps: Int,
    val encoderFps: Int,
    val bitrate: Int,
) {
    STABLE(
        label = "Stable",
        summary = "4 fps · 200 kbps",
        detail = "Use this if the dash drops frames or disconnects on weak Wi-Fi.",
        movingFps = 4,
        idleFps = 2,
        encoderFps = 4,
        bitrate = 200_000,
    ),
    SMOOTH(
        label = "Smooth",
        summary = "8 fps · 300 kbps",
        detail = "Balanced motion and heat for most phones.",
        movingFps = 8,
        idleFps = 3,
        encoderFps = 8,
        bitrate = 300_000,
    ),
    EXPERIMENTAL(
        label = "Experimental",
        summary = "12 fps · 350 kbps",
        detail = "Highest test rate based on better-dash findings; use only if stable.",
        movingFps = 12,
        idleFps = 4,
        encoderFps = 12,
        bitrate = 350_000,
    );

    companion object {
        fun fromLabel(label: String): DashStreamQuality? =
            entries.firstOrNull { it.label == label }
    }
}

object DashStreamSettings {
    private const val PREFS = "dash_stream_settings"
    private const val KEY_QUALITY = "quality"

    private lateinit var appContext: Context
    private val _quality = MutableStateFlow(DashStreamQuality.EXPERIMENTAL)
    val quality = _quality.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _quality.value = loadQuality()
    }

    fun current(): DashStreamQuality = _quality.value

    fun setQuality(quality: DashStreamQuality) {
        if (!::appContext.isInitialized) return
        prefs().edit().putString(KEY_QUALITY, quality.name).apply()
        _quality.value = quality
    }

    private fun loadQuality(): DashStreamQuality {
        val stored = prefs().getString(KEY_QUALITY, null)
        return stored?.let { name ->
            runCatching { DashStreamQuality.valueOf(name) }.getOrNull()
        } ?: DashStreamQuality.EXPERIMENTAL
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
