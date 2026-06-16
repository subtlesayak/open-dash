package com.example.opendash.dash.nav

/**
 * Tracks the rider's progress along a [Route]: where they are on the line, the
 * next maneuver, distance to it, remaining distance, ETA, and off-route state.
 * Pure/stateless per update — call [progress] with the latest GPS fix.
 */
object NavEngine {

    data class Progress(
        val snapped: GeoPoint,         // position snapped to the route
        val routeBearing: Double,      // heading of the route at the rider
        val distanceToManeuverM: Double,
        val nextManeuver: Maneuver?,
        val remainingMeters: Double,
        val etaSeconds: Double,
        val offRoute: Boolean,
        val arrived: Boolean,
    )

    private const val OFF_ROUTE_M = 60.0
    private const val ARRIVE_M = 25.0
    private const val DEFAULT_SPEED_MPS = 11.0 // ~40 km/h fallback

    /**
     * @param speedMps recent GPS speed; <=0 falls back to a default for ETA.
     */
    fun progress(route: Route, pos: GeoPoint, speedMps: Float): Progress {
        val geom = route.geometry

        // Find nearest segment + snapped point + cumulative distance there.
        var bestDist = Double.MAX_VALUE
        var bestSnap = geom.first()
        var bestCum = 0.0
        var bestBearing = 0.0
        for (i in 0 until geom.size - 1) {
            val a = geom[i]; val b = geom[i + 1]
            val (proj, t) = GeoPoint.projectOnSegment(pos, a, b)
            val d = GeoPoint.distMeters(pos, proj)
            if (d < bestDist) {
                bestDist = d
                bestSnap = proj
                val segLen = GeoPoint.distMeters(a, b)
                bestCum = route.cumulative[i] + segLen * t
                bestBearing = GeoPoint.bearing(a, b)
            }
        }

        val remaining = (route.totalMeters - bestCum).coerceAtLeast(0.0)
        val arrived = remaining <= ARRIVE_M
        val offRoute = bestDist > OFF_ROUTE_M

        // Next maneuver = first maneuver whose cumulative distance is ahead of us.
        val next = route.maneuvers.firstOrNull {
            it.cumulativeMeters > bestCum + 1.0 && it.type != ManeuverType.DEPART
        }
        val distToManeuver = next?.let { (it.cumulativeMeters - bestCum).coerceAtLeast(0.0) } ?: remaining

        val speed = if (speedMps > 0.5f) speedMps.toDouble() else DEFAULT_SPEED_MPS
        val eta = remaining / speed

        return Progress(
            snapped = bestSnap,
            routeBearing = bestBearing,
            distanceToManeuverM = distToManeuver,
            nextManeuver = next,
            remainingMeters = remaining,
            etaSeconds = eta,
            offRoute = offRoute,
            arrived = arrived,
        )
    }
}
