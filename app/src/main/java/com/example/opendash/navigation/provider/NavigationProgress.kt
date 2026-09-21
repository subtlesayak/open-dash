package com.example.opendash.navigation.provider

data class NavigationProgress(
    val routeId: String,
    val nextManeuver: DashManeuver?,
    val distanceToNextManeuverMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double,
    val etaEpochMillis: Long,
    val offRoute: Boolean,
)
