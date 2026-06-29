package com.example.opendash.navigation.provider

import android.content.Context
import com.example.opendash.BuildConfig
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.dash.nav.PolylineCodec
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.max

class MapboxNavigationProvider(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : NavigationProvider {
    private var callback: ((NavigationProgress) -> Unit)? = null
    private var guidanceJob: Job? = null
    private var activeRoute: DashRoute? = null
    private var mapboxNavigationInstance: Any? = null

    init {
        initializeMapboxNavigationSafely()
    }

    override suspend fun requestRoute(
        originLat: Double,
        originLng: Double,
        destinationLat: Double,
        destinationLng: Double,
    ): DashRoute = withContext(Dispatchers.IO) {
        ensureEnabled()
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN.takeIf { it.isNotBlank() }
            ?: error("MAPBOX_ACCESS_TOKEN is missing")
        val url = buildDirectionsUrl(originLat, originLng, destinationLat, destinationLng, token)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "OpenDash/${BuildConfig.VERSION_NAME} MapboxExperiment")
        }
        try {
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parseDirectionsRoute(body)
        } finally {
            conn.disconnect()
        }
    }

    override fun startGuidance(route: DashRoute) {
        ensureEnabled()
        activeRoute = route
        guidanceJob?.cancel()
        registerRouteProgressObserverSafely()
        guidanceJob = scope.launch {
            val startMs = System.currentTimeMillis()
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                callback?.invoke(progressFromElapsed(route, elapsed))
                delay(1_000)
            }
        }
    }

    override fun stopGuidance() {
        guidanceJob?.cancel()
        guidanceJob = null
        activeRoute = null
    }

    override fun observeProgress(callback: (NavigationProgress) -> Unit) {
        this.callback = callback
    }

    private fun ensureEnabled() {
        check(BuildConfig.USE_MAPBOX_NAVIGATION_EXPERIMENTAL) {
            "Mapbox navigation experiment is disabled. Set USE_MAPBOX_NAVIGATION_EXPERIMENTAL=true."
        }
    }

    private fun initializeMapboxNavigationSafely() {
        if (!BuildConfig.USE_MAPBOX_NAVIGATION_EXPERIMENTAL) return
        runCatching {
            val app = context.applicationContext
            val providerClass = Class.forName("com.mapbox.navigation.core.MapboxNavigationProvider")
            val method = providerClass.methods.firstOrNull { it.name == "retrieve" && it.parameterTypes.isEmpty() }
            mapboxNavigationInstance = method?.invoke(null)
            DebugLog.i(TAG) { "MapboxNavigation initialized via provider: ${mapboxNavigationInstance != null}" }
            app
        }.onFailure {
            DebugLog.w(TAG) { "MapboxNavigation SDK not initialized yet: ${it.message}" }
        }
    }

    private fun registerRouteProgressObserverSafely() {
        val instance = mapboxNavigationInstance ?: return
        runCatching {
            val observerClass = Class.forName("com.mapbox.navigation.core.trip.session.RouteProgressObserver")
            DebugLog.i(TAG) {
                "Mapbox RouteProgressObserver available=${observerClass.name.isNotBlank()} instance=${instance.javaClass.name}"
            }
        }.onFailure {
            DebugLog.w(TAG) { "Mapbox RouteProgressObserver unavailable: ${it.message}" }
        }
    }

    private fun buildDirectionsUrl(
        originLat: Double,
        originLng: Double,
        destinationLat: Double,
        destinationLng: Double,
        token: String,
    ): String {
        val encodedToken = URLEncoder.encode(token, Charsets.UTF_8.name())
        return "https://api.mapbox.com/directions/v5/$MOTORCYCLE_ROUTE_PROFILE/" +
            "$originLng,$originLat;$destinationLng,$destinationLat" +
            "?alternatives=true" +
            "&geometries=polyline" +
            "&overview=full" +
            "&steps=true" +
            "&voice_units=metric" +
            "&max_height=$MOTORCYCLE_MAX_HEIGHT_METERS" +
            "&max_width=$MOTORCYCLE_MAX_WIDTH_METERS" +
            "&max_weight=$MOTORCYCLE_MAX_WEIGHT_TONS" +
            "&alley_bias=$MOTORCYCLE_ALLEY_BIAS" +
            "&access_token=$encodedToken"
    }

    private fun parseDirectionsRoute(json: String): DashRoute {
        val root = JSONObject(json)
        val routes = root.optJSONArray("routes") ?: error("Mapbox route response has no routes")
        check(routes.length() > 0) { "Mapbox returned no routes" }
        val route = routes.getJSONObject(0)
        val geometry = PolylineCodec.decode(route.getString("geometry"))
        check(geometry.size >= 2) { "Mapbox route geometry is empty" }
        val cumulative = cumulativeMeters(geometry)
        return DashRoute(
            routeId = route.optString("uuid").ifBlank { UUID.randomUUID().toString() },
            geometry = geometry,
            totalDistanceMeters = route.optDouble("distance", cumulative.lastOrNull() ?: 0.0),
            totalDurationSeconds = route.optDouble("duration", 0.0),
            maneuvers = parseManeuvers(route, geometry, cumulative),
        )
    }

    private fun parseManeuvers(
        route: JSONObject,
        geometry: List<GeoPoint>,
        cumulative: DoubleArray,
    ): List<DashManeuver> {
        val out = ArrayList<DashManeuver>()
        val legs = route.optJSONArray("legs") ?: return out
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val step = steps.getJSONObject(si)
                val maneuver = step.optJSONObject("maneuver") ?: continue
                val loc = maneuver.optJSONArray("location") ?: continue
                val type = maneuver.optString("type")
                val modifier = maneuver.optString("modifier").takeIf { it.isNotBlank() }
                val point = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                val roadName = step.optString("name").ifBlank { "road" }
                out += DashManeuver(
                    instruction = maneuver.optString("instruction").ifBlank { instructionFor(type, modifier, roadName) },
                    roadName = roadName,
                    distanceFromStartMeters = nearestCumulative(point, geometry, cumulative),
                    type = MapboxManeuverMapper.toDashType(type, modifier),
                    modifier = modifier,
                    locationLat = point.lat,
                    locationLng = point.lng,
                )
            }
        }
        return out
    }

    private fun progressFromElapsed(route: DashRoute, elapsedSeconds: Double): NavigationProgress {
        val fraction = if (route.totalDurationSeconds > 0.0) {
            (elapsedSeconds / route.totalDurationSeconds).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val traveled = route.totalDistanceMeters * fraction
        val next = route.maneuvers.firstOrNull { it.distanceFromStartMeters >= traveled }
        val remainingSeconds = max(0.0, route.totalDurationSeconds - elapsedSeconds)
        return NavigationProgress(
            routeId = route.routeId,
            nextManeuver = next,
            distanceToNextManeuverMeters = next?.let { max(0.0, it.distanceFromStartMeters - traveled) } ?: 0.0,
            remainingDistanceMeters = max(0.0, route.totalDistanceMeters - traveled),
            remainingDurationSeconds = remainingSeconds,
            etaEpochMillis = System.currentTimeMillis() + (remainingSeconds * 1000).toLong(),
            offRoute = false,
        )
    }

    private fun cumulativeMeters(geometry: List<GeoPoint>): DoubleArray =
        DoubleArray(geometry.size).also { cumulative ->
            for (i in 1 until geometry.size) {
                cumulative[i] = cumulative[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
            }
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

    private fun instructionFor(type: String?, modifier: String?, roadName: String): String =
        when (MapboxManeuverMapper.toDashType(type, modifier)) {
            DashManeuverType.DEPART -> "Head out on $roadName"
            DashManeuverType.ARRIVE -> "Arrive at destination"
            DashManeuverType.TURN_LEFT -> "Turn left onto $roadName"
            DashManeuverType.TURN_RIGHT -> "Turn right onto $roadName"
            DashManeuverType.SLIGHT_LEFT -> "Slight left onto $roadName"
            DashManeuverType.SLIGHT_RIGHT -> "Slight right onto $roadName"
            DashManeuverType.SHARP_LEFT -> "Sharp left onto $roadName"
            DashManeuverType.SHARP_RIGHT -> "Sharp right onto $roadName"
            DashManeuverType.UTURN -> "Make a U-turn"
            DashManeuverType.ROUNDABOUT -> "At the roundabout, continue onto $roadName"
            DashManeuverType.MERGE -> "Merge onto $roadName"
            DashManeuverType.FORK -> "Keep toward $roadName"
            DashManeuverType.STRAIGHT -> "Continue on $roadName"
            DashManeuverType.UNKNOWN -> "Continue on $roadName"
        }

    companion object {
        private const val TAG = "MapboxNavigationProvider"
        // Mapbox Directions has no public motorcycle profile. Use automotive routing with
        // motorcycle-sized constraints as OpenDash's default motorcycle mode.
        private const val MOTORCYCLE_ROUTE_PROFILE = "mapbox/driving"
        private const val MOTORCYCLE_MAX_HEIGHT_METERS = "1.5"
        private const val MOTORCYCLE_MAX_WIDTH_METERS = "0.9"
        private const val MOTORCYCLE_MAX_WEIGHT_TONS = "0.4"
        private const val MOTORCYCLE_ALLEY_BIAS = "0.25"
    }
}
