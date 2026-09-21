package com.example.opendash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opendash.data.MaintenanceItem
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.*
import com.example.opendash.ui.theme.*
import com.example.opendash.viewmodel.GarageUi
import com.example.opendash.viewmodel.GarageViewModel
import com.example.opendash.viewmodel.MaintRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dfHistory = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

private fun iconFor(key: String): ImageVector = when (key) {
    "chain"  -> OpenDashIcons.Chain
    "drop"   -> OpenDashIcons.Drop
    "gauge"  -> OpenDashIcons.Gauge
    "thermo" -> OpenDashIcons.Thermo
    "fuel"   -> OpenDashIcons.Fuel
    else     -> OpenDashIcons.Wrench
}

private fun dueText(row: MaintRow): String {
    if (row.tone == "alert") return "Overdue"
    val verb = row.officialSchedule?.action?.verb ?: "Service"
    return "$verb in ${"%,d".format(row.remainingKm.coerceAtLeast(0))} km"
}

@Composable
fun GarageScreen(
    vm: GarageViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    var showAddService by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showOdometer by remember { mutableStateOf(false) }
    var selectedPart by remember { mutableStateOf<MaintRow?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(title = "Garage")

        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            Eyebrow("Active vehicle")
            Text(
                ui.activeVehicleName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistFamily,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "${"%,d".format(ui.odometerKm)} km on odometer",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 18.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Icon(OpenDashIcons.Gauge, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Latest odometer reading", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(
                        "${"%,d".format(ui.odometerKm)} km",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistMonoFamily,
                    )
                }
                OpenDashIconBtn(OpenDashIcons.Edit, onClick = { showOdometer = true })
            }
        }

        Spacer(Modifier.height(24.dp))
        MileageSummary(ui.avgKmplLast5)
        GarageSectionHeader("Spare parts", "Condition and service distance for ${ui.activeVehicleName}")
        MaintenanceTab(ui, onSelect = { selectedPart = it }, onLog = { showLog = true }, onAdd = { showAddService = true })
    }
    if (showAddService) AddServiceDialog(
        onAdd = { n, ic, iv -> vm.addService(n, ic, iv); showAddService = false },
        onDismiss = { showAddService = false },
    )
    if (showLog) LogServiceDialog(
        rows = ui.maint, odo = ui.odometerKm,
        onMark = { item -> vm.markServiceDone(item, ui.odometerKm) },
        onDelete = { vm.deleteService(it) },
        onAddNew = { showLog = false; showAddService = true },
        onDismiss = { showLog = false },
    )
    if (showOdometer) OdometerDialog(
        current = ui.odometerKm,
        onSet = { vm.setOdometer(it); showOdometer = false },
        onDismiss = { showOdometer = false },
    )
    selectedPart?.let { row ->
        SparePartDetailsSheet(
            row = row,
            odometerKm = ui.odometerKm,
            onLogService = { interval ->
                vm.logService(row.item, ui.odometerKm, interval)
                selectedPart = null
            },
            onDismiss = { selectedPart = null },
        )
    }
}

