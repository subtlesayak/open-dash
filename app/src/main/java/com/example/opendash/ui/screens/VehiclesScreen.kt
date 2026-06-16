package com.example.opendash.ui.screens

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.OpenDashCard
import com.example.opendash.ui.components.OpenDashDivider
import com.example.opendash.ui.components.OpenDashIconBtn
import com.example.opendash.ui.components.ScreenHeader
import com.example.opendash.ui.theme.Alert
import com.example.opendash.ui.theme.GeistFamily
import com.example.opendash.ui.theme.Gold
import com.example.opendash.ui.theme.TextHi
import com.example.opendash.ui.theme.TextLo
import com.example.opendash.ui.theme.TextMid

private data class VehicleProfile(
    val title: String,
    val nickname: String,
    val puc: String,
    val insurance: String,
    val service: String,
)

@Composable
fun VehiclesScreen() {
    var vehicles by remember {
        mutableStateOf(
            listOf(
                VehicleProfile(
                    title = "Royal Enfield Himalayan 450",
                    nickname = "Primary bike",
                    puc = "Not set",
                    insurance = "Not set",
                    service = "Not set",
                ),
                VehicleProfile(
                    title = "Honda Activa",
                    nickname = "Secondary vehicle",
                    puc = "Not set",
                    insurance = "Not set",
                    service = "Not set",
                ),
            ),
        )
    }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

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
                    onEdit = { editingIndex = index },
                )
            }
        }
    }

    editingIndex?.let { index ->
        EditVehicleDialog(
            vehicle = vehicles[index],
            onDismiss = { editingIndex = null },
            onSave = { updated ->
                vehicles = vehicles.toMutableList().also { it[index] = updated }
                editingIndex = null
            },
        )
    }
}

@Composable
private fun SectionTitle(label: String) {
    Text(
        label,
        color = TextHi,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = GeistFamily,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp, start = 2.dp),
    )
}

@Composable
private fun VehicleBlock(vehicle: VehicleProfile, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(OpenDashIcons.Motor, contentDescription = null, tint = TextMid, modifier = Modifier.size(30.dp).padding(top = 5.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(vehicle.title, color = Gold, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (vehicle.nickname.isNotBlank()) Text(vehicle.nickname, color = TextMid, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(14.dp))
            VehicleMeta("PUC", vehicle.puc, alert = vehicle.puc.isProblemValue())
            VehicleMeta("Insurance", vehicle.insurance, alert = vehicle.insurance.isProblemValue())
            VehicleMeta("Service", vehicle.service)
        }
        OpenDashIconBtn(OpenDashIcons.Edit, onClick = onEdit, size = 34.dp)
    }
}

@Composable
private fun VehicleMeta(label: String, value: String, alert: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = TextLo, fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Text(":", color = TextLo, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(value, color = if (alert) Alert else TextMid, fontSize = 13.sp)
    }
}

@Composable
private fun EditVehicleDialog(
    vehicle: VehicleProfile,
    onSave: (VehicleProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(vehicle) { mutableStateOf(vehicle.title) }
    var nickname by remember(vehicle) { mutableStateOf(vehicle.nickname) }
    var puc by remember(vehicle) { mutableStateOf(vehicle.puc) }
    var insurance by remember(vehicle) { mutableStateOf(vehicle.insurance) }
    var service by remember(vehicle) { mutableStateOf(vehicle.service) }
    val valid = title.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit vehicle", color = TextHi) },
        text = {
            Column {
                VehicleTextField(title, { title = it }, "Vehicle name")
                VehicleTextField(nickname, { nickname = it }, "Nickname")
                VehicleTextField(puc, { puc = it }, "PUC")
                VehicleTextField(insurance, { insurance = it }, "Insurance")
                VehicleTextField(service, { service = it }, "Service")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        VehicleProfile(
                            title = title.trim(),
                            nickname = nickname.trim(),
                            puc = puc.trim().ifBlank { "Not set" },
                            insurance = insurance.trim().ifBlank { "Not set" },
                            service = service.trim().ifBlank { "Not set" },
                        ),
                    )
                },
            ) {
                Text("Save", color = if (valid) Gold else TextLo)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) }
        },
    )
}

@Composable
private fun VehicleTextField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

private fun String.isProblemValue(): Boolean =
    equals("expired", ignoreCase = true) || equals("na", ignoreCase = true)
