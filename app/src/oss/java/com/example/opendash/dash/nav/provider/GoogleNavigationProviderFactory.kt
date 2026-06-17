package com.example.opendash.dash.nav.provider

import android.content.Context

object GoogleNavigationProviderFactory {
    fun create(context: Context, prefs: NavigationProviderPrefs): NavigationProvider =
        UnavailableGoogleNavigationProvider
}

private object UnavailableGoogleNavigationProvider : NavigationProvider {
    override val id = NavigationProviderId.GOOGLE_NAVIGATION
    override val displayName = "Google Navigation SDK"
    override val capabilities = emptySet<NavigationProviderCapability>()

    override fun status(): NavigationProviderStatus =
        NavigationProviderStatus(
            available = false,
            enabled = false,
            reason = "Google Navigation SDK is not included in the OSS build.",
        )

    override suspend fun route(request: NavigationRouteRequest): NavigationRouteResult =
        NavigationRouteResult.Failure(id, status().reason ?: "Unavailable")
}
