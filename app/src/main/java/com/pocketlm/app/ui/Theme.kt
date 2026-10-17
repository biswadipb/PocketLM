package com.pocketlm.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val label: String) {
    System("Follow system"),
    Light("Light"),
    Dark("Dark"),
}

/** The light and dark colours; internal so a test can check that text on them is readable. */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF6D28D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE4FF),
    onPrimaryContainer = Color(0xFF2E0A6B),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color.White,
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1C1A22),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1C1A22),
    surfaceVariant = Color(0xFFEFE9F8),
    onSurfaceVariant = Color(0xFF494453),
    outline = Color(0xFF7B7487),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFC4A8FF),
    onPrimary = Color(0xFF2E0A6B),
    primaryContainer = Color(0xFF4A1FA8),
    onPrimaryContainer = Color(0xFFEDE4FF),
    secondary = Color(0xFFB794F6),
    onSecondary = Color(0xFF2E0A6B),
    background = Color(0xFF141018),
    onBackground = Color(0xFFE8E2EE),
    surface = Color(0xFF141018),
    onSurface = Color(0xFFE8E2EE),
    surfaceVariant = Color(0xFF2A2433),
    onSurfaceVariant = Color(0xFFCBC3D6),
    outline = Color(0xFF948DA0),
)

@Composable
fun PocketLmTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
