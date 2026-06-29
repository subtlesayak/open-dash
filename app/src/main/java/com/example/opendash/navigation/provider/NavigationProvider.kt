package com.example.opendash.navigation.provider

interface NavigationProvider {
    suspend fun requestRoute(
        originLat: Double,
        originLng: Double,
        destinationLat: Double,
        destinationLng: Double,
    ): DashRoute

    fun startGuidance(route: DashRoute)
    fun stopGuidance()
    fun observeProgress(callback: (NavigationProgress) -> Unit)
}
