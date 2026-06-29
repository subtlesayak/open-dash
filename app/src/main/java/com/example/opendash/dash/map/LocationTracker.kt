package com.example.opendash.dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** GPS position via LocationManager (no Play Services dependency). */
class LocationTracker(context: Context) {
    companion object {
        private const val TAG = "LocationTracker"
        // While a GPS fix is younger than this, ignore coarse NETWORK fixes entirely.
        private const val GPS_STALE_MS = 10_000L
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()

    private val _enabled = MutableStateFlow(isLocationEnabled())
    val enabled = _enabled.asStateFlow()

    private val listener = LocationListener { loc ->
        val cur = _location.value
        if (acceptFix(cur, loc)) {
            _location.value = loc
            DebugLog.d(TAG) { "fix ${loc.provider} acc=${loc.accuracy} (${loc.latitude},${loc.longitude})" }
        } else {
            DebugLog.d(TAG) { "REJECT ${loc.provider} acc=${loc.accuracy} dt=${loc.time - (cur?.time ?: 0)}ms" }
        }
    }

    private var rejectStreak = 0

    private fun acceptFix(cur: Location?, loc: Location): Boolean {
        if (cur == null) {
            rejectStreak = 0
            return true
        }
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        if (!isGps && cur.provider == LocationManager.GPS_PROVIDER &&
            loc.time - cur.time < GPS_STALE_MS
        ) return false
        if (loc.time < cur.time) return false
        val dt = (loc.time - cur.time) / 1000.0
        val jump = cur.distanceTo(loc)
        if (dt > 0 && jump > 200f && jump / dt > 85.0) return reject()
        if (dt in 0.0..6.0 && rejectStreak < 3) {
            val plausibleSpeed = maxOf(cur.speed, loc.speed).coerceAtLeast(1f)
            val expected = plausibleSpeed * dt
            val noise = loc.accuracy + cur.accuracy
            val gate = expected + noise * 1.5 + 12f
            if (jump > gate && jump > 25f) return reject()
        }
        rejectStreak = 0
        return true
    }

    private fun reject(): Boolean {
        rejectStreak++
        return false
    }

    private var running = false

    /** Requires ACCESS_FINE_LOCATION at runtime; no-ops without it. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running && isLocationEnabled()) return
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }
            _enabled.value = providers.isNotEmpty()
            if (providers.isEmpty()) {
                running = false
                DebugLog.w(TAG) { "Location services disabled — no providers registered" }
                return
            }
            if (running) {
                lm.removeUpdates(listener)
                running = false
            }
            _location.value = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            // GPS for accuracy + heading; NETWORK as a fallback while GPS warms up.
            // minDistance=0: keep GPS fixes flowing every second even when parked.
            // With a minimum distance, GPS goes quiet while stationary, its last fix
            // ages out, and a coarse NETWORK fix takes over → the marker drifts.
            for (provider in providers) {
                lm.requestLocationUpdates(provider, 500L, 0f, listener, Looper.getMainLooper())
            }
            running = true
            DebugLog.i(TAG) { "Location updates started" }
        } catch (e: SecurityException) {
            DebugLog.w(TAG) { "Location permission missing — GPS disabled" }
        } catch (e: Exception) {
            DebugLog.w(TAG) { "GPS start failed: ${e.message}" }
        }
    }

    fun stop() {
        if (!running) return
        lm.removeUpdates(listener)
        running = false
    }

    fun refreshEnabled(): Boolean {
        val nowEnabled = isLocationEnabled()
        _enabled.value = nowEnabled
        if (!nowEnabled && running) {
            stop()
        }
        return nowEnabled
    }

    /** Best last-known fix without starting updates (for routing before connecting). */
    @SuppressLint("MissingPermission")
    fun lastKnown(): android.location.Location? = try {
        _location.value
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
    } catch (e: Exception) {
        null
    }

    private fun isLocationEnabled(): Boolean = try {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) {
        false
    }
}
