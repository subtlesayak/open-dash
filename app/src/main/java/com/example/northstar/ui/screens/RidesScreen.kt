package com.example.northstar.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.northstar.data.Ride
import com.example.northstar.dash.nav.PolylineCodec
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.RidesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RidesScreen(ridesViewModel: RidesViewModel = viewModel()) {
    val rides by ridesViewModel.rides.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(eyebrow = "Telemetry", title = "Ride history")

        if (rides.isEmpty()) {
            EmptyRides()
        } else {
            RideTotals(rides)
            Spacer(Modifier.height(14.dp))
            rides.forEach { ride ->
                RideCard(ride, onDelete = { ridesViewModel.deleteRide(ride) })
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun RideTotals(rides: List<Ride>) {
    val totalKm = rides.sumOf { it.distanceKm }
    val totalSec = rides.sumOf { it.durationSec }
    NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 18.dp) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("${rides.size}", "rides")
            Stat("%.0f".format(totalKm), "km total")
            Stat(fmtDuration(totalSec), "time")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily)
        Text(label, color = TextLo, fontSize = 12.sp, fontFamily = GeistFamily)
    }
}

@Composable
private fun RideCard(ride: Ride, onDelete: () -> Unit) {
    val track = remember(ride.trackPolyline) {
        if (ride.trackPolyline.isBlank()) emptyList()
        else PolylineCodec.decode(ride.trackPolyline)
    }
    NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Track sketch
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Surf2),
                ) {
                    if (track.size >= 2) TrackSketch(track, Modifier.fillMaxSize().padding(8.dp))
                    else Icon(NorthstarIcons.Route, contentDescription = null, tint = TextLo, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(fmtDate(ride.startMs), color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text(
                        "${fmtTime(ride.startMs)} – ${fmtTime(ride.endMs)}",
                        color = TextLo, fontSize = 12.sp, fontFamily = GeistFamily,
                    )
                }
                Text("%.1f km".format(ride.distanceKm), color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat(fmtDuration(ride.durationSec), "duration")
                MiniStat("%.0f km/h".format(ride.avgSpeedKmh), "avg")
                MiniStat("%.0f km/h".format(ride.maxSpeedKmh), "max")
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(Surf2)
                        .clickable { onDelete() },
                ) {
                    Icon(NorthstarIcons.X, contentDescription = "Delete ride", tint = TextLo, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
        Text(label, color = TextLo, fontSize = 11.sp, fontFamily = GeistFamily)
    }
}

@Composable
private fun TrackSketch(points: List<com.example.northstar.dash.nav.GeoPoint>, modifier: Modifier) {
    Canvas(modifier) {
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }; val maxLng = points.maxOf { it.lng }
        val spanLat = (maxLat - minLat).coerceAtLeast(1e-6)
        val spanLng = (maxLng - minLng).coerceAtLeast(1e-6)
        val span = maxOf(spanLat, spanLng)   // keep aspect square-ish
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = ((p.lng - minLng) / span * size.width).toFloat()
            val y = (size.height - (p.lat - minLat) / span * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Gold, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // start (green-ish) + end (gold) dots
        val first = points.first(); val last = points.last()
        drawCircle(TextHi, 2.5f, Offset(((first.lng - minLng) / span * size.width).toFloat(),
            (size.height - (first.lat - minLat) / span * size.height).toFloat()))
        drawCircle(Gold, 2.5f, Offset(((last.lng - minLng) / span * size.width).toFloat(),
            (size.height - (last.lat - minLat) / span * size.height).toFloat()))
    }
}

@Composable
private fun EmptyRides() {
    NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 28.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(56.dp).clip(CircleShape).background(Surf2),
            ) {
                Icon(NorthstarIcons.Route, contentDescription = null, tint = TextLo, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("No rides recorded yet", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            Spacer(Modifier.height(6.dp))
            Text(
                "Connect to your dash to start a ride — it's saved automatically when you disconnect.",
                color = TextLo, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

private fun fmtDate(ms: Long) = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(ms))
private fun fmtTime(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
private fun fmtDuration(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
