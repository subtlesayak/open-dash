package com.example.northstar.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.data.DashWallpaperKind
import com.example.northstar.data.DashWallpaperInfo
import com.example.northstar.data.DashWallpaperStore
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
import com.example.northstar.dash.video.DashIdleRenderer
import com.example.northstar.dash.video.NalProcessor
import com.example.northstar.dash.video.RtpPacketizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    val wallpaperPath: String? = null,
    val wallpaperKind: DashWallpaperKind? = null,
    val wallpaperCropX: Float = 0f,
    val wallpaperCropY: Float = 0f,
    val wallpaperGalleryCount: Int = 0,
    val wallpaperGalleryIndex: Int = 0,
    val wallpaperSaving: Boolean = false,
    val wallpaperError: String? = null,
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
    private val wallpaperStore = DashWallpaperStore(app)
    private val idleRenderer = DashIdleRenderer()
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
    private var lastTickNs = 0L          // for framerate-independent smoothing

    // Dead-reckoning: last GPS fix + its velocity, so the camera keeps gliding forward
    // between the 1 Hz fixes instead of easing to a stop (the "laggy" feel at speed).
    private var fixLat = 0.0
    private var fixLng = 0.0
    private var fixBearing = 0f
    private var fixSpeed = 0f             // m/s
    private var fixWallMs = 0L
    private var lastFixTime = 0L

    // Smoothed rider position shown on the dash frame (locked to the camera centre so the
    // marker stays put and the map slides under it). null = no GPS.
    @Volatile private var frameRiderLat: Double? = null
    @Volatile private var frameRiderLng: Double? = null
    @Volatile private var camMoving = false   // drives the dynamic frame rate

    // Stable ETA (Google-like): the raw estimate jitters with instantaneous speed, so we
    // smooth it and only recompute the absolute arrival clock occasionally — no per-frame churn.
    private var smoothEtaSec = 0.0
    @Volatile private var etaArrivalMs = 0L
    private var lastArrivalCalcMs = 0L

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
        private const val SMOOTH_TAU = 0.28      // camera smoothing time constant (s)
        private const val FPS_MOVING = 24        // buttery while riding
        private const val FPS_IDLE = 7           // throttle when stopped (saves power)
    }

    /** Project a lat/lng forward [distM] metres along [bearingDeg] (great-circle). */
    private fun project(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val r = 6_371_000.0
        val br = Math.toRadians(bearingDeg)
        val dr = distM / r
        val lat1 = Math.toRadians(lat); val lng1 = Math.toRadians(lng)
        val lat2 = Math.asin(Math.sin(lat1) * Math.cos(dr) + Math.cos(lat1) * Math.sin(dr) * Math.cos(br))
        val lng2 = lng1 + Math.atan2(
            Math.sin(br) * Math.sin(dr) * Math.cos(lat1),
            Math.cos(dr) - Math.sin(lat1) * Math.sin(lat2),
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lng2)
    }

    init {
        // Reflect the rider's stored dash WiFi config (SSID may be blank until discovered).
        _ui.value = _ui.value.copy(
            ssid = dashConfig.ssid,
            wifiPassword = dashConfig.password,
        )
        publishWallpaper(wallpaperStore.currentInfo())

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
                            // Re-check: the user may have hit Disconnect during the delay.
                            if (userWantsConnection &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED
                            ) session.connect(_ui.value.ssid, wifiManager.network)
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
        // Joystick left/right cycles idle wallpapers; during navigation it remains zoom.
        session.onButton = { btn ->
            val code = btn.toInt() and 0xFF
            val label = when (code) {
                0x09 -> {
                    if (isIdleWallpaperMode()) {
                        cycleWallpaper(1)
                        "Next wallpaper"
                    } else {
                        zoomIn()
                        "Zoom in (right)"
                    }
                }
                0x0A -> {
                    if (isIdleWallpaperMode()) {
                        cycleWallpaper(-1)
                        "Previous wallpaper"
                    } else {
                        zoomOut()
                        "Zoom out (left)"
                    }
                }
                else -> when {
                    isIdleWallpaperMode() && isNextWallpaperButton(code) -> {
                        cycleWallpaper(1)
                        "Next wallpaper"
                    }
                    isIdleWallpaperMode() && isPreviousWallpaperButton(code) -> {
                        cycleWallpaper(-1)
                        "Previous wallpaper"
                    }
                    else -> "code 0x${code.toString(16).uppercase()}"
                }
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

    fun setWallpaperFromUri(uri: Uri, horizontalBias: Float = 0f, verticalBias: Float = 0f) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(wallpaperSaving = true, wallpaperError = null) }
            runCatching {
                wallpaperStore.saveFromUri(uri, horizontalBias, verticalBias)
                wallpaperStore.currentInfo()
            }.onSuccess { info ->
                    lastSignature = ""
                    publishWallpaper(info, saving = false, error = null)
                }
                .onFailure { err ->
                    val msg = err.message ?: "Unable to save wallpaper"
                    _ui.update {
                        it.copy(
                            wallpaperSaving = false,
                            wallpaperError = msg,
                            errorMessage = msg,
                        )
                    }
                }
        }
    }

    fun addWallpapersFromUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(wallpaperSaving = true, wallpaperError = null) }
            runCatching {
                wallpaperStore.saveManyFromUris(uris)
                wallpaperStore.currentInfo()
            }.onSuccess { info ->
                lastSignature = ""
                publishWallpaper(info, saving = false, error = null)
            }.onFailure { err ->
                val msg = err.message ?: "Unable to save wallpapers"
                _ui.update {
                    it.copy(
                        wallpaperSaving = false,
                        wallpaperError = msg,
                        errorMessage = msg,
                    )
                }
            }
        }
    }

    fun clearWallpaper() {
        viewModelScope.launch(Dispatchers.IO) {
            val next = wallpaperStore.clearCurrent()
            lastSignature = ""
            publishWallpaper(next, saving = false, error = null)
        }
    }

    fun cycleWallpaperFromSettings(delta: Int) {
        cycleWallpaper(delta)
    }

    private fun cycleWallpaper(delta: Int) {
        val next = wallpaperStore.cycle(delta)
        lastSignature = ""
        publishWallpaper(next)
    }

    private fun publishWallpaper(
        info: DashWallpaperInfo?,
        saving: Boolean = _ui.value.wallpaperSaving,
        error: String? = _ui.value.wallpaperError,
    ) {
        val gallery = wallpaperStore.allInfos()
        val index = info?.let { current -> gallery.indexOfFirst { it.slot == current.slot } } ?: -1
        _ui.update {
            it.copy(
                wallpaperPath = info?.path,
                wallpaperKind = info?.kind,
                wallpaperCropX = info?.horizontalBias ?: 0f,
                wallpaperCropY = info?.verticalBias ?: 0f,
                wallpaperGalleryCount = gallery.size,
                wallpaperGalleryIndex = if (index >= 0) index else 0,
                wallpaperSaving = saving,
                wallpaperError = error,
                errorMessage = error ?: it.errorMessage,
            )
        }
    }

    private fun isIdleWallpaperMode(): Boolean =
        destLat == null && destLng == null && route == null

    private fun isNextWallpaperButton(code: Int): Boolean =
        code == 0x06 || code == 0x09 || code == 0x22

    private fun isPreviousWallpaperButton(code: Int): Boolean =
        code == 0x05 || code == 0x07 || code == 0x0A

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
        smoothEtaSec = 0.0; etaArrivalMs = 0L   // fresh ETA for the new route
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
        smoothEtaSec = 0.0; etaArrivalMs = 0L
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
            // Atomic update: this runs on the encoder's callback thread, concurrent with the
            // frame loop's _ui writes — a plain copy() read-modify-write would drop updates.
            _ui.update { it.copy(frameCount = it.frameCount + 1) }
        }
        encoder?.release()
        encoder = DashEncoder(onEncoded).also { it.prepare() }

        frameBitmap = Bitmap.createBitmap(DashEncoder.WIDTH, DashEncoder.HEIGHT, Bitmap.Config.ARGB_8888)
        lastSignature = ""
        // Fresh camera so it snaps to the first fix instead of gliding from a stale spot.
        camInit = false; lastTickNs = 0L; lastFixTime = 0L

        session.startStreaming()
        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }

        streamJob = viewModelScope.launch(Dispatchers.Default) {
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
                // Dynamic pacing: buttery while moving, throttled when stopped (power).
                delay(1000L / (if (camMoving) FPS_MOVING else FPS_IDLE))
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
        // Keep the last heading on GPS dropout (tunnels) — don't snap the map to north.
        var heading = loc?.bearing ?: (if (camInit) camHdg else 0f)
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
            // Smooth the ETA so it doesn't flicker every second with raw speed; recompute the
            // absolute arrival clock only every 5 s so "arrives 1:32 PM" stays steady.
            val rawEta = ns.remainingM / speed
            smoothEtaSec = if (smoothEtaSec <= 0.0) rawEta else smoothEtaSec + (rawEta - smoothEtaSec) * 0.08
            etaSec = smoothEtaSec
            val nowMs = System.currentTimeMillis()
            if (etaArrivalMs == 0L || nowMs - lastArrivalCalcMs > 5_000) {
                etaArrivalMs = nowMs + (smoothEtaSec * 1000).toLong()
                lastArrivalCalcMs = nowMs
            }
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

        // ── Predictive, framerate-independent camera (CarPlay-smooth) ──
        // Capture each fresh GPS fix + its velocity for dead-reckoning.
        if (loc != null && loc.time != lastFixTime) {
            lastFixTime = loc.time
            fixLat = loc.latitude; fixLng = loc.longitude
            fixBearing = loc.bearing; fixSpeed = loc.speed
            fixWallMs = System.currentTimeMillis()
        }
        // Extrapolate where the rider IS NOW (last fix + velocity·elapsed), so the camera
        // doesn't trail the bike between 1 Hz fixes. Only predict while genuinely moving.
        val rider: Pair<Double, Double>? = when {
            loc == null -> null
            fixSpeed > 1.0f -> {
                val elapsed = ((System.currentTimeMillis() - fixWallMs) / 1000.0).coerceAtMost(1.5)
                project(fixLat, fixLng, fixBearing.toDouble(), fixSpeed * elapsed)
            }
            else -> matchedLat!! to matchedLng!!
        }

        val haveTarget = rider != null || (dLat != null && dLng != null)
        val targetLat = rider?.first ?: dLat ?: camLat
        val targetLng = rider?.second ?: dLng ?: camLng

        // Time-based smoothing: alpha derived from the real frame interval + a time
        // constant, so motion is equally smooth at any (dynamic) frame rate.
        val nowNs = System.nanoTime()
        val dt = if (lastTickNs == 0L) 0.042 else ((nowNs - lastTickNs) / 1e9).coerceIn(0.0, 0.5)
        lastTickNs = nowNs
        val a = if (camInit) (1.0 - Math.exp(-dt / SMOOTH_TAU)) else 1.0

        val prevLat = camLat; val prevLng = camLng
        if (haveTarget) {
            if (!camInit) { camLat = targetLat; camLng = targetLng; camHdg = heading; camInit = true }
            else {
                camLat += (targetLat - camLat) * a
                camLng += (targetLng - camLng) * a
                val dh = (((heading - camHdg) % 360f) + 540f) % 360f - 180f  // shortest arc
                camHdg += dh * a.toFloat()
            }
        }
        // Lock the dash marker to the smoothed centre (map slides under it).
        frameRiderLat = if (rider != null) camLat else null
        frameRiderLng = if (rider != null) camLng else null
        // Drive the dynamic frame rate: full speed while moving/turning, throttle when idle.
        val movedM = if (camInit) GeoPoint.distMeters(GeoPoint(prevLat, prevLng), GeoPoint(camLat, camLng)) else 0.0
        camMoving = movedM > 0.25 || (loc?.speed ?: 0f) > 0.8f

        val centerLat = if (haveTarget) camLat else 0.0
        val centerLng = if (haveTarget) camLng else 0.0
        val camHeading = if (haveTarget) camHdg else heading

        val sig = buildString {
            append(if (r == null && dLat == null && dLng == null) "idle:${_ui.value.wallpaperPath}" else "nav")
            // High resolution (6 dp ≈ 0.1 m, 0.1° heading) so every smoothed step redraws
            // for buttery motion. Safe from standstill jitter because the camera is fed the
            // SMOOTHED position (which settles and stops), not raw GPS.
            append("%.6f".format(centerLat)); append("%.6f".format(centerLng))
            append(zoom); append(panX.toInt()); append(panY.toInt())
            append(if (headingUp) (camHeading * 10).toInt() else 0)
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
        if (route == null && destLat == null && destLng == null) {
            idleRenderer.draw(
                Canvas(bmp),
                _ui.value.wallpaperPath,
                _ui.value.wallpaperKind,
                _ui.value.wallpaperCropX,
                _ui.value.wallpaperCropY,
            )
            return
        }
        // Glanceable ETA — minutes remaining + a stable 12-hour arrival clock. Both come
        // from the smoothed estimate so they don't flicker every second.
        val mins = _ui.value.etaMinutes
        val arriving = mins != null && mins <= 0
        val etaPrimary = if (mins != null && etaArrivalMs > 0L) when {
            arriving   -> "Arriving"
            mins >= 60 -> "${mins / 60}h ${mins % 60}m"
            else       -> "$mins min"
        } else null
        // 12-hour arrival clock; hidden once arriving.
        val etaSecondary = if (etaPrimary != null && !arriving)
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(etaArrivalMs))
        else null
        val frame = MapRenderer.Frame(
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = zoom,
            panX = panX,
            panY = panY,
            headingUp = headingUp && (loc != null),
            heading = heading,
            riderLat = frameRiderLat,
            riderLng = frameRiderLng,
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
        idleRenderer.release()
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
