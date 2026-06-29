package com.example.opendash.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OpenDashThemeVariant(
    val name: String,
    val theme: String,
    val colors: List<Color>,
) {
    val background: Color get() = colors[0]
    val text: Color get() = when (name) {
        "Kamet White" -> colors[4]
        "Slate Himalayan Salt", "Slate Poppy Blue" -> colors[3]
        "Kaza Brown" -> colors[4]
        else -> colors[5]
    }
    val textMid: Color get() = when (name) {
        "Kamet White" -> colors[3]
        "Slate Himalayan Salt", "Slate Poppy Blue" -> colors[2]
        "Kaza Brown" -> colors[3]
        else -> lerp(background, text, 0.68f)
    }
    val surfaceLow: Color get() = lerp(background, text, 0.055f)
    val surface: Color get() = lerp(background, text, 0.095f)
    val surfaceHigh: Color get() = lerp(background, text, 0.15f)
    val accent: Color get() = when (name) {
        "Slate Himalayan Salt", "Slate Poppy Blue" -> colors[4]
        "Kamet White" -> colors[3]
        else -> colors[3]
    }
    val accentStrong: Color get() = when (name) {
        "Slate Himalayan Salt", "Slate Poppy Blue" -> colors[4]
        "Kamet White" -> colors[4]
        else -> colors[4]
    }
}

val OpenDashThemeVariants = listOf(
    OpenDashThemeVariant(
        name = "Dynamic Wallpaper",
        theme = "Android wallpaper colours",
        colors = listOf(0xFF101014, 0xFF2A2630, 0xFF625B71, 0xFF6750A4, 0xFFD0BCFF, 0xFFE6E1E5).map(::Color),
    ),
    OpenDashThemeVariant(
        name = "Auto Day/Night",
        theme = "System black and white",
        colors = listOf(0xFF000000, 0xFF202124, 0xFF5F6368, 0xFF9AA0A6, 0xFFE8EAED, 0xFFFFFFFF).map(::Color),
    ),
    OpenDashThemeVariant(
        name = "Mana Black",
        theme = "Monochrome camo / stealth grey",
        colors = listOf(0xFF0B0C0E, 0xFF1F2225, 0xFF3F4448, 0xFF7D8588, 0xFFB4B9BA, 0xFFC9C7C3).map(::Color),
    ),
    OpenDashThemeVariant(
        name = "Hanle Black",
        theme = "Black + bronze adventure",
        colors = listOf(0xFF070707, 0xFF242426, 0xFF8B6439, 0xFFB78B4B, 0xFFC6A46A, 0xFFC8C4BD).map(::Color),
    ),
    OpenDashThemeVariant(
        name = "Kamet White",
        theme = "Ice white / grey camo",
        colors = listOf(0xFFE6E6DD, 0xFFC9CBC5, 0xFF8D918D, 0xFF34383A, 0xFF0A0B0C, 0xFFBFC0BC).map(::Color),
    ),
    OpenDashThemeVariant(
        name = "Slate Himalayan Salt",
        theme = "Grey camo + red accent",
        colors = listOf(0xFF08090A, 0xFF222629, 0xFF5E6364, 0xFFAEB3AF, 0xFFE05257, 0xFF9A3B43).map(::Color),
    ),
    OpenDashThemeVariant(
        name = "Slate Poppy Blue",
        theme = "Grey slate + blue accent",
        colors = listOf(0xFF08090A, 0xFF202428, 0xFF4E565A, 0xFF7B8285, 0xFF3F78B7, 0xFF183E68).map(::Color),
    ),
    OpenDashThemeVariant(
        name = "Kaza Brown",
        theme = "Desert cream / neutral adventure",
        colors = listOf(0xFF090A0A, 0xFF25282A, 0xFF686B66, 0xFFC8C4B0, 0xFFE6E2D0, 0xFFC7C4BA).map(::Color),
    ),
)

object OpenDashThemeController {
    private const val PREFS = "appearance"
    private const val KEY_VARIANT = "theme_variant"
    private const val KEY_OLED_DARK = "oled_dark"
    val defaultVariant = OpenDashThemeVariants.first { it.name == "Hanle Black" }

