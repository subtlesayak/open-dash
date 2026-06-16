package com.example.opendash.dash.nav

/** Decoder for the Google/OSRM encoded-polyline format (precision 5 by default). */
object PolylineCodec {
    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0
        var lng = 0
        val len = encoded.length

        while (index < len) {
            // latitude delta
            var shift = 0; var result = 0; var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            // Truncated input: a lat group consumed the last char, no lng follows → stop
            // cleanly instead of reading past the end (StringIndexOutOfBounds).
            if (index >= len) break

            // longitude delta
            shift = 0; result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(GeoPoint(lat / factor, lng / factor))
        }
        return points
    }

    /** Encode points to the Google/OSRM polyline format (used to store ride tracks compactly). */
    fun encode(points: List<GeoPoint>, precision: Int = 5): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var lastLat = 0L
        var lastLng = 0L
        for (p in points) {
            val lat = Math.round(p.lat * factor)
            val lng = Math.round(p.lng * factor)
            encodeDelta(lat - lastLat, sb)
            encodeDelta(lng - lastLng, sb)
            lastLat = lat; lastLng = lng
        }
        return sb.toString()
    }

    private fun encodeDelta(v: Long, sb: StringBuilder) {
        var value = if (v < 0) (v shl 1).inv() else (v shl 1)
        while (value >= 0x20) {
            sb.append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        sb.append((value.toInt() + 63).toChar())
    }
}
