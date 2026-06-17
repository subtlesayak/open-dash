package com.example.opendash.dash.nav.provider

import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.dash.nav.Route

enum class NavigationProviderId { OSRM, GOOGLE_NAVIGATION }

enum class NavigationProviderCapability {
    RouteGeometry,
    LiveGuidance,
    TermsAcceptance,
}

data class NavigationProviderStatus(
    val available: Boolean,
    val enabled: Boolean,
    val reason: String? = null,
)

data class NavigationRouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val destinationName: String = "",
)

sealed class NavigationRouteResult {
    data class Success(
        val route: Route?,
        val providerId: NavigationProviderId,
        val usesProviderGeometry: Boolean,
        val message: String? = null,
    ) : NavigationRouteResult()

    data class Failure(
        val providerId: NavigationProviderId,
        val reason: String,
    ) : NavigationRouteResult()
}

interface NavigationProvider {
    val id: NavigationProviderId
    val displayName: String
    val capabilities: Set<NavigationProviderCapability>

    fun status(): NavigationProviderStatus
    suspend fun route(request: NavigationRouteRequest): NavigationRouteResult
    suspend fun startGuidance(request: NavigationRouteRequest): NavigationRouteResult = route(request)
}
