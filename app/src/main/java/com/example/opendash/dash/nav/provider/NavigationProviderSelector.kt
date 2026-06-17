package com.example.opendash.dash.nav.provider

import android.content.Context
import com.example.opendash.BuildConfig

class NavigationProviderSelector(
    private val context: Context,
    private val prefs: NavigationProviderPrefs = NavigationProviderPrefs(context),
) {
    fun activeProvider(): NavigationProvider {
        val selected = NavigationProviderPolicy.select(
            experimentalEnabled = prefs.experimentalGoogleNavEnabled,
            googleNavFlavor = BuildConfig.GOOGLE_NAV_FLAVOR,
            mapsApiKeyPresent = BuildConfig.MAPS_API_KEY_PRESENT,
            googleTermsAccepted = prefs.googleNavTermsAccepted,
        )
        if (selected == NavigationProviderId.OSRM) {
            return OsrmRouteProvider
        }
        val google = GoogleNavigationProviderFactory.create(context, prefs)
        return if (google.status().enabled) google else OsrmRouteProvider
    }

    fun googleProviderStatus(): NavigationProviderStatus =
        GoogleNavigationProviderFactory.create(context, prefs).status()
}
