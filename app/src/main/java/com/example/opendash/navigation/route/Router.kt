package com.example.opendash.navigation.route

import com.example.opendash.BuildConfig
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Router {
    private const val TAG = "Router"
    private const val BASE = "https://router.project-osrm.org/route/v1/driving"
    private val userAgent = "OpenDash/${BuildConfig.VERSION_NAME} route-preview"

    suspend fun route(from: GeoPoint, to: GeoPoint): Route? = withContext(Dispatchers.IO) {
        val url = "$BASE/${from.lng},${from.lat};${to.lng},${to.lat}" +
            "?overview=full&geometries=polyline&steps=true&annotations=false"
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", userAgent)
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            parse(body)
        } catch (e: Exception) {
            DebugLog.w(TAG) { "route() failed: ${e.message}" }
            null
        }
    }

    private fun parse(json: String): Route? {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") return null
        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val r0 = routes.getJSONObject(0)
        val geometry = PolylineCodec.decode(r0.getString("geometry"))
        if (geometry.size < 2) return null

        val cumulative = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cumulative[i] = cumulative[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

        val maneuvers = ArrayList<Maneuver>()
        val legs = r0.optJSONArray("legs")
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until steps.length()) {
                    val step = steps.getJSONObject(si)
                    val man = step.optJSONObject("maneuver") ?: continue
                    val loc = man.optJSONArray("location") ?: continue
                    val point = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                    val type = ManeuverType.fromOsrm(man.optString("type"), man.optString("modifier"))
                    val name = step.optString("name").ifBlank { "road" }
                    maneuvers += Maneuver(
                        type = type,
                        instruction = buildInstruction(type, name),
                        location = point,
                        cumulativeMeters = nearestCumulative(point, geometry, cumulative),
                    )
                }
            }
        }

        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = r0.optDouble("distance", cumulative.last()),
            totalSeconds = r0.optDouble("duration", 0.0),
            cumulative = cumulative,
        )
    }

    private fun buildInstruction(type: ManeuverType, road: String): String = when (type) {
        ManeuverType.DEPART -> "Head out on $road"
        ManeuverType.ARRIVE -> "Arrive at destination"
        ManeuverType.TURN_LEFT -> "Turn left onto $road"
        ManeuverType.TURN_RIGHT -> "Turn right onto $road"
        ManeuverType.SLIGHT_LEFT -> "Slight left onto $road"
        ManeuverType.SLIGHT_RIGHT -> "Slight right onto $road"
        ManeuverType.SHARP_LEFT -> "Sharp left onto $road"
        ManeuverType.SHARP_RIGHT -> "Sharp right onto $road"
        ManeuverType.UTURN -> "Make a U-turn"
        ManeuverType.ROUNDABOUT -> "At the roundabout, take $road"
        ManeuverType.CONTINUE -> "Continue on $road"
    }

    private fun nearestCumulative(point: GeoPoint, geometry: List<GeoPoint>, cumulative: DoubleArray): Double {
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in geometry.indices) {
            val d = GeoPoint.distMeters(point, geometry[i])
            if (d < bestD) {
                bestD = d
                best = cumulative[i]
            }
        }
        return best
    }
}