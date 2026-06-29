package com.example.opendash.viewmodel

import android.annotation.SuppressLint
import com.example.opendash.data.DashDisplayMode
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.CallLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opendash.data.DashWallpaperFit
import com.example.opendash.data.ExperimentalNavigationSettings
import com.example.opendash.data.DashStreamQuality
import com.example.opendash.data.DashStreamSettings
import com.example.opendash.data.DashWallpaperKind
import com.example.opendash.data.DashWallpaperInfo
import com.example.opendash.data.DashWallpaperStore
import com.example.opendash.data.VehicleStore
import com.example.opendash.dash.DashConfig
import com.example.opendash.dash.DashKeepAliveService
import com.example.opendash.dash.DashSession
import com.example.opendash.dash.DashState
import com.example.opendash.dash.DashWifiManager
import com.example.opendash.dash.WifiConnStatus
import com.example.opendash.dash.map.LocationTracker
import com.example.opendash.dash.map.MapRenderer
import com.example.opendash.dash.map.Mercator
import com.example.opendash.dash.map.TileProvider
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.dash.nav.NavEngine
import com.example.opendash.dash.nav.Route
import com.example.opendash.dash.nav.Router
import com.example.opendash.dash.protocol.DashCommands
import com.example.opendash.dash.video.DashEncoder
import com.example.opendash.dash.video.DashIdleRenderer
import com.example.opendash.dash.video.NalProcessor
import com.example.opendash.dash.video.RtpPacketizer
import com.example.opendash.media.CallController
import com.example.opendash.media.CallInfoProvider
import com.example.opendash.media.IncomingCall
import com.example.opendash.media.MediaInfoProvider
import com.example.opendash.media.NowPlaying
import com.example.opendash.media.RecentCall
import com.example.opendash.navigation.provider.DashManeuverType
import com.example.opendash.navigation.provider.DashRoute
import com.example.opendash.navigation.provider.MapboxNavigationProvider
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

enum class ConnStage { OFFLINE, WIFI, AUTH, STREAMING, ERROR }
enum class GpsStatus { GOOD, WEAK, LOST }

private enum class PhoneMenuMode { CATEGORIES, CONTACTS, CALLING }

private enum class PhoneCallCategory(val label: String) {
    MISSED("Missed Calls"),
    RECEIVED("Received Calls"),
    DIALED("Dialed Calls"),
    FAVORITES("Favorites"),
}

data class DashUiState(
    val stage: ConnStage = ConnStage.OFFLINE,
    val frameCount: Int = 0,
    val lastButton: String? = null,
    val ssid: String = "",            // empty until a dash is discovered/paired (see DashConfig)
    val wifiPassword: String = "12345678",  // Dash factory passphrase; rider-overridable
    val destinationName: String? = null,
    val errorMessage: String? = null,
    val mapZoom: Int = 19,
    val remainingKm: Double? = null,
    val etaMinutes: Int? = null,
    val maneuver: String? = null,
    val hasGps: Boolean = false,
    val gpsStatus: GpsStatus = GpsStatus.LOST,
    val locationServicesEnabled: Boolean = true,
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
    val wallpaperFit: DashWallpaperFit = DashWallpaperFit.CROP,
    val wallpaperCropX: Float = 0f,
    val wallpaperCropY: Float = 0f,
    val wallpaperPreserveRpmArc: Boolean = false,
    val wallpaperGalleryCount: Int = 0,
    val wallpaperGalleryIndex: Int = 0,
    val wallpaperSaving: Boolean = false,
    val wallpaperError: String? = null,
    val pendingPairingSsid: String? = null,
)

class DashViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(DashUiState())
    val ui = _ui.asStateFlow()

    private val session     = DashSession(viewModelScope)
    private val wifiManager = DashWifiManager(app, viewModelScope)
    private val dashConfig  = DashConfig.get(app)
    private val voice        = com.example.opendash.dash.nav.VoiceManager.get(app)
    private val repo         = com.example.opendash.data.SyncRepository.get(app)
    private val recorder     = com.example.opendash.data.RideRecorder()
    private val wallpaperStore = DashWallpaperStore(app)
    private val idleRenderer = DashIdleRenderer()
    private var recordJob: Job? = null
    private val tiles       = TileProvider(app, viewModelScope)
    private val mapboxNavigationProvider = MapboxNavigationProvider(app, viewModelScope)
    private val location    = LocationTracker(app)
    private val mapRenderer = MapRenderer(tiles)
    private val powerManager = app.getSystemService(Application.POWER_SERVICE) as PowerManager
    private val mediaInfo = MediaInfoProvider(app)
    private val callController = CallController(app)

    private var encoder: DashEncoder? = null
    private var streamJob: Job? = null
    private var mediaObserveJob: Job? = null
    private var mediaCardJob: Job? = null
    @Volatile private var mediaCardVisible = false
    @Volatile private var mediaCardMessage = ""
    @Volatile private var currentMedia: NowPlaying? = null
    @Volatile private var mediaControlUntilMs = 0L
    @Volatile private var activeCallStartedAtMs = 0L
    @Volatile private var activeCallIdentity = ""
    private var phoneCardJob: Job? = null
    @Volatile private var phoneCardVisible = false
    @Volatile private var phoneCardTitle = ""
    @Volatile private var phoneCardLabel = ""
    @Volatile private var phoneCardSubtitle = ""
    @Volatile private var phoneCardRows: List<String> = emptyList()
    @Volatile private var phoneMenuMode = PhoneMenuMode.CATEGORIES
    private var phoneRecentCalls: List<RecentCall> = emptyList()
    private var phoneAllCalls: List<RecentCall> = emptyList()
    private var phoneCategoryIndex = 0
    private var phoneRecentIndex = 0

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
    @Volatile private var gpsStatus = GpsStatus.LOST

    // Smoothed rider position shown on the dash frame (locked to the camera centre so the
    // marker stays put and the map slides under it). null = no GPS.
    @Volatile private var frameRiderLat: Double? = null
    @Volatile private var frameRiderLng: Double? = null
    @Volatile private var camMoving = false   // drives the dynamic frame rate
    @Volatile private var streamQuality = DashStreamQuality.EXPERIMENTAL

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
    @Volatile private var routeRequestInFlight = false
    @Volatile private var lastRouteRequestAt = 0L

    // Map-matched rider position: snapped onto the route while on it (kills the GPS
    // lane/road jitter), raw GPS when genuinely off-route. Drives the marker + camera.
    @Volatile private var matchedLat: Double? = null
    @Volatile private var matchedLng: Double? = null

    // Frame cache (avoid the expensive redraw when nothing changed)
    private var frameBitmap: Bitmap? = null
    private var lastSignature = ""
    private var lastRedrawAt = 0L
    @Volatile private var wallpaperFrameRevision = 0

    companion object {
        private const val MANUAL_IDLE_MS = 8_000L
        private const val FORCE_REDRAW_MS = 2_000L
        private const val SMOOTH_TAU = 0.28      // camera smoothing time constant (s)
        private const val BTN_JOYSTICK_RIGHT = 0x13
        private const val BTN_JOYSTICK_LEFT = 0x14
        private const val BTN_JOYSTICK_DOWN = 0x15
        private const val BTN_JOYSTICK_UP = 0x16
        private const val BTN_JOYSTICK_CLICK = 0x18
        private const val BTN_CALL_ANSWER = 0x06
        private const val BTN_CALL_REJECT = 0x07
        private const val BTN_PHONE_RECENTS = 0x05
        private const val BTN_PHONE_SELECT = BTN_JOYSTICK_CLICK
        private const val BTN_NATIVE_SELECT = 0x22
        private const val BTN_MAP_ZOOM_IN = 0x14
        private const val BTN_MAP_ZOOM_OUT = 0x13
        private const val BTN_MEDIA_NEXT = 0x09
        private const val BTN_MEDIA_PREVIOUS = 0x0A
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
        ExperimentalNavigationSettings.init(app)
        DashStreamSettings.init(app)
        streamQuality = DashStreamSettings.current()
        // Reflect the rider's stored dash WiFi config (SSID may be blank until discovered).
        _ui.value = _ui.value.copy(
            ssid = dashConfig.ssid,
            wifiPassword = dashConfig.password,
            locationServicesEnabled = location.refreshEnabled(),
        )
        publishWallpaper(wallpaperStore.currentInfo())

        viewModelScope.launch {
            DashStreamSettings.quality.collect { quality ->
                streamQuality = quality
                lastSignature = ""
            }
        }

        viewModelScope.launch {
            VehicleStore.activeVehicleId.collect {
                if (!userWantsConnection) {
                    _ui.value = _ui.value.copy(
                        ssid = dashConfig.ssid,
                        wifiPassword = dashConfig.password,
                        pendingPairingSsid = null,
                    )
                }
            }
        }

        // When we connect to a previously-unknown dash by prefix, Android can reveal the
        // exact SSID. Do not persist it until the rider confirms the pairing.
        wifiManager.onSsidResolved = { learned ->
            requestPairingConfirmation(learned)
        }

        viewModelScope.launch {
            wifiManager.state.collect { ws ->
                when (ws.status) {
                    WifiConnStatus.CONNECTED -> {
                        _ui.value = _ui.value.copy(errorMessage = null)
                        refreshStage()
                        if (userWantsConnection &&
                            session.state.value in listOf(DashState.IDLE, DashState.ERROR)
                        ) {
                            delay(1_200)
                            // Re-check: the user may have hit Disconnect during the delay.
                            if (userWantsConnection &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED &&
                                _ui.value.pendingPairingSsid == null
                            ) {
                                connectSessionWhenSsidResolved()
                            }
                        }
                    }
                    WifiConnStatus.ERROR -> { _ui.value = _ui.value.copy(errorMessage = ws.error); refreshStage() }
                    WifiConnStatus.REQUESTING -> {
                        _ui.value = _ui.value.copy(errorMessage = ws.error)
                        refreshStage()
                    }
                    else -> {
                        _ui.value = _ui.value.copy(errorMessage = null)
                        refreshStage()
                    }
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
        session.onAmbientLight = { ambient ->
            DashDisplayMode.updateFromDashAmbient(ambient)
        }
        session.onButton = { btn ->
            val code = btn.toInt() and 0xFF
            val call = CallInfoProvider.incomingCall.value
            val mediaControlActive = mediaCardVisible || System.currentTimeMillis() < mediaControlUntilMs
            val label = when {
                call != null && isCallVolumeUpButton(code) -> {
                    callController.volumeUp()
                    "Call volume up"
                }
                call != null && isCallVolumeDownButton(code) -> {
                    callController.volumeDown()
                    "Call volume down"
                }
                call?.incoming == true && isCallAnswerButton(code) -> {
                    answerCall(call)
                    "Call answered"
                }
                call != null && isCallRejectButton(code) -> {
                    endCall(call)
                    if (call.incoming) "Call rejected" else "Call ended"
                }
                phoneCardVisible && code == BTN_PHONE_SELECT -> {
                    selectPhoneCardItem()
                }
                phoneCardVisible && isNextContactButton(code) -> {
                    stepPhoneCard(1)
                }
                phoneCardVisible && isPreviousContactButton(code) -> {
                    stepPhoneCard(-1)
                }
                phoneCardVisible && isClosePhoneCardButton(code) -> {
                    backOrClosePhoneCard()
                }
                code == BTN_PHONE_RECENTS -> {
                    showPhoneMenuOnDash()
                }
                isMediaPlayButton(code) -> {
                    requestMediaPlay()
                    "Play media"
                }
                isIdleWallpaperMode() && !mediaControlActive && isNextWallpaperButton(code) -> {
                    cycleWallpaper(1)
                    "Next wallpaper"
                }
                isIdleWallpaperMode() && !mediaControlActive && isPreviousWallpaperButton(code) -> {
                    cycleWallpaper(-1)
                    "Previous wallpaper"
                }
                mediaControlActive && isMediaNextButton(code) -> {
                    mediaInfo.skipNext()
                    keepMediaControlMode()
                    showMediaCard("Next track")
                    "Next track"
                }
                mediaControlActive && isMediaPreviousButton(code) -> {
                    mediaInfo.skipPrevious()
                    keepMediaControlMode()
                    showMediaCard("Previous track")
                    "Previous track"
                }
                mediaControlActive && isMediaVolumeUpButton(code) -> {
                    mediaInfo.volumeUp()
                    keepMediaControlMode()
                    showMediaCard("Volume up")
                    "Volume up"
                }
                mediaControlActive && isMediaVolumeDownButton(code) -> {
                    mediaInfo.volumeDown()
                    keepMediaControlMode()
                    showMediaCard("Volume down")
                    "Volume down"
                }
                code == BTN_MAP_ZOOM_IN || (!mediaControlActive && code == BTN_MEDIA_NEXT) -> {
                    zoomIn()
                    "Zoom in"
                }
                code == BTN_MAP_ZOOM_OUT || (!mediaControlActive && code == BTN_MEDIA_PREVIOUS) -> {
                    zoomOut()
                    "Zoom out"
                }
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
        refreshLocationServices()
        location.start()
        startRecording()

        // We must know the EXACT dash SSID before connecting — the dash validates it inside
        // the encrypted auth handshake (DashAuth), and Android redacts the SSID of a network
        // we've already joined. So if we don't have it stored, find it from a WiFi scan.
        if (dashConfig.needsDiscovery) {
            wifiManager.findDashSsid(dashConfig.ssidPrefix)?.let { found ->
                requestPairingConfirmation(found)
                return
            }
        }

        when {
            wifiManager.state.value.status == WifiConnStatus.CONNECTED ->
                connectSessionWhenSsidResolved()
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
                if (loc != null) {
                    recorder.add(loc.latitude, loc.longitude, loc.speed, loc.accuracy, loc.time)
                }
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
    fun setSsid(s: String) {
        dashConfig.ssid = s.trim()
        _ui.value = _ui.value.copy(ssid = s.trim())
        pushProfileSettings()
    }
    fun setWifiPassword(p: String) {
        dashConfig.password = p
        _ui.value = _ui.value.copy(wifiPassword = p)
        pushProfileSettings()
    }
    fun saveManualDashCode(rawCode: String): Boolean {
        val ssid = normalizeManualDashSsid(rawCode)
        if (ssid.length <= DashConfig.DEFAULT_PREFIX.length) {
            _ui.value = _ui.value.copy(errorMessage = "Enter a valid RE code")
            refreshStage()
            return false
        }
        dashConfig.ssid = ssid
        _ui.value = _ui.value.copy(
            ssid = ssid,
            wifiPassword = dashConfig.password,
            pendingPairingSsid = null,
            errorMessage = null,
        )
        pushProfileSettings()
        if (userWantsConnection &&
            wifiManager.network != null &&
            session.state.value in listOf(DashState.IDLE, DashState.ERROR)
        ) {
            connectSessionWhenSsidResolved()
        }
        return true
    }

    fun placeCall(number: String): Boolean {
        val ok = callController.placeCall(number)
        _ui.value = _ui.value.copy(
            lastButton = if (ok) "Calling" else "Call failed",
            errorMessage = if (ok) _ui.value.errorMessage else "Could not place call",
        )
        return ok
    }

    /** Forget the paired dash so the next connect rediscovers any RE_* dash by prefix. */
    fun forgetDash() {
        dashConfig.forgetDash()
        _ui.value = _ui.value.copy(ssid = "", pendingPairingSsid = null)
    }

    fun confirmDiscoveredDash() {
        val ssid = _ui.value.pendingPairingSsid?.trim().orEmpty()
        if (ssid.isBlank()) return
        dashConfig.ssid = ssid
        _ui.value = _ui.value.copy(ssid = ssid, pendingPairingSsid = null, errorMessage = null)
        pushProfileSettings()
        if (!userWantsConnection) return
        when (wifiManager.state.value.status) {
            WifiConnStatus.CONNECTED -> connectSessionWhenSsidResolved()
            else -> wifiManager.connect(ssid, dashConfig.password)
        }
    }

    fun rejectDiscoveredDash() {
        _ui.value = _ui.value.copy(
            pendingPairingSsid = null,
            ssid = dashConfig.ssid,
            errorMessage = "Dash pairing cancelled",
        )
        if (dashConfig.needsDiscovery) {
            disconnect()
        }
    }

    private fun requestPairingConfirmation(learnedSsid: String) {
        val ssid = learnedSsid.trim()
        if (ssid.isBlank()) return
        if (dashConfig.ssid == ssid) {
            _ui.value = _ui.value.copy(ssid = ssid, pendingPairingSsid = null)
            return
        }
        if (userWantsConnection && wifiManager.state.value.status == WifiConnStatus.CONNECTED) {
            dashConfig.ssid = ssid
            _ui.value = _ui.value.copy(ssid = ssid, pendingPairingSsid = null, errorMessage = null)
            pushProfileSettings()
            connectSessionWhenSsidResolved()
            return
        }
        _ui.value = _ui.value.copy(
            ssid = ssid,
            pendingPairingSsid = ssid,
            errorMessage = null,
        )
    }

    private fun connectSessionWhenSsidResolved() {
        val ssid = listOf(_ui.value.ssid, wifiManager.state.value.ssid, dashConfig.ssid)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it != dashConfig.ssidPrefix }
            .orEmpty()
        if (ssid.isBlank() || ssid == dashConfig.ssidPrefix) {
            DebugLog.w("DashViewModel") { "Session connect deferred until exact dash SSID is resolved" }
            return
        }
        if (_ui.value.ssid != ssid) _ui.value = _ui.value.copy(ssid = ssid)
        session.connect(ssid, wifiManager.network)
    }

    private fun normalizeManualDashSsid(rawCode: String): String {
        val compact = rawCode.trim().replace(Regex("\\s+"), "")
        if (compact.isBlank()) return ""
        val upper = compact.uppercase(Locale.US)
        return when {
            upper.startsWith(DashConfig.DEFAULT_PREFIX) -> upper
            upper.startsWith("RE") -> DashConfig.DEFAULT_PREFIX + upper.removePrefix("RE").trimStart('_')
            else -> DashConfig.DEFAULT_PREFIX + upper.trimStart('_')
        }
    }

    fun setWallpaperFromUri(
        uri: Uri,
        horizontalBias: Float = 0f,
        verticalBias: Float = 0f,
        fit: DashWallpaperFit = DashWallpaperFit.CROP,
        preserveRpmArc: Boolean = false,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(wallpaperSaving = true, wallpaperError = null) }
            runCatching {
                wallpaperStore.saveFromUri(uri, horizontalBias, verticalBias, fit, preserveRpmArc)
                wallpaperStore.currentInfo()
            }.onSuccess { info ->
                    invalidateWallpaperFrame()
                    publishWallpaper(info, saving = false, error = null)
                    pushProfileSettings()
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
                invalidateWallpaperFrame()
                publishWallpaper(info, saving = false, error = null)
                pushProfileSettings()
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

    fun updateCurrentWallpaperOptions(
        horizontalBias: Float,
        verticalBias: Float,
        fit: DashWallpaperFit,
        preserveRpmArc: Boolean = _ui.value.wallpaperPreserveRpmArc,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(wallpaperSaving = true, wallpaperError = null) }
            runCatching {
                wallpaperStore.updateCurrentOptions(horizontalBias, verticalBias, fit, preserveRpmArc)
                    ?: error("No wallpaper selected")
            }.onSuccess { info ->
                invalidateWallpaperFrame()
                publishWallpaper(info, saving = false, error = null)
                pushProfileSettings()
            }.onFailure { err ->
                val msg = err.message ?: "Unable to update wallpaper"
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
            invalidateWallpaperFrame()
            publishWallpaper(next, saving = false, error = null)
            pushProfileSettings()
        }
    }

    fun cycleWallpaperFromSettings(delta: Int) {
        cycleWallpaper(delta)
    }

    private fun cycleWallpaper(delta: Int) {
        val next = wallpaperStore.cycle(delta)
        invalidateWallpaperFrame()
        publishWallpaper(next)
        pushProfileSettings()
    }

    private fun pushProfileSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.pushProfileSettings()
        }
    }

    private fun invalidateWallpaperFrame() {
        wallpaperFrameRevision++
        lastSignature = ""
        lastRedrawAt = 0L
        camMoving = true
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
                wallpaperFit = info?.fit ?: DashWallpaperFit.CROP,
                wallpaperCropX = info?.horizontalBias ?: 0f,
                wallpaperCropY = info?.verticalBias ?: 0f,
                wallpaperPreserveRpmArc = info?.preserveRpmArc ?: false,
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

    private fun isCallAnswerButton(code: Int): Boolean =
        code == BTN_JOYSTICK_LEFT || code == BTN_MEDIA_PREVIOUS

    private fun isCallRejectButton(code: Int): Boolean =
        code == BTN_JOYSTICK_RIGHT || code == BTN_MEDIA_NEXT

    private fun isCallVolumeUpButton(code: Int): Boolean =
        code == BTN_JOYSTICK_UP || code == BTN_CALL_ANSWER

    private fun isCallVolumeDownButton(code: Int): Boolean =
        code == BTN_JOYSTICK_DOWN || code == BTN_CALL_REJECT

    private fun isNextContactButton(code: Int): Boolean =
        code == BTN_MEDIA_NEXT || code == BTN_JOYSTICK_RIGHT || code == BTN_JOYSTICK_DOWN || code == BTN_CALL_REJECT

    private fun isPreviousContactButton(code: Int): Boolean =
        code == BTN_MEDIA_PREVIOUS || code == BTN_JOYSTICK_LEFT || code == BTN_JOYSTICK_UP || code == BTN_CALL_ANSWER

    private fun isClosePhoneCardButton(code: Int): Boolean =
        code == BTN_PHONE_RECENTS

    private fun isMediaPlayButton(code: Int): Boolean =
        code == BTN_NATIVE_SELECT || code == BTN_JOYSTICK_CLICK

    private fun isMediaNextButton(code: Int): Boolean =
        code == BTN_MEDIA_NEXT || code == BTN_JOYSTICK_RIGHT

    private fun isMediaPreviousButton(code: Int): Boolean =
        code == BTN_MEDIA_PREVIOUS || code == BTN_JOYSTICK_LEFT

    private fun isMediaVolumeUpButton(code: Int): Boolean =
        code == BTN_CALL_ANSWER || code == BTN_JOYSTICK_UP

    private fun isMediaVolumeDownButton(code: Int): Boolean =
        code == BTN_CALL_REJECT || code == BTN_JOYSTICK_DOWN

    // ── Destination + routing ───────────────────────────────────────────────

    fun prefetchTiles(lat: Double, lng: Double) {
        val loc = location.location.value
        tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
    }

    fun setDestination(name: String, lat: Double?, lng: Double?) {
        refreshLocationServices()
        _ui.value = _ui.value.copy(
            destinationName = name, hasRoute = false,
            destLatLng = if (lat != null && lng != null) lat to lng else null,
            routePoints = emptyList(),
        )
        destLat = lat
        destLng = lng
        route = null
        lastRouteRequestAt = 0L
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
        session.updateRouteCard("OpenDash")   // dash card → name + 0.0 km, nav off
    }

    /** Compute the road route now (while internet is reachable) and cache it. */
    private fun fetchRoute(destLatV: Double, destLngV: Double) {
        val loc = location.lastKnown()
        if (loc == null) {
            DebugLog.w("DashViewModel") { "fetchRoute: no origin location yet" }
            return
        }
        if (routeRequestInFlight) return
        routeRequestInFlight = true
        lastRouteRequestAt = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                val r = if (ExperimentalNavigationSettings.isMapboxNavigationEnabled()) {
                    runCatching {
                        mapboxNavigationProvider.requestRoute(
                            originLat = loc.latitude,
                            originLng = loc.longitude,
                            destinationLat = destLatV,
                            destinationLng = destLngV,
                        ).toLegacyRoute()
                    }.onFailure {
                        DebugLog.w("DashViewModel") { "Mapbox route failed, falling back: ${it.message}" }
                    }.getOrNull() ?: Router.route(GeoPoint(loc.latitude, loc.longitude), GeoPoint(destLatV, destLngV))
                } else {
                    Router.route(GeoPoint(loc.latitude, loc.longitude), GeoPoint(destLatV, destLngV))
                }
                if (r != null) {
                    route = r
                    tiles.prefetchRoute(r.geometry)
                    _ui.value = _ui.value.copy(hasRoute = true, routePoints = r.geometry)
                    DebugLog.i("DashViewModel") { "Route ready: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m" }
                } else {
                    DebugLog.w("DashViewModel") { "Router returned null" }
                }
            } finally {
                routeRequestInFlight = false
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

    fun invalidateMapFrame() {
        lastSignature = ""
        tiles.prefetchRoute(route?.geometry ?: emptyList())
    }

    fun refreshLocationServices(): Boolean {
        val enabled = location.refreshEnabled()
        _ui.update { it.copy(locationServicesEnabled = enabled) }
        if (enabled && (userWantsConnection || destLat != null || destLng != null)) {
            location.start()
        }
        return enabled
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
        fun buildEncoder(profile: DashStreamQuality): DashEncoder =
            DashEncoder(
                onEncodedData = onEncoded,
                fps = profile.encoderFps,
                bitrate = profile.bitrate,
            ).also { it.prepare() }

        var encoderProfile = streamQuality
        encoder?.release()
        encoder = buildEncoder(encoderProfile)

        frameBitmap = Bitmap.createBitmap(DashEncoder.WIDTH, DashEncoder.HEIGHT, Bitmap.Config.ARGB_8888)
        lastSignature = ""
        // Fresh camera so it snaps to the first fix instead of gliding from a stale spot.
        camInit = false; lastTickNs = 0L; lastFixTime = 0L

        session.startStreaming()
        startMediaForwarding()
        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }

        streamJob = viewModelScope.launch(Dispatchers.Default) {
            var lastPrefetch = 0L
            var failures = 0
            // The loop must NEVER die silently: the session's heartbeats keep the dash
            // connected, so a dead frame loop = frozen map with the connection "up".
            while (isActive && session.state.value == DashState.STREAMING) {
                try {
                    val activeProfile = streamQuality
                    if (activeProfile != encoderProfile) {
                        runCatching { encoder?.release() }
                        encoder = runCatching { buildEncoder(activeProfile) }
                            .onFailure { DebugLog.e("DashViewModel", { "Encoder profile switch failed" }, it) }
                            .getOrNull()
                        if (encoder != null) {
                            encoderProfile = activeProfile
                            lastSignature = ""
                        }
                    }
                    tick()
                    // Push the (possibly cached) frame to the encoder at the active stream profile.
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
                    DebugLog.e("DashViewModel", { "Frame loop error #$failures" }, e)
                    if (failures >= 3) {
                        // MediaCodec is in an error state — rebuild the encoder so the
                        // stream recovers. The fresh encoder re-emits SPS/PPS, which the
                        // NAL processor bundles into the next IDR for the dash decoder.
                        runCatching { encoder?.release() }
                        encoder = runCatching { buildEncoder(streamQuality) }
                            .onFailure { DebugLog.e("DashViewModel", { "Encoder rebuild failed" }, it) }
                            .getOrNull()
                        if (encoder != null) encoderProfile = streamQuality
                        lastSignature = "" // force a full redraw on the next tick
                        failures = 0
                    }
                }
                // Dynamic pacing: buttery while moving, throttled when stopped (power).
                val pacingProfile = streamQuality
                val frameRate = if (camMoving) pacingProfile.movingFps else pacingProfile.idleFps
                delay(1000L / frameRate.coerceAtLeast(1))
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
            val headingKnown = loc.hasBearing() && loc.speed >= 1.5f
            val headingOff = headingKnown && angleDelta(loc.bearing, ns.heading) > 50f
            offRoute = when {
                ns.snapDist > 150.0 -> true
                ns.snapDist > 70.0 -> headingOff
                else -> false
            }
            // NOTE: no marker snapping. Raw GPS is accurate; snapping to the route
            // polyline pinned the rider onto a parallel road in dense areas (showed
            // "Indira Enclave" when actually at "Isha"). trackProgress is still used
            // for nav distances + off-route/reroute detection (ns.offRoute), just not
            // to move the displayed marker.
            if (!headingKnown) heading = if (camInit) camHdg else 0f
            // Route-provider ETA is much more stable than instantaneous GPS speed. Speed-based
            // ETA swings wildly while stopped or when GPS reports tiny phantom motion.
            val rawEta = if (r.totalMeters > 0.0 && r.totalSeconds > 0.0) {
                r.totalSeconds * (ns.remainingM / r.totalMeters).coerceIn(0.0, 1.0)
            } else {
                ns.remainingM / 11.0
            }
            smoothEtaSec = if (smoothEtaSec <= 0.0) rawEta else smoothEtaSec + (rawEta - smoothEtaSec) * 0.02
            etaSec = smoothEtaSec
            val nowMs = System.currentTimeMillis()
            if (etaArrivalMs == 0L || nowMs - lastArrivalCalcMs > 30_000) {
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
            val nowMs = System.currentTimeMillis()
            if (!routeRequestInFlight && nowMs - lastRouteRequestAt > 3_000L) {
                fetchRoute(dLat, dLng)
            }
        }

        // Recompute the route if the rider has clearly left it for a few seconds.
        maybeReroute(offRoute, loc)

        val fixAgeMs = loc?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        gpsStatus = when {
            loc == null || fixAgeMs > 4_000L -> GpsStatus.LOST
            loc.accuracy > 25f -> GpsStatus.WEAK
            else -> GpsStatus.GOOD
        }

        // Publish nav figures to the phone UI.
        _ui.value = _ui.value.copy(
            hasGps = loc != null,
            gpsStatus = gpsStatus,
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

        val callState = CallInfoProvider.incomingCall.value
        updateActiveCallTimer(callState)

        val sig = buildString {
            if (r == null && dLat == null && dLng == null) {
                append("idle:${_ui.value.wallpaperPath}")
                append(_ui.value.wallpaperKind)
                append(_ui.value.wallpaperFit)
                append(_ui.value.wallpaperCropX)
                append(_ui.value.wallpaperCropY)
                append(_ui.value.wallpaperPreserveRpmArc)
                append(_ui.value.wallpaperGalleryIndex)
                append(wallpaperFrameRevision)
            } else {
                append("nav")
            }
            // High resolution (6 dp ≈ 0.1 m, 0.1° heading) so every smoothed step redraws
            // for buttery motion. Safe from standstill jitter because the camera is fed the
            // SMOOTHED position (which settles and stops), not raw GPS.
            append("%.6f".format(centerLat)); append("%.6f".format(centerLng))
            append(zoom); append(panX.toInt()); append(panY.toInt())
            append(if (headingUp) (camHeading * 10).toInt() else 0)
            append(remainingM?.let { (it / 100).toInt() } ?: -1) // 100 m resolution to avoid jitter
            append(if (r != null) r.geometry.size else 0)
            append(callState?.caller.orEmpty())
            append(callState?.incoming)
            if (callState?.incoming == false) {
                append((System.currentTimeMillis() - activeCallStartedAtMs).coerceAtLeast(0L) / 1000L)
            }
            append(phoneCardVisible)
            append(phoneMenuMode)
            append(phoneCardTitle)
            append(phoneCardLabel)
            append(phoneCardSubtitle)
            append(phoneCardRows.joinToString("|"))
            append(phoneCategoryIndex)
            append(phoneRecentIndex)
            append(mediaCardVisible)
            append(mediaCardMessage)
            append(mediaControlUntilMs > System.currentTimeMillis())
            currentMedia?.let { media ->
                append(media.title)
                append(media.artist)
                append(media.album)
                append(media.art?.width ?: 0)
                append(media.art?.height ?: 0)
                append(media.art?.generationId ?: 0)
            }
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
        if (now - offRouteSince < 1_200 || now - lastRerouteAt < 5_000 || rerouting) return
        lastRerouteAt = now
        rerouting = true
        DebugLog.i("DashViewModel") { "Off-route ${(now - offRouteSince) / 1000}s → rerouting" }
        viewModelScope.launch {
            val r = if (ExperimentalNavigationSettings.isMapboxNavigationEnabled()) {
                runCatching {
                    mapboxNavigationProvider.requestRoute(
                        originLat = loc.latitude,
                        originLng = loc.longitude,
                        destinationLat = dLat,
                        destinationLng = dLng,
                    ).toLegacyRoute()
                }.onFailure {
                    DebugLog.w("DashViewModel") { "Mapbox reroute failed, falling back: ${it.message}" }
                }.getOrNull() ?: Router.route(GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng))
            } else {
                Router.route(GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng))
            }
            if (r != null) {
                route = r
                progressM = 0.0
                smoothEtaSec = 0.0
                etaArrivalMs = 0L
                lastSignature = ""
                offRouteSince = 0L
                tiles.prefetchRoute(r.geometry)
                _ui.value = _ui.value.copy(hasRoute = true, routePoints = r.geometry)
                DebugLog.i("DashViewModel") { "Reroute ok: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m" }
            } else {
                DebugLog.w("DashViewModel") { "Reroute failed (no internet?)" }
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
            val canvas = Canvas(bmp)
            idleRenderer.draw(
                canvas,
                _ui.value.wallpaperPath,
                _ui.value.wallpaperKind,
                _ui.value.wallpaperCropX,
                _ui.value.wallpaperCropY,
                _ui.value.wallpaperFit,
                _ui.value.wallpaperPreserveRpmArc,
            )
            drawMediaOverlay(canvas, bmp.width, bmp.height)
            drawCallOverlay(canvas, bmp.width, bmp.height)
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
        val etaSecondary = if (etaPrimary != null) {
            if (arriving) {
                "ETA now"
            } else {
                "ETA " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(etaArrivalMs))
            }
        } else null
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
            // Mild heading-up pseudo-pitch. Keep this subtle: true Google-style 3D needs
            // vector tiles, while this dash stream renders raster tiles.
            tilt3d = headingUp && loc != null,
            etaPrimary = etaPrimary,
            etaSecondary = etaSecondary,
            gpsWeak = gpsStatus == GpsStatus.WEAK,
            gpsLost = gpsStatus == GpsStatus.LOST,
        )
        val canvas = Canvas(bmp)
        mapRenderer.draw(canvas, frame)
        drawMediaOverlay(canvas, bmp.width, bmp.height)
        drawCallOverlay(canvas, bmp.width, bmp.height)
    }

    private val mediaOverlayBackground by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDD0B0D0E.toInt()
        }
    }
    private val mediaOverlayArtBackground by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33212224
        }
    }
    private val mediaOverlayTitle by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
    private val mediaOverlayText by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE7E0D3.toInt()
            textSize = 12f
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
    private val mediaOverlayHint by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2A93B.toInt()
            textSize = 10f
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }

    private fun drawMediaOverlay(canvas: Canvas, width: Int, height: Int) {
        val media = currentMedia
        if (!mediaCardVisible && media == null) return

        val panelWidth = width * 0.62f
        val panelHeight = 104f
        val left = (width - panelWidth) / 2f
        val top = height * 0.18f
        val right = left + panelWidth
        val bottom = top + panelHeight
        canvas.drawRoundRect(left, top, right, bottom, 10f, 10f, mediaOverlayBackground)

        val artSize = 54f
        val artLeft = left + 16f
        val artTop = top + 22f
        val artDst = android.graphics.RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
        val art = media?.art
        if (art != null && !art.isRecycled && art.width > 0 && art.height > 0) {
            val side = minOf(art.width, art.height)
            val srcLeft = (art.width - side) / 2
            val srcTop = (art.height - side) / 2
            val src = android.graphics.Rect(srcLeft, srcTop, srcLeft + side, srcTop + side)
            canvas.drawBitmap(art, src, artDst, null)
        } else {
            canvas.drawRoundRect(artDst, 8f, 8f, mediaOverlayArtBackground)
            canvas.drawText("MEDIA", artLeft + 9f, artTop + 33f, mediaOverlayHint)
        }

        val textLeft = artDst.right + 14f
        val title = media?.title?.takeIf { it.isNotBlank() } ?: "Music"
        val artist = media?.artist?.takeIf { it.isNotBlank() } ?: mediaCardMessage.ifBlank { "Press to play" }
        val album = media?.album?.takeIf { it.isNotBlank() } ?: mediaCardMessage
        canvas.drawText(title.ellipsizeForDash(20), textLeft, top + 30f, mediaOverlayTitle)
        canvas.drawText(artist.ellipsizeForDash(24), textLeft, top + 50f, mediaOverlayText)
        if (album.isNotBlank()) {
            canvas.drawText(album.ellipsizeForDash(24), textLeft, top + 68f, mediaOverlayText)
        }
        if (mediaCardMessage.isNotBlank() && media != null) {
            canvas.drawText(mediaCardMessage.ellipsizeForDash(24), textLeft, bottom - 12f, mediaOverlayHint)
        }
    }

    private val callOverlayBackground by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDD0B0D0E.toInt()
        }
    }
    private val callOverlayTitle by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    private val callOverlayLabel by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2A93B.toInt()
            textSize = 10f
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    private val callPillTitle by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
    private val callPillLabel by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2A93B.toInt()
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
    private val callPillHint by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE7E0D3.toInt()
            textSize = 8f
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
    private val callPillRuntime by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8DF48D.toInt()
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
    private val callPillBorder by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88C8AA62.toInt()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.2f
        }
    }
    private val callAcceptButton by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF27C46B.toInt()
            style = android.graphics.Paint.Style.FILL
        }
    }
    private val callDeclineButton by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE94B4B.toInt()
            style = android.graphics.Paint.Style.FILL
        }
    }
    private val callButtonIcon by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.4f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
    }
    private val callOverlayRow by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 13f
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    private val callOverlaySelected by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55C8AA62
        }
    }
    private val callOverlayDivider by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88C8AA62.toInt()
            strokeWidth = 1f
        }
    }

    private fun drawCallOverlay(canvas: Canvas, width: Int, height: Int) {
        val call = CallInfoProvider.incomingCall.value
        updateActiveCallTimer(call)
        val showRecentCard = call == null && phoneCardVisible &&
            (phoneCardRows.isNotEmpty() || phoneCardLabel.isNotBlank())
        if (call == null && !showRecentCard) return
        val centerX = width / 2f
        val halfWidth = width * 0.36f
        if (call != null) {
            val right = width - 18f
            val pillHeight = 74f
            val bottom = height - 116f
            val top = bottom - pillHeight
            val pillWidth = minOf(width * 0.64f, 318f)
            val left = (right - pillWidth).coerceAtLeast(16f)
            val radius = pillHeight / 2f
            val buttonRadius = 15.5f
            val buttonY = top + pillHeight / 2f
            val declineX = right - 27f
            val acceptX = if (call.incoming) declineX - 42f else declineX
            val textLeft = left + 18f
            val textRight = acceptX - buttonRadius - 12f
            val callerChars = (((textRight - textLeft) / 8f).toInt()).coerceIn(8, 22)

            canvas.drawRoundRect(left, top, right, bottom, radius, radius, callOverlayBackground)
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, callPillBorder)
            canvas.drawText(if (call.incoming) "Incoming call" else "On call", textLeft, top + 21f, callPillLabel)
            canvas.drawText(call.caller.ellipsizeForDash(callerChars), textLeft, top + 43f, callPillTitle)
            if (call.incoming) {
                canvas.drawText("LEFT answer  RIGHT decline", textLeft, top + 61f, callPillHint)
                canvas.drawCircle(acceptX, buttonY, buttonRadius, callAcceptButton)
                drawCallButtonIcon(canvas, acceptX, buttonY, accept = true)
            } else {
                canvas.drawText(activeCallElapsedText(), textLeft, top + 62f, callPillRuntime)
            }
            canvas.drawCircle(declineX, buttonY, buttonRadius, callDeclineButton)
            drawCallButtonIcon(canvas, declineX, buttonY, accept = false)
        } else {
            val rows = phoneCardRows
            val selectedIndex = when (phoneMenuMode) {
                PhoneMenuMode.CATEGORIES -> phoneCategoryIndex
                PhoneMenuMode.CONTACTS, PhoneMenuMode.CALLING -> phoneRecentIndex
            }.coerceAtLeast(0)
            val visibleCount = if (rows.isEmpty()) 1 else minOf(4, rows.size)
            val first = if (rows.isEmpty()) 0 else {
                (selectedIndex - visibleCount + 1)
                    .coerceAtLeast(0)
                    .coerceAtMost((rows.size - visibleCount).coerceAtLeast(0))
            }
            val visibleRows = if (rows.isEmpty()) listOf(phoneCardLabel) else rows.drop(first).take(visibleCount)
            val rowHeight = 20f
            val top = height * 0.14f
            val bottom = top + 42f + visibleRows.size * rowHeight + 20f
            val left = centerX - halfWidth
            val right = centerX + halfWidth
            canvas.drawRoundRect(left, top, right, bottom, 10f, 10f, callOverlayBackground)
            canvas.drawText(phoneCardTitle.ifBlank { "Phone" }, centerX, top + 24f, callOverlayTitle)
            canvas.drawLine(left + 18f, top + 33f, right - 18f, top + 33f, callOverlayDivider)
            visibleRows.forEachIndexed { index, row ->
                val absoluteIndex = first + index
                val y = top + 54f + index * rowHeight
                if (absoluteIndex == selectedIndex && rows.isNotEmpty()) {
                    canvas.drawRoundRect(left + 28f, y - 15f, right - 28f, y + 4f, 5f, 5f, callOverlaySelected)
                }
                canvas.drawText(row.ellipsizeForDash(23), centerX, y, callOverlayRow)
            }
            canvas.drawText(phoneCardSubtitle.ellipsizeForDash(30), centerX, bottom - 8f, callOverlayLabel)
        }
    }

    private fun drawCallButtonIcon(canvas: Canvas, centerX: Float, centerY: Float, accept: Boolean) {
        val iconBounds = android.graphics.RectF(centerX - 8f, centerY - 7f, centerX + 8f, centerY + 9f)
        canvas.save()
        canvas.rotate(if (accept) -38f else 138f, centerX, centerY)
        canvas.drawArc(iconBounds, 205f, 130f, false, callButtonIcon)
        canvas.drawLine(centerX - 8.5f, centerY + 1.5f, centerX - 11f, centerY + 4f, callButtonIcon)
        canvas.drawLine(centerX + 8.5f, centerY + 1.5f, centerX + 11f, centerY + 4f, callButtonIcon)
        canvas.restore()
    }

    private fun updateActiveCallTimer(call: IncomingCall?) {
        when {
            call == null -> {
                activeCallStartedAtMs = 0L
                activeCallIdentity = ""
            }
            call.incoming -> Unit
            activeCallStartedAtMs == 0L -> {
                activeCallStartedAtMs = System.currentTimeMillis()
                activeCallIdentity = call.caller
            }
            activeCallIdentity.isBlank() || activeCallIdentity == "On call" -> {
                activeCallIdentity = call.caller
            }
        }
    }

    private fun activeCallElapsedText(): String {
        val startedAt = activeCallStartedAtMs
        val seconds = if (startedAt > 0L) {
            ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val secs = seconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
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
        val nextManeuver: com.example.opendash.dash.nav.Maneuver?,
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
            it.cumulativeMeters > progressM + 1.0 && it.type != com.example.opendash.dash.nav.ManeuverType.DEPART
        }
        val nextTurn = nextMan?.let { (it.cumulativeMeters - progressM).coerceAtLeast(0.0) } ?: remaining
        return NavState(remaining, nextTurn, m.bearing, m.dist > 70.0, m.proj, m.dist, nextMan)
    }

    @SuppressLint("NewApi")
    private fun updateThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (_ui.value.thermal != "OK") _ui.value = _ui.value.copy(thermal = "OK")
            return
        }
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

    private fun DashRoute.toLegacyRoute(): Route {
        val cumulative = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cumulative[i] = cumulative[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }
        return Route(
            geometry = geometry,
            maneuvers = maneuvers.map {
                com.example.opendash.dash.nav.Maneuver(
                    type = it.type.toLegacyType(),
                    instruction = it.instruction,
                    location = GeoPoint(it.locationLat, it.locationLng),
                    cumulativeMeters = it.distanceFromStartMeters,
                )
            },
            totalMeters = totalDistanceMeters,
            totalSeconds = totalDurationSeconds,
            cumulative = cumulative,
        )
    }

    private fun DashManeuverType.toLegacyType(): com.example.opendash.dash.nav.ManeuverType =
        when (this) {
            DashManeuverType.DEPART -> com.example.opendash.dash.nav.ManeuverType.DEPART
            DashManeuverType.ARRIVE -> com.example.opendash.dash.nav.ManeuverType.ARRIVE
            DashManeuverType.TURN_LEFT -> com.example.opendash.dash.nav.ManeuverType.TURN_LEFT
            DashManeuverType.TURN_RIGHT -> com.example.opendash.dash.nav.ManeuverType.TURN_RIGHT
            DashManeuverType.SLIGHT_LEFT -> com.example.opendash.dash.nav.ManeuverType.SLIGHT_LEFT
            DashManeuverType.SLIGHT_RIGHT -> com.example.opendash.dash.nav.ManeuverType.SLIGHT_RIGHT
            DashManeuverType.SHARP_LEFT -> com.example.opendash.dash.nav.ManeuverType.SHARP_LEFT
            DashManeuverType.SHARP_RIGHT -> com.example.opendash.dash.nav.ManeuverType.SHARP_RIGHT
            DashManeuverType.UTURN -> com.example.opendash.dash.nav.ManeuverType.UTURN
            DashManeuverType.ROUNDABOUT -> com.example.opendash.dash.nav.ManeuverType.ROUNDABOUT
            else -> com.example.opendash.dash.nav.ManeuverType.CONTINUE
        }

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)

    private fun String.ellipsizeForDash(maxChars: Int): String =
        if (length <= maxChars) this else take((maxChars - 1).coerceAtLeast(0)) + "..."

    private fun angleDelta(first: Float, second: Float): Float {
        val delta = (((first - second) % 360f) + 540f) % 360f - 180f
        return kotlin.math.abs(delta)
    }

    private fun teardown() {
        streamJob?.cancel(); streamJob = null
        phoneCardJob?.cancel(); phoneCardJob = null
        mediaCardJob?.cancel(); mediaCardJob = null
        mediaCardVisible = false
        mediaCardMessage = ""
        currentMedia = null
        mediaControlUntilMs = 0L
        activeCallStartedAtMs = 0L
        activeCallIdentity = ""
        phoneCardVisible = false
        phoneCardTitle = ""
        phoneCardLabel = ""
        phoneCardSubtitle = ""
        phoneCardRows = emptyList()
        phoneRecentCalls = emptyList()
        phoneAllCalls = emptyList()
        stopMediaForwarding()
        encoder?.release(); encoder = null
        idleRenderer.release()
        frameBitmap?.recycle(); frameBitmap = null
    }

    private fun startMediaForwarding() {
        mediaInfo.start()
        mediaObserveJob?.cancel()
        mediaObserveJob = viewModelScope.launch {
            launch {
                while (isActive) {
                    mediaInfo.refresh()
                    delay(2_000)
                }
            }
            launch {
                mediaInfo.nowPlaying.collect { media ->
                    currentMedia = media
                    if (media != null) {
                        session.updateNowPlaying(
                            media.title,
                            media.album,
                            media.artist,
                        )
                        if (mediaCardVisible || System.currentTimeMillis() < mediaControlUntilMs) {
                            keepMediaControlMode()
                            showMediaCard()
                        }
                    } else {
                        if (System.currentTimeMillis() < mediaControlUntilMs) {
                            publishMediaPlaceholder()
                            showMediaCard("Playing on phone")
                        } else {
                            mediaCardVisible = false
                            mediaCardMessage = ""
                            lastSignature = ""
                            session.updateNowPlaying(null, "", "")
                        }
                    }
                }
            }
            launch {
                CallInfoProvider.incomingCall.collect { call ->
                    updateActiveCallTimer(call)
                    lastSignature = ""
                    session.updateCall(call?.caller)
                }
            }
        }
    }

    private fun stopMediaForwarding() {
        mediaObserveJob?.cancel()
        mediaObserveJob = null
        mediaInfo.stop()
        mediaCardJob?.cancel()
        mediaCardJob = null
        mediaCardVisible = false
        mediaCardMessage = ""
        currentMedia = null
        mediaControlUntilMs = 0L
        activeCallStartedAtMs = 0L
        activeCallIdentity = ""
        lastSignature = ""
        session.updateNowPlaying(null, "", "")
        session.updateCall(null)
    }

    private fun requestMediaPlay() {
        keepMediaControlMode()
        val ok = mediaInfo.play()
        publishMediaPlaceholder(if (ok) "Starting music" else "Open player")
        showMediaCard(if (ok) "Starting music" else "Allow media access")
        viewModelScope.launch {
            repeat(6) {
                delay(650)
                mediaInfo.refresh()
                if (mediaInfo.nowPlaying.value != null) return@launch
            }
        }
    }

    private fun keepMediaControlMode(durationMs: Long = 120_000L) {
        mediaControlUntilMs = System.currentTimeMillis() + durationMs
    }

    private fun publishMediaPlaceholder(status: String = "Playing on phone") {
        session.updateNowPlaying("Phone media", status, "OpenDash")
    }

    private fun showMediaCard(message: String = "") {
        mediaCardVisible = true
        mediaCardMessage = message
        lastSignature = ""
        scheduleMediaCardTimeout()
    }

    private fun scheduleMediaCardTimeout() {
        mediaCardJob?.cancel()
        mediaCardJob = viewModelScope.launch {
            delay(if (currentMedia != null) 30_000 else 10_000)
            mediaCardVisible = false
            mediaCardMessage = ""
            lastSignature = ""
        }
    }

    private fun showPhoneMenuOnDash(): String {
        phoneAllCalls = CallInfoProvider.recentCalls(getApplication(), 40)
        phoneMenuMode = PhoneMenuMode.CATEGORIES
        phoneCategoryIndex = phoneCategoryIndex.coerceIn(0, PhoneCallCategory.entries.lastIndex)
        phoneRecentIndex = 0
        phoneCardVisible = true
        refreshPhoneCardRows()
        schedulePhoneCardTimeout()
        return "Phone menu"
    }

    private fun stepPhoneCard(delta: Int): String {
        if (!phoneCardVisible) return showPhoneMenuOnDash()
        when (phoneMenuMode) {
            PhoneMenuMode.CATEGORIES -> {
                phoneCategoryIndex = (phoneCategoryIndex + delta).floorMod(PhoneCallCategory.entries.size)
            }
            PhoneMenuMode.CONTACTS -> {
                if (phoneRecentCalls.isNotEmpty()) {
                    phoneRecentIndex = (phoneRecentIndex + delta).floorMod(phoneRecentCalls.size)
                }
            }
            PhoneMenuMode.CALLING -> Unit
        }
        refreshPhoneCardRows()
        schedulePhoneCardTimeout()
        return phoneCardLabel.ifBlank { "Phone" }
    }

    private fun selectPhoneCardItem(): String =
        when (phoneMenuMode) {
            PhoneMenuMode.CATEGORIES -> {
                phoneMenuMode = PhoneMenuMode.CONTACTS
                phoneRecentIndex = 0
                refreshPhoneCardRows()
                schedulePhoneCardTimeout()
                phoneCardTitle.ifBlank { "Contacts" }
            }
            PhoneMenuMode.CONTACTS -> callSelectedRecent()
            PhoneMenuMode.CALLING -> phoneCardSubtitle.ifBlank { "Calling" }
        }

    private fun backOrClosePhoneCard(): String {
        if (phoneMenuMode == PhoneMenuMode.CONTACTS) {
            phoneMenuMode = PhoneMenuMode.CATEGORIES
            refreshPhoneCardRows()
            schedulePhoneCardTimeout()
            return "Phone menu"
        }
        hidePhoneCard()
        return "Phone card closed"
    }

    private fun callSelectedRecent(): String {
        val call = phoneRecentCalls.getOrNull(phoneRecentIndex)
        if (call == null) {
            refreshPhoneCardRows()
            return "No recent call"
        }
        val direct = callController.hasDirectCallPermission()
        val ok = callController.placeCall(call.number)
        phoneMenuMode = PhoneMenuMode.CALLING
        phoneCardLabel = if (ok) call.displayName else "Call failed"
        phoneCardTitle = "Phone"
        phoneCardRows = emptyList()
        phoneCardSubtitle = if (ok) {
            if (direct) "Call initiating" else "Confirm on phone"
        } else {
            if (direct) "Check phone dialer" else "Allow phone permission"
        }
        lastSignature = ""
        schedulePhoneCardTimeout()
        _ui.value = _ui.value.copy(
            lastButton = if (ok) "Calling" else "Call failed",
            errorMessage = if (ok) _ui.value.errorMessage else phoneCardSubtitle,
        )
        return if (ok) "Calling ${call.displayName}".take(32) else "Call failed"
    }

    private fun refreshPhoneCardRows() {
        when (phoneMenuMode) {
            PhoneMenuMode.CATEGORIES -> {
                phoneCardTitle = "Phone"
                phoneCardRows = PhoneCallCategory.entries.map { it.label }
                phoneCardLabel = PhoneCallCategory.entries[phoneCategoryIndex].label
                phoneCardSubtitle = "U/D choose | CLICK open"
            }
            PhoneMenuMode.CONTACTS -> {
                val category = PhoneCallCategory.entries[phoneCategoryIndex]
                phoneCardTitle = category.label
                phoneRecentCalls = callsForCategory(category)
                phoneRecentIndex = phoneRecentIndex.coerceIn(0, (phoneRecentCalls.size - 1).coerceAtLeast(0))
                phoneCardRows = phoneRecentCalls.map { it.displayName }
                phoneCardLabel = phoneRecentCalls.getOrNull(phoneRecentIndex)?.displayName
                    ?: noContactsLabel(category)
                phoneCardSubtitle = currentPhoneCardHint()
            }
            PhoneMenuMode.CALLING -> Unit
        }
        lastSignature = ""
    }

    private fun callsForCategory(category: PhoneCallCategory): List<RecentCall> =
        when (category) {
            PhoneCallCategory.MISSED -> phoneAllCalls.filter { it.type == CallLog.Calls.MISSED_TYPE }
            PhoneCallCategory.RECEIVED -> phoneAllCalls.filter { it.type == CallLog.Calls.INCOMING_TYPE }
            PhoneCallCategory.DIALED -> phoneAllCalls.filter { it.type == CallLog.Calls.OUTGOING_TYPE }
            PhoneCallCategory.FAVORITES -> phoneAllCalls
                .filter { it.number.isNotBlank() }
                .distinctBy { it.number }
                .take(5)
        }.take(5)

    private fun noContactsLabel(category: PhoneCallCategory): String =
        if (CallInfoProvider.hasCallLogPermission(getApplication())) {
            "No ${category.label.lowercase(Locale.US)}"
        } else {
            "Allow call log"
        }

    private fun currentPhoneCardHint(): String =
        when {
            phoneRecentCalls.isNotEmpty() -> "U/D choose | CLICK call"
            CallInfoProvider.hasCallLogPermission(getApplication()) -> "No contact to call"
            else -> "Allow call log on phone"
        }

    private fun schedulePhoneCardTimeout() {
        phoneCardJob?.cancel()
        phoneCardJob = viewModelScope.launch {
            delay(30_000)
            if (CallInfoProvider.incomingCall.value == null) {
                hidePhoneCard()
            }
        }
    }

    private fun Int.floorMod(divisor: Int): Int =
        ((this % divisor) + divisor) % divisor

    private fun hidePhoneCard() {
        phoneCardJob?.cancel()
        phoneCardJob = null
        phoneCardVisible = false
        phoneCardTitle = ""
        phoneCardLabel = ""
        phoneCardSubtitle = ""
        phoneCardRows = emptyList()
        phoneRecentCalls = emptyList()
        phoneAllCalls = emptyList()
        phoneMenuMode = PhoneMenuMode.CATEGORIES
        lastSignature = ""
    }

    private fun answerCall(call: IncomingCall) {
        val handled = call.answerIntent?.let { runCatching { it.send() }.isSuccess } ?: false
        if (!handled) callController.answer()
    }

    private fun endCall(call: IncomingCall) {
        val handled = call.declineIntent?.let { runCatching { it.send() }.isSuccess } ?: false
        if (!handled) callController.hangup()
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
