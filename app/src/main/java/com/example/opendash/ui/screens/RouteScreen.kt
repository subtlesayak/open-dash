package com.example.opendash.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.media.CallInfoProvider
import com.example.opendash.media.RecentCall
import com.example.opendash.viewmodel.ConnStage
import com.example.opendash.viewmodel.DashUiState
import com.example.opendash.viewmodel.DashViewModel
import com.example.opendash.viewmodel.RouteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun RouteScreen(
    onBack: () -> Unit,
    onSentToDash: (String) -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
    dashViewModel: DashViewModel = viewModel(),
    connectRequest: Long = 0L,
) {
    val routeState by routeViewModel.state.collectAsState()
    val dashUi by dashViewModel.ui.collectAsState()
    val dest       = routeState.destination
    val destName   = dest?.name?.ifBlank { "Shared location" } ?: "Shared location"
    val destSub    = when {
        dest?.lat != null && dest.lng != null ->
            "%.5f, %.5f".format(dest.lat, dest.lng)
        dest?.url != null -> "Maps link"
        else              -> ""
    }

    val savedList by routeViewModel.saved.collectAsState()
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val voiceManager = remember { com.example.opendash.dash.nav.VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()
    val voice = when (voiceMode) {
        com.example.opendash.dash.nav.VoiceMode.OFF   -> "Off"
        com.example.opendash.dash.nav.VoiceMode.CHIME -> "Chime only"
        com.example.opendash.dash.nav.VoiceMode.FULL  -> "Full TTS"
    }
    var sent by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.example.opendash.data.SavedLocation?>(null) }
    var adjustMode by rememberSaveable { mutableStateOf(true) }
    var joystickVelocity by remember { mutableStateOf(Offset.Zero) }
    var previewPan by remember { mutableStateOf(Offset.Zero) }
    var recentCalls by remember { mutableStateOf<List<RecentCall>>(emptyList()) }
    var callLogAllowed by remember { mutableStateOf(CallInfoProvider.hasCallLogPermission(ctx)) }
    var locationEnabled by remember { mutableStateOf(isDeviceLocationEnabled(ctx)) }

    val essentialPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }
    val requestedPermissions = remember {
        buildList {
            addAll(essentialPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
        }.toTypedArray()
    }

    fun hasEssentialPermissions() = essentialPermissions.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val essentialOk = essentialPermissions.all { results[it] == true }
        callLogAllowed = CallInfoProvider.hasCallLogPermission(ctx)
        locationEnabled = dashViewModel.refreshLocationServices() || isDeviceLocationEnabled(ctx)
        if (essentialOk) dashViewModel.connect()
    }

    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        locationEnabled = dashViewModel.refreshLocationServices() || isDeviceLocationEnabled(ctx)
        routeViewModel.refreshRouteIfPossible()
    }

    fun openLocationSettings() {
        locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    fun connectToDash() {
        if (hasEssentialPermissions()) dashViewModel.connect()
        else permissionLauncher.launch(requestedPermissions)
    }

    val streaming = dashUi.stage == ConnStage.STREAMING

    LaunchedEffect(sent) {
        if (sent) {
            delay(650)
            onSentToDash(destName)
        }
    }

    LaunchedEffect(dest?.lat, dest?.lng, dest?.url) {
        sent = false
    }

    LaunchedEffect(connectRequest) {
        if (connectRequest > 0L && dashUi.stage != ConnStage.STREAMING) {
            connectToDash()
        }
    }

    DisposableEffect(lifecycleOwner, ctx) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationEnabled = dashViewModel.refreshLocationServices() || isDeviceLocationEnabled(ctx)
                if (locationEnabled) routeViewModel.refreshRouteIfPossible()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(dashUi.stage, callLogAllowed) {
        callLogAllowed = CallInfoProvider.hasCallLogPermission(ctx)
        recentCalls = withContext(Dispatchers.IO) {
            CallInfoProvider.recentCalls(ctx, 5)
        }
    }

    LaunchedEffect(dashUi.lastButton) {
        val b = dashUi.lastButton ?: return@LaunchedEffect
        when {
            b.startsWith("→") -> previewPan = Offset((previewPan.x - 12f).coerceIn(-46f, 46f), previewPan.y)
            b.startsWith("←") -> previewPan = Offset((previewPan.x + 12f).coerceIn(-46f, 46f), previewPan.y)
            b.startsWith("↓") -> previewPan = Offset(previewPan.x, (previewPan.y - 12f).coerceIn(-46f, 46f))
            b.startsWith("↑") -> previewPan = Offset(previewPan.x, (previewPan.y + 12f).coerceIn(-46f, 46f))
            b.startsWith("●") -> previewPan = Offset.Zero
        }
    }

    LaunchedEffect(joystickVelocity, adjustMode) {
        while (adjustMode && (joystickVelocity.x != 0f || joystickVelocity.y != 0f)) {
            previewPan = Offset(
                (previewPan.x - joystickVelocity.x * 2.4f).coerceIn(-46f, 46f),
                (previewPan.y - joystickVelocity.y * 2.4f).coerceIn(-46f, 46f),
            )
            dashViewModel.panBy(joystickVelocity.x * 4f, joystickVelocity.y * 4f)
            delay(16)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            title = "Start navigation",
            onBack = onBack,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (streaming && dashUi.thermal != "OK") {
                        OpenDashChip(
                            dashUi.thermal,
                            if (dashUi.thermal == "Warm") ChipTone.Warn else ChipTone.Alert,
                        )
                    }
                    when (dashUi.stage) {
                        ConnStage.STREAMING -> OpenDashChip("LIVE", ChipTone.Gold, dot = true)
                        ConnStage.WIFI,
                        ConnStage.AUTH -> OpenDashChip("Connecting...", ChipTone.Neutral)
                        ConnStage.ERROR -> OpenDashChip("Error", ChipTone.Alert)
                        ConnStage.OFFLINE -> OpenDashChip("Offline", ChipTone.Off)
                    }
                }
            },
        )

        Spacer(Modifier.height(10.dp))

        // Responsive route preview
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 330.dp)
                .aspectRatio(1.45f)
                .clip(RoundedCornerShape(28.dp))
                .background(MapBase)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)),
        ) {
            // MapLibre preview — destination pin + route line.
            OpenDashMap(
                riderLat = null,
                riderLng = null,
                dest = dest?.let { d -> if (d.lat != null && d.lng != null) d.lat to d.lng else null },
                routePoints = routeState.route?.geometry.orEmpty(),
                hasLocationPermission = true,
                fitRoute = true,
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xD9080C0C), Color.Transparent))
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                OpenDashChip(
                    if (routeState.isResolving) "Resolving link…" else "Shared location",
                    tone = if (routeState.isResolving) ChipTone.Gold else ChipTone.Neutral,
                    icon = OpenDashIcons.Share,
                    modifier = Modifier.background(Color(0xB30D0F11), CircleShape),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Eyebrow("Destination", Modifier.padding(bottom = 6.dp))

            OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Icon(OpenDashIcons.LocationPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (routeState.isResolving) "Resolving..." else destName,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GeistFamily,
                        )
                        if (destSub.isNotBlank()) {
                            Text(destSub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            val canStart = dest?.lat != null && dest.lng != null && !routeState.isResolving
            val needsLocationForRoute = canStart || routeState.route != null || sent
            if (needsLocationForRoute && !locationEnabled) {
                LocationDisabledCard(onEnableLocation = { openLocationSettings() })
                Spacer(Modifier.height(18.dp))
            }

            // Route stats (real values once routing completes)
            val routing = routeState.routing
            val statsList = listOf(
                Triple(if (routing) "…" else (routeState.distanceText ?: "—"), "", "Distance"),
                Triple(if (routing) "…" else (routeState.durationText ?: "—"), "", "Duration"),
                Triple(if (routing) "…" else (routeState.etaText ?: "—"), "", "Arrive"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                statsList.forEach { (v, u, k) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                            .padding(13.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(v, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                            Spacer(Modifier.width(4.dp))
                            Text(u, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        Eyebrow(k, Modifier.padding(top = 4.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
            ) {
                Eyebrow("Voice guidance")
                Icon(
                    if (voice == "Off") OpenDashIcons.SpeakerOff else OpenDashIcons.Speaker,
                    contentDescription = null,
                    tint = if (voice == "Off") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }

            OpenDashSegmented(
                options = listOf("Off", "Chime only", "Full TTS"),
                selected = voice,
                onSelect = {
                    voiceManager.setMode(when (it) {
                        "Off"  -> com.example.opendash.dash.nav.VoiceMode.OFF
                        "Full TTS" -> com.example.opendash.dash.nav.VoiceMode.FULL
                        else   -> com.example.opendash.dash.nav.VoiceMode.CHIME
                    })
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
            )

            OpenDashBtn(
                label = when {
                    needsLocationForRoute && !locationEnabled -> "Enable location"
                    sent                   -> "Starting navigation…"
                    routeState.isResolving -> "Resolving destination…"
                    else                   -> "Start navigation"
                },
                onClick = {
                    if (needsLocationForRoute && !locationEnabled) openLocationSettings()
                    else sent = true
                },
                icon = if (needsLocationForRoute && !locationEnabled) OpenDashIcons.Recenter else if (sent) OpenDashIcons.Check else OpenDashIcons.Navi,
                variant = if (sent) BtnVariant.Secondary else BtnVariant.Primary,
                size = BtnSize.Lg,
                enabled = !sent && canStart,
                modifier = Modifier.fillMaxWidth(),
            )

            if (canStart) {
                Spacer(Modifier.height(10.dp))
                OpenDashBtn(
                    label = "Save this destination",
                    onClick = { showSave = true },
                    icon = OpenDashIcons.Pin,
                    variant = BtnVariant.Ghost,
                    size = BtnSize.Md,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            Eyebrow("Dash", Modifier.padding(bottom = 8.dp, start = 4.dp))
            RouteDashPanel(
                dashUi = dashUi,
                routePreviewPoints = routeState.route?.geometry.orEmpty(),
                destinationPreview = dest?.let { d ->
                    if (d.lat != null && d.lng != null) d.lat to d.lng else null
                },
                streaming = streaming,
                hasLocationPermission = hasEssentialPermissions(),
                adjustMode = adjustMode,
                onAdjustModeChange = { adjustMode = it },
                onJoystickMove = { joystickVelocity = it },
                onZoomIn = { dashViewModel.zoomIn() },
                onZoomOut = { dashViewModel.zoomOut() },
                onRecenter = {
                    dashViewModel.recenter()
                    previewPan = Offset.Zero
                },
                onToggleHeadingUp = { dashViewModel.toggleHeadingUp() },
                onConnect = { connectToDash() },
                onCancelOrDisconnect = { dashViewModel.disconnect() },
                onConfirmPairing = { dashViewModel.confirmDiscoveredDash() },
                onRejectPairing = { dashViewModel.rejectDiscoveredDash() },
                onExitNavigation = { dashViewModel.exitNavigation() },
                recentCalls = recentCalls,
                callLogAllowed = callLogAllowed,
                onCallRecent = { call -> dashViewModel.placeCall(call.number) },
            )

            // ── Saved destinations ──
            if (savedList.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Eyebrow("Saved destinations", Modifier.padding(bottom = 6.dp, start = 4.dp))
                OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                    savedList.forEachIndexed { i, loc ->
                        if (i > 0) OpenDashDivider(Modifier.padding(horizontal = 4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { routeViewModel.selectSaved(loc) }
                                .padding(horizontal = 6.dp, vertical = 12.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                            ) { Icon(OpenDashIcons.LocationPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(loc.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily, maxLines = 1)
                                Text(
                                    if (loc.note.isNotBlank()) loc.note else "%.4f, %.4f".format(loc.lat, loc.lng),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = GeistMonoFamily,
                                    modifier = Modifier.padding(top = 2.dp), maxLines = 1,
                                )
                            }
                            Icon(
                                OpenDashIcons.Edit, "edit", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp).clickable { editing = loc },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSave) SaveLocationDialog(
        defaultName = destName,
        onSave = { name, note -> routeViewModel.saveCurrentDestination(name, note); showSave = false },
        onDismiss = { showSave = false },
    )
    editing?.let { loc ->
        EditLocationDialog(
            loc = loc,
            onSave = { name, note -> routeViewModel.renameSaved(loc, name, note); editing = null },
            onDelete = { routeViewModel.deleteSaved(loc); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun LocationDisabledCard(
    onEnableLocation: () -> Unit,
) {
    OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Icon(
                        OpenDashIcons.Recenter,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Location is off",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
                    )
                    Text(
                        "Enable it to calculate the route and update the dash.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            OpenDashBtn(
                "Enable",
                onClick = onEnableLocation,
                icon = OpenDashIcons.Recenter,
                variant = BtnVariant.Primary,
                size = BtnSize.Sm,
            )
        }
    }
}

@Composable
private fun RouteDashPanel(
    dashUi: DashUiState,
    routePreviewPoints: List<com.example.opendash.dash.nav.GeoPoint>,
    destinationPreview: Pair<Double, Double>?,
    streaming: Boolean,
    hasLocationPermission: Boolean,
    adjustMode: Boolean,
    onAdjustModeChange: (Boolean) -> Unit,
    onJoystickMove: (Offset) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onRecenter: () -> Unit,
    onToggleHeadingUp: () -> Unit,
    onConnect: () -> Unit,
    onCancelOrDisconnect: () -> Unit,
    onConfirmPairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onExitNavigation: () -> Unit,
    recentCalls: List<RecentCall>,
    callLogAllowed: Boolean,
    onCallRecent: (RecentCall) -> Unit,
) {
    val connecting = dashUi.stage == ConnStage.WIFI || dashUi.stage == ConnStage.AUTH
    val previewRoute = if (dashUi.routePoints.isNotEmpty()) dashUi.routePoints else routePreviewPoints
    val previewDestination = dashUi.destLatLng ?: destinationPreview

    dashUi.pendingPairingSsid?.let { pendingSsid ->
        OpenDashCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), padding = 14.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        "Pair dash",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        pendingSsid,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontFamily = GeistMonoFamily,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenDashBtn(
                        "Cancel",
                        onClick = onRejectPairing,
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Sm,
                    )
                    OpenDashBtn(
                        "Pair",
                        onClick = onConfirmPairing,
                        icon = OpenDashIcons.Check,
                        variant = BtnVariant.Primary,
                        size = BtnSize.Sm,
                    )
                }
            }
        }
    }

    OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 0.dp, glow = streaming) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .widthIn(max = 360.dp)
                    .aspectRatio(DASH_ARTBOARD_ASPECT)
                    .clip(DashViewportShape)
                    .background(Color.Black)
                    .border(5.dp, MaterialTheme.colorScheme.surfaceContainerLow, DashViewportShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, DashViewportShape),
            ) {
                OpenDashMap(
                    riderLat = dashUi.riderLat,
                    riderLng = dashUi.riderLng,
                    dest = previewDestination,
                    routePoints = previewRoute,
                    hasLocationPermission = hasLocationPermission,
                    navMode = dashUi.headingUp,
                    riderBearing = dashUi.riderBearing,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        when (dashUi.stage) {
                            ConnStage.OFFLINE -> "Dash offline"
                            ConnStage.WIFI -> "Joining ${dashUi.ssid}..."
                            ConnStage.AUTH -> "Authenticating..."
                            ConnStage.ERROR -> "Connection failed"
                            ConnStage.STREAMING -> "Streaming to dash"
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily,
                    )
                    Text(
                        when (dashUi.stage) {
                            ConnStage.OFFLINE -> dashUi.ssid.ifBlank { "No paired dash" }
                            ConnStage.WIFI -> "Waiting for Android WiFi"
                            ConnStage.AUTH -> "Secure session"
                            ConnStage.ERROR -> dashUi.errorMessage ?: "Could not connect"
                            ConnStage.STREAMING -> "Frame ${dashUi.frameCount} · z${dashUi.mapZoom}"
                        },
                        color = if (dashUi.stage == ConnStage.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 12.sp,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                OpenDashBtn(
                    label = when {
                        streaming -> "Disconnect"
                        connecting -> "Cancel"
                        else -> "Connect"
                    },
                    onClick = if (streaming || connecting) onCancelOrDisconnect else onConnect,
                    icon = if (streaming || connecting) null else OpenDashIcons.Wifi,
                    variant = if (streaming) BtnVariant.Danger else if (connecting) BtnVariant.Ghost else BtnVariant.Primary,
                    size = BtnSize.Sm,
                )
            }

            dashUi.lastButton?.let { btn ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "Joystick $btn",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontFamily = GeistMonoFamily,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (dashUi.maneuver != null || dashUi.offRoute) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (dashUi.offRoute) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer)
                        .border(1.dp, if (dashUi.offRoute) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    Icon(
                        OpenDashIcons.Navi,
                        contentDescription = null,
                        tint = if (dashUi.offRoute) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (dashUi.offRoute) "Rerouting..." else dashUi.maneuver.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            RoutePhoneSection(
                recentCalls = recentCalls,
                callLogAllowed = callLogAllowed,
                onCallRecent = onCallRecent,
            )

            if (streaming) {
                Spacer(Modifier.height(14.dp))
                RouteDashToggleRow(
                    title = "Map adjust",
                    subtitle = if (adjustMode) "Pan enabled" else "Locked",
                    icon = OpenDashIcons.Cross,
                    active = adjustMode,
                    onChange = onAdjustModeChange,
                )
                Spacer(Modifier.height(10.dp))
                RouteDashToggleRow(
                    title = "Heading-up",
                    subtitle = if (dashUi.headingUp) "Travel direction" else "North-up",
                    icon = OpenDashIcons.Navi,
                    active = dashUi.headingUp,
                    onChange = { onToggleHeadingUp() },
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                            .padding(vertical = 14.dp, horizontal = 10.dp),
                    ) {
                        Joystick(
                            size = 116.dp,
                            onMove = { v -> onJoystickMove(if (adjustMode) v else Offset.Zero) },
                        )
                        Spacer(Modifier.height(8.dp))
                        Eyebrow("Pan")
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OpenDashIconBtn(OpenDashIcons.Plus, onClick = onZoomIn, size = 50.dp)
                        Text(
                            "z${dashUi.mapZoom}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontFamily = GeistMonoFamily,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OpenDashIconBtn(OpenDashIcons.Minus, onClick = onZoomOut, size = 50.dp)
                        OpenDashIconBtn(OpenDashIcons.Recenter, onClick = onRecenter, size = 50.dp, active = true)
                    }
                }

                if (dashUi.destinationName != null) {
                    Spacer(Modifier.height(14.dp))
                    OpenDashBtn(
                        "Exit navigation",
                        onClick = onExitNavigation,
                        icon = OpenDashIcons.Navi,
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Md,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutePhoneSection(
    recentCalls: List<RecentCall>,
    callLogAllowed: Boolean,
    onCallRecent: (RecentCall) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                OpenDashIcons.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Phone",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                )
                Text(
                    if (callLogAllowed) "Tap a recent contact to call" else "Allow call log permission to show recents",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }

        if (recentCalls.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (callLogAllowed) "No recent calls yet" else "Open Connect and grant Phone + Call Log permissions",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            recentCalls.take(3).forEachIndexed { index, call ->
                if (index > 0) OpenDashDivider(Modifier.padding(vertical = 2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onCallRecent(call) }
                        .padding(horizontal = 6.dp, vertical = 9.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Icon(
                            OpenDashIcons.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            call.displayName,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            call.number.ifBlank { "Unknown number" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontFamily = GeistMonoFamily,
                            maxLines = 1,
                        )
                    }
                    OpenDashBtn(
                        "Call",
                        onClick = { onCallRecent(call) },
                        variant = BtnVariant.Secondary,
                        size = BtnSize.Sm,
                    )
                }
            }
        }
    }
}

private fun isDeviceLocationEnabled(context: Context): Boolean = try {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
} catch (e: Exception) {
    false
}

@Composable
private fun RouteDashToggleRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        OpenDashToggle(on = active, onChange = onChange)
    }
}

@Composable
private fun SaveLocationDialog(defaultName: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        title = { Text("Save destination", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

@Composable
private fun EditLocationDialog(loc: com.example.opendash.data.SavedLocation, onSave: (String, String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(loc.name) }
    var note by remember { mutableStateOf(loc.note) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete", color = Alert) } },
        title = { Text("Edit destination", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

