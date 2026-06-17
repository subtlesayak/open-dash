package com.example.opendash.dash.nav.provider

import android.content.Context
import com.example.opendash.BuildConfig

object GoogleNavigationProviderFactory {
    fun create(context: Context, prefs: NavigationProviderPrefs): NavigationProvider =
        GoogleNavigationProvider(context.applicationContext, prefs)
}

private class GoogleNavigationProvider(
    private val context: Context,
    private val prefs: NavigationProviderPrefs,
) : NavigationProvider {
    override val id = NavigationProviderId.GOOGLE_NAVIGATION
    override val displayName = "Google Navigation SDK"
    override val capabilities = setOf(
        NavigationProviderCapability.LiveGuidance,
        NavigationProviderCapability.TermsAcceptance,
    )

    override fun status(): NavigationProviderStatus {
        if (!BuildConfig.GOOGLE_NAV_FLAVOR) {
            return NavigationProviderStatus(false, false, "Google Navigation SDK is not included.")
        }
        if (!BuildConfig.MAPS_API_KEY_PRESENT) {
            return NavigationProviderStatus(false, false, "MAPS_API_KEY is missing or using DEFAULT_API_KEY.")
        }
        val sdkPresent = runCatching {
            Class.forName("com.google.android.libraries.navigation.NavigationApi")
        }.isSuccess
        if (!sdkPresent) {
            return NavigationProviderStatus(false, false, "Google Navigation SDK classes are unavailable.")
        }
        if (!prefs.googleNavTermsAccepted) {
            return NavigationProviderStatus(true, false, "Google Navigation SDK terms must be accepted.")
        }
        return NavigationProviderStatus(true, true)
    }

    override suspend fun route(request: NavigationRouteRequest): NavigationRouteResult {
        val status = status()
        if (!status.enabled) {
            return NavigationRouteResult.Failure(id, status.reason ?: "Google Navigation SDK is not ready.")
        }
        return NavigationRouteResult.Success(
            route = null,
            providerId = id,
            usesProviderGeometry = false,
            message = "Google guidance is enabled. Route geometry is intentionally not exported to MapLibre/OpenFreeMap.",
        )
    }

    override suspend fun startGuidance(request: NavigationRouteRequest): NavigationRouteResult =
        route(request)
}
