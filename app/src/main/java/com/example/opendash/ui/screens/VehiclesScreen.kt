package com.example.opendash.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.BtnSize
import com.example.opendash.ui.components.BtnVariant
import com.example.opendash.ui.components.OpenDashBtn
import com.example.opendash.ui.components.OpenDashCard
import com.example.opendash.ui.components.OpenDashDivider
import com.example.opendash.ui.components.OpenDashIconBtn
import com.example.opendash.ui.components.OpenDashChip
import com.example.opendash.ui.components.ChipTone
import com.example.opendash.ui.components.ScreenHeader
import com.example.opendash.data.VehicleProfile
import com.example.opendash.data.VehicleStore
import com.example.opendash.ui.theme.Alert
import com.example.opendash.ui.theme.GeistFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VehiclesScreen() {
    val context = LocalContext.current
    val vehicles by VehicleStore.vehicles.collectAsState()
    val activeVehicleId by VehicleStore.activeVehicleId.collectAsState()
    var editingVehicleId by remember { mutableStateOf<String?>(null) }
    var addingVehicle by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(title = "Vehicles")

        SectionTitle("My Vehicles")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
            vehicles.forEachIndexed { index, vehicle ->
                if (index > 0) OpenDashDivider(Modifier.padding(vertical = 14.dp))
                VehicleBlock(
                    vehicle = vehicle,
                    active = vehicle.id == activeVehicleId,
                    onSelect = { VehicleStore.select(context, vehicle.id) },
                    onEdit = { editingVehicleId = vehicle.id },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        OpenDashBtn(
            "Add vehicle",
            onClick = { addingVehicle = true },
            icon = OpenDashIcons.Plus,
            variant = BtnVariant.Primary,
            size = BtnSize.Md,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    editingVehicleId?.let { vehicleId ->
        val vehicle = vehicles.firstOrNull { it.id == vehicleId } ?: return@let
        EditVehicleDialog(
            dialogTitle = "Edit vehicle",
            vehicle = vehicle,
            onDismiss = { editingVehicleId = null },
            onSave = { updated ->
                VehicleStore.update(context, updated)
                editingVehicleId = null
            },
        )
    }

    if (addingVehicle) {
        EditVehicleDialog(
            dialogTitle = "Add vehicle",
            vehicle = VehicleProfile(
                id = "",
                title = "",
                nickname = "",
                puc = "Not set",
                insurance = "Not set",
                service = "Not set",
            ),
            onDismiss = { addingVehicle = false },
            onSave = { updated ->
                VehicleStore.add(context, updated)
                addingVehicle = false
            },
        )
    }
}

@Composable
private fun SectionTitle(label: String) {
    Text(
        label,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = GeistFamily,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp, start = 2.dp),
    )
}

@Composable
private fun VehicleBlock(
    vehicle: VehicleProfile,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(OpenDashIcons.Motor, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp).padding(top = 5.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(vehicle.title, color = MaterialTheme.colorScheme.primary, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (vehicle.nickname.isNotBlank()) Text(vehicle.nickname, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(14.dp))
            VehicleMeta("PUC", vehicle.puc, alert = vehicle.puc.isProblemValue())
            VehicleMeta("Insurance", vehicle.insurance, alert = vehicle.insurance.isProblemValue())
            VehicleMeta("Service", vehicle.service)
            Spacer(Modifier.height(12.dp))
            if (active) {
                OpenDashChip("Current vehicle", ChipTone.Gold, icon = OpenDashIcons.Check)
            } else {
                OpenDashBtn(
                    "Set current",
                    onClick = onSelect,
                    icon = OpenDashIcons.Check,
                    variant = BtnVariant.Secondary,
                    size = BtnSize.Sm,
                )
            }
        }
        OpenDashIconBtn(OpenDashIcons.Edit, onClick = onEdit, size = 34.dp)
    }
}

@Composable
private fun VehicleMeta(label: String, value: String, alert: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Text(":", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(value, color = if (alert) Alert else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditVehicleDialog(
    dialogTitle: String,
    vehicle: VehicleProfile,
    onSave: (VehicleProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(vehicle) { mutableStateOf(vehicle.title) }
    var nickname by remember(vehicle) { mutableStateOf(vehicle.nickname) }
    var pucDate by remember(vehicle) { mutableStateOf(vehicle.puc.normalizedVehicleDate()) }
    var insuranceDate by remember(vehicle) { mutableStateOf(vehicle.insurance.normalizedVehicleDate()) }
    var editingDate by remember { mutableStateOf<VehicleDateTarget?>(null) }
    var service by remember(vehicle) { mutableStateOf(vehicle.service) }
    val valid = title.isNotBlank()


    editingDate?.let { target ->
        val current = when (target) {
            VehicleDateTarget.PUC -> pucDate
            VehicleDateTarget.INSURANCE -> insuranceDate
        }
        key(target, current) {
            val pickerState = rememberDatePickerState(initialSelectedDateMillis = current.toVehicleDateMillis())
            DatePickerDialog(
                onDismissRequest = { editingDate = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                when (target) {
                                    VehicleDateTarget.PUC -> pucDate = formatVehicleDateMillis(millis)
                                    VehicleDateTarget.INSURANCE -> insuranceDate = formatVehicleDateMillis(millis)
                                }
                            }
                            editingDate = null
                        },
                    ) { Text("Done", color = MaterialTheme.colorScheme.primary) }
                },
                dismissButton = {
                    TextButton(onClick = { editingDate = null }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            ) {
                DatePicker(state = pickerState)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(dialogTitle) },
        text = {
            Column {
                VehicleTextField(title, { title = it }, "Vehicle name")
                VehicleTextField(nickname, { nickname = it }, "Nickname")
                VehicleDateFields(
                    label = "PUC expiry",
                    value = pucDate,
                    onClick = { editingDate = VehicleDateTarget.PUC },
                )
                VehicleDateFields(
                    label = "Insurance expiry",
                    value = insuranceDate,
                    onClick = { editingDate = VehicleDateTarget.INSURANCE },
                )
                VehicleTextField(service, { service = it }, "Service")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        VehicleProfile(
                            id = vehicle.id,
                            title = title.trim(),
                            nickname = nickname.trim(),
                            puc = pucDate,
                            insurance = insuranceDate,
                            service = service.trim().ifBlank { "Not set" },
                        ),
                    )
                },
            ) {
                Text("Save", color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

@Composable
private fun VehicleDateFields(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.5.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
    OpenDashBtn(
        label = value,
        onClick = onClick,
        variant = BtnVariant.Secondary,
        size = BtnSize.Md,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun VehicleTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.padding(top = 8.dp),
    )
}

private fun String.isProblemValue(): Boolean =
    equals("expired", ignoreCase = true) || equals("na", ignoreCase = true)

private enum class VehicleDateTarget { PUC, INSURANCE }

private data class VehicleDateParts(val day: String, val month: String, val year: String)

private val vehicleMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val vehicleDateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).apply {
    isLenient = false
}

private fun String.normalizedVehicleDate(): String {
    val parts = toVehicleDateParts()
    return formatVehicleDate(parts.day, parts.month, parts.year)
}

private fun String.toVehicleDateMillis(): Long = runCatching {
    vehicleDateFormat.parse(this)?.time
}.getOrNull() ?: Date().time

private fun formatVehicleDateMillis(millis: Long): String = vehicleDateFormat.format(Date(millis))

private fun String.toVehicleDateParts(): VehicleDateParts {
    val match = Regex("""(\d{1,2})-([A-Za-z]{3})-(\d{4})""").find(this.trim())
    if (match != null) {
        val (day, month, year) = match.destructured
        return VehicleDateParts(day.padStart(2, '0'), month.replaceFirstChar { it.uppercase() }, year)
    }
    return VehicleDateParts("01", "Jan", "2030")
}

private fun formatVehicleDate(day: String, month: String, year: String): String {
    val cleanDay = day.toIntOrNull()?.coerceIn(1, 31)?.toString()?.padStart(2, '0') ?: "01"
    val cleanMonth = vehicleMonths.firstOrNull { it.equals(month.trim(), ignoreCase = true) }
        ?: vehicleMonths.first()
    val cleanYear = year.toIntOrNull()?.coerceIn(2024, 2099)?.toString() ?: "2030"
    return "$cleanDay-$cleanMonth-$cleanYear"
}
