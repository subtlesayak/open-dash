package com.example.opendash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.opendash.BuildConfig
import com.example.opendash.navigation.provider.MapboxNavigationProvider
import com.example.opendash.navigation.provider.NavigationProgress
import com.example.opendash.ui.components.BtnSize
import com.example.opendash.ui.components.BtnVariant
import com.example.opendash.ui.components.OpenDashBtn
import com.example.opendash.ui.components.ScreenHeader
import com.example.opendash.ui.theme.GeistFamily
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun MapboxNavigationDebugScreen(
    originLat: Double?,
    originLng: Double?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember { MapboxNavigationProvider(context.applicationContext) }
    var destinationLat by remember { mutableStateOf("") }
    var destinationLng by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<NavigationProgress?>(null) }
    var status by remember { mutableStateOf("Idle") }

    provider.observeProgress { progress = it }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        ScreenHeader(title = "Mapbox debug", onBack = onBack)
        Text(
            if (BuildConfig.USE_MAPBOX_NAVIGATION_EXPERIMENTAL) "Experimental provider enabled" else "Experimental provider disabled",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = GeistFamily,
        )
        OutlinedTextField(
            value = destinationLat,
            onValueChange = { destinationLat = it },
            label = { Text("Destination latitude") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = destinationLng,
            onValueChange = { destinationLng = it },
            label = { Text("Destination longitude") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OpenDashBtn(
                "Start navigation",
                onClick = {
                    scope.launch {
                        val oLat = originLat
                        val oLng = originLng
                        val dLat = destinationLat.toDoubleOrNull()
                        val dLng = destinationLng.toDoubleOrNull()
                        if (oLat == null || oLng == null || dLat == null || dLng == null) {
                            status = "Enter valid coordinates and wait for GPS"
                            return@launch
                        }
                        status = "Requesting Mapbox route..."
                        runCatching {
                            provider.requestRoute(oLat, oLng, dLat, dLng)
                        }.onSuccess {
                            status = "Guidance running: ${it.maneuvers.size} maneuvers"
                            provider.startGuidance(it)
                        }.onFailure {
                            status = it.message ?: "Mapbox route request failed"
                        }
                    }
                },
                variant = BtnVariant.Primary,
                size = BtnSize.Sm,
                modifier = Modifier.weight(1f),
            )
            OpenDashBtn(
                "Stop navigation",
                onClick = {
                    provider.stopGuidance()
                    status = "Stopped"
                },
                variant = BtnVariant.Ghost,
                size = BtnSize.Sm,
                modifier = Modifier.weight(1f),
            )
        }
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = GeistFamily)
        DebugValue("Current instruction", progress?.nextManeuver?.instruction ?: "None")
        DebugValue("Distance to next maneuver", progress?.distanceToNextManeuverMeters?.formatMeters() ?: "-")
        DebugValue("Remaining distance", progress?.remainingDistanceMeters?.formatMeters() ?: "-")
        DebugValue("Remaining time", progress?.remainingDurationSeconds?.formatDuration() ?: "-")
        DebugValue("ETA", progress?.etaEpochMillis?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "-")
        DebugValue("Off route", progress?.offRoute?.toString() ?: "-")
    }
}

@Composable
private fun DebugValue(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = GeistFamily)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontFamily = GeistFamily)
    }
}

private fun Double.formatMeters(): String =
    if (this >= 1000.0) "%.1f km".format(this / 1000.0) else "${toInt()} m"

private fun Double.formatDuration(): String {
    val minutes = (this / 60.0).toInt()
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
}
