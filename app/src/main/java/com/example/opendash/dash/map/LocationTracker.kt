package com.example.opendash.dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
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

    private val listener = LocationListener { loc ->
        val cur = _location.value
        if (acceptFix(cur, loc)) {
            _location.value = loc
            Log.d(TAG, "fix ${loc.provider} acc=${loc.accuracy} (${loc.latitude},${loc.longitude})")
        } else {
            Log.d(TAG, "REJECT ${loc.provider} acc=${loc.accuracy} dt=${loc.time - (cur?.time ?: 0)}ms")
        }
    }

    /**
     * Decide whether [loc] should replace [cur].
     *  - GPS always wins.
     *  - A coarse NETWORK fix is ignored while a recent GPS fix exists — otherwise it
     *    jumps the marker (and the camera/route) to a cell-tower estimate km away,
     *    which is exactly what happens with the screen off as GPS callbacks slow.
     *  - Physically impossible jumps (bad fixes) are rejected.
     */
    private fun acceptFix(cur: Location?, loc: Location): Boolean {
        if (cur == null) return true
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        if (!isGps && cur.provider == LocationManager.GPS_PROVIDER &&
            loc.time - cur.time < GPS_STALE_MS
        ) return false
        if (loc.time < cur.time) return false
        val dt = (loc.time - cur.time) / 1000.0
        if (dt > 0 && cur.distanceTo(loc) > 200f && cur.distanceTo(loc) / dt > 85.0) return false
        return true
    }

    private var running = false

    /** Requires ACCESS_FINE_LOCATION at runtime; no-ops without it. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        try {
            _location.value = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            // GPS for accuracy + heading; NETWORK as a fallback while GPS warms up.
            // minDistance=0: keep GPS fixes flowing every second even when parked.
            // With a minimum distance, GPS goes quiet while stationary, its last fix
            // ages out, and a coarse NETWORK fix takes over → the marker drifts.
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                }
            }
            running = true
            Log.i(TAG, "Location updates started")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing — GPS disabled")
        } catch (e: Exception) {
            Log.w(TAG, "GPS start failed: ${e.message}")
        }
    }

    fun stop() {
        if (!running) return
        lm.removeUpdates(listener)
        running = false
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
}
