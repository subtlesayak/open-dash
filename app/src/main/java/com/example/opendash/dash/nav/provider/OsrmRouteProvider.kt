package com.example.opendash.dash.nav.provider

import com.example.opendash.dash.nav.Router

object OsrmRouteProvider : NavigationProvider {
    override val id = NavigationProviderId.OSRM
    override val displayName = "OpenFreeMap / OSRM"
    override val capabilities = setOf(NavigationProviderCapability.RouteGeometry)

    override fun status(): NavigationProviderStatus =
        NavigationProviderStatus(available = true, enabled = true)

    override suspend fun route(request: NavigationRouteRequest): NavigationRouteResult {
        val route = Router.route(request.origin, request.destination)
        return if (route != null) {
            NavigationRouteResult.Success(
                route = route,
                providerId = id,
                usesProviderGeometry = true,
            )
        } else {
            NavigationRouteResult.Failure(id, "OSRM route unavailable")
        }
    }
}
