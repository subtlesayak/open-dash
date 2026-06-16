package com.example.opendash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
import com.example.opendash.ui.theme.Line
import com.example.opendash.ui.theme.Surf2
import com.example.opendash.ui.theme.TextHi
import com.example.opendash.ui.theme.TextLo
import com.example.opendash.ui.theme.TextMid

@Composable
fun VehiclesScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            title = "Profile",
            trailing = { OpenDashIconBtn(OpenDashIcons.Edit, onClick = {}) },
        )

        SectionTitle("My Vehicles")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
            VehicleBlock(
                title = "Royal Enfield Himalayan 450",
                nickname = "Four50",
                puc = "Expired",
                pucAlert = true,
                insurance = "19-Feb-2030",
                service = "20000 km or 12 Months",
            )
            OpenDashDivider(Modifier.padding(vertical = 14.dp))
            VehicleBlock(
                title = "Honda Activa",
                nickname = "",
                puc = "NA",
                pucAlert = true,
                insurance = "NA",
                service = "Not set",
            )
        }

        SectionTitle("Personal Details")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 18.dp) {
            DetailRow(OpenDashIcons.Motor, "Name", "Sayak Sajith")
            DetailRow(OpenDashIcons.Wifi, "Mobile", "8547606755")
            DetailRow(OpenDashIcons.Target, "Gender", "Male")
            DetailRow(OpenDashIcons.Cal, "Date of Birth", "11-Apr-1999")
            DetailRow(OpenDashIcons.Drop, "Blood Group", "O+")
            DetailRow(OpenDashIcons.Home, "Home Location", "Bengaluru, Karnataka")
            DetailRow(OpenDashIcons.Motor, "Display Name", "Sayakopath", last = true)
        }
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
private fun VehicleBlock(
    title: String,
    nickname: String,
    puc: String,
    pucAlert: Boolean,
    insurance: String,
    service: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(OpenDashIcons.Motor, contentDescription = null, tint = TextMid, modifier = Modifier.size(30.dp).padding(top = 5.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Gold, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (nickname.isNotBlank()) Text(nickname, color = TextMid, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(14.dp))
            VehicleMeta("PUC", puc, alert = pucAlert)
            VehicleMeta("Insurance", insurance)
            VehicleMeta("Service", service)
        }
        OpenDashIconBtn(OpenDashIcons.Edit, onClick = {}, size = 34.dp)
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
private fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, last: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(8.dp)),
        ) {
            Icon(icon, contentDescription = null, tint = TextMid, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = TextLo, fontSize = 12.5.sp)
            Text(value, color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = GeistFamily)
        }
    }
    if (!last) OpenDashDivider(Modifier.padding(start = 60.dp))
}
