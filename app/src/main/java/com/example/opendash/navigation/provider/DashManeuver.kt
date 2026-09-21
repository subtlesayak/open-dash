package com.example.opendash.navigation.provider

data class DashManeuver(
    val instruction: String,
    val roadName: String,
    val distanceFromStartMeters: Double,
    val type: DashManeuverType,
    val modifier: String?,
    val locationLat: Double,
    val locationLng: Double,
)
