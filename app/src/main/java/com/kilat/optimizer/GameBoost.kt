package com.kilat.optimizer

import android.content.Context

/**
 * Boost per game lewat device config (butuh Shizuku):
 *
 *   device_config put game_overlay <paket> mode=2,fps=90
 *
 * Contoh: Free Fire MAX = com.dts.freefiremax, Free Fire = com.dts.freefireth.
 * Nilai lama dicatat sebelum diubah, jadi [restore] mengembalikan persis seperti semula.
 * Boost hanya dijalankan manual dari titik tiga pada game.
 */
object GameBoost {
    private const val FILE = "kilat_boost"
    private const val NAMESPACE = "game_overlay"
    private const val VALUE = "mode=2,fps=90"
    private const val NONE = "<none>"

    // Nama paket masuk ke perintah shell, jadi harus lolos pemeriksaan ketat ini.
    private val PKG = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private val SAFE = Regex("""^[A-Za-z0-9_.,=:\-]+$""")

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isBoosted(ctx: Context, pkg: String): Boolean =
        prefs(ctx).getStringSet("boosted", emptySet())?.contains(pkg) == true

    /** Menerapkan boost. Melempar error bila Shizuku menolak perintahnya. */
    suspend fun apply(ctx: Context, pkg: String) {
        require(PKG.matches(pkg)) { "Nama paket tidak valid" }
        val p = prefs(ctx)
        val prevKey = "prev.$pkg"
        // Catat nilai asli sekali saja, supaya menerapkan ulang tidak menimpanya dengan nilai boost.
        if (!p.contains(prevKey)) {
            val old = runCatching { Shell.run("device_config get $NAMESPACE $pkg").trim() }.getOrDefault("")
            val none = old.isEmpty() || old == "null" || !SAFE.matches(old)
            p.edit().putString(prevKey, if (none) NONE else old).apply()
        }
        Shell.runChecked("device_config put $NAMESPACE $pkg $VALUE")
        val set = (p.getStringSet("boosted", emptySet()) ?: emptySet()).toMutableSet()
        set.add(pkg)
        p.edit().putStringSet("boosted", set).apply()
    }

    /** Mengembalikan device config game ke nilai aslinya. */
    suspend fun restore(ctx: Context, pkg: String) {
        require(PKG.matches(pkg)) { "Nama paket tidak valid" }
        val p = prefs(ctx)
        val prev = p.getString("prev.$pkg", NONE) ?: NONE
        if (prev == NONE || !SAFE.matches(prev)) Shell.runChecked("device_config delete $NAMESPACE $pkg")
        else Shell.runChecked("device_config put $NAMESPACE $pkg $prev")
        p.edit().remove("prev.$pkg").apply()
        val set = (p.getStringSet("boosted", emptySet()) ?: emptySet()).toMutableSet()
        set.remove(pkg)
        p.edit().putStringSet("boosted", set).apply()
    }
}
