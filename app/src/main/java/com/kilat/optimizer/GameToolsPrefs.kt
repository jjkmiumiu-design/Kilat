package com.kilat.optimizer

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** Semua pengaturan Game Tools dalam satu objek. */
data class GameToolsSettings(
    val crossOn: Boolean,
    val crossStyle: Int,
    val crossColor: Int,
    val crossSize: Int,
    val crossOpacity: Int,
    val fpsOn: Boolean,
    /** 0 kiri atas, 1 kanan atas, 2 kiri bawah, 3 kanan bawah. */
    val fpsCorner: Int,
    /** true = semua aplikasi, false = hanya game pilihan. */
    val targetAny: Boolean,
    val games: Set<String>,
    /** Zona sensitivitas sentuh aktif atau tidak. */
    val sensiOn: Boolean,
)

/**
 * Pengaturan disimpan di SharedPreferences. Layar di aplikasi dan panel di dalam game menulis ke
 * tempat yang sama, dan overlay mendengarkan perubahannya, jadi semua perubahan langsung terlihat.
 */
object GameToolsPrefs {
    private const val FILE = "kilat_gametools"

    const val STYLE_COUNT = 5
    const val SIZE_MIN = 16
    const val SIZE_MAX = 128
    const val OPACITY_MIN = 10
    const val OPACITY_MAX = 100

    /** Putih, biru, merah, kuning, hijau. */
    val COLORS: IntArray = intArrayOf(
        Color.WHITE,
        Color.rgb(10, 132, 255),
        Color.rgb(255, 23, 68),
        Color.rgb(255, 224, 0),
        Color.rgb(0, 255, 85),
    )

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(ctx: Context): GameToolsSettings {
        val p = prefs(ctx)
        return GameToolsSettings(
            crossOn = p.getBoolean("cross_on", true),
            crossStyle = p.getInt("cross_style", 0).coerceIn(0, STYLE_COUNT - 1),
            crossColor = p.getInt("cross_color", 0).coerceIn(0, COLORS.size - 1),
            crossSize = p.getInt("cross_size", 48).coerceIn(SIZE_MIN, SIZE_MAX),
            crossOpacity = p.getInt("cross_opacity", 100).coerceIn(OPACITY_MIN, OPACITY_MAX),
            fpsOn = p.getBoolean("fps_on", true),
            fpsCorner = p.getInt("fps_corner", 0).coerceIn(0, 3),
            targetAny = p.getBoolean("target_any", false),
            games = p.getStringSet("games", emptySet())?.toSet() ?: emptySet(),
            sensiOn = p.getBoolean("sensi_on", false),
        )
    }

    fun setCrossOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("cross_on", v).apply()
    fun setCrossStyle(ctx: Context, v: Int) = prefs(ctx).edit().putInt("cross_style", v).apply()
    fun setCrossColor(ctx: Context, v: Int) = prefs(ctx).edit().putInt("cross_color", v).apply()
    fun setCrossSize(ctx: Context, v: Int) = prefs(ctx).edit().putInt("cross_size", v).apply()
    fun setCrossOpacity(ctx: Context, v: Int) = prefs(ctx).edit().putInt("cross_opacity", v).apply()
    fun setFpsOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("fps_on", v).apply()
    fun setFpsCorner(ctx: Context, v: Int) = prefs(ctx).edit().putInt("fps_corner", v).apply()
    fun setSensiOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("sensi_on", v).apply()
    fun setTargetAny(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("target_any", v).apply()

    fun toggleGame(ctx: Context, pkg: String) {
        val now = read(ctx).games
        setGames(ctx, if (pkg in now) now - pkg else now + pkg)
    }

    fun removeGame(ctx: Context, pkg: String) = setGames(ctx, read(ctx).games - pkg)

    private fun setGames(ctx: Context, games: Set<String>) {
        // StringSet harus berupa salinan baru, kalau tidak perubahan bisa tidak tersimpan.
        prefs(ctx).edit().putStringSet("games", HashSet(games)).apply()
    }
}
