package com.galaxyairpods.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DayColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    secondary = Color(0xFF455D95),
    tertiary = Color(0xFF34C759),
    background = Color(0xFFEFF1F4),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFF8F9FA),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFE3E6EB),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFD1D1D6),
)

@Composable
fun AirPodsGalaxyTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DayColors,
        typography = Typography(),
        content = content,
    )
}
