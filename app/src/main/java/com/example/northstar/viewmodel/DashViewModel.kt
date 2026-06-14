package com.example.northstar.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.dash.DashKeepAliveService
import com.example.northstar.dash.DashSession
import com.example.northstar.dash.DashState
import com.example.northstar.dash.DashWifiManager
import com.example.northstar.dash.WifiConnStatus
import com.example.northstar.dash.map.LocationTracker
import com.example.northstar.dash.map.MapRenderer
import com.example.northstar.dash.map.Mercator
import com.example.northstar.dash.map.TileProvider
import com.example.northstar.dash.nav.GeoPoint
import com.example.northstar.dash.nav.NavEngine
import com.example.northstar.dash.nav.Route
import com.example.northstar.dash.nav.Router
import com.example.northstar.dash.protocol.DashCommands
import com.example.northstar.dash.video.DashEncoder
import com.example.northstar.dash.video.NalProcessor
import com.example.northstar.dash.video.RtpPacketizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnStage { OFFLINE, WIFI, AUTH, STREAMING, ERROR }

data class DashUiState(
    val stage: ConnStage = ConnStage.OFFLINE,
    val frameCount: Int = 0,
    val lastButton: String? = null,
    val ssid: String = "",            // empty until a dash is discovered/paired (see DashConfig)
    val wifiPassword: String = "12345678",  // RE Tripper factory passphrase; rider-overridable
    val destinationName: String? = null,
    val errorMessage: String? = null,
    val mapZoom: Int = 19,
    val remainingKm: Double? = null,
    val etaMinutes: Int? = null,
    val maneuver: String? = null,
    val hasGps: Boolean = false,
    val hasRoute: Boolean = false,
    val offRoute: Boolean = false,
    val headingUp: Boolean = true,
    val followMode: Boolean = true,
    val thermal: String = "OK",
    // For the in-app Google Map view
    val riderLat: Double? = null,
    val riderLng: Double? = null,
    val riderBearing: Float = 0f,
    val destLatLng: Pair<Double, Double>? = null,
    val routePoints: List<GeoPoint> = emptyList(),
)

class DashViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(DashUiState())
    val ui = _ui.asStateFlow()

    private val session     = DashSession(viewModelScope)
    private val wifiManager = DashWifiManager(app, viewModelScope)
    private val dashConfig  = com.example.northstar.dash.DashConfig.get(app)
    private val voice        = com.example.northstar.dash.nav.VoiceManager.get(app)
    private val repo         = com.example.northstar.data.SyncRepository.get(app)
    private val recorder     = com.example.northstar.data.RideRecorder()
    private var recordJob: Job? = null
    private val tiles       = TileProvider(app, viewModelScope)
    private val location    = LocationTracker(app)
    private val mapRenderer = MapRenderer(tiles)
    private val powerManager = app.getSystemService(Application.POWER_SERVICE) as PowerManager

    private var encoder: DashEncoder? = null
    private var streamJob: Job? = null

    private var userWantsConnection = false

    // ── Navigation/map state read by the 4 fps frame loop ──
    @Volatile private var destLat: Double? = null
    @Volatile private var destLng: Double? = null
    @Volatile private var route: Route? = null
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    @Volatile private var zoom = 19          // nav-level zoom on the dash (street-level default)
    @Volatile private var headingUp = true
    @Volatile private var followMode = true
    @Volatile private var lastManualPanAt = 0L

    // Smoothed camera — eased toward the latest GPS target every frame so the map
    // glides at 8 fps instead of jumping once per 1 Hz fix (the "cheap"/laggy feel).
    private var camLat = 0.0
    private var camLng = 0.0
    private var camHdg = 0f
    private var camInit = false

    // Off-route → reroute, debounced. Now feasible because the app keeps cellular
    // internet while bound to the dash (per-socket binding), so Router can run mid-ride.
    @Volatile private var offRouteSince = 0L
    @Volatile private var lastRerouteAt = 0L
    @Volatile private var rerouting = false

    // Map-matched rider position: snapped onto the route while on it (kills the GPS
    // lane/road jitter), raw GPS when genuinely off-route. Drives the marker + camera.
    @Volatile private var matchedLat: Double? = null
    @Volatile private var matchedLng: Double? = null

    // Frame cache (avoid the expensive redraw when nothing changed)
    private var frameBitmap: Bitmap? = null
    private var lastSignature = ""
    private var lastRedrawAt = 0L

    companion object {
        private const val MANUAL_IDLE_MS = 8_000L
        private const val FORCE_REDRAW_MS = 2_000L
    }

    init {
        // Reflect the rider's stored dash WiFi config (SSID may be blank until discovered).
        _ui.value = _ui.value.copy(ssid = dashConfig.ssid, wifiPassword = dashConfig.password)

        // When we connect to a previously-unknown dash by prefix, learn + persist its exact
        // SSID so subsequent connects target it directly (no system picker again).
        wifiManager.onSsidResolved = { learned ->
            dashConfig.ssid = learned
            _ui.value = _ui.value.copy(ssid = learned)
        }

        viewModelScope.launch {
            wifiManager.state.collect { ws ->
                when (ws.status) {
                    WifiConnStatus.CONNECTED -> {
                        refreshStage()
                        if (userWantsConnection &&
                            session.state.value in listOf(DashState.IDLE, DashState.ERROR)
                        ) {
                            delay(1_200)
                            session.connect(_ui.value.ssid, wifiManager.network)
                        }
                    }
                    WifiConnStatus.ERROR -> { _ui.value = _ui.value.copy(errorMessage = ws.error); refreshStage() }
                    else -> refreshStage()
                }
            }
        }

        viewModelScope.launch {
            session.state.collect { state ->
                refreshStage()
                if (state == DashState.READY) startStream()
            }
        }

        session.onError = { msg -> _ui.value = _ui.value.copy(errorMessage = msg); refreshStage() }
        // Joystick → map zoom only. RIGHT (0x09) = zoom in, LEFT (0x0A) = zoom out.
        // No exit gesture, no other map control (media section is for media).
        session.onButton = { btn ->
            val code = btn.toInt() and 0xFF
            val label = when (code) {
                0x09 -> { zoomIn();  "Zoom in (right)" }
                0x0A -> { zoomOut(); "Zoom out (left)" }
                else -> "code 0x${code.toString(16).uppercase()}"
            }
            _ui.value = _ui.value.copy(lastButton = label)
        }
    }

    private fun refreshStage() {
        val wifi = wifiManager.state.value.status
        val dash = session.state.value
        val stage = when {
            dash == DashState.STREAMING -> ConnStage.STREAMING
            dash == DashState.ERROR || wifi == WifiConnStatus.ERROR -> ConnStage.ERROR
            dash == DashState.AUTHENTICATING || dash == DashState.CONNECTING || dash == DashState.READY -> ConnStage.AUTH
            wifi == WifiConnStatus.REQUESTING || wifi == WifiConnStatus.CONNECTED -> ConnStage.WIFI
            else -> ConnStage.OFFLINE
        }
        _ui.value = _ui.value.copy(stage = stage)
    }

    // ── Connection ─────────────────────────────────────────────────────────

    fun connect() {
        userWantsConnection = true
        _ui.value = _ui.value.copy(errorMessage = null)
        DashKeepAliveService.start(getApplication())
        location.start()
        startRecording()

        // We must know the EXACT dash SSID before connecting — the dash validates it inside
        // the encrypted auth handshake (DashAuth), and Android redacts the SSID of a network
        // we've already joined. So if we don't have it stored, find it from a WiFi scan.
        if (dashConfig.needsDiscovery) {
            wifiManager.findDashSsid(dashConfig.ssidPrefix)?.let { found ->
                dashConfig.ssid = found
                _ui.value = _ui.value.copy(ssid = found)
            }
        }

        when {
            wifiManager.state.value.status == WifiConnStatus.CONNECTED ->
                session.connect(_ui.value.ssid, wifiManager.network)
            // Known SSID (stored or just found by scan) → exact connect + correct auth.
            dashConfig.ssid.isNotBlank() ->
                wifiManager.connect(dashConfig.ssid, dashConfig.password)
            // Couldn't find it in scan results — fall back to prefix discovery so we at
            // least associate (auth may still need the SSID; a rescan usually fixes it).
            else ->
                wifiManager.connect(dashConfig.ssidPrefix, dashConfig.password, prefixMatch = true)
        }
    }

    fun disconnect() {
        userWantsConnection = false
        stopRecording()        // a connect→disconnect session = one saved ride
        teardown()
        session.disconnect()
        wifiManager.disconnect()
        location.stop()
        DashKeepAliveService.stop(getApplication())
        refreshStage()
    }

    // ── Ride recording (the connected session) ───────────────────────────────
    private fun startRecording() {
        if (recorder.isRecording) return
        recorder.start()
        recordJob = viewModelScope.launch {
            location.location.collect { loc ->
                if (loc != null) recorder.add(loc.latitude, loc.longitude, loc.speed, loc.time)
            }
        }
    }

    private fun stopRecording() {
        recordJob?.cancel(); recordJob = null
        if (!recorder.isRecording) return
        val ride = recorder.stop() ?: return   // null = trivial session, don't save
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { repo.addRide(ride) }
    }

    // ── Dash WiFi config (Settings) ──────────────────────────────────────────
    fun setSsid(s: String) { dashConfig.ssid = s.trim(); _ui.value = _ui.value.copy(ssid = s.trim()) }
    fun setWifiPassword(p: String) { dashConfig.password = p; _ui.value = _ui.value.copy(wifiPassword = p) }
    /** Forget the paired dash so the next connect rediscovers any RE_* dash by prefix. */
    fun forgetDash() { dashConfig.forgetDash(); _ui.value = _ui.value.copy(ssid = "") }

    // ── Destination + routing ───────────────────────────────────────────────

    fun prefetchTiles(lat: Double, lng: Double) {
        val loc = location.location.value
        tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
    }

    fun setDestination(name: String, lat: Double?, lng: Double?) {
        _ui.value = _ui.value.copy(
            destinationName = name, hasRoute = false,
            destLatLng = if (lat != null && lng != null) lat to lng else null,
            routePoints = emptyList(),
        )
        destLat = lat
        destLng = lng
        route = null
        progressM = 0.0
        voice.resetTrip()   // fresh announcements for the new route
        session.updateRouteCard(name)
        if (lat != null && lng != null) {
            val loc = location.lastKnown()
            tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
            fetchRoute(lat, lng)
        }
    }

    /** Drop the destination/route → free roam. The map keeps streaming and follows the rider. */
    fun exitNavigation() {
        destLat = null
        destLng = null
        route = null
        progressM = 0.0
        offRouteSince = 0L
        panX = 0f; panY = 0f; followMode = true
        voice.resetTrip()
        lastSignature = ""   // force a redraw with no route line
        _ui.value = _ui.value.copy(
            destinationName = null,
            hasRoute = false,
            destLatLng = null,
            routePoints = emptyList(),
            remainingKm = null,
            etaMinutes = null,
            maneuver = null,
            offRoute = false,
            followMode = true,
        )
        session.updateRouteCard("Northstar")   // dash card → name + 0.0 km, nav off
    }

    /** Compute the road route now (while internet is reachable) and cache it. */
    private fun fetchRoute(destLatV: Double, destLngV: Double) {
        val loc = location.lastKnown()
        if (loc == null) {
            android.util.Log.w("DashViewModel", "fetchRoute: no origin location yet")
            return
        }
        viewModelScope.launch {
            val r = Router.route(GeoPoint(loc.latitude, loc.longitude), GeoPoint(destLatV, destLngV))
            if (r != null) {
                route = r
                tiles.prefetchRoute(r.geometry)
                _ui.value = _ui.value.copy(hasRoute = true, routePoints = r.geometry)
                android.util.Log.i("DashViewModel", "Route ready: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m")
            } else {
                android.util.Log.w("DashViewModel", "Router returned null")
            }
        }
    }

    // ── Map controls ────────────────────────────────────────────────────────

    fun zoomIn()  { zoom = (zoom + 1).coerceAtMost(20); _ui.value = _ui.value.copy(mapZoom = zoom) }
    fun zoomOut() { zoom = (zoom - 1).coerceAtLeast(11); _ui.value = _ui.value.copy(mapZoom = zoom) }
    fun panBy(dx: Float, dy: Float) = manualPan(dx, dy)
    fun recenter() {
        panX = 0f; panY = 0f; followMode = true
        _ui.value = _ui.value.copy(followMode = true)
    }
    fun toggleHeadingUp() {
        headingUp = !headingUp
        _ui.value = _ui.value.copy(headingUp = headingUp)
    }

    private fun manualPan(dx: Float, dy: Float) {
        panX += dx; panY += dy
        followMode = false
        lastManualPanAt = System.currentTimeMillis()
        _ui.value = _ui.value.copy(followMode = false)
    }

    // ── Video + nav loop ────────────────────────────────────────────────────

    private fun startStream() {
        val packetizer = RtpPacketizer { rtpPkt -> session.sendRtp(rtpPkt) }
        val nalProc    = NalProcessor { nal, _ ->
            packetizer.packetize(nal, endOfAU = true, wallClockMs = System.currentTimeMillis())
        }
        val onEncoded: (ByteArray, Boolean) -> Unit = { annexB, _ ->
            nalProc.process(annexB)
            _ui.value = _ui.value.copy(frameCount = _ui.value.frameCount + 1)
        }
        encoder?.release()
        encoder = DashEncoder(onEncoded).also { it.prepare() }

        frameBitmap = Bitmap.createBitmap(DashEncoder.WIDTH, DashEncoder.HEIGHT, Bitmap.Config.ARGB_8888)
        lastSignature = ""

        session.startStreaming()
        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }

        streamJob = viewModelScope.launch(Dispatchers.Default) {
            val intervalMs = 1000L / DashEncoder.FPS
            var lastPrefetch = 0L
            var failures = 0
            // The loop must NEVER die silently: the session's heartbeats keep the dash
            // connected, so a dead frame loop = frozen map with the connection "up".
            while (isActive && session.state.value == DashState.STREAMING) {
                try {
                    tick()
                    // Push the (possibly cached) frame to the encoder at a steady 4 fps.
                    val bmp = frameBitmap
                    val enc = encoder
                    if (bmp != null && enc != null) {
                        enc.renderFrame { canvas -> canvas.drawBitmap(bmp, 0f, 0f, null) }
                        enc.drain()
                    }
                    failures = 0
                    // Warm the tile cache ahead of the rider every ~20 s.
                    val now = System.currentTimeMillis()
                    if (now - lastPrefetch > 20_000) {
                        lastPrefetch = now
                        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    android.util.Log.e("DashViewModel", "Frame loop error #$failures", e)
                    if (failures >= 3) {
                        // MediaCodec is in an error state — rebuild the encoder so the
                        // stream recovers. The fresh encoder re-emits SPS/PPS, which the
                        // NAL processor bundles into the next IDR for the dash decoder.
                        runCatching { encoder?.release() }
                        encoder = runCatching { DashEncoder(onEncoded).also { it.prepare() } }
                            .onFailure { android.util.Log.e("DashViewModel", "Encoder rebuild failed", it) }
                            .getOrNull()
                        lastSignature = "" // force a full redraw on the next tick
                        failures = 0
                    }
                }
                delay(intervalMs)
            }
        }
    }

    /** Compute nav state, push nav-info to the dash, and redraw the frame only if it changed. */
    private fun tick() {
        // Revert to follow mode after the rider stops nudging the joystick.
        if (!followMode && System.currentTimeMillis() - lastManualPanAt > MANUAL_IDLE_MS) {
            panX = 0f; panY = 0f; followMode = true
            _ui.value = _ui.value.copy(followMode = true)
        }

        val loc = location.location.value
        val r = route
        val dLat = destLat; val dLng = destLng

        // Default to raw GPS; map-matching below snaps it onto the route when on it.
        matchedLat = loc?.latitude
        matchedLng = loc?.longitude

        var remainingM: Double? = null
        var etaSec: Double? = null
        var heading = loc?.bearing ?: 0f
        var offRoute = false

        if (r != null && loc != null) {
            val ns = trackProgress(r, GeoPoint(loc.latitude, loc.longitude))
            remainingM = ns.remainingM
            offRoute = ns.offRoute
            // NOTE: no marker snapping. Raw GPS is accurate; snapping to the route
            // polyline pinned the rider onto a parallel road in dense areas (showed
            // "Indira Enclave" when actually at "Isha"). trackProgress is still used
            // for nav distances + off-route/reroute detection (ns.offRoute), just not
            // to move the displayed marker.
            if (loc.speed < 0.5f) heading = ns.heading
            val speed = if (loc.speed > 0.5f) loc.speed.toDouble() else 11.0
            etaSec = ns.remainingM / speed
            // Feed the dash's own turn-by-turn widget with CORRECT distances (next-turn
            // + total remaining) and real arrival time. Glyph stays CONTINUE until
            // other codes are verified.
            val (pv, pu) = toDashDistance(ns.nextTurnM)
            val (tv, tu) = toDashDistance(ns.remainingM)
            val arrival = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.SECOND, etaSec!!.toInt())
            }
            val etaHHMM = "%02d%02d".format(
                arrival.get(java.util.Calendar.HOUR_OF_DAY), arrival.get(java.util.Calendar.MINUTE)
            )
            session.updateNavInfo(DashCommands.NAV_MANEUVER_CONTINUE, pv, pu, tv, tu, etaHHMM)
            // Spoken/chime turn guidance (no-op when voice mode is OFF).
            voice.maybeAnnounce(ns.nextManeuver, ns.nextTurnM, ns.remainingM)
        } else if (loc != null && dLat != null && dLng != null) {
            remainingM = GeoPoint.distMeters(
                GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng)
            )
        }

        // Recompute the route if the rider has clearly left it for a few seconds.
        maybeReroute(offRoute, loc)

        // Publish nav figures to the phone UI.
        _ui.value = _ui.value.copy(
            hasGps = loc != null,
            riderLat = matchedLat,
            riderLng = matchedLng,
            riderBearing = heading,
            remainingKm = remainingM?.let { it / 1000.0 },
            etaMinutes = etaSec?.let { (it / 60.0).toInt() },
            maneuver = null,
            offRoute = offRoute,
        )

        updateThermal()

        // Frame-center target: the rider, else the destination, else standby (0,0).
        val haveTarget = loc != null || (dLat != null && dLng != null)
        val targetLat = matchedLat ?: dLat ?: camLat
        val targetLng = matchedLng ?: dLng ?: camLng
        if (haveTarget) {
            if (!camInit) {
                camLat = targetLat; camLng = targetLng; camHdg = heading; camInit = true
            } else {
                // Ease ~0.35/frame → settles in <0.5 s at 8 fps, so the map slides
                // smoothly between 1 Hz fixes without visibly lagging the bike.
                val a = 0.35
                camLat += (targetLat - camLat) * a
                camLng += (targetLng - camLng) * a
                val dh = (((heading - camHdg) % 360f) + 540f) % 360f - 180f  // shortest arc
                camHdg += dh * a.toFloat()
            }
        }
        val centerLat = if (haveTarget) camLat else 0.0
        val centerLng = if (haveTarget) camLng else 0.0
        val camHeading = if (haveTarget) camHdg else heading

        val sig = buildString {
            // ~1 m resolution (5 dp) so each eased step redraws (smooth motion) while
            // sub-metre GPS jitter at a standstill doesn't churn the cache.
            append("%.5f".format(centerLat)); append("%.5f".format(centerLng))
            append(zoom); append(panX.toInt()); append(panY.toInt())
            append(if (headingUp) camHeading.toInt() else 0)
            append(remainingM?.let { (it / 100).toInt() } ?: -1) // 100 m resolution to avoid jitter
            append(if (r != null) r.geometry.size else 0)
        }
        val now = System.currentTimeMillis()
        if (sig != lastSignature || now - lastRedrawAt > FORCE_REDRAW_MS) {
            lastSignature = sig
            lastRedrawAt = now
            redrawFrame(centerLat, centerLng, camHeading, remainingM)
        }
    }

    /**
     * Reroute when off the line for >5 s (12 s cooldown between attempts). Routes from
     * the live GPS position to the saved destination and swaps the polyline in. Needs
     * internet — available now because only the dash sockets are bound to the dash WiFi.
     */
    private fun maybeReroute(offRoute: Boolean, loc: android.location.Location?) {
        val dLat = destLat; val dLng = destLng
        if (!offRoute || loc == null || dLat == null || dLng == null) { offRouteSince = 0L; return }
        val now = System.currentTimeMillis()
        if (offRouteSince == 0L) offRouteSince = now
        if (now - offRouteSince < 4_000 || now - lastRerouteAt < 12_000 || rerouting) return
        lastRerouteAt = now
        rerouting = true
        android.util.Log.i("DashViewModel", "Off-route ${(now - offRouteSince) / 1000}s → rerouting")
        viewModelScope.launch {
            val r = Router.route(GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng))
            if (r != null) {
                route = r
                progressM = 0.0
                offRouteSince = 0L
                tiles.prefetchRoute(r.geometry)
                _ui.value = _ui.value.copy(hasRoute = true, routePoints = r.geometry)
                android.util.Log.i("DashViewModel", "Reroute ok: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m")
            } else {
                android.util.Log.w("DashViewModel", "Reroute failed (no internet?)")
            }
            rerouting = false
        }
    }

    private fun redrawFrame(
        centerLat: Double, centerLng: Double, heading: Float, remainingM: Double?,
    ) {
        val bmp = frameBitmap ?: return
        val loc = location.location.value
        // Glanceable ETA, shown only while navigating to a destination.
        val mins = _ui.value.etaMinutes
        val navingToDest = destLat != null && destLng != null
        val etaPrimary = if (navingToDest && mins != null)
            (if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "$mins min") else null
        // ETA only — no distance (the dash's own widget shows distance).
        val etaSecondary = if (etaPrimary != null && mins != null) {
            "arrives " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(System.currentTimeMillis() + (mins * 60_000L)))
        } else null
        val frame = MapRenderer.Frame(
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = zoom,
            panX = panX,
            panY = panY,
            headingUp = headingUp && (loc != null),
            heading = heading,
            riderLat = matchedLat,
            riderLng = matchedLng,
            destLat = destLat,
            destLng = destLng,
            destName = _ui.value.destinationName,
            route = route?.geometry ?: emptyList(),
            maneuverText = null, // turn-by-turn maneuver banner removed
            remainingText = remainingM?.let { fmtDist(it) },
            // Top-down (heading-up) nav view. The 3D perspective tilt is DISABLED: warping
            // flat raster tiles via setPolyToPoly stretches the baked-in map labels and
            // skews the angle (you can't get true Google-Maps 3D without vector tiles).
            tilt3d = false,
            etaPrimary = etaPrimary,
            etaSecondary = etaSecondary,
        )
        mapRenderer.draw(Canvas(bmp), frame)
    }

    // ── Monotonic route-progress tracker ────────────────────────────────────
    // Snapping to the GLOBALLY nearest segment makes the remaining distance flicker
    // (a winding route can pass near the start again, so it jumps to ~straight-line).
    // Instead we advance progress along the route, searching a window AHEAD of where
    // we already are, only re-acquiring globally if we're clearly off-route.
    @Volatile private var progressM = 0.0

    private data class NavState(
        val remainingM: Double, val nextTurnM: Double, val heading: Float, val offRoute: Boolean,
        val snapped: GeoPoint, val snapDist: Double,
        val nextManeuver: com.example.northstar.dash.nav.Maneuver?,
    )

    private data class Match(val cum: Double, val dist: Double, val bearing: Float, val proj: GeoPoint)

    private fun trackProgress(r: Route, pos: GeoPoint): NavState {
        val geom = r.geometry
        val cum = r.cumulative

        fun search(lo: Double, hi: Double): Match {
            var bestDist = Double.MAX_VALUE; var bestCum = progressM; var bestBearing = 0f; var bestProj = pos
            for (i in 0 until geom.size - 1) {
                if (cum[i + 1] < lo || cum[i] > hi) continue
                val (proj, t) = GeoPoint.projectOnSegment(pos, geom[i], geom[i + 1])
                val d = GeoPoint.distMeters(pos, proj)
                if (d < bestDist) {
                    bestDist = d
                    bestCum = cum[i] + GeoPoint.distMeters(geom[i], geom[i + 1]) * t
                    bestBearing = GeoPoint.bearing(geom[i], geom[i + 1]).toFloat()
                    bestProj = proj
                }
            }
            return Match(bestCum, bestDist, bestBearing, bestProj)
        }

        var m = search(progressM - 60.0, progressM + 1000.0)
        if (m.dist > 80.0) {
            val g = search(0.0, r.totalMeters) // off-window → re-acquire globally
            if (g.dist < m.dist) m = g
        }
        progressM = maxOf(progressM - 25.0, m.cum) // mostly forward, tolerate small GPS slide

        val remaining = (r.totalMeters - progressM).coerceAtLeast(0.0)
        val nextMan = r.maneuvers.firstOrNull {
            it.cumulativeMeters > progressM + 1.0 && it.type != com.example.northstar.dash.nav.ManeuverType.DEPART
        }
        val nextTurn = nextMan?.let { (it.cumulativeMeters - progressM).coerceAtLeast(0.0) } ?: remaining
        return NavState(remaining, nextTurn, m.bearing, m.dist > 70.0, m.proj, m.dist, nextMan)
    }

    private fun updateThermal() {
        val status = runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val label = when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "OK"
            PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> "Hot"
            else -> "Throttling"
        }
        if (label != _ui.value.thermal) _ui.value = _ui.value.copy(thermal = label)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** metres → (value, dash unit). <1 km → metres (0x30); else km×10 (0x10). */
    private fun toDashDistance(meters: Double): Pair<Int, Int> =
        if (meters < 1000.0) meters.toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_METERS
        else (meters / 100.0).toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_KM_TENTHS

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)

    private fun teardown() {
        streamJob?.cancel(); streamJob = null
        encoder?.release(); encoder = null
        frameBitmap?.recycle(); frameBitmap = null
    }

    override fun onCleared() {
        super.onCleared()
        stopRecording()        // save the in-progress ride if the app is closed mid-session
        teardown()
        session.disconnect()
        wifiManager.disconnect()
        location.stop()
        voice.shutdown()
    }
}