@Composable
private fun MileageSummary(avgKmpl: Double?) {
    OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(OpenDashIcons.Fuel, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Avg. mileage of last 5 fuel-ups", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(
                    avgKmpl?.let { "%.2f km/l".format(it) } ?: "Not enough fuel data",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun GarageSectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(top = 22.dp, bottom = 10.dp, start = 2.dp, end = 2.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun MaintenanceTab(ui: GarageUi, onSelect: (MaintRow) -> Unit, onLog: () -> Unit, onAdd: () -> Unit) {
    val toneColor = mapOf("ok" to MaterialTheme.colorScheme.primary, "warn" to Warn, "alert" to MaterialTheme.colorScheme.error)

    Eyebrow("Service intervals", Modifier.padding(bottom = 8.dp, start = 4.dp))

    OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
        if (ui.maint.isEmpty()) {
            Text("No intervals yet — add one below.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        }
        ui.maint.forEachIndexed { i, row ->
            if (i > 0) OpenDashDivider(Modifier.padding(horizontal = 4.dp))
            val color = toneColor[row.tone] ?: MaterialTheme.colorScheme.primary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onSelect(row) }.padding(horizontal = 6.dp, vertical = 12.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(11.dp))) {
                    Icon(iconFor(row.item.iconKey), null, tint = color, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.item.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text("Last · ${"%,d".format(row.item.lastDoneOdoKm)} km", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(dueText(row), color = color, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                    row.remainingDays?.let { days ->
                        Text(
                            "or ${days.coerceAtLeast(0)} days",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(OpenDashIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(top = 4.dp))
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OpenDashBtn("Log a service", onClick = onLog, icon = OpenDashIcons.Check, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.weight(1f))
        OpenDashBtn("Add interval", onClick = onAdd, icon = OpenDashIcons.Plus, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SparePartDetailsSheet(
    row: MaintRow,
    odometerKm: Int,
    onLogService: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var interval by remember(row.item.sid) { mutableStateOf(row.item.intervalKm.toString()) }
    val parsedInterval = interval.toIntOrNull()
    val distanceUsed = (odometerKm - row.item.lastDoneOdoKm).coerceAtLeast(0)
    val status = when (row.tone) { "alert" -> "Overdue"; "warn" -> "Due soon"; else -> "Good" }
    val statusColor = when (row.tone) { "alert" -> MaterialTheme.colorScheme.error; "warn" -> Warn; else -> MaterialTheme.colorScheme.primary }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.item.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    status,
                    color = statusColor,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PartMetric("Distance used", "${"%,d".format(distanceUsed)} km", Modifier.weight(1f))
                PartMetric(
                    "Remaining",
                    buildString {
                        append("${"%,d".format(row.remainingKm.coerceAtLeast(0))} km")
                        row.remainingDays?.let { append(" or ${it.coerceAtLeast(0)} days") }
                    },
                    Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = it.filter(Char::isDigit) },
                label = { Text(row.officialSchedule?.action?.intervalLabel ?: "Service interval (km)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                row.officialSchedule?.guidance
                    ?: "Adjust the service interval for your riding style, terrain, and usage.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
            )
            row.officialSchedule?.let { official ->
                Text(
                    "Official source: ${official.manualPages}. Schedule is whichever comes earlier; shorten it for severe or dusty conditions.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            Text("History", color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.padding(top = 14.dp, start = 4.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(dfHistory.format(Date(row.item.lastDoneDateMs)), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Text("${"%,d".format(row.item.lastDoneOdoKm)} km", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
            OpenDashBtn(
                "Log service",
                onClick = { parsedInterval?.takeIf { it > 0 }?.let(onLogService) },
                icon = OpenDashIcons.Check,
                enabled = parsedInterval != null && parsedInterval > 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PartMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────

@Composable
private fun AddServiceDialog(onAdd: (String, String, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("") }
    val icons = listOf("wrench", "chain", "drop", "gauge", "thermo", "fuel")
    var icon by remember { mutableStateOf("wrench") }
    val valid = name.isNotBlank() && interval.toIntOrNull() != null && interval.toInt() > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(name.trim(), icon, interval.toInt()) }) { Text("Add", color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        title = { Text("Add interval", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumField(interval, { interval = it }, "Interval (km)", false)
                Spacer(Modifier.height(10.dp))
                Eyebrow("Icon")
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    icons.forEach { key ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (key == icon) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(1.dp, if (key == icon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                .clickable { icon = key },
                        ) { Icon(iconFor(key), null, tint = if (key == icon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

@Composable
private fun LogServiceDialog(rows: List<MaintRow>, odo: Int, onMark: (MaintenanceItem) -> Unit, onDelete: (MaintenanceItem) -> Unit, onAddNew: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onAddNew) { Text("Add interval", color = MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        title = { Text("Mark a service done", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                Text("Marks the item done at ${"%,d".format(odo)} km.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                rows.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Icon(iconFor(row.item.iconKey), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(row.item.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onMark(row.item) }) { Text("Done", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
                        Icon(OpenDashIcons.Cross, "delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).clickable { onDelete(row.item) })
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

@Composable
private fun OdometerDialog(current: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    var odo by remember { mutableStateOf(current.toString()) }
    val valid = odo.toIntOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = valid, onClick = { onSet(odo.toInt()) }) { Text("Save", color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        title = { Text("Set odometer", color = MaterialTheme.colorScheme.onSurface) },
        text = { NumField(odo, { odo = it }, "Odometer (km)", false) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: String, decimal: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
