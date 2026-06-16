package com.example.opendash.dash.nav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 coordinate plus geo helpers used by the routing/nav engine. */
data class GeoPoint(val lat: Double, val lng: Double) {

    companion object {
        private const val EARTH_KM = 6371.0

        /** Great-circle distance in metres. */
        fun distMeters(a: GeoPoint, b: GeoPoint): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val s = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
                    sin(dLng / 2) * sin(dLng / 2)
            return 2 * EARTH_KM * 1000.0 * atan2(sqrt(s), sqrt(1 - s))
        }

        /** Initial bearing a→b in degrees [0,360). */
        fun bearing(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val y = sin(dLng) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        /**
         * Nearest point to [p] on the segment [a]→[b], plus the fraction t∈[0,1]
         * along the segment. Uses a local equirectangular approximation — fine at
         * the segment scale of a road.
         */
        fun projectOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<GeoPoint, Double> {
            val latRef = Math.toRadians(a.lat)
            fun x(g: GeoPoint) = Math.toRadians(g.lng) * cos(latRef)
            fun y(g: GeoPoint) = Math.toRadians(g.lat)
            val ax = x(a); val ay = y(a)
            val bx = x(b); val by = y(b)
            val px = x(p); val py = y(p)
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val proj = GeoPoint(
                lat = a.lat + (b.lat - a.lat) * t,
                lng = a.lng + (b.lng - a.lng) * t,
            )
            return proj to t
        }
    }
}
