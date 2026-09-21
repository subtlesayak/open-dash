package com.example.opendash.data

import android.content.Context
import com.example.opendash.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object MapboxNavigationSettings {
    private const val PREFS = "mapbox_navigation"
    private const val LEGACY_PREFS = "experimental_navigation"
    private const val KEY_MAPBOX_NAVIGATION = "mapbox_navigation_enabled"

    private lateinit var appContext: Context
    private val _mapboxNavigationEnabled = MutableStateFlow(false)
    val mapboxNavigationEnabled = _mapboxNavigationEnabled.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _mapboxNavigationEnabled.value = BuildConfig.USE_MAPBOX_NAVIGATION &&
            prefs().getBoolean(
                KEY_MAPBOX_NAVIGATION,
                legacyPrefs().getBoolean(KEY_MAPBOX_NAVIGATION, true),
            )
    }

    fun setMapboxNavigationEnabled(enabled: Boolean) {
        if (!BuildConfig.USE_MAPBOX_NAVIGATION) return
        prefs().edit().putBoolean(KEY_MAPBOX_NAVIGATION, enabled).apply()
        _mapboxNavigationEnabled.value = enabled
    }

    fun isMapboxNavigationEnabled(): Boolean =
        BuildConfig.USE_MAPBOX_NAVIGATION &&
            _mapboxNavigationEnabled.value &&
            BuildConfig.MAPBOX_ACCESS_TOKEN.isNotBlank()

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun legacyPrefs() = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
}
