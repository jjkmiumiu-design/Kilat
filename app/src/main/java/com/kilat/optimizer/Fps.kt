package com.kilat.optimizer

/**
 * Hitungan FPS dan deteksi game di layar depan, semuanya lewat Shizuku (perintah dumpsys).
 *
 * FPS dibaca dari SurfaceFlinger: `dumpsys SurfaceFlinger --latency <layer>` mengembalikan waktu
 * tiap frame (nanodetik) yang benar-benar ditampilkan untuk layer itu. Ini angka frame yang tampil
 * di layar, bukan angka tebakan.
 */
object FpsMath {
    private const val PENDING = Long.MAX_VALUE
    private const val WINDOW_NS = 1_000_000_000L

    /**
     * Keluaran `--latency`: baris pertama periode refresh, lalu baris berisi tiga angka
     * (desiredPresent, actualPresent, frameReady). Yang diambil adalah kolom kedua.
     * Entri 0 atau Long.MAX_VALUE berarti belum terisi dan dibuang.
     */
    fun parsePresentTimes(output: String): List<Long> {
        val times = ArrayList<Long>()
        for (raw in output.lineSequence()) {
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val actual = parts[1].toLongOrNull() ?: continue
            if (actual <= 0L || actual == PENDING) continue
            times += actual
        }
        times.sort()
        return times
    }

    /** FPS dari frame dalam 1 detik terakhir. 0.0 bila datanya kurang. */
    fun fps(times: List<Long>): Double {
        if (times.size < 2) return 0.0
        val last = times.last()
        val window = times.filter { it >= last - WINDOW_NS }
        if (window.size < 2) return 0.0
        val span = (window.last() - window.first()) / 1e9
        if (span <= 0.0) return 0.0
        return (window.size - 1) / span
    }
}

object ForegroundApp {
    private val pkgRegex = Regex("([A-Za-z][A-Za-z0-9_.]*)/[^\\s}]+")

    /** Nama paket aplikasi yang sedang fokus, atau null bila tidak terbaca. */
    suspend fun packageName(): String? =
        parse(Shell.run("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"))

    /**
     * Contoh baris:
     *   mCurrentFocus=Window{1a2b3c u0 com.dts.freefiremax/com.dts.freefireth.GameActivity}
     *   mFocusedApp=ActivityRecord{4d5e6f u0 com.dts.freefiremax/.MainActivity t123}
     * mCurrentFocus didahulukan. mFocusedApp dipakai bila mCurrentFocus kosong (sedang transisi).
     */
    fun parse(output: String): String? {
        val lines = output.lines()
        for (key in listOf("mCurrentFocus", "mFocusedApp")) {
            for (line in lines) {
                if (!line.contains(key)) continue
                pkgRegex.find(line)?.let { return it.groupValues[1] }
            }
        }
        return null
    }
}

/** Mengukur FPS satu game. Layer SurfaceFlinger dicari sekali lalu dipakai ulang. */
class FpsMeter {
    private var pkg: String? = null
    private var layer: String? = null
    private var lastPresent = 0L
    private var idlePolls = 0
    private var cooldown = 0

    fun reset() {
        pkg = null
        layer = null
        lastPresent = 0L
        idlePolls = 0
        cooldown = 0
    }

    /** FPS saat ini, 0.0 bila game tidak menggambar frame baru, null bila layer game tidak ditemukan. */
    suspend fun sample(forPackage: String): Double? {
        if (forPackage != pkg) {
            reset()
            pkg = forPackage
        }
        var name = layer
        if (name == null) {
            // Pencarian layer memakai beberapa perintah, jadi bila gagal jangan diulang tiap detik.
            if (cooldown > 0) {
                cooldown--
                return null
            }
            name = findLayer(forPackage)
            if (name == null) {
                cooldown = 4
                return null
            }
            layer = name
        }

        val times = FpsMath.parsePresentTimes(latency(name))
        if (times.isEmpty()) {
            layer = null // nama layer berubah (game dibuka ulang), cari lagi di putaran berikutnya
            return null
        }
        val last = times.last()
        if (last == lastPresent) {
            if (++idlePolls >= 3) layer = null
            return 0.0
        }
        idlePolls = 0
        lastPresent = last
        return FpsMath.fps(times)
    }

    private suspend fun latency(layerName: String): String =
        Shell.run("dumpsys SurfaceFlinger --latency '$layerName'")

    /**
     * Game biasanya menggambar di layer "SurfaceView[<paket>/<activity>]". Semua layer yang memuat
     * nama paket dicoba, lalu dipilih yang paling banyak menampilkan frame.
     */
    private suspend fun findLayer(forPackage: String): String? {
        val all = Shell.run("dumpsys SurfaceFlinger --list").lineSequence().map { it.trim() }
        val candidates = all
            .filter { it.contains(forPackage) && !it.contains('\'') }
            .filterNot { it.startsWith("Background for") || it.startsWith("Bounds for") }
            .distinct()
            .sortedBy { if (it.startsWith("SurfaceView")) 0 else 1 }
            .take(6)
            .toList()

        var best: String? = null
        var bestFps = -1.0
        for (c in candidates) {
            val times = FpsMath.parsePresentTimes(latency(c))
            if (times.size < 2) continue
            val f = FpsMath.fps(times)
            if (f > bestFps) { // urutan sudah mengutamakan SurfaceView bila hasilnya seri
                best = c
                bestFps = f
            }
        }
        return best
    }
}
