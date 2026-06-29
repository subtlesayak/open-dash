package com.example.opendash.navigation.provider

import com.example.opendash.dash.nav.GeoPoint

data class DashRoute(
    val routeId: String,
    val geometry: List<GeoPoint>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val maneuvers: List<DashManeuver>,
)
