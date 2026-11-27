package com.savevault.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Violet = Color(0xFF8B6CFF)
private val Cyan = Color(0xFF22D3EE)

private val Dark = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF14102A),
    primaryContainer = Color(0xFF2E2557),
    onPrimaryContainer = Color(0xFFE4DCFF),
    secondary = Cyan,
    onSecondary = Color(0xFF00313A),
    secondaryContainer = Color(0xFF0E3A44),
    onSecondaryContainer = Color(0xFFBDF3FF),
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFE6E7EE),
    surface = Color(0xFF0F1117),
    onSurface = Color(0xFFE6E7EE),
    surfaceVariant = Color(0xFF1F2330),
    onSurfaceVariant = Color(0xFFA9ADBF),
    surfaceContainer = Color(0xFF171A23),
    surfaceContainerHigh = Color(0xFF1D212C),
    surfaceContainerHighest = Color(0xFF242936),
    outline = Color(0xFF3A3F50),
    outlineVariant = Color(0xFF2A2E3B),
    error = Color(0xFFFF6B7A),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B3FE0),
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1B0D5C),
    secondary = Color(0xFF00788C),
    secondaryContainer = Color(0xFFCDF4FB),
    background = Color(0xFFF7F7FB),
    surface = Color(0xFFF7F7FB),
    surfaceContainer = Color(0xFFEEEEF5),
)

@Composable
fun SaveVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
