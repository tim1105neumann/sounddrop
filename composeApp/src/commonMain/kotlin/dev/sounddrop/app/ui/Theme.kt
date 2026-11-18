package dev.sounddrop.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Violet = Color(0xFF7F52FF)
private val Orange = Color(0xFFFF8A3D)
private val Magenta = Color(0xFFE44FA8)

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFB9A3FF),
        onPrimary = Color(0xFF24005A),
        primaryContainer = Color(0xFF4A2BA8),
        onPrimaryContainer = Color(0xFFE9DDFF),
        secondary = Orange,
        onSecondary = Color(0xFF3B1700),
        tertiary = Magenta,
        background = Color(0xFF0F0D17),
        onBackground = Color(0xFFE7E1F0),
        surface = Color(0xFF0F0D17),
        onSurface = Color(0xFFE7E1F0),
        surfaceVariant = Color(0xFF221E30),
        onSurfaceVariant = Color(0xFFC9C2D8),
        surfaceContainer = Color(0xFF1A1725),
        surfaceContainerHigh = Color(0xFF242032),
    )

private val LightColors =
    lightColorScheme(
        primary = Violet,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9DDFF),
        onPrimaryContainer = Color(0xFF24005A),
        secondary = Color(0xFFC75A12),
        tertiary = Color(0xFFB0247A),
        background = Color(0xFFFCF8FF),
        surface = Color(0xFFFCF8FF),
        surfaceVariant = Color(0xFFE8E0F3),
        surfaceContainer = Color(0xFFF3EDFA),
        surfaceContainerHigh = Color(0xFFEDE6F6),
    )

/** SoundDrop's colours: a violet-to-orange palette that matches the spectrogram colour map. */
@Composable
fun SoundDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
