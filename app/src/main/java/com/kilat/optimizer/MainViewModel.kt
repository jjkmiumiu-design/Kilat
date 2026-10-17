package com.kilat.optimizer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class AppRow(val pkg: String, val label: String, val on: Boolean)

data class Ui(
    val status: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val stats: Stats? = null,
    val governor: String = "-",
    val active: Set<String> = emptySet(),
    val lastAt: Long = 0L,
    val busy: Boolean = false,
    val progress: String = "",
    val bloat: List<AppRow> = emptyList(),
    val games: List<AppRow> = emptyList(),
    val display: DisplayState? = null,
    val presets: List<Preset> = emptyList(),
    val message: String? = null,
) {
    /** Skor naik sesuai jumlah tweak yang sedang aktif. */
    val score: Int get() = 55 + 45 * active.size / TWEAKS.size
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("kilat", Context.MODE_PRIVATE)
    private val engine = Engine(prefs)

    private val _ui = MutableStateFlow(
        Ui(
            active = TWEAKS.filter { engine.isOn(it.id) }.map { it.id }.toSet(),
            lastAt = prefs.getLong("lastAt", 0L),
        )
    )
    val ui: StateFlow<Ui> = _ui

    init {
        viewModelScope.launch {
            Shell.status.collect { st ->
                _ui.update { it.copy(status = st) }
                if (st == ShizukuStatus.READY) refreshAll()
            }
        }
        refreshStats()
    }

    // ---------- util ----------

    private fun getSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun putSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }

    private fun toast(msg: String) = _ui.update { it.copy(message = msg) }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    private fun ready(): Boolean {
        if (_ui.value.status == ShizukuStatus.READY) return true
        toast("Jalankan Shizuku dan beri izin dulu")
        return false
    }

    private fun launchTask(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: Throwable) {
            toast("Gagal: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- statistik ----------

    fun refreshStats() {
        _ui.update { it.copy(stats = readStats(getApplication())) }
    }

    fun refreshAll() {
        refreshStats()
        if (_ui.value.status != ShizukuStatus.READY) return
        viewModelScope.launch {
            val g = runCatching {
                Shell.run("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").trim()
            }.getOrDefault("-")
            val clean = if (g.isBlank() || g.contains(" ")) "-" else g.take(20)
            _ui.update { it.copy(governor = clean) }
        }
    }

    // ---------- optimasi ----------

    fun optimizeAll() {
        if (_ui.value.busy || !ready()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                // Satu tweak yang ditolak sistem tidak menghentikan yang lain.
                val rejected = mutableListOf<String>()
                for (t in TWEAKS) {
                    _ui.update { it.copy(progress = "Menerapkan: ${t.title}") }
                    try {
                        engine.apply(t)
                        _ui.update { it.copy(active = it.active + t.id) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        rejected += t.title
                    }
                }
                _ui.update { it.copy(progress = "Menutup proses latar belakang") }
                Shell.run("am kill-all")
                _ui.update { it.copy(progress = "Membersihkan cache") }
                Shell.run("pm trim-caches 128G")

                val now = System.currentTimeMillis()
                prefs.edit().putLong("lastAt", now).apply()
                _ui.update { it.copy(lastAt = now) }
                toast(
                    if (rejected.isEmpty()) "Selesai. Skor ${_ui.value.score}/100"
                    else "Selesai. Skor ${_ui.value.score}/100. Ditolak sistem: ${rejected.joinToString()}"
                )
            } catch (e: Throwable) {
                toast("Gagal: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _ui.update { it.copy(busy = false, progress = "") }
                refreshAll()
            }
        }
    }

    fun revertAll() {
        if (_ui.value.busy || !ready()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, progress = "Mengembalikan pengaturan") }
            try {
                for (t in TWEAKS.filter { engine.isOn(it.id) }) {
                    engine.revert(t)
                    _ui.update { it.copy(active = it.active - t.id) }
                }
                for (pkg in getSet("bloat.off")) Pkg.setDisabled(pkg, false)
                putSet("bloat.off", emptySet())
                if (Build.VERSION.SDK_INT >= 31) {
                    for (pkg in getSet("games.perf")) Pkg.setGameMode(pkg, false)
                }
                putSet("games.perf", emptySet())
                prefs.edit().putLong("lastAt", 0L).apply()
                _ui.update { it.copy(lastAt = 0L) }
                toast("Semua perubahan dikembalikan")
            } catch (e: Throwable) {
                toast("Gagal: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _ui.update { it.copy(busy = false, progress = "") }
                loadBloat()
                loadGames()
                refreshAll()
            }
        }
    }

    fun toggleTweak(id: String) {
        if (_ui.value.busy || !ready()) return
        val t = TWEAKS.firstOrNull { it.id == id } ?: return
        launchTask {
            if (engine.isOn(id)) {
                engine.revert(t)
                _ui.update { it.copy(active = it.active - id) }
            } else {
                engine.apply(t)
                _ui.update { it.copy(active = it.active + id) }
            }
            refreshAll()
        }
    }

    fun killBackground() {
        if (!ready()) return
        launchTask {
            Shell.run("am kill-all")
            refreshStats()
            toast("Proses latar belakang ditutup")
        }
    }

    fun trimCaches() {
        if (!ready()) return
        launchTask {
            Shell.run("pm trim-caches 128G")
            refreshStats()
            toast("Cache dibersihkan")
        }
    }

    // ---------- aplikasi bawaan ----------

    fun loadBloat() {
        if (_ui.value.status != ShizukuStatus.READY) return
        launchTask {
            val installed = Pkg.list()
            val disabled = Pkg.list("-d")
            val rows = BLOAT_CANDIDATES
                .filter { it.pkg in installed }
                .map { AppRow(it.pkg, it.label, it.pkg in disabled) }
            _ui.update { it.copy(bloat = rows) }
        }
    }

    fun toggleBloat(pkg: String) {
        if (!ready()) return
        val row = _ui.value.bloat.firstOrNull { it.pkg == pkg } ?: return
        launchTask {
            val disable = !row.on
            Pkg.setDisabled(pkg, disable)
            val set = getSet("bloat.off")
            putSet("bloat.off", if (disable) set + pkg else set - pkg)
            _ui.update { u ->
                u.copy(bloat = u.bloat.map { if (it.pkg == pkg) it.copy(on = disable) else it })
            }
        }
    }

    // ---------- resolusi dan density ----------

    private val resRegex = Regex("""^\s*(\d{3,5})\s*[xX×]\s*(\d{3,5})\s*$""")

    private fun parseRes(text: String): Pair<Int, Int>? {
        val m = resRegex.find(text) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun readPresets(): List<Preset> {
        val raw = prefs.getString("display.presets", null) ?: return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 4) null
            else Preset(p[0], p[1].toIntOrNull(), p[2].toIntOrNull(), p[3].toIntOrNull())
        }.toList()
    }

    private fun writePresets(list: List<Preset>) {
        val raw = list.joinToString("\n") { "${it.name}\t${it.w ?: ""}\t${it.h ?: ""}\t${it.dpi ?: ""}" }
        prefs.edit().putString("display.presets", raw).apply()
        _ui.update { it.copy(presets = list) }
    }

    /** Preset awal dibuat dari ukuran layar fisik HP ini. */
    private fun seedPresets(s: DisplayState): List<Preset> =
        listOf(720, 1080, 1440).map { w ->
            val h = (w * s.physH.toDouble() / s.physW).roundToInt()
            val dpi = (s.physDpi * w.toDouble() / s.physW).roundToInt()
            Preset("${w}p", w, h, dpi)
        }

    fun loadDisplay() {
        if (_ui.value.status != ShizukuStatus.READY) return
        launchTask {
            val s = DisplayTools.read()
            if (s != null && !prefs.getBoolean("display.seeded", false)) {
                writePresets(seedPresets(s))
                prefs.edit().putBoolean("display.seeded", true).apply()
            }
            _ui.update { it.copy(display = s, presets = readPresets()) }
        }
    }

    fun applyDisplay(resText: String, dpiText: String) {
        if (!ready()) return
        val cur = _ui.value.display
        if (cur == null) {
            toast("Data layar belum terbaca")
            return
        }
        var w = cur.curW
        var h = cur.curH
        if (resText.isNotBlank()) {
            val r = parseRes(resText)
            if (r == null) {
                toast("Format resolusi: 1080x2400")
                return
            }
            w = r.first
            h = r.second
        }
        var dpi = cur.curDpi
        if (dpiText.isNotBlank()) {
            val d = dpiText.trim().toIntOrNull()
            if (d == null) {
                toast("Density harus berupa angka")
                return
            }
            dpi = d
        }
        val err = DisplayTools.validate(w, h, dpi)
        if (err != null) {
            toast(err)
            return
        }
        if (w == cur.curW && h == cur.curH && dpi == cur.curDpi) {
            toast("Tidak ada perubahan")
            return
        }
        // Langsung berlaku, tanpa dialog konfirmasi dan tanpa hitung mundur.
        launchTask {
            try {
                DisplayTools.apply(cur, w, h, dpi)
            } catch (e: Throwable) {
                // Salah satu perintah gagal: pulihkan supaya layar tidak setengah berubah.
                runCatching { Shell.run(DisplayTools.revertCommand(cur)) }
                throw e
            } finally {
                DisplayTools.read()?.let { s -> _ui.update { it.copy(display = s) } }
            }
            toast("Resolusi dan density diterapkan")
        }
    }

    fun resetDisplay() {
        if (!ready()) return
        launchTask {
            DisplayTools.reset()
            DisplayTools.read()?.let { s -> _ui.update { it.copy(display = s) } }
            toast("Resolusi dan density dikembalikan ke bawaan")
        }
    }

    fun savePreset(name: String, resText: String, dpiText: String) {
        val clean = name.trim().replace(Regex("""[\t\r\n|]"""), " ").take(24)
        if (clean.isEmpty()) {
            toast("Isi nama preset dulu")
            return
        }
        var w: Int? = null
        var h: Int? = null
        if (resText.isNotBlank()) {
            val r = parseRes(resText)
            if (r == null) {
                toast("Format resolusi: 1080x2400")
                return
            }
            w = r.first
            h = r.second
        }
        var dpi: Int? = null
        if (dpiText.isNotBlank()) {
            dpi = dpiText.trim().toIntOrNull()
            if (dpi == null) {
                toast("Density harus berupa angka")
                return
            }
        }
        if (w == null && dpi == null) {
            toast("Isi resolusi atau density dulu")
            return
        }
        val list = readPresets().filter { it.name != clean } + Preset(clean, w, h, dpi)
        writePresets(list)
        toast("Preset \"$clean\" disimpan")
    }

    fun deletePreset(name: String) {
        writePresets(readPresets().filter { it.name != name })
    }

    // ---------- game ----------

    fun loadGames() {
        val ctx = getApplication<Application>()
        val pm = ctx.packageManager
        val perf = getSet("games.perf")
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val rows = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .filter {
                it.packageName != ctx.packageName &&
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .distinctBy { it.packageName }
            .map { AppRow(it.packageName, it.loadLabel(pm).toString(), it.packageName in perf) }
            .sortedBy { it.label.lowercase() }
        _ui.update { it.copy(games = rows) }
    }

    fun toggleGame(pkg: String) {
        if (Build.VERSION.SDK_INT < 31) {
            toast("Mode game butuh Android 12 atau lebih baru")
            return
        }
        if (!ready()) return
        val row = _ui.value.games.firstOrNull { it.pkg == pkg } ?: return
        launchTask {
            val enable = !row.on
            Pkg.setGameMode(pkg, enable)
            val set = getSet("games.perf")
            putSet("games.perf", if (enable) set + pkg else set - pkg)
            _ui.update { u ->
                u.copy(games = u.games.map { if (it.pkg == pkg) it.copy(on = enable) else it })
            }
        }
    }
}
