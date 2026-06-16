package com.example.opendash.data

import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.dash.nav.PolylineCodec

/**
 * Accumulates GPS fixes for one ride (a connect→disconnect dash session) and produces a
 * [Ride] at the end. Points are thinned to ~every [MIN_MOVE_M] metres so the stored track
 * stays small; distance/speed are computed from the accepted points.
 *
 * Not thread-safe — driven from a single coroutine in DashViewModel.
 */
class RideRecorder {

    private val points = ArrayList<GeoPoint>()
    private var startMs = 0L
    private var lastMs = 0L
    private var distanceM = 0.0
    private var maxSpeed = 0.0
    private var last: GeoPoint? = null
    private var recording = false

    val isRecording: Boolean get() = recording

    fun start() {
        points.clear(); distanceM = 0.0; maxSpeed = 0.0; last = null
        startMs = System.currentTimeMillis(); lastMs = startMs
        recording = true
    }

    fun add(lat: Double, lng: Double, speedMps: Float, timeMs: Long) {
        if (!recording) return
        val p = GeoPoint(lat, lng)
        val prev = last
        if (prev == null) {
            points.add(p); last = p; lastMs = timeMs; return
        }
        val step = GeoPoint.distMeters(prev, p)
        if (step < MIN_MOVE_M) return   // thin out jitter / stationary noise
        distanceM += step
        // Only count max speed on genuine movement, so parked GPS speed spikes don't inflate it.
        if (speedMps > maxSpeed) maxSpeed = speedMps.toDouble()
        points.add(p); last = p; lastMs = timeMs
    }

    /**
     * Finish the ride. Returns null for a trivial session (barely moved / too short) so we
     * don't litter the history with parking-lot blips.
     */
    fun stop(): Ride? {
        recording = false
        val end = System.currentTimeMillis()
        val durationS = ((end - startMs) / 1000L).coerceAtLeast(0)
        if (points.size < 2 || (distanceM < MIN_RIDE_M && durationS < MIN_RIDE_S)) return null
        val avg = if (durationS > 0) distanceM / durationS else 0.0
        val first = points.first(); val lastPt = points.last()
        return Ride(
            sid = OpenDashDb.newSid(),
            startMs = startMs, endMs = end,
            distanceMeters = distanceM, durationSec = durationS,
            avgSpeedMps = avg, maxSpeedMps = maxSpeed,
            trackPolyline = PolylineCodec.encode(points),
            startLat = first.lat, startLng = first.lng, endLat = lastPt.lat, endLng = lastPt.lng,
        )
    }

    companion object {
        private const val MIN_MOVE_M = 8.0     // thin track points to ~every 8 m
        private const val MIN_RIDE_M = 150.0   // discard rides shorter than this…
        private const val MIN_RIDE_S = 90L     // …unless they lasted at least this long
    }
}
