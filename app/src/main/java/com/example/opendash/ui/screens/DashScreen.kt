package com.example.opendash.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.ConnStage
import com.example.opendash.viewmodel.DashViewModel
import kotlinx.coroutines.delay

@Composable
fun DashScreen(vm: DashViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    // WiFi network request (13+: NEARBY_WIFI_DEVICES) + GPS for the map
    // Essential perms gate the connection; notifications are requested but optional.
    val essentialPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }
    val requestedPermissions = remember {
        buildList {
            addAll(essentialPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    fun hasEssentialPermissions() = essentialPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val essentialOk = essentialPermissions.all { results[it] == true }
        if (essentialOk) vm.connect()
    }

    // Local preview state (mirrors what the dash shows)
    var pan by remember { mutableStateOf(Offset.Zero) }
    var adjustMode by remember { mutableStateOf(true) }
    var joystickVelocity by remember { mutableStateOf(Offset.Zero) }

    // Physical joystick button → preview-only nudge (real map pan happens in the ViewModel)
    LaunchedEffect(ui.lastButton) {
        val b = ui.lastButton ?: return@LaunchedEffect
        when {
            b.startsWith("→") -> pan = Offset((pan.x - 12f).coerceIn(-46f, 46f), pan.y)
            b.startsWith("←") -> pan = Offset((pan.x + 12f).coerceIn(-46f, 46f), pan.y)
            b.startsWith("↓") -> pan = Offset(pan.x, (pan.y - 12f).coerceIn(-46f, 46f))
            b.startsWith("↑") -> pan = Offset(pan.x, (pan.y + 12f).coerceIn(-46f, 46f))
            b.startsWith("●") -> pan = Offset.Zero
        }
    }

    LaunchedEffect(joystickVelocity, adjustMode) {
        while (adjustMode && (joystickVelocity.x != 0f || joystickVelocity.y != 0f)) {
            pan = Offset(
                (pan.x - joystickVelocity.x * 2.4f).coerceIn(-46f, 46f),
                (pan.y - joystickVelocity.y * 2.4f).coerceIn(-46f, 46f),
            )
            vm.panBy(joystickVelocity.x * 4f, joystickVelocity.y * 4f)
            delay(16)
        }
    }

    val streaming = ui.stage == ConnStage.STREAMING

    // Auto-connect on opening the Dash screen, so the rider doesn't tap "Connect" every
    // ride — just open the app. Fires once; if the dash is off it errors out quietly and
    // the rider can retry. (Needs permissions already granted from a prior run.)
    // rememberSaveable so a config change (rotation/theme) doesn't reset this and silently
    // reconnect after the rider deliberately disconnected.
    var autoConnectTried by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoConnectTried && ui.stage == ConnStage.OFFLINE && hasEssentialPermissions()) {
            autoConnectTried = true
            vm.connect()
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
            eyebrow = "What the rider sees",
            title = "Dash view",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (streaming && ui.thermal != "OK") {
                        OpenDashChip(
                            ui.thermal,
                            if (ui.thermal == "Warm") ChipTone.Warn else ChipTone.Alert,
                        )
                    }
                    when (ui.stage) {
                        ConnStage.STREAMING -> OpenDashChip("LIVE", ChipTone.Gold, dot = true)
                        ConnStage.WIFI,
                        ConnStage.AUTH      -> OpenDashChip("Connecting…", ChipTone.Neutral)
                        ConnStage.ERROR     -> OpenDashChip("Error", ChipTone.Alert)
                        ConnStage.OFFLINE   -> OpenDashChip("Offline", ChipTone.Off)
                    }
                }
            },
        )

        // Single connection card (hidden once streaming)
        if (!streaming) {
            OpenDashCard(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            when (ui.stage) {
                                ConnStage.OFFLINE -> "Connect to Tripper Dash"
                                ConnStage.WIFI    -> "Joining ${ui.ssid}…"
                                ConnStage.AUTH    -> "Authenticating with dash…"
                                ConnStage.ERROR   -> "Connection failed"
                                ConnStage.STREAMING -> "Streaming"
                            },
                            color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when (ui.stage) {
                                ConnStage.OFFLINE -> "WiFi + handshake + stream, one tap"
                                ConnStage.WIFI    -> "Accept the system dialog if it appears"
                                ConnStage.AUTH    -> "Handshaking with firmware 11.63…"
                                ConnStage.ERROR   -> ui.errorMessage ?: "See Logcat tag DashSession"
                                ConnStage.STREAMING -> ""
                            },
                            color = if (ui.stage == ConnStage.ERROR) Warn else TextMid,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (ui.stage == ConnStage.OFFLINE || ui.stage == ConnStage.ERROR) {
                        OpenDashBtn(
                            "Connect",
                            onClick = {
                                if (hasEssentialPermissions()) vm.connect()
                                else permissionLauncher.launch(requestedPermissions)
                            },
                            icon = OpenDashIcons.Wifi,
                            variant = BtnVariant.Primary,
                            size = BtnSize.Sm,
                        )
                    } else {
                        OpenDashBtn(
                            "Cancel",
                            onClick = { vm.disconnect() },
                            variant = BtnVariant.Ghost,
                            size = BtnSize.Sm,
                        )
                    }
                }
                if (ui.frameCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Frames encoded: ${ui.frameCount}",
                        color = TextLo, fontSize = 11.sp, fontFamily = GeistMonoFamily,
                    )
                }
                ui.lastButton?.let { btn ->
                    Text(
                        "Joystick: $btn",
                        color = Gold, fontSize = 11.sp, fontFamily = GeistMonoFamily,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // Circular viewport with streaming aura
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            if (streaming) {
                Box(
                    Modifier
                        .size(300.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Brush.radialGradient(listOf(GoldGlow, Color.Transparent), radius = 200f))
                )
            }
            // Real Google Maps, clipped to the round Tripper shape.
            Box(
                modifier = Modifier
                    .size(272.dp)
                    .clip(CircleShape)
                    .border(6.dp, Color(0xFF0D0F10), CircleShape)
                    .border(2.dp, Line2, CircleShape),
            ) {
                OpenDashMap(
                    riderLat = ui.riderLat,
                    riderLng = ui.riderLng,
                    dest = ui.destLatLng,
                    routePoints = ui.routePoints,
                    hasLocationPermission = hasEssentialPermissions(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Next-turn banner (real turn-by-turn from the routing engine)
        ui.maneuver?.let { mv ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (ui.offRoute) Color(0x33D8853E) else GoldTint)
                    .border(1.dp, if (ui.offRoute) Warn else GoldTint2, RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Icon(OpenDashIcons.Navi, null, tint = if (ui.offRoute) Warn else Gold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(11.dp))
                Text(
                    if (ui.offRoute) "Off route — rerouting…" else mv,
                    color = TextHi, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // Live info strip — real remaining distance, ETA, zoom
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(
                    ui.remainingKm?.let { if (it >= 10) "%.0f".format(it) else "%.1f".format(it) } ?: "—",
                    if (ui.remainingKm != null) "km" else "", "Remaining",
                ),
                Triple(ui.etaMinutes?.toString() ?: "—", if (ui.etaMinutes != null) "min" else "", "ETA"),
                Triple("z${ui.mapZoom}", "", "Zoom"),
            ).forEach { (v, u, k) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Surf1)
                        .border(1.dp, Line, RoundedCornerShape(13.dp))
                        .padding(11.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                        Text(v, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                        if (u.isNotEmpty()) {
                            Spacer(Modifier.width(3.dp))
                            Text(u, color = TextLo, fontSize = 10.5.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 2.dp))
                        }
                    }
                    Eyebrow(k, Modifier.padding(top = 3.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Map adjust mode toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (adjustMode) GoldTint else Surf1)
                .border(1.dp, if (adjustMode) GoldTint2 else Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(OpenDashIcons.Cross, null, tint = if (adjustMode) Gold else TextMid, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("Map adjust mode", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text("Mirrors the bike joystick", color = TextLo, fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }
            OpenDashToggle(on = adjustMode, onChange = { adjustMode = it })
        }

        Spacer(Modifier.height(10.dp))

        // Heading-up toggle (rotate map to travel direction)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surf1)
                .border(1.dp, Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(OpenDashIcons.Navi, null, tint = if (ui.headingUp) Gold else TextMid, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("Heading-up map", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text(if (ui.headingUp) "Rotates to travel direction" else "North-up", color = TextLo, fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }
            OpenDashToggle(on = ui.headingUp, onChange = { vm.toggleHeadingUp() })
        }

        Spacer(Modifier.height(16.dp))

        // Controls: joystick + zoom (drive the actual dash map)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Surf1)
                    .border(1.dp, Line, RoundedCornerShape(18.dp))
                    .padding(vertical = 16.dp, horizontal = 12.dp),
            ) {
                Joystick(
                    size = 128.dp,
                    onMove = { v -> joystickVelocity = if (adjustMode) v else Offset.Zero },
                )
                Spacer(Modifier.height(9.dp))
                Eyebrow("Pan")
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OpenDashIconBtn(OpenDashIcons.Plus,  onClick = { vm.zoomIn() },  size = 52.dp)
                Text("z${ui.mapZoom}", color = Gold, fontSize = 12.sp, fontFamily = GeistMonoFamily, fontWeight = FontWeight.SemiBold)
                OpenDashIconBtn(OpenDashIcons.Minus, onClick = { vm.zoomOut() }, size = 52.dp)
                OpenDashIconBtn(OpenDashIcons.Recenter, onClick = { vm.recenter(); pan = Offset.Zero }, size = 52.dp, active = true)
            }
        }

        // Exit navigation → free roam (keeps streaming, just drops the route)
        if (streaming && ui.destinationName != null) {
            Spacer(Modifier.height(16.dp))
            OpenDashBtn(
                "Exit navigation",
                onClick = { vm.exitNavigation() },
                icon = OpenDashIcons.Navi,
                variant = BtnVariant.Ghost,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Disconnect button when streaming
        if (streaming) {
            Spacer(Modifier.height(20.dp))
            OpenDashBtn(
                "Disconnect",
                onClick = { vm.disconnect() },
                icon = OpenDashIcons.Power,
                variant = BtnVariant.Danger,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
