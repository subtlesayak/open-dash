package com.example.opendash.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OpenDashThemeVariant(
    val name: String,
    val theme: String,
    val colors: List<Color>,
) {
    val background: Color get() = colors[0]
    val surfaceLow: Color get() = colors[1]
    val surface: Color get() = colors[1]
    val surfaceHigh: Color get() = colors[2]
    val text: Color get() = colors[5]
    val textMid: Color get() = colors[4]
    val accent: Color get() = colors[3]
    val accentStrong: Color get() = colors[4]
}

val OpenDashThemeVariants = listOf(
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
    val defaultVariant = OpenDashThemeVariants.first { it.name == "Hanle Black" }

    private val _variant = MutableStateFlow(defaultVariant)
    val variant = _variant.asStateFlow()

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VARIANT, defaultVariant.name)
        _variant.value = OpenDashThemeVariants.firstOrNull { it.name == saved } ?: defaultVariant
    }

    fun select(context: Context, variant: OpenDashThemeVariant) {
        _variant.value = variant
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VARIANT, variant.name)
            .apply()
    }
}

private fun openDashColorScheme(variant: OpenDashThemeVariant): ColorScheme = darkColorScheme(
    primary            = variant.accentStrong,
    onPrimary          = variant.background,
    primaryContainer   = variant.accent.copy(alpha = 0.28f),
    onPrimaryContainer = variant.accentStrong,
    secondary          = TextMid,
    onSecondary        = Bg1,
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
    scrim              = variant.background,
)

@Composable
fun OpenDashTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember(context) {
        OpenDashThemeController.init(context)
        true
    }
    val variant by OpenDashThemeController.variant.collectAsState()
    MaterialTheme(
        colorScheme = openDashColorScheme(variant),
        typography = Typography,
        content = content,
    )
}
