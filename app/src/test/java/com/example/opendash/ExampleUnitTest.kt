package com.example.opendash

import com.example.opendash.navigation.route.GeoPoint
import com.example.opendash.navigation.route.PolylineCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenDashAppSmokeTest {
    @Test
    fun polylineRoundTripKeepsCoordinates() {
        val points = listOf(
            GeoPoint(17.385044, 78.486671),
            GeoPoint(17.387140, 78.491684),
        )

        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))

        assertEquals(points.size, decoded.size)
        assertEquals(points.first().lat, decoded.first().lat, 0.00001)
        assertEquals(points.first().lng, decoded.first().lng, 0.00001)
        assertEquals(points.last().lat, decoded.last().lat, 0.00001)
        assertEquals(points.last().lng, decoded.last().lng, 0.00001)
    }
}