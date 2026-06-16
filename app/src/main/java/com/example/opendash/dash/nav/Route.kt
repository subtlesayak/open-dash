package com.example.opendash.dash.nav

/** Maneuver glyphs the dash understands. Only CONTINUE (0x0B) is hardware-verified;
 *  the rest are best-effort guesses and must be checked on fw 11.63. Until then
 *  [Maneuver.dashCode] falls back to CONTINUE so the dash never shows a wrong arrow. */
enum class ManeuverType { CONTINUE, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, DEPART, ARRIVE;

    companion object {
        /** Map an OSRM step maneuver (type + modifier) to our enum. */
        fun fromOsrm(type: String?, modifier: String?): ManeuverType = when (type) {
            "depart"   -> DEPART
            "arrive"   -> ARRIVE
            "roundabout", "rotary" -> ROUNDABOUT
            "fork", "end of road", "turn", "new name", "continue", "merge", "on ramp", "off ramp" ->
                when (modifier) {
                    "left"         -> TURN_LEFT
                    "right"        -> TURN_RIGHT
                    "slight left"  -> SLIGHT_LEFT
                    "slight right" -> SLIGHT_RIGHT
                    "sharp left"   -> SHARP_LEFT
                    "sharp right"  -> SHARP_RIGHT
                    "uturn"        -> UTURN
                    else           -> CONTINUE
                }
            else -> CONTINUE
        }
    }
}

/** One routing instruction located at a point along the geometry. */
data class Maneuver(
    val type: ManeuverType,
    val instruction: String,
    val location: GeoPoint,
    /** Cumulative distance (m) from the route start to this maneuver's location. */
    val cumulativeMeters: Double,
) {
    /** Dash maneuver glyph byte. CONTINUE (0x0B) is the only verified value. */
    val dashCode: Int get() = 0x0B // TODO: verify other glyph codes on fw 11.63
}

/** A computed road route from origin to destination. */
data class Route(
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val totalMeters: Double,
    val totalSeconds: Double,
    /** Cumulative distance (m) at each geometry vertex — same length as [geometry]. */
    val cumulative: DoubleArray,
) {
    val destination: GeoPoint? get() = geometry.lastOrNull()
}
