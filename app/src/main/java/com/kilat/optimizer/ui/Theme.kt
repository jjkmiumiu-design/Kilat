package com.kilat.optimizer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class KilatColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val fg: Color,
    val muted: Color,
    val accent: Color,
    val accentInk: Color,
    val good: Color,
    val warn: Color,
    val track: Color,
)

private val DarkColors = KilatColors(
    bg = Color(0xFF0B1117), surface = Color(0xFF131C25), surface2 = Color(0xFF1A2631),
    line = Color(0xFF25333F), fg = Color(0xFFE8EEF3), muted = Color(0xFF8A9AA8),
    accent = Color(0xFFFF7A45), accentInk = Color(0xFF1A0C05), good = Color(0xFF4CC38A),
    warn = Color(0xFFF2B84B), track = Color(0xFF22303B),
)

private val LightColors = KilatColors(
    bg = Color(0xFFF3F6F8), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEAF0F4),
    line = Color(0xFFD5DFE6), fg = Color(0xFF10202C), muted = Color(0xFF5B6C7A),
    accent = Color(0xFFE5531A), accentInk = Color(0xFFFFFFFF), good = Color(0xFF1F9D63),
    warn = Color(0xFFB7791F), track = Color(0xFFDDE6EC),
)

val LocalK = staticCompositionLocalOf { DarkColors }

@Composable
fun KilatTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(primary = c.accent, background = c.bg, surface = c.surface, onSurface = c.fg, onBackground = c.fg)
    } else {
        lightColorScheme(primary = c.accent, background = c.bg, surface = c.surface, onSurface = c.fg, onBackground = c.fg)
    }
    CompositionLocalProvider(LocalK provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
