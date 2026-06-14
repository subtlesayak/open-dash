package com.example.northstar.data

/** A single fuel fill-up. Mileage (km/l) is derived from the odometer gap to the prior fill. */
data class FuelFillup(
    val id: Long = 0,
    val dateMs: Long,
    val litres: Double,
    val cost: Double,
    val odometerKm: Int,
    val location: String = "",
    val sid: String = "",       // stable cross-device id (Firestore doc id)
)

/**
 * One recorded ride = one connect→disconnect session with the dash. Stats are computed
 * from the GPS track as it streams; [trackPolyline] is the encoded path (for the map
 * snapshot on RidesScreen).
 */
data class Ride(
    val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val distanceMeters: Double,
    val durationSec: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val trackPolyline: String = "",   // Google/OSRM-encoded lat/lng path
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0,
    val sid: String = "",             // stable cross-device id (Firestore doc id)
) {
    val avgSpeedKmh: Double get() = avgSpeedMps * 3.6
    val maxSpeedKmh: Double get() = maxSpeedMps * 3.6
    val distanceKm: Double get() = distanceMeters / 1000.0
}

/** A recurring maintenance item with its interval and when it was last serviced. */
data class MaintenanceItem(
    val id: Long = 0,
    val name: String,
    val iconKey: String,        // "chain" | "drop" | "wrench" | "gauge" | "thermo" | "fuel"
    val intervalKm: Int,
    val lastDoneOdoKm: Int,
    val lastDoneDateMs: Long,
    val sid: String = "",       // stable cross-device id (Firestore doc id)
)
