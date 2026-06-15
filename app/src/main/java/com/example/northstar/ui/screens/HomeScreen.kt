package com.example.northstar.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.ConnectionState
import com.example.northstar.viewmodel.RouteViewModel

@Composable
fun HomeScreen(
    conn: ConnectionState,
    onNavigate: (String) -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
) {
    val saved by routeViewModel.saved.collectAsState()
    val status = when (conn) {
        ConnectionState.Connected -> Triple("Connected", "Streaming to Tripper Dash", Gold)
        ConnectionState.Searching -> Triple("Searching…", "Looking for Tripper Dash", Warn)
        ConnectionState.Offline   -> Triple("Offline", "Dash not detected", Offline)
    }
    val (statusLabel, statusSub, statusDot) = status

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
            trailing = { NorthstarIconBtn(NorthstarIcons.Gear, onClick = { onNavigate("settings") }) },
        )

        // Connection hero card
        NorthstarCard(
            glow = conn == ConnectionState.Connected,
            padding = 20.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularDash(
                    size = 118.dp,
                    pan = Offset.Zero,
                    zoom = 1f,
                    compact = true,
                    live = conn == ConnectionState.Connected,
                )

                Spacer(Modifier.width(18.dp))

                Column(Modifier.weight(1f)) {
                    Eyebrow("Royal Enfield · Tripper")

                    Spacer(Modifier.height(7.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(9.dp).clip(CircleShape).background(
                                statusDot.copy(alpha = if (conn == ConnectionState.Connected) pulseAlpha else 1f)
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            statusLabel, color = TextHi, fontSize = 19.sp,
                            fontWeight = FontWeight.Bold, fontFamily = GeistFamily,
                            letterSpacing = (-0.38).sp,
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(statusSub, color = TextMid, fontSize = 13.sp)

                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NorthstarChip("Himalayan 450", ChipTone.Gold, icon = NorthstarIcons.Motor)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        NorthstarBtn(
            "Start navigation", onClick = { onNavigate("route") },
            icon = NorthstarIcons.Navi, variant = BtnVariant.Primary, size = BtnSize.Lg,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        NorthstarBtn(
            if (conn == ConnectionState.Connected) "Open dash view" else "Connect to dash",
            onClick = { onNavigate("dash") },
            icon = if (conn == ConnectionState.Connected) NorthstarIcons.Dash else NorthstarIcons.Wifi,
            variant = if (conn == ConnectionState.Connected) BtnVariant.Secondary else BtnVariant.Primary,
            size = BtnSize.Md,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(18.dp))

        Eyebrow("Saved destinations", Modifier.padding(bottom = 6.dp, start = 4.dp))

        if (saved.isEmpty()) {
            NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
                Text(
                    "No saved destinations yet. Share a place from Google Maps, then tap “Save this destination”.",
                    color = TextLo, fontSize = 13.sp,
                )
            }
        } else {
            NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                saved.forEachIndexed { i, loc ->
                    if (i > 0) NorthstarDivider(Modifier.padding(horizontal = 4.dp))
                    NorthstarRow(
                        loc.name, icon = NorthstarIcons.LocationPin,
                        sub = loc.note.ifBlank { "%.4f, %.4f".format(loc.lat, loc.lng) },
                        trailingIcon = true,
                        onClick = { routeViewModel.selectSaved(loc); onNavigate("route") },
                    )
                }
            }
        }
    }
}
