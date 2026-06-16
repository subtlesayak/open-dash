package com.example.opendash.dash.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Web Mercator tile math (slippy map convention, 256 px tiles). */
object Mercator {
    const val TILE_SIZE = 256

    /** Longitude → fractional tile X at zoom [z]. */
    fun lngToTileX(lng: Double, z: Int): Double = (lng + 180.0) / 360.0 * (1 shl z)

    /** Latitude → fractional tile Y at zoom [z]. */
    fun latToTileY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)
    }

    fun tileXToLng(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0

    fun tileYToLat(y: Double, z: Int): Double =
        Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))

    /** Great-circle distance in km. */
    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan(sqrt(a) / sqrt(1 - a))
    }
}
