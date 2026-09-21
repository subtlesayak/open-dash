package com.example.opendash.data

/** A single fuel fill-up. Mileage (km/l) is derived from the odometer gap to the prior fill. */
data class FuelFillup(
    val id: Long = 0,
    val dateMs: Long,
    val litres: Double,
    val cost: Double,
    val odometerKm: Int,
    val location: String = "",
    val sid: String = "",       // stable cross-device id (Firestore doc id)
    val vehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID,
)

/** A manually logged expense for the bike or ride. */
data class Expense(
    val id: Long = 0,
    val dateMs: Long,
    val category: String,
    val amount: Double,
    val note: String = "",
    val sid: String = "",
    val vehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID,
)

/**
 * One recorded ride is one user-controlled tracking session. Stats are computed from
 * the GPS track as it streams; [trackPolyline] is the encoded path used for the map
 * snapshot on RidesScreen.
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
    val vehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID,
)

enum class MaintenanceAction(val intervalLabel: String, val verb: String) {
    REPLACE("Replacement interval (km)", "Replace"),
    INSPECT("Inspection interval (km)", "Inspect"),
    MAINTAIN("Maintenance interval (km)", "Maintain"),
}

data class OfficialMaintenanceSchedule(
    val action: MaintenanceAction,
    val intervalKm: Int,
    val intervalMonths: Int?,
    val guidance: String,
    val manualPages: String,
)

/** Official UK Himalayan 450 owner's manual, Periodical Maintenance, printed pp. 122-127. */
object Himalayan450MaintenanceSchedule {
    private const val SOURCE_PAGES = "Owner's Manual, Periodical Maintenance, pp. 122-127"

    fun forItem(item: MaintenanceItem): OfficialMaintenanceSchedule? {
        if (item.vehicleId != VehicleStore.DEFAULT_VEHICLE_ID) return null
        return when (item.name.trim().lowercase()) {
            "engine oil" -> OfficialMaintenanceSchedule(
                MaintenanceAction.REPLACE, 10_000, 12,
                "Replace every 10,000 km or 12 months after the initial 500 km service. Check the oil level every 1,000 km.",
                SOURCE_PAGES,
            )
            "oil filter", "engine oil filter" -> OfficialMaintenanceSchedule(
                MaintenanceAction.REPLACE, 10_000, 12,
                "Replace every 10,000 km or 12 months after the initial 500 km service.",
                SOURCE_PAGES,
            )
            "air filter", "air filter element" -> OfficialMaintenanceSchedule(
                MaintenanceAction.REPLACE, 10_000, 12,
                "Replace every 10,000 km or 12 months. Clean or replace more frequently in dusty conditions.",
                SOURCE_PAGES,
            )
            "brake pads - front", "brake pads - rear" -> OfficialMaintenanceSchedule(
                MaintenanceAction.INSPECT, 10_000, 12,
                "Inspect at each scheduled service and replace if necessary; the manual specifies no fixed replacement distance.",
                SOURCE_PAGES,
            )
            "front tyre", "rear tyre" -> OfficialMaintenanceSchedule(
                MaintenanceAction.INSPECT, 10_000, 12,
                "Inspect tyre wear at each scheduled service and replace if necessary; the manual specifies no fixed replacement distance.",
                SOURCE_PAGES,
            )
            "chain sprocket", "drive chain", "rear wheel drive chain" -> OfficialMaintenanceSchedule(
                MaintenanceAction.MAINTAIN, 500, null,
                "Clean, lubricate, and adjust every 500 km or earlier, and after wet, dusty, or muddy riding. Replace worn parts as necessary.",
                SOURCE_PAGES,
            )
            else -> null
        }
    }
}
