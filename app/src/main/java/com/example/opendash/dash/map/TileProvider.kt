package com.example.opendash.dash.map

import com.example.opendash.data.DashDisplayMode
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.LruCache
import com.example.opendash.BuildConfig
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.data.ExperimentalNavigationSettings
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Raster tile provider with memory + disk cache.
 *
 * Tiles are darkened ONCE at load (invert + desaturate + dim) and the dark bitmap
 * is cached, so the render loop never re-runs the colour matrix per frame — a key
 * power win at 4 fps.
 *
 * While riding, the process is bound to the dash WiFi (no internet), so tiles
 * must come from cache — [prefetch]/[prefetchRoute] populate it while internet is
 * still reachable. Cache misses fetch through whichever network has connectivity
 * (cellular when bound to the dash WiFi), rate-limited to avoid hot loops.
 */
private fun mapboxStyleId(): String =
    if (DashDisplayMode.nightMode.value) "dark-v11" else "streets-v12"

private fun mapboxFallbackStyleId(): String =
    if (DashDisplayMode.nightMode.value) "navigation-night-v1" else "navigation-day-v1"

class TileProvider(context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "TileProvider"
        // Keyless fallback used only when the Mapbox experiment is disabled.
        // Format args: (z, x, y).
        private const val URL_TEMPLATE =
            "https://tile.openstreetmap.org/%d/%d/%d.png"
        private const val MAPBOX_URL_TEMPLATE =
            "https://api.mapbox.com/styles/v1/mapbox/%s/tiles/256/%d/%d/%d?access_token=%s"
        // Browser-like UA so the tile endpoint serves us normally.
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val MAX_PREFETCH_TILES = 600
        private const val MIN_FETCH_GAP_MS = 45L // be gentle on tile servers + the radio
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // Cache dir per tile source so a source switch doesn't serve stale tiles.
    private val diskDir = File(context.cacheDir, "tiles_stream").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(120)
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastFetchAt = 0L

    fun usesMapboxTiles(): Boolean = ExperimentalNavigationSettings.isMapboxNavigationEnabled()

    /** Non-blocking: returns the cached tile, else kicks off async load. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val xw = ((x % max) + max) % max
        val key = "${sourceKey()}/$z/$xw/$y"

        memory.get(key)?.let { return it }

        if (inflight.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    // Standard map tiles. No per-tile image transforms here; renderer chooses
                    // display paint per source so Mapbox labels stay crisp.
                    val raw = loadDisk(key) ?: fetch(z, xw, y, key)
                    if (raw != null) memory.put(key, raw)
                } finally {
                    inflight.remove(key)
                }
            }
        }
        return null
    }

    /** Prefetch tiles around a point (and optionally a straight corridor) into disk. */
    fun prefetch(lat: Double, lng: Double, fromLat: Double? = null, fromLng: Double? = null) {
        scope.launch(Dispatchers.IO) {
            var count = 0
            // 11..20 so the rider's vicinity has tiles at every zoom level offline
            // (default nav zoom is now 19, max 20).
            for (z in 11..20) {
                val radius = if (z in 15..16) 2 else 1
                count += prefetchAround(lat, lng, z, radius)
                if (fromLat != null && fromLng != null) {
                    count += prefetchAround(fromLat, fromLng, z, radius)
                    if (z in 12..13) for (i in 1..8) {
                        val f = i / 9.0
                        count += prefetchAround(fromLat + (lat - fromLat) * f, fromLng + (lng - fromLng) * f, z, 1)
                    }
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            DebugLog.i(TAG) { "Prefetch (point) done — ~$count tiles ensured" }
        }
    }

    /** Prefetch tiles along the actual route polyline so offline riding has coverage. */
    fun prefetchRoute(route: List<GeoPoint>) {
        if (route.size < 2) return
        scope.launch(Dispatchers.IO) {
            var count = 0
            // Sample the polyline so we don't fetch a tile for every vertex.
            for (z in 12..18) {
                val seen = HashSet<String>()
                val step = when {
                    z >= 17 -> 2
                    z >= 15 -> 1
                    else -> 3
                }
                var i = 0
                while (i < route.size) {
                    val p = route[i]
                    val cx = Mercator.lngToTileX(p.lng, z).toInt()
                    val cy = Mercator.latToTileY(p.lat, z).toInt()
                    val r = if (z >= 15) 1 else 0
                    for (dx in -r..r) for (dy in -r..r) {
                        val key = "${sourceKey()}/$z/${cx + dx}/${cy + dy}"
                        if (seen.add(key) && !diskFile(key).exists()) {
                            fetchKey(z, cx + dx, cy + dy, key); count++
                        }
                    }
                    i += step
                    if (count > MAX_PREFETCH_TILES) break
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            DebugLog.i(TAG) { "Prefetch (route) done — ~$count tiles ensured" }
        }
    }

    private fun prefetchAround(lat: Double, lng: Double, z: Int, radius: Int): Int {
        val cx = Mercator.lngToTileX(lng, z).toInt()
        val cy = Mercator.latToTileY(lat, z).toInt()
        var n = 0
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val x = cx + dx; val y = cy + dy
            if (y < 0 || y >= (1 shl z)) continue
            val key = "${sourceKey()}/$z/$x/$y"
            if (!diskFile(key).exists()) { fetchKey(z, x, y, key); n++ }
        }
        return n
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun diskFile(key: String) = File(diskDir, key.replace('/', '_') + ".png")

    private fun loadDisk(key: String): Bitmap? {
        val f = diskFile(key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    private fun fetch(z: Int, x: Int, y: Int, key: String): Bitmap? = fetchKey(z, x, y, key)

    private fun fetchKey(z: Int, x: Int, y: Int, key: String): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        // Rate-limit network fetches to avoid hot loops on the radio.
        val now = System.currentTimeMillis()
        val wait = MIN_FETCH_GAP_MS - (now - lastFetchAt)
        if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) {}
        lastFetchAt = System.currentTimeMillis()

        val net = internetNetwork()
        val urls = tileUrls(z, x, ((y % max) + max) % max)
        for (urlString in urls) {
            try {
                val url = URL(urlString)
                val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                diskFile(key).writeBytes(bytes)
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                DebugLog.w(TAG) { "Tile $key fetch failed: ${e.message}" }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun internetNetwork(): Network? =
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }

    private fun sourceKey(): String =
        if (ExperimentalNavigationSettings.isMapboxNavigationEnabled()) {
            "mapbox_${mapboxStyleId()}_256_detail_v2"
        } else {
            "osm"
        }

    private fun tileUrls(z: Int, x: Int, y: Int): List<String> =
        if (ExperimentalNavigationSettings.isMapboxNavigationEnabled()) {
            listOf(
                MAPBOX_URL_TEMPLATE.format(mapboxStyleId(), z, x, y, BuildConfig.MAPBOX_ACCESS_TOKEN),
                MAPBOX_URL_TEMPLATE.format(mapboxFallbackStyleId(), z, x, y, BuildConfig.MAPBOX_ACCESS_TOKEN),
            ).distinct()
        } else {
            listOf(URL_TEMPLATE.format(z, x, y))
        }
}
