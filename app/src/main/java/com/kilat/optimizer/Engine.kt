package com.kilat.optimizer

import android.content.SharedPreferences

enum class Group(val label: String) { DISPLAY("Tampilan"), TOUCH("Sentuhan") }

data class Setting(val ns: String, val key: String, val value: String)

data class Tweak(
    val id: String,
    val group: Group,
    val title: String,
    val desc: String,
    val settings: List<Setting>,
)

/** Namespace khusus: [Setting.key] adalah nama properti sistem, dipasang dengan `setprop` (bukan `settings put`). */
const val PROP = "prop"

/** Tweak berupa `settings put` atau `setprop`. Nilai asli disimpan dulu agar bisa dikembalikan. */
val TWEAKS = listOf(
    Tweak(
        "anim", Group.DISPLAY, "Percepat animasi (0,5x)", "Transisi antarlayar lebih singkat",
        listOf(
            Setting("global", "window_animation_scale", "0.5"),
            Setting("global", "transition_animation_scale", "0.5"),
            Setting("global", "animator_duration_scale", "0.5"),
        ),
    ),
    Tweak(
        "hz", Group.DISPLAY, "Refresh rate tinggi", "Izinkan layar sampai 120 Hz bila didukung",
        listOf(Setting("system", "peak_refresh_rate", "120.0")),
    ),
    Tweak(
        "blur", Group.DISPLAY, "Matikan efek blur", "Kurangi beban GPU (Android 12+)",
        listOf(Setting("global", "disable_window_blurs", "1")),
    ),
    Tweak(
        "longpress", Group.TOUCH, "Tekan lama lebih singkat", "250 ms, bawaan sistem 400 ms",
        listOf(Setting("secure", "long_press_timeout", "250")),
    ),
    Tweak(
        "multipress", Group.TOUCH, "Ketuk ganda lebih cepat", "Jeda ketukan berulang 200 ms",
        listOf(Setting("secure", "multi_press_timeout", "200")),
    ),
    Tweak(
        "haptic", Group.TOUCH, "Matikan getar sentuh", "Hemat baterai dan tanpa jeda getar",
        listOf(Setting("system", "haptic_feedback_enabled", "0")),
    ),
    Tweak(
        "slop", Group.TOUCH, "Touch slop 12", "Gerakan jari lebih cepat terbaca",
        listOf(Setting(PROP, "debug.input.touch_slop", "12")),
    ),
    Tweak(
        "touchdelay", Group.TOUCH, "Hilangkan delay sentuh", "Ketukan langsung direspons tanpa jeda",
        listOf(Setting(PROP, "touch.screen.delay", "0.00")),
    ),
)

data class BloatCandidate(val pkg: String, val label: String)

/** Daftar kecil aplikasi yang umum dinonaktifkan. Hanya yang terpasang di HP yang akan tampil. */
val BLOAT_CANDIDATES = listOf(
    BloatCandidate("com.facebook.appmanager", "Facebook App Manager"),
    BloatCandidate("com.facebook.services", "Facebook Services"),
    BloatCandidate("com.facebook.system", "Facebook System"),
    BloatCandidate("com.miui.analytics", "MIUI Analytics"),
    BloatCandidate("com.miui.msa.global", "MIUI System Ads"),
    BloatCandidate("com.netflix.partner.activation", "Netflix Partner Activation"),
    BloatCandidate("com.google.android.videos", "Google TV"),
)

class Engine(private val prefs: SharedPreferences) {

    private val safeValue = Regex("^[A-Za-z0-9._-]+$")
    private val propKey = Regex("^[A-Za-z][A-Za-z0-9._-]*$")

    fun isOn(id: String): Boolean = prefs.getBoolean("on.$id", false)

    suspend fun apply(tweak: Tweak) {
        for (s in tweak.settings) {
            val origKey = "orig.${s.ns}.${s.key}"
            if (s.ns == PROP) {
                applyProp(s, origKey)
                continue
            }
            if (!prefs.contains(origKey)) {
                val old = Shell.run("settings get ${s.ns} ${s.key}").trim()
                prefs.edit().putString(origKey, old).apply()
            }
            Shell.runChecked("settings put ${s.ns} ${s.key} ${s.value}")
        }
        prefs.edit().putBoolean("on.${tweak.id}", true).apply()
    }

    /**
     * `setprop` dari shell hanya berlaku untuk properti yang boleh diubah shell, dan perintahnya
     * tidak melaporkan kegagalan. Jadi hasilnya dibaca lagi dengan `getprop`, dan dianggap gagal
     * bila nilainya tidak berubah.
     */
    private suspend fun applyProp(s: Setting, origKey: String) {
        require(propKey.matches(s.key) && safeValue.matches(s.value)) { "Properti tidak valid" }
        val firstTime = !prefs.contains(origKey)
        if (firstTime) {
            prefs.edit().putString(origKey, Shell.run("getprop ${s.key}").trim()).apply()
        }
        Shell.run("setprop ${s.key} ${s.value}")
        if (Shell.run("getprop ${s.key}").trim() != s.value) {
            if (firstTime) prefs.edit().remove(origKey).apply()
            throw IllegalStateException("Sistem menolak setprop ${s.key}")
        }
    }

    private suspend fun revertProp(s: Setting, origKey: String) {
        val old = prefs.getString(origKey, null)
        // Kosong berarti propertinya dulu tidak diisi, jadi dikosongkan lagi.
        val value = if (old != null && safeValue.matches(old)) old else ""
        Shell.run("setprop ${s.key} '$value'")
    }

    suspend fun revert(tweak: Tweak) {
        for (s in tweak.settings) {
            val origKey = "orig.${s.ns}.${s.key}"
            if (s.ns == PROP) {
                revertProp(s, origKey)
                prefs.edit().remove(origKey).apply()
                continue
            }
            val old = prefs.getString(origKey, null)
            if (old == null || old == "null" || !safeValue.matches(old)) {
                Shell.runChecked("settings delete ${s.ns} ${s.key}")
            } else {
                Shell.runChecked("settings put ${s.ns} ${s.key} $old")
            }
            prefs.edit().remove(origKey).apply()
        }
        prefs.edit().putBoolean("on.${tweak.id}", false).apply()
    }
}

object Pkg {
    private val safe = Regex("^[A-Za-z0-9._]+$")

    suspend fun list(flags: String = ""): Set<String> =
        Shell.run("pm list packages $flags")
            .lineSequence()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toSet()

    suspend fun setDisabled(pkg: String, disabled: Boolean) {
        require(safe.matches(pkg)) { "Nama paket tidak valid" }
        val cmd = if (disabled) "pm disable-user --user 0 $pkg" else "pm enable $pkg"
        val out = Shell.runChecked(cmd)
        if (!out.contains("new state")) {
            throw IllegalStateException(out.lineSequence().firstOrNull()?.take(140) ?: "Gagal")
        }
    }

    suspend fun setGameMode(pkg: String, performance: Boolean) {
        require(safe.matches(pkg)) { "Nama paket tidak valid" }
        val mode = if (performance) "performance" else "standard"
        Shell.runChecked("cmd game mode $mode $pkg")
    }
}
