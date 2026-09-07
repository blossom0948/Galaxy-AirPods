package com.galaxyairpods.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NightColors = darkColorScheme(
    primary = Color(0xFF8FE2D2),
    onPrimary = Color(0xFF003731),
    secondary = Color(0xFFB8C7FF),
    tertiary = Color(0xFFE8B9FF),
    background = Color(0xFF0A0D12),
    onBackground = Color(0xFFE7E8EE),
    surface = Color(0xFF12171F),
    onSurface = Color(0xFFE7E8EE),
    surfaceVariant = Color(0xFF202731),
    onSurfaceVariant = Color(0xFFB8C0CC),
)

private val DayColors = lightColorScheme(
    primary = Color(0xFF006B60),
    secondary = Color(0xFF455D95),
    tertiary = Color(0xFF7E498F),
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6E9F1),
)

@Composable
fun AirPodsGalaxyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightColors else DayColors,
        typography = Typography(),
        content = content,
    )
}
