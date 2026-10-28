package com.kilat.optimizer.ui

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Palet Kilat. Hitam, kartu membulat bergaris tepi tipis, satu aksen kuning.
 * Tombol dan tab terpilih sedikit 3D (gradasi + garis cahaya putih tipis).
 *  - accent : ungu. Dipakai sebagai isian (tombol, saklar aktif), selalu dengan teks [accentInk].
 *  - link   : ungu muda untuk judul dan teks tebal di atas latar gelap.
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
    /** Tombol dan tab "3D": gradasi gelap kekuningan dengan garis cahaya putih tipis di tepi atas. */
    val keyTop: Color,
    val keyBottom: Color,
    val sheen: Color,
)

private val DarkColors = KilatColors(
    bg = Color(0xFF0A0A08), surface = Color(0xFF121210), surface2 = Color(0xFF1C1B16),
    line = Color(0xFF2E2C22), fg = Color(0xFFF2F0E6), muted = Color(0xFF98958A),
    accent = Color(0xFFF5C518), accentInk = Color(0xFF1A1400), link = Color(0xFFFFD84D),
    good = Color(0xFF4CB36E), warn = Color(0xFFF0A23A), bad = Color(0xFFF0605A),
    track = Color(0xFF2E2C22),
    keyTop = Color(0xFF3A3320), keyBottom = Color(0xFF1A170E), sheen = Color(0x80FFFFFF),
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
    // Tampilan Kilat hanya satu: hitam dan kuning.
    val c = DarkColors
    val scheme = darkColorScheme(
        primary = c.accent, onPrimary = c.accentInk, background = c.bg, surface = c.surface,
        onSurface = c.fg, onBackground = c.fg,
    )
    CompositionLocalProvider(LocalK provides c) {
        MaterialTheme(colorScheme = scheme, typography = kTypography()) {
            ProvideTextStyle(TextStyle(fontFamily = KFont.body)) { content() }
        }
    }
}
