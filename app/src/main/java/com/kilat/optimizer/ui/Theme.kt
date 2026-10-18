package com.kilat.optimizer.ui

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Palet Kilat. Abu-abu netral, satu warna aksen kuning, tanpa gradasi dan bayangan.
 *  - accent : kuning. Dipakai sebagai isian (tombol, saklar aktif), selalu dengan teks [accentInk].
 *  - link   : warna aksen untuk teks kecil di atas latar. Gelap: kuning. Terang: cokelat tua
 *             (kuning di atas latar terang tidak terbaca).
 *  - bad/warn : merah untuk masalah, jingga untuk peringatan.
 */
data class KilatColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val fg: Color,
    val muted: Color,
    val accent: Color,
    val accentInk: Color,
    val link: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val track: Color,
)

private val DarkColors = KilatColors(
    bg = Color(0xFF151618), surface = Color(0xFF1D1E21), surface2 = Color(0xFF26282C),
    line = Color(0xFF2E3035), fg = Color(0xFFECEBE7), muted = Color(0xFF9B9DA2),
    accent = Color(0xFFF2BE0C), accentInk = Color(0xFF181400), link = Color(0xFFF2BE0C),
    good = Color(0xFF6FBF86), warn = Color(0xFFF09A3A), bad = Color(0xFFEE5A4D),
    track = Color(0xFF34363B),
)

private val LightColors = KilatColors(
    bg = Color(0xFFF4F3EF), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEAE9E4),
    line = Color(0xFFD6D4CD), fg = Color(0xFF1A1A1C), muted = Color(0xFF65676B),
    accent = Color(0xFFF2BE0C), accentInk = Color(0xFF181400), link = Color(0xFF7A5800),
    good = Color(0xFF2E7D4A), warn = Color(0xFFA85A06), bad = Color(0xFFC0392B),
    track = Color(0xFFD9D7D0),
)

val LocalK = staticCompositionLocalOf { DarkColors }

/**
 * Huruf. Semuanya huruf sistem Android (Roboto), jadi tidak perlu berkas font tambahan.
 * Judul dan angka memakai bobot medium dari keluarga yang sama.
 */
object KFont {
    val display: FontFamily = FontFamily(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    val body: FontFamily = FontFamily.Default
}

private fun kTypography(): Typography {
    val d = Typography()
    val f = KFont.body
    return Typography(
        displayLarge = d.displayLarge.copy(fontFamily = f),
        displayMedium = d.displayMedium.copy(fontFamily = f),
        displaySmall = d.displaySmall.copy(fontFamily = f),
        headlineLarge = d.headlineLarge.copy(fontFamily = f),
        headlineMedium = d.headlineMedium.copy(fontFamily = f),
        headlineSmall = d.headlineSmall.copy(fontFamily = f),
        titleLarge = d.titleLarge.copy(fontFamily = f),
        titleMedium = d.titleMedium.copy(fontFamily = f),
        titleSmall = d.titleSmall.copy(fontFamily = f),
        bodyLarge = d.bodyLarge.copy(fontFamily = f),
        bodyMedium = d.bodyMedium.copy(fontFamily = f),
        bodySmall = d.bodySmall.copy(fontFamily = f),
        labelLarge = d.labelLarge.copy(fontFamily = f),
        labelMedium = d.labelMedium.copy(fontFamily = f),
        labelSmall = d.labelSmall.copy(fontFamily = f),
    )
}

@Composable
fun KilatTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.accentInk, background = c.bg, surface = c.surface,
            onSurface = c.fg, onBackground = c.fg,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.accentInk, background = c.bg, surface = c.surface,
            onSurface = c.fg, onBackground = c.fg,
        )
    }
    CompositionLocalProvider(LocalK provides c) {
        MaterialTheme(colorScheme = scheme, typography = kTypography()) {
            ProvideTextStyle(TextStyle(fontFamily = KFont.body)) { content() }
        }
    }
}
