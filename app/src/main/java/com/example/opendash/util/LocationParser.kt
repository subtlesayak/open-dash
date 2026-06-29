package com.example.opendash.util

import com.example.opendash.data.SharedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

object LocationParser {
    private const val TAG = "LocationParser"
    private const val MAX_BODY_BYTES = 256 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Nothing Phone 3) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val urlRegex  = Regex("https?://[^\\s)]+")
    private val coord3d4d = Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)")
    private val coordAt   = Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val coordQ    = Regex("[?&](?:q|query|destination|daddr)=(-?\\d+\\.\\d+),\\s*(-?\\d+\\.\\d+)")
    private val coordLl   = Regex("[?&]ll=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val coordGeo  = Regex("geo:(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val coordSearch = Regex("/search/(-?\\d+\\.\\d+),\\+?(-?\\d+\\.\\d+)")
    private val placePath = Regex("/place/([^/@?]+)")
    private val placeQ    = Regex("[?&]q=([^&0-9\\-@][^&]*)")

    // Body-scan patterns used for non-Google map pages that expose coordinates.
    private val bodyPatterns = listOf(
        coord3d4d,
        Regex("\\[null,null,(-?\\d+\\.\\d{3,}),(-?\\d+\\.\\d{3,})\\]"),
        Regex("center=(-?\\d+\\.\\d+)(?:%2C|,)(-?\\d+\\.\\d+)"),
        Regex("\"latitude\":\\s*(-?\\d+\\.\\d+)[^}]{0,60}?\"longitude\":\\s*(-?\\d+\\.\\d+)"),
        coordAt,
    )

    /** Synchronous parse of the raw shared text/URI (no network). */
    fun parse(text: String): SharedLocation {
        val trimmed = text.trim()
        safeLog { "parse(): input received" }
        // Strip trailing sentence punctuation that often clings to a shared link
        // ("...goo.gl/abc." / "(...)") so the redirect/resolve doesn't 404.
        val candidateUrl = urlRegex.find(trimmed)?.value?.trimEnd('.', ',', ';', '!', '?', ')', ']', '"', '\'')
            ?: if (trimmed.startsWith("geo:")) trimmed else null
        val url = candidateUrl?.takeIf { isAllowedShareUri(it) }
        val rejectedUrl = candidateUrl != null && url == null

        val textBefore = url?.let { trimmed.substringBefore(it).trim() }
        val textName = textBefore?.lines()?.lastOrNull { it.isNotBlank() }
            ?.removeSuffix(":")?.removePrefix("Check out")?.trim()

        val coords = when {
            url != null -> extractCoords(url)
            rejectedUrl -> null
            else -> extractCoords(trimmed)
        }

        val name = when {
            !textName.isNullOrBlank() && textName != "Check out" -> textName
            url != null -> extractPlaceName(url) ?: "Shared location"
            coords != null -> "Dropped pin"
            else -> "Loading…"
        }

        safeLog { "parse() -> name='$name' hasCoords=${coords != null} acceptedUrl=${url != null}" }
        return SharedLocation(
            name = name,
            lat = coords?.first,
            lng = coords?.second,
            url = url,
            needsExpansion = url != null && coords == null,
        )
    }

    /**
     * Network resolve of an accepted map URL → (coords, place-name). This follows only
     * rider-shared map links and parses coordinates from the redirect/body; it does not
     * use any Google Maps SDK, Google tiles, or Google Maps web-service API.
     * Run off the main thread.
     */
    suspend fun resolve(url: String): Pair<Pair<Double, Double>?, String?> = withContext(Dispatchers.IO) {
        if (!isAllowedNetworkUrl(url)) {
            safeLog { "resolve(): rejected URL" }
            return@withContext null to null
        }
        val (finalUrl, body) = fetchFollowing(url)
        var coords = extractCoords(finalUrl)
        val name = extractPlaceName(finalUrl)
        if (coords == null) coords = scanBody(body)
        safeLog { "resolve() -> hasCoords=${coords != null} hasName=${!name.isNullOrBlank()} finalHost=${hostOf(finalUrl) ?: "unknown"}" }
        coords to name
    }

    fun extractCoords(s: String): Pair<Double, Double>? {
        for (regex in listOf(coord3d4d, coordGeo, coordSearch, coordAt, coordQ, coordLl)) {
            val m = regex.find(s) ?: continue
            val pair = m.groupValues[1].toDoubleOrNull()?.let { lat ->
                m.groupValues[2].toDoubleOrNull()?.let { lng -> lat to lng }
            } ?: continue
            if (valid(pair.first, pair.second)) return pair
        }
        return null
    }

    fun extractPlaceName(s: String): String? {
        placePath.find(s)?.let { m ->
            return URLDecoder.decode(m.groupValues[1].replace("+", " "), "UTF-8")
                .replace("_", " ").trim().ifBlank { null }
        }
        placeQ.find(s)?.let { m ->
            return URLDecoder.decode(m.groupValues[1].replace("+", " "), "UTF-8").trim().ifBlank { null }
        }
        return null
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun valid(lat: Double, lng: Double) =
        lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    private fun scanBody(body: String): Pair<Double, Double>? {
        if (body.isBlank()) return null
        for (p in bodyPatterns) {
            val m = p.find(body) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (valid(lat, lng)) {
                safeLog { "scanBody matched ${p.pattern.take(24)}" }
                return lat to lng
            }
        }
        return null
    }

    /** Follow redirects manually (incl. http↔https), bypass consent, return (finalUrl, body). */
    private fun fetchFollowing(start: String): Pair<String, String> {
        var url = start
        var body = ""
        try {
            repeat(8) { hop ->
                if (!isAllowedNetworkUrl(url)) return url to body
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    connectTimeout = 9_000
                    readTimeout = 9_000
                }
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                safeLog { "hop $hop: $code locationHost=${loc?.let { resolvedRedirectUrl(url, it) }?.let { hostOf(it) } ?: ""}" }
                if (code in 300..399 && !loc.isNullOrBlank()) {
                    conn.disconnect()
                    url = resolvedRedirectUrl(url, loc)
                    if (!isAllowedNetworkUrl(url)) return url to ""
                    if (extractCoords(url) != null) return url to ""
                } else {
                    body = conn.inputStream.use { stream ->
                        stream.readLimitedBytes(MAX_BODY_BYTES).toString(Charsets.UTF_8)
                    }
                    conn.disconnect()
                    return url to body
                }
            }
        } catch (e: Exception) {
            safeWarn("fetchFollowing failed: ${e.javaClass.simpleName}")
        }
        return url to body
    }

    private fun isAllowedShareUri(value: String): Boolean =
        value.startsWith("geo:") || isAllowedNetworkUrl(value)

    private fun isAllowedNetworkUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        return when {
            host.endsWith("openstreetmap.org") || host.endsWith("osm.org") -> true
            host == "maps.app.goo.gl" || host == "maps.google.com" -> true
            host == "goo.gl" -> path.startsWith("/maps")
            host == "www.google.com" || host == "google.com" -> path.startsWith("/maps")
            else -> false
        }
    }

    private fun resolvedRedirectUrl(base: String, location: String): String =
        if (location.startsWith("http", ignoreCase = true)) location else URL(URL(base), location).toString()

    private fun hostOf(value: String): String? =
        runCatching { URI(value).host?.lowercase() }.getOrNull()

    private fun java.io.InputStream.readLimitedBytes(maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read == -1) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun safeLog(message: () -> String) {
        DebugLog.d(TAG, message)
    }

    private fun safeWarn(message: String) {
        DebugLog.w(TAG) { message }
    }
}
