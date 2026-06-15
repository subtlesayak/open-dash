package com.example.northstar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NorthstarColorScheme = darkColorScheme(
    primary            = Gold,
    onPrimary          = OnGold,
    primaryContainer   = GoldTint,
    onPrimaryContainer = Gold,
    secondary          = TextMid,
    onSecondary        = Bg1,
    tertiary           = Warn,
    onTertiary         = Bg1,
    tertiaryContainer  = Warn.copy(alpha = 0.18f),
    onTertiaryContainer = Warn,
    background         = Bg1,
    onBackground       = TextHi,
    surface            = Surf1,
    onSurface          = TextHi,
    surfaceDim         = Bg0,
    surfaceBright      = Surf3,
    surfaceContainerLowest = Bg0,
    surfaceContainerLow = Bg1,
    surfaceContainer   = Surf1,
    surfaceContainerHigh = Surf2,
    surfaceContainerHighest = Surf3,
    surfaceVariant     = Surf2,
    onSurfaceVariant   = TextMid,
    outline            = Line2,
    outlineVariant     = Line,
    error              = Alert,
    onError            = TextHi,
    errorContainer     = Alert.copy(alpha = 0.16f),
    onErrorContainer   = Alert,
    scrim              = Bg0,
)

@Composable
fun NorthstarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NorthstarColorScheme,
        typography = Typography,
        content = content,
    )
}
