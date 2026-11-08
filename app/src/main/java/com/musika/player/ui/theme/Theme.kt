package com.musika.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Samsung Music Deep AMOLED + Winamp Classic Telemetry Colors
val SamsungBlue = Color(0xFF387AFF)
val SamsungPurple = Color(0xFF7C4DFF)
val WinampLedGreen = Color(0xFF00E676)
val WinampAmberGold = Color(0xFFFFB300)
val WinampDarkBezel = Color(0xFF0B0E14)
val DeepCharcoal = Color(0xFF12141C)
val CardSurface = Color(0xFF1C202B)
val ElevatedSurface = Color(0xFF252A38)
val SubtleText = Color(0xFF9EA6B8)

private val MusikaDarkColorScheme = darkColorScheme(
    primary = SamsungBlue,
    onPrimary = Color.White,
    secondary = WinampLedGreen,
    onSecondary = Color.Black,
    tertiary = WinampAmberGold,
    background = DeepCharcoal,
    onBackground = Color(0xFFF2F4F8),
    surface = CardSurface,
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = ElevatedSurface,
    onSurfaceVariant = SubtleText
)

@Composable
fun MusikaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MusikaDarkColorScheme,
        content = content
    )
}
