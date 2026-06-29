package com.example.opendash.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.ConnStage
import com.example.opendash.viewmodel.ConnectionState
import com.example.opendash.viewmodel.DashUiState
import com.example.opendash.viewmodel.DashViewModel
import com.example.opendash.viewmodel.RidesViewModel
import com.example.opendash.viewmodel.RouteViewModel
import com.example.opendash.data.VehicleStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    conn: ConnectionState,
    onNavigate: (String) -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
    ridesViewModel: RidesViewModel = viewModel(),
    dashViewModel: DashViewModel = viewModel(),
) {
    val saved by routeViewModel.saved.collectAsState()
    val rides by ridesViewModel.rides.collectAsState()
    val dashUi by dashViewModel.ui.collectAsState()
    val vehicles by VehicleStore.vehicles.collectAsState()
    val activeVehicleId by VehicleStore.activeVehicleId.collectAsState()
    val activeVehicle = vehicles.firstOrNull { it.id == activeVehicleId } ?: vehicles.first()
    val context = LocalContext.current
    val status = when (conn) {
        ConnectionState.Connected -> Triple("Connected", "Streaming to dash", MaterialTheme.colorScheme.primary)
        ConnectionState.Searching -> Triple("Searching…", "Looking for dash", MaterialTheme.colorScheme.tertiary)
        ConnectionState.Offline   -> Triple("Offline", "Dash not detected", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val (statusLabel, statusSub, statusDot) = status
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
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val essentialOk = essentialPermissions.all { results[it] == true }
        if (essentialOk) dashViewModel.connect()
    }

    fun connectToDash() {
        if (hasEssentialPermissions()) dashViewModel.connect()
        else permissionLauncher.launch(requestedPermissions)
    }

    // Pulse animation for connected dot
    val infiniteTransition = rememberInfiniteTransition(label = "dot-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        1f, 0.35f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            wordmark = true,
        )

        HomeDashPreviewCard(
            dashUi = dashUi,
            vehicleTitle = activeVehicle.title,
            statusLabel = statusLabel,
            statusSub = statusSub,
            statusDot = statusDot,
            pulseAlpha = pulseAlpha,
            glowing = conn == ConnectionState.Connected,
        )

        Spacer(Modifier.height(12.dp))

        HomeConnectionCard(
            dashUi = dashUi,
            onConnect = { connectToDash() },
            onCancel = { dashViewModel.disconnect() },
            onManualDashCode = { code -> dashViewModel.saveManualDashCode(code) },
        )

        Spacer(Modifier.height(12.dp))

        Eyebrow("OpenDash", Modifier.padding(bottom = 8.dp, start = 4.dp))
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            OpenDashRow(
                "Start navigation",
                icon = OpenDashIcons.Navi,
                sub = "Pick a destination and send it to the dash",
                trailingIcon = true,
                onClick = { onNavigate("route") },
            )
        }

        Spacer(Modifier.height(12.dp))

        DashWallpaperHomeSection(dashViewModel = dashViewModel)

        Spacer(Modifier.height(18.dp))

        Eyebrow("Saved destinations", Modifier.padding(bottom = 6.dp, start = 4.dp))

        if (saved.isEmpty()) {
            OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
                Text(
                    "No saved destinations yet. Share a place or geo link, then tap “Save this destination”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                )
            }
        } else {
            OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                saved.forEachIndexed { i, loc ->
                    if (i > 0) OpenDashDivider(Modifier.padding(horizontal = 4.dp))
                    OpenDashRow(
                        loc.name, icon = OpenDashIcons.LocationPin,
                        sub = loc.note.ifBlank { "%.4f, %.4f".format(loc.lat, loc.lng) },
                        trailingIcon = true,
                        onClick = { routeViewModel.selectSaved(loc); onNavigate("route") },
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Eyebrow("Rides", Modifier.padding(bottom = 6.dp, start = 4.dp))
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            if (rides.isEmpty()) {
                OpenDashRow(
                    "No rides recorded yet",
                    icon = OpenDashIcons.History,
                    sub = "Connected rides appear here automatically",
                    trailingIcon = false,
                )
            } else {
                val totalKm = rides.sumOf { it.distanceKm }
                val totalMinutes = rides.sumOf { it.durationSec } / 60
                OpenDashRow(
                    "${rides.size} rides · %.1f km".format(totalKm),
                    icon = OpenDashIcons.History,
                    sub = "$totalMinutes min recorded · View full history",
                    trailingIcon = true,
                    onClick = { onNavigate("rides") },
                )
                rides.take(3).forEach { ride ->
                    OpenDashDivider(Modifier.padding(horizontal = 4.dp))
                    OpenDashRow(
                        "%.1f km · %s".format(ride.distanceKm, formatRideDuration(ride.durationSec)),
                        icon = OpenDashIcons.Navi,
                        sub = formatRideDate(ride.startMs),
                        trailingIcon = false,
                        onClick = { onNavigate("rides") },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeDashPreviewCard(
    dashUi: DashUiState,
    vehicleTitle: String,
    statusLabel: String,
    statusSub: String,
    statusDot: Color,
    pulseAlpha: Float,
    glowing: Boolean,
) {
    val vehicleLabelFontSize = when {
        vehicleTitle.length > 30 -> 9.5.sp
        vehicleTitle.length > 26 -> 10.5.sp
        vehicleTitle.length > 22 -> 11.5.sp
        vehicleTitle.length > 18 -> 12.5.sp
        vehicleTitle.length > 14 -> 14.sp
        else -> 16.sp
    }

    OpenDashCard(
        glow = glowing,
        padding = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .widthIn(max = 230.dp)
                    .aspectRatio(DASH_ARTBOARD_ASPECT)
                    .clip(DashViewportShape)
                    .background(Color.Black)
                    .border(4.dp, MaterialTheme.colorScheme.surfaceContainerLow, DashViewportShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, DashViewportShape),
            ) {
                OpenDashMap(
                    riderLat = dashUi.riderLat,
                    riderLng = dashUi.riderLng,
                    dest = dashUi.destLatLng,
                    routePoints = dashUi.routePoints,
                    hasLocationPermission = true,
                    modifier = Modifier.fillMaxSize(),
                    navMode = dashUi.headingUp,
                    riderBearing = dashUi.riderBearing,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.48f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        OpenDashIcons.Motor,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        vehicleTitle,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = vehicleLabelFontSize,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistFamily,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusDot.copy(alpha = if (glowing) pulseAlpha else 1f)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            statusLabel,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GeistFamily,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        statusSub,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 20.dp, top = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeConnectionCard(
    dashUi: DashUiState,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onManualDashCode: (String) -> Boolean,
) {
    val connecting = dashUi.stage == ConnStage.WIFI || dashUi.stage == ConnStage.AUTH
    val connected = dashUi.stage == ConnStage.STREAMING
    val resolvingExactSsid = dashUi.stage == ConnStage.WIFI &&
        dashUi.errorMessage?.contains("exact SSID", ignoreCase = true) == true
    val showManualEntry = (!connecting && !connected) || resolvingExactSsid
    var manualDashCode by rememberSaveable { mutableStateOf(dashUi.ssid) }
    LaunchedEffect(dashUi.ssid) {
        if (manualDashCode != dashUi.ssid) {
            manualDashCode = dashUi.ssid
        }
    }

    fun saveManualCodeIfPresent(): Boolean =
        manualDashCode.isBlank() || onManualDashCode(manualDashCode)

    val title = when (dashUi.stage) {
        ConnStage.OFFLINE -> "Connect to dash"
        ConnStage.WIFI -> if (resolvingExactSsid) "Dash code needed" else "Joining ${dashUi.ssid}..."
        ConnStage.AUTH -> "Authenticating with dash..."
        ConnStage.ERROR -> "Connection failed"
        ConnStage.STREAMING -> "Connected to dash"
    }
    val sub = when (dashUi.stage) {
        ConnStage.OFFLINE -> "WiFi + handshake + stream, one tap"
        ConnStage.WIFI -> dashUi.errorMessage ?: "Accept the system dialog if it appears"
        ConnStage.AUTH -> "Handshaking with firmware 11.63..."
        ConnStage.ERROR -> dashUi.errorMessage ?: "Could not connect to dash"
        ConnStage.STREAMING -> "Projection is active"
    }

    OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    sub,
                    color = if (dashUi.stage == ConnStage.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            OpenDashBtn(
                when {
                    connecting -> "Stop"
                    connected -> "Disconnect"
                    else -> "Connect"
                },
                onClick = {
                    if (connecting || connected) {
                        onCancel()
                    } else if (saveManualCodeIfPresent()) {
                        onConnect()
                    }
                },
                icon = if (connecting || connected) null else OpenDashIcons.Wifi,
                variant = if (connecting || connected) BtnVariant.Ghost else BtnVariant.Primary,
                size = BtnSize.Sm,
            )
        }
        if (showManualEntry) {
            Spacer(Modifier.height(12.dp))
            if (resolvingExactSsid) {
                Text(
                    "Enter the dash code printed on the Wi-Fi name, for example RE_1234.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = manualDashCode,
                    onValueChange = { manualDashCode = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Dash code / SSID") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = GeistMonoFamily,
                        fontSize = 13.sp,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                )
                OpenDashBtn(
                    "Use",
                    onClick = { onManualDashCode(manualDashCode) },
                    modifier = Modifier.width(84.dp),
                    variant = BtnVariant.Secondary,
                    size = BtnSize.Sm,
                    enabled = manualDashCode.isNotBlank(),
                )
            }
        }
    }
}

private fun formatRideDate(timeMs: Long): String =
    SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(timeMs))

private fun formatRideDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
