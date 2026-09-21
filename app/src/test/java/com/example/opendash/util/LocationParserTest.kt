package com.example.opendash.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationParserTest {
    @Test
    fun parse_acceptsGoogleMapsHostWithCoordinates() {
        val location = LocationParser.parse("Meet here https://maps.google.com/?q=12.9716,77.5946")

        assertEquals("https://maps.google.com/?q=12.9716,77.5946", location.url)
        assertEquals(12.9716, location.lat!!, 0.000001)
        assertEquals(77.5946, location.lng!!, 0.000001)
        assertFalse(location.needsExpansion)
    }

    @Test
    fun parse_acceptsGeoUriWithoutNetworkUrl() {
        val location = LocationParser.parse("geo:12.9716,77.5946")

        assertEquals(12.9716, location.lat!!, 0.000001)
        assertEquals(77.5946, location.lng!!, 0.000001)
        assertEquals("geo:12.9716,77.5946", location.url)
        assertFalse(location.needsExpansion)
    }

    @Test
    fun parse_rejectsHttpMapUrlEvenWithCoordinates() {
        val location = LocationParser.parse("http://maps.google.com/?q=12.9716,77.5946")

        assertNull(location.url)
        assertNull(location.lat)
        assertNull(location.lng)
        assertFalse(location.needsExpansion)
    }

    @Test
    fun parse_rejectsUnknownHttpsHost() {
        val location = LocationParser.parse("https://example.com/?q=12.9716,77.5946")

        assertNull(location.url)
        assertNull(location.lat)
        assertNull(location.lng)
        assertFalse(location.needsExpansion)
    }

    @Test
    fun parse_acceptsGoogleShortMapHostForExpansion() {
        val location = LocationParser.parse("https://maps.app.goo.gl/abc123")

        assertEquals("https://maps.app.goo.gl/abc123", location.url)
        assertNull(location.lat)
        assertNull(location.lng)
        assertEquals("Shared location", location.name)
        assertTrue(location.needsExpansion)
    }
}
