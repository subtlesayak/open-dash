package com.example.opendash.navigation.route

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lng: Double) {
    companion object {
        private const val EARTH_KM = 6371.0

        fun distMeters(a: GeoPoint, b: GeoPoint): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val s = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
                sin(dLng / 2) * sin(dLng / 2)
            return 2 * EARTH_KM * 1000.0 * atan2(sqrt(s), sqrt(1 - s))
        }

        fun bearing(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val y = sin(dLng) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }
    }
}
