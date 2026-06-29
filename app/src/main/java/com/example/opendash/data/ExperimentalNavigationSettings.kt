package com.example.opendash.data

import android.content.Context
import com.example.opendash.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ExperimentalNavigationSettings {
    private const val PREFS = "experimental_navigation"
    private const val KEY_MAPBOX_NAVIGATION = "mapbox_navigation_enabled"

    private lateinit var appContext: Context
    private val _mapboxNavigationEnabled = MutableStateFlow(false)
    val mapboxNavigationEnabled = _mapboxNavigationEnabled.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _mapboxNavigationEnabled.value = BuildConfig.USE_MAPBOX_NAVIGATION_EXPERIMENTAL &&
            prefs().getBoolean(KEY_MAPBOX_NAVIGATION, false)
    }

    fun setMapboxNavigationEnabled(enabled: Boolean) {
        if (!BuildConfig.USE_MAPBOX_NAVIGATION_EXPERIMENTAL) return
        prefs().edit().putBoolean(KEY_MAPBOX_NAVIGATION, enabled).apply()
        _mapboxNavigationEnabled.value = enabled
    }

    fun isMapboxNavigationEnabled(): Boolean =
        BuildConfig.USE_MAPBOX_NAVIGATION_EXPERIMENTAL &&
            _mapboxNavigationEnabled.value &&
            BuildConfig.MAPBOX_ACCESS_TOKEN.isNotBlank()

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
