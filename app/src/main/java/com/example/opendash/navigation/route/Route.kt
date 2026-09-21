package com.example.opendash.navigation.route

enum class ManeuverType {
    CONTINUE, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, DEPART, ARRIVE;

    companion object {
        fun fromOsrm(type: String?, modifier: String?): ManeuverType = when (type) {
            "depart" -> DEPART
            "arrive" -> ARRIVE
            "roundabout", "rotary" -> ROUNDABOUT
            "fork", "end of road", "turn", "new name", "continue", "merge", "on ramp", "off ramp" ->
                when (modifier) {
                    "left" -> TURN_LEFT
                    "right" -> TURN_RIGHT
                    "slight left" -> SLIGHT_LEFT
                    "slight right" -> SLIGHT_RIGHT
                    "sharp left" -> SHARP_LEFT
                    "sharp right" -> SHARP_RIGHT
                    "uturn" -> UTURN
                    else -> CONTINUE
                }
            else -> CONTINUE
        }
    }
}

data class Maneuver(
    val type: ManeuverType,
    val instruction: String,
    val location: GeoPoint,
    val cumulativeMeters: Double,
)

data class Route(
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val totalMeters: Double,
    val totalSeconds: Double,
    val cumulative: DoubleArray,
) {
    val destination: GeoPoint? get() = geometry.lastOrNull()
}