    private val _variant = MutableStateFlow(defaultVariant)
    val variant = _variant.asStateFlow()
    private val _oledDark = MutableStateFlow(false)
    val oledDark = _oledDark.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_VARIANT, defaultVariant.name)
        _variant.value = OpenDashThemeVariants.firstOrNull { it.name == saved } ?: defaultVariant
        _oledDark.value = prefs.getBoolean(KEY_OLED_DARK, false)
    }

    fun select(context: Context, variant: OpenDashThemeVariant) {
        _variant.value = variant
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VARIANT, variant.name)
            .apply()
    }

    fun setOledDark(context: Context, enabled: Boolean) {
        _oledDark.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OLED_DARK, enabled)
            .apply()
    }
}

private fun autoDayNightColorScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF303134),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFBDC1C6),
        onSecondary = Color.Black,
        background = Color.Black,
        onBackground = Color.White,
        surface = Color(0xFF151515),
        onSurface = Color.White,
        surfaceContainerLow = Color(0xFF101010),
        surfaceContainer = Color(0xFF1D1D1F),
        surfaceContainerHigh = Color(0xFF262628),
        surfaceContainerHighest = Color(0xFF303134),
        onSurfaceVariant = Color(0xFFBDC1C6),
        outline = Color.White.copy(alpha = 0.28f),
        outlineVariant = Color.White.copy(alpha = 0.14f),
        error = Alert,
        errorContainer = Alert.copy(alpha = 0.16f),
        onErrorContainer = Alert,
    )
} else {
    lightColorScheme(
        primary = Color.Black,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE8EAED),
        onPrimaryContainer = Color.Black,
        secondary = Color(0xFF3C4043),
        onSecondary = Color.White,
        background = Color.White,
        onBackground = Color.Black,
        surface = Color(0xFFF8F9FA),
        onSurface = Color.Black,
        surfaceContainerLow = Color(0xFFF8F9FA),
        surfaceContainer = Color(0xFFF1F3F4),
        surfaceContainerHigh = Color(0xFFE8EAED),
        surfaceContainerHighest = Color(0xFFDADCE0),
        onSurfaceVariant = Color(0xFF3C4043),
        outline = Color.Black.copy(alpha = 0.26f),
        outlineVariant = Color.Black.copy(alpha = 0.13f),
        error = Alert,
        errorContainer = Alert.copy(alpha = 0.16f),
        onErrorContainer = Alert,
    )
}

private fun openDashColorScheme(variant: OpenDashThemeVariant): ColorScheme = darkColorScheme(
    primary            = variant.accentStrong,
    onPrimary          = variant.background,
    primaryContainer   = variant.accent.copy(alpha = 0.28f),
    onPrimaryContainer = variant.accentStrong,
    secondary          = variant.textMid,
    onSecondary        = variant.background,
    tertiary           = Warn,
    onTertiary         = Bg1,
    tertiaryContainer  = Warn.copy(alpha = 0.18f),
    onTertiaryContainer = Warn,
    background         = variant.background,
    onBackground       = variant.text,
    surface            = variant.surface,
    onSurface          = variant.text,
    surfaceDim         = variant.background,
    surfaceBright      = variant.surfaceHigh,
    surfaceContainerLowest = variant.background,
    surfaceContainerLow = variant.surfaceLow,
    surfaceContainer   = variant.surface,
    surfaceContainerHigh = variant.surfaceHigh,
    surfaceContainerHighest = variant.surfaceHigh,
    surfaceVariant     = variant.surfaceHigh,
    onSurfaceVariant   = variant.textMid,
    outline            = variant.textMid.copy(alpha = 0.28f),
    outlineVariant     = variant.textMid.copy(alpha = 0.14f),
    error              = Alert,
    onError            = variant.text,
    errorContainer     = Alert.copy(alpha = 0.16f),
    onErrorContainer   = Alert,
    scrim              = Color.Black,
)

private fun ColorScheme.withOledDark(enabled: Boolean): ColorScheme {
    if (!enabled) return this
    val black = Color.Black
    val lifted = Color(0xFF050505)
    val raised = Color(0xFF0A0A0A)
    return copy(
        background = black,
        surface = black,
        surfaceDim = black,
        surfaceContainerLowest = black,
        surfaceContainerLow = lifted,
        surfaceContainer = raised,
        surfaceVariant = raised,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenDashTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember(context) {
        OpenDashThemeController.init(context)
        true
    }
    val variant by OpenDashThemeController.variant.collectAsState()
    val oledDark by OpenDashThemeController.oledDark.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val colorScheme = when (variant.name) {
        "Dynamic Wallpaper" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            autoDayNightColorScheme(systemDark)
        }
        "Auto Day/Night" -> autoDayNightColorScheme(systemDark)
        else -> openDashColorScheme(variant)
    }.withOledDark(oledDark)
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
