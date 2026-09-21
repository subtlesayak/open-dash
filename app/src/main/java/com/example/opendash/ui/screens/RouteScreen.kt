package com.example.opendash.ui.screens

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.GeistFamily
import com.example.opendash.ui.theme.GeistMonoFamily
import com.example.opendash.ui.theme.MapBase
import com.example.opendash.viewmodel.RouteViewModel

@Composable
fun RouteScreen(
    onBack: () -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
) {
    val routeState by routeViewModel.state.collectAsState()
    val savedList by routeViewModel.saved.collectAsState()
    val destination = routeState.destination
    val destinationName = destination?.name?.ifBlank { "Shared location" } ?: "Shared location"
    val destinationSub = when {
        destination?.lat != null && destination.lng != null -> "%.5f, %.5f".format(destination.lat, destination.lng)
        destination?.url != null -> "Maps link"
        else -> ""
    }
    val context = LocalContext.current
    var locationEnabled by remember { mutableStateOf(isDeviceLocationEnabled(context)) }
    var showSave by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.example.opendash.data.SavedLocation?>(null) }

    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        locationEnabled = isDeviceLocationEnabled(context)
        routeViewModel.refreshRouteIfPossible()
    }

    fun openLocationSettings() {
        locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    val hasDestination = destination?.lat != null && destination.lng != null && !routeState.isResolving
    val hasRouteStats = routeState.routing || routeState.distanceText != null ||
        routeState.durationText != null || routeState.etaText != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            title = "Route preview",
            onBack = onBack,
            trailing = {
                if (routeState.routing) {
                    OpenDashChip("Routing", ChipTone.Gold, dot = true)
                }
            },
        )

        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp, max = 220.dp)
                .aspectRatio(1.62f)
                .clip(RoundedCornerShape(22.dp))
                .background(MapBase)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)),
        ) {
            OpenDashMap(
                riderLat = null,
                riderLng = null,
                dest = destination?.let { d -> if (d.lat != null && d.lng != null) d.lat to d.lng else null },
                routePoints = routeState.route?.geometry.orEmpty(),
                hasLocationPermission = true,
                fitRoute = true,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(Modifier.height(16.dp))
        Eyebrow("Destination", Modifier.padding(bottom = 6.dp))
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Icon(
                        OpenDashIcons.LocationPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (routeState.isResolving) "Resolving..." else destinationName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistFamily,
                    )
                    if (destinationSub.isNotBlank()) {
                        Text(
                            destinationSub,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontFamily = GeistMonoFamily,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        if (hasDestination && !locationEnabled) {
            LocationDisabledCard(onEnableLocation = { openLocationSettings() })
            Spacer(Modifier.height(18.dp))
        }

        if (hasRouteStats) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(if (routeState.routing) "..." else routeState.distanceText.orEmpty(), "Distance", OpenDashIcons.Route),
                    Triple(if (routeState.routing) "..." else routeState.durationText.orEmpty(), "Duration", OpenDashIcons.Clock),
                    Triple(if (routeState.routing) "..." else routeState.etaText.orEmpty(), "Arrive", OpenDashIcons.Cal),
                ).forEach { (value, label, icon) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                            .padding(horizontal = 11.dp, vertical = 10.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                        Eyebrow(label, Modifier.padding(top = 3.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        OpenDashBtn(
            label = if (hasDestination && !locationEnabled) "Enable location" else "Refresh route",
            onClick = {
                if (hasDestination && !locationEnabled) openLocationSettings()
                else routeViewModel.refreshRouteIfPossible()
            },
            icon = if (hasDestination && !locationEnabled) OpenDashIcons.Recenter else OpenDashIcons.Route,
            variant = BtnVariant.Secondary,
            size = BtnSize.Md,
            enabled = hasDestination,
            modifier = Modifier.fillMaxWidth(),
        )

        if (hasDestination) {
            Spacer(Modifier.height(10.dp))
            OpenDashBtn(
                label = "Save this destination",
                onClick = { showSave = true },
                icon = OpenDashIcons.Pin,
                variant = BtnVariant.Primary,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (savedList.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Eyebrow("Saved destinations", Modifier.padding(bottom = 6.dp, start = 4.dp))
            OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                savedList.forEachIndexed { index, loc ->
                    if (index > 0) OpenDashDivider(Modifier.padding(horizontal = 4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { routeViewModel.selectSaved(loc) }
                            .padding(horizontal = 6.dp, vertical = 12.dp),
                    ) {
                        Icon(OpenDashIcons.LocationPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(loc.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily, maxLines = 1)
                            Text(
                                if (loc.note.isNotBlank()) loc.note else "%.4f, %.4f".format(loc.lat, loc.lng),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontFamily = GeistMonoFamily,
                                modifier = Modifier.padding(top = 2.dp),
                                maxLines = 1,
                            )
                        }
                        Icon(
                            OpenDashIcons.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).clickable { editing = loc },
                        )
                    }
                }
            }
        }
    }

    if (showSave) {
        SaveLocationDialog(
            defaultName = destinationName,
            onSave = { name, note ->
                routeViewModel.saveCurrentDestination(name, note)
                showSave = false
            },
            onDismiss = { showSave = false },
        )
    }

    editing?.let { loc ->
        EditLocationDialog(
            loc = loc,
            onSave = { name, note ->
                routeViewModel.renameSaved(loc, name, note)
                editing = null
            },
            onDelete = {
                routeViewModel.deleteSaved(loc)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun LocationDisabledCard(onEnableLocation: () -> Unit) {
    OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                OpenDashIcons.Recenter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Location is off",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                )
                Text(
                    "Enable location to refresh route distance and ETA.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            OpenDashBtn(
                label = "Enable",
                onClick = onEnableLocation,
                variant = BtnVariant.Secondary,
                size = BtnSize.Sm,
            )
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
private fun SaveLocationDialog(defaultName: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) {
                Text("Save", color = if (name.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
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
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) {
                Text("Save", color = if (name.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
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
