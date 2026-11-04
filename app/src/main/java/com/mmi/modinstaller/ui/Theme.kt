package com.mmi.modinstaller.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Verde del pulsante INSTALLA IN MINECRAFT: uguale in tema chiaro e scuro. */
val InstallGreen = Color(0xFF2E7D32)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F0B2),
    onPrimaryContainer = Color(0xFF00210A),
    secondary = Color(0xFF52634F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF101F10),
    tertiaryContainer = Color(0xFFFFE9B5),
    onTertiaryContainer = Color(0xFF3B2A00),
    background = Color(0xFFF7FBF2),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFF7FBF2),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDEE5D8),
    onSurfaceVariant = Color(0xFF424940),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD797),
    onPrimary = Color(0xFF00390F),
    primaryContainer = Color(0xFF145A1E),
    onPrimaryContainer = Color(0xFFB8F0B2),
    secondary = Color(0xFFB9CCB4),
    onSecondary = Color(0xFF253423),
    secondaryContainer = Color(0xFF3B4B38),
    onSecondaryContainer = Color(0xFFD5E8CF),
    tertiaryContainer = Color(0xFF574500),
    onTertiaryContainer = Color(0xFFFFE9B5),
    background = Color(0xFF11140F),
    onBackground = Color(0xFFE1E4DA),
    surface = Color(0xFF11140F),
    onSurface = Color(0xFFE1E4DA),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun MmiTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

@Composable
fun toneColor(tone: Tone): Color {
    val dark = isSystemInDarkTheme()
    return when (tone) {
        Tone.OK -> if (dark) Color(0xFF7FD68A) else Color(0xFF2E7D32)
        Tone.WARN -> if (dark) Color(0xFFFFC857) else Color(0xFF9A5B00)
        Tone.BAD -> if (dark) Color(0xFFFF8A80) else Color(0xFFB3261E)
        Tone.INFO -> if (dark) Color(0xFF8AB4F8) else Color(0xFF1565C0)
    }
}
