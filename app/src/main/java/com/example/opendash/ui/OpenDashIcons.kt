package com.example.opendash.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object OpenDashIcons {
    // ---- Material icon aliases ----
    val Home          = Icons.Outlined.Home
    val Route         = Icons.Outlined.Route
    val Navi          = Icons.Outlined.Navigation
    val Wrench        = Icons.Outlined.Build
    val Fuel          = Icons.Outlined.LocalGasStation
    val History       = Icons.Outlined.History
    val Gear          = Icons.Outlined.Settings
    val Bt            = Icons.Outlined.Bluetooth
    val Wifi          = Icons.Outlined.Wifi
    val ChevronRight  = Icons.Outlined.ChevronRight
    val ChevronLeft   = Icons.Outlined.ChevronLeft
    val ChevronDown   = Icons.Outlined.ExpandMore
    val Plus          = Icons.Outlined.Add
    val Minus         = Icons.Outlined.Remove
    val Recenter      = Icons.Outlined.MyLocation
    val Cross         = Icons.Outlined.GpsFixed
    val Mic           = Icons.Outlined.Mic
    val Bell          = Icons.Outlined.Notifications
    val Check         = Icons.Outlined.Check
    val X             = Icons.Outlined.Close
    val ArrowUp       = Icons.Outlined.Navigation
    val Gauge         = Icons.Outlined.Speed
    val Cal           = Icons.Outlined.CalendarToday
    val Pin           = Icons.Outlined.Route
    val Share         = Icons.Outlined.Share
    val Power         = Icons.Outlined.PowerSettingsNew
    val Lock          = Icons.Outlined.Lock
    val Mail          = Icons.Outlined.Email
    val Chart         = Icons.Outlined.BarChart
    val Speaker       = Icons.Outlined.VolumeUp
    val SpeakerOff    = Icons.Outlined.VolumeOff
    val Drop          = Icons.Outlined.WaterDrop
    val Trend         = Icons.Outlined.TrendingUp
    val Moon          = Icons.Outlined.DarkMode
    val Sync          = Icons.Outlined.Sync
    val Motor         = Icons.Outlined.TwoWheeler
    val Flag          = Icons.Outlined.Flag
    val Clock         = Icons.Outlined.Schedule
    val Road          = Icons.Outlined.Straighten
    val Target        = Icons.Outlined.GpsFixed
    val Thermo        = Icons.Outlined.Thermostat
    val Edit          = Icons.Outlined.Edit
    val Zap           = Icons.Outlined.Bolt
    val Units         = Icons.Outlined.Straighten

    // ---- Custom icons (SVG path → ImageVector) ----
    val Dash: ImageVector = pathVec(
        // Outer ring
        "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z",
        // Inner ring (inner dot of Tripper)
        "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z"
    )

    val Chain: ImageVector = pathVec(
        "M9 12a3 3 0 0 1 3-3h0a3 3 0 0 1 0 6",
        "M15 12a3 3 0 0 1-3 3h0a3 3 0 0 1 0-6"
    )

    val LocationPin: ImageVector = pathVec(
        "M12 21s7-6.3 7-11a7 7 0 1 0-14 0c0 4.7 7 11 7 11Z",
        "M12 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z"
    )
}

private fun pathVec(vararg paths: String, strokeWidth: Float = 1.7f): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { pathData ->
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
