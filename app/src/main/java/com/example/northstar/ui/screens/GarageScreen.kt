package com.example.northstar.ui.screens

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
import androidx.compose.material3.Icon
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
import com.example.northstar.data.FuelFillup
import com.example.northstar.data.MaintenanceItem
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.GarageUi
import com.example.northstar.viewmodel.GarageViewModel
import com.example.northstar.viewmodel.MaintRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dfRow = SimpleDateFormat("MMM d", Locale.getDefault())
private val dfBar = SimpleDateFormat("d/M", Locale.getDefault())

private fun iconFor(key: String): ImageVector = when (key) {
    "chain"  -> NorthstarIcons.Chain
    "drop"   -> NorthstarIcons.Drop
    "gauge"  -> NorthstarIcons.Gauge
    "thermo" -> NorthstarIcons.Thermo
    "fuel"   -> NorthstarIcons.Fuel
    else     -> NorthstarIcons.Wrench
}

private fun dueText(remainingKm: Int): String = when {
    remainingKm < 0  -> "overdue ${-remainingKm} km"
    else             -> "in ${"%,d".format(remainingKm)} km"
}

@Composable
fun GarageScreen(
    tab: String,
    onTabChange: (String) -> Unit,
    vm: GarageViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    var showFuel by remember { mutableStateOf(false) }
    var showAddService by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showOdo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            eyebrow = "Himalayan 450 · ${"%,d".format(ui.odometerKm)} km",
            title = "Garage",
            trailing = {
                NorthstarBtn("Odometer", onClick = { showOdo = true }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
            },
        )

        NorthstarSegmented(
            options = listOf("Maintenance", "Fuel diary"),
            selected = tab,
            onSelect = onTabChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
        )

        if (tab == "Maintenance")
            MaintenanceTab(ui, onMark = { item -> vm.markServiceDone(item, ui.odometerKm) }, onLog = { showLog = true }, onAdd = { showAddService = true })
        else
            FuelTab(ui, onAdd = { showFuel = true }, onDelete = { vm.deleteFuel(it) })
    }

    if (showFuel) AddFuelDialog(
        defaultOdo = ui.odometerKm,
        onAdd = { l, c, o, loc -> vm.addFuel(l, c, o, loc); showFuel = false },
        onDismiss = { showFuel = false },
    )
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
    if (showOdo) OdometerDialog(ui.odometerKm, onSet = { vm.setOdometer(it); showOdo = false }, onDismiss = { showOdo = false })
}

