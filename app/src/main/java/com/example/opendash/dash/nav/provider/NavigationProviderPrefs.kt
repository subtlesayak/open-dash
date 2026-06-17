package com.example.opendash.dash.nav.provider

import android.content.Context

class NavigationProviderPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var experimentalGoogleNavEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXPERIMENTAL_GOOGLE_NAV, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPERIMENTAL_GOOGLE_NAV, value).apply()

    var googleNavTermsAccepted: Boolean
        get() = prefs.getBoolean(KEY_GOOGLE_NAV_TERMS, false)
        set(value) = prefs.edit().putBoolean(KEY_GOOGLE_NAV_TERMS, value).apply()

    companion object {
        private const val PREFS = "navigation_provider"
        private const val KEY_EXPERIMENTAL_GOOGLE_NAV = "experimental_google_nav_enabled"
        private const val KEY_GOOGLE_NAV_TERMS = "google_nav_terms_accepted"
    }
}
