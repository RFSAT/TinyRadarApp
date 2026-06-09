package com.rfsat.tinyrad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary          = RadarBlue,
    secondary        = RadarAccent,
    tertiary         = ColorHuman,
    background       = RadarDark,
    surface          = RadarDarkMid,
    surfaceVariant   = RadarSurface,
    onBackground     = RadarOnSurface,
    onSurface        = RadarOnSurface,
    onPrimary        = RadarDark,
    error            = RadarError,
)

@Composable
fun TinyRadAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content     = content
    )
}