@Composable
private fun MaintenanceTab(ui: GarageUi, onMark: (MaintenanceItem) -> Unit, onLog: () -> Unit, onAdd: () -> Unit) {
    val toneColor = mapOf("ok" to Gold, "warn" to Warn, "alert" to Alert)
    val hero = ui.maint.minByOrNull { it.remainingKm }

    if (hero != null) {
        val ridden = (ui.odometerKm - hero.item.lastDoneOdoKm).coerceAtLeast(0)
        val frac = (ridden.toFloat() / hero.item.intervalKm.toFloat()).coerceIn(0f, 1f)
        val tone = toneColor[hero.tone] ?: Gold
        NorthstarCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Eyebrow("Most urgent")
                    Text(hero.item.name, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily, letterSpacing = (-0.36).sp, modifier = Modifier.padding(top = 5.dp))
                }
                NorthstarChip(
                    dueText(hero.remainingKm),
                    if (hero.tone == "alert") ChipTone.Alert else if (hero.tone == "warn") ChipTone.Warn else ChipTone.Gold,
                    dot = true,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%,d".format(ridden), color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                Spacer(Modifier.width(6.dp))
                Text("/ ${"%,d".format(hero.item.intervalKm)} km ridden", color = TextLo, fontSize = 13.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Surf3)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(GoldDeep, tone))))
            }
            Spacer(Modifier.height(14.dp))
            NorthstarBtn("Mark done today", onClick = { onMark(hero.item) }, icon = NorthstarIcons.Check, variant = BtnVariant.Primary, size = BtnSize.Sm, modifier = Modifier.fillMaxWidth())
        }
    }

    Eyebrow("Service intervals", Modifier.padding(bottom = 8.dp, start = 4.dp))

    NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
        if (ui.maint.isEmpty()) {
            Text("No intervals yet — add one below.", color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        }
        ui.maint.forEachIndexed { i, row ->
            if (i > 0) NorthstarDivider(Modifier.padding(horizontal = 4.dp))
            val color = toneColor[row.tone] ?: Gold
            val fill = (1f - (row.remainingKm.toFloat().coerceAtLeast(0f) / row.item.intervalKm.toFloat())).coerceIn(0.06f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(11.dp))) {
                    Icon(iconFor(row.item.iconKey), null, tint = color, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.item.name, color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text("Last · ${"%,d".format(row.item.lastDoneOdoKm)} km", color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(dueText(row.remainingKm), color = color, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(56.dp).height(4.dp).clip(CircleShape).background(Surf3)) {
                        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(CircleShape).background(color))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NorthstarBtn("Log a service", onClick = onLog, icon = NorthstarIcons.Check, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.weight(1f))
        NorthstarBtn("Add interval", onClick = onAdd, icon = NorthstarIcons.Plus, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FuelTab(ui: GarageUi, onAdd: () -> Unit, onDelete: (FuelFillup) -> Unit) {
    NorthstarCard(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("Avg. efficiency · 30 days")
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 7.dp)) {
                    Text(ui.avgKmpl30?.let { "%.1f".format(it) } ?: "—", color = Gold, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily, letterSpacing = (-0.76).sp)
                    Spacer(Modifier.width(6.dp))
                    Text("km / l", color = TextMid, fontSize = 14.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
        }
        val chart = ui.fuel.filter { it.kmpl != null }.take(6).reversed()
        if (chart.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            NorthstarBarChart(
                data = chart.map { BarEntry(dfBar.format(Date(it.fill.dateMs)), it.kmpl!!.toFloat()) },
                height = 108.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 18.dp)) {
        listOf(
            Pair("₹${"%,.0f".format(ui.spent30)}", "Spent · 30 days"),
            Pair("%.1f l".format(ui.litres30), "Fuel · ${ui.fills30} fills"),
        ).forEach { (v, k) ->
            Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Surf1).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(v, color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                Eyebrow(k, Modifier.padding(top = 4.dp))
            }
        }
    }

    Eyebrow("Fill-ups", Modifier.padding(bottom = 8.dp, start = 4.dp))
    NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
        if (ui.fuel.isEmpty()) {
            Text("No fill-ups yet — add your first below.", color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        }
        ui.fuel.forEachIndexed { i, row ->
            if (i > 0) NorthstarDivider(Modifier.padding(horizontal = 4.dp))
            val f = row.fill
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onDelete(f) }.padding(horizontal = 6.dp, vertical = 12.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(11.dp))) {
                    Icon(NorthstarIcons.Fuel, null, tint = TextMid, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("%.1f l · ₹%,.0f".format(f.litres, f.cost), color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text("${dfRow.format(Date(f.dateMs))} · ${"%,d".format(f.odometerKm)} km${if (f.location.isNotBlank()) " · ${f.location}" else ""}", color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(row.kmpl?.let { "%.1f".format(it) } ?: "—", color = Gold, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                    Eyebrow("km/l", Modifier.padding(top = 2.dp))
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    NorthstarBtn("Add fill-up", onClick = onAdd, icon = NorthstarIcons.Plus, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.fillMaxWidth())
}

// ── Dialogs ──────────────────────────────────────────────────────────────

@Composable
private fun AddFuelDialog(defaultOdo: Int, onAdd: (Double, Double, Int, String) -> Unit, onDismiss: () -> Unit) {
    var litres by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf(defaultOdo.toString()) }
    var loc by remember { mutableStateOf("") }
    val valid = litres.toDoubleOrNull() != null && cost.toDoubleOrNull() != null && odo.toIntOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = valid, onClick = { onAdd(litres.toDouble(), cost.toDouble(), odo.toInt(), loc.trim()) }) { Text("Add", color = if (valid) Gold else TextLo) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Add fill-up", color = TextHi) },
        text = {
            Column {
                NumField(litres, { litres = it }, "Litres", true)
                NumField(cost, { cost = it }, "Cost (₹)", true)
                NumField(odo, { odo = it }, "Odometer (km)", false)
                OutlinedTextField(loc, { loc = it }, label = { Text("Location (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = Surf1,
    )
}

@Composable
private fun AddServiceDialog(onAdd: (String, String, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("") }
    val icons = listOf("wrench", "chain", "drop", "gauge", "thermo", "fuel")
    var icon by remember { mutableStateOf("wrench") }
    val valid = name.isNotBlank() && interval.toIntOrNull() != null && interval.toInt() > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(name.trim(), icon, interval.toInt()) }) { Text("Add", color = if (valid) Gold else TextLo) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Add interval", color = TextHi) },
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
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (key == icon) GoldTint else Surf2)
                                .border(1.dp, if (key == icon) Gold else Line, RoundedCornerShape(10.dp))
                                .clickable { icon = key },
                        ) { Icon(iconFor(key), null, tint = if (key == icon) Gold else TextMid, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        },
        containerColor = Surf1,
    )
}

@Composable
private fun LogServiceDialog(rows: List<MaintRow>, odo: Int, onMark: (MaintenanceItem) -> Unit, onDelete: (MaintenanceItem) -> Unit, onAddNew: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onAddNew) { Text("Add interval", color = Gold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextMid) } },
        title = { Text("Mark a service done", color = TextHi) },
        text = {
            Column {
                Text("Marks the item done at ${"%,d".format(odo)} km.", color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                rows.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Icon(iconFor(row.item.iconKey), null, tint = TextMid, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(row.item.name, color = TextHi, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onMark(row.item) }) { Text("Done", color = Gold, fontSize = 13.sp) }
                        Icon(NorthstarIcons.Cross, "delete", tint = TextLo, modifier = Modifier.size(16.dp).clickable { onDelete(row.item) })
                    }
                }
            }
        },
        containerColor = Surf1,
    )
}

@Composable
private fun OdometerDialog(current: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    var odo by remember { mutableStateOf(current.toString()) }
    val valid = odo.toIntOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = valid, onClick = { onSet(odo.toInt()) }) { Text("Save", color = if (valid) Gold else TextLo) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Set odometer", color = TextHi) },
        text = { NumField(odo, { odo = it }, "Odometer (km)", false) },
        containerColor = Surf1,
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
