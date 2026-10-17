package com.kilat.optimizer

import kotlin.math.max
import kotlin.math.min

/** Satu jari dari pembacaan mentah layar sentuh, sudah dalam piksel layar. */
class RawPt(val id: Int, val x: Float, val y: Float)

/** Satu jari mentah (satuan koordinat panel sentuh). */
class RawSample(val id: Int, val x: Int, val y: Int)

/** Layar sentuh yang ditemukan: jalur perangkat dan rentang koordinat panelnya. */
class TouchInfo(val path: String, val maxX: Int, val maxY: Int)

/**
 * Pengurai keluaran `getevent -l` untuk layar sentuh protokol B (multi-slot).
 * Murni Kotlin tanpa Android, supaya bisa diuji sendiri.
 */
class EvdevParser {
    private val tid = IntArray(SLOTS) { -1 }
    private val rx = IntArray(SLOTS)
    private val ry = IntArray(SLOTS)
    private var slot = 0

    /** Memproses satu baris. true bila baris itu menutup satu frame (SYN_REPORT). */
    fun feed(line: String): Boolean {
        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
        val n = parts.size
        if (n < 3) return false
        val type = parts[n - 3]
        val code = parts[n - 2]
        val value = parts[n - 1]
        if (type == "EV_SYN") return code == "SYN_REPORT"
        if (type != "EV_ABS") return false
        val v = try {
            java.lang.Long.parseLong(value, 16).toInt()
        } catch (e: NumberFormatException) {
            return false
        }
        when (code) {
            "ABS_MT_SLOT" -> slot = if (v in 0 until SLOTS) v else -1
            "ABS_MT_TRACKING_ID" -> if (slot >= 0) tid[slot] = v
            "ABS_MT_POSITION_X" -> if (slot >= 0) rx[slot] = v
            "ABS_MT_POSITION_Y" -> if (slot >= 0) ry[slot] = v
        }
        return false
    }

    /** Jari yang sedang menyentuh layar. */
    fun active(): List<RawSample> {
        val out = ArrayList<RawSample>(4)
        for (i in 0 until SLOTS) if (tid[i] >= 0) out += RawSample(tid[i], rx[i], ry[i])
        return out
    }

    companion object {
        const val SLOTS = 16

        private val devRe = Regex("""(/dev/input/event\d+)""")
        private val maxXRe = Regex("""ABS_MT_POSITION_X\s*:[^\n]*?max\s+(\d+)""")
        private val maxYRe = Regex("""ABS_MT_POSITION_Y\s*:[^\n]*?max\s+(\d+)""")

        /** Mencari layar sentuh dari keluaran `getevent -lp`. Mengutamakan yang bertanda INPUT_PROP_DIRECT. */
        fun findTouchscreen(text: String): TouchInfo? {
            var fallback: TouchInfo? = null
            for (block in text.split("add device")) {
                if (!block.contains("ABS_MT_POSITION_X") || !block.contains("ABS_MT_SLOT")) continue
                val path = devRe.find(block)?.groupValues?.get(1) ?: continue
                val mx = maxXRe.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val my = maxYRe.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (mx <= 0 || my <= 0) continue
                val info = TouchInfo(path, mx, my)
                if (block.contains("INPUT_PROP_DIRECT")) return info
                if (fallback == null) fallback = info
            }
            return fallback
        }
    }
}

object RawMath {
    /**
     * Mengubah koordinat panel menjadi piksel layar pada rotasi [rot] (0..3, nilai Surface.ROTATION_*).
     * Panel selalu dalam orientasi potret. [sw] dan [sh] adalah ukuran layar saat ini.
     */
    fun toScreen(x: Int, y: Int, maxX: Int, maxY: Int, sw: Int, sh: Int, rot: Int): Pair<Float, Float> {
        val pw = min(sw, sh).toFloat()
        val ph = max(sw, sh).toFloat()
        val u = x / maxX.toFloat() * pw
        val v = y / maxY.toFloat() * ph
        return when (rot and 3) {
            1 -> Pair(v, pw - u)
            2 -> Pair(pw - u, ph - v)
            3 -> Pair(ph - v, u)
            else -> Pair(u, v)
        }
    }
}
