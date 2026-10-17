package com.kilat.optimizer

import kotlin.math.min

/** Kondisi layar saat ini. Nilai "ov" (override) bernilai null bila memakai bawaan perangkat. */
data class DisplayState(
    val physW: Int,
    val physH: Int,
    val physDpi: Int,
    val ovW: Int?,
    val ovH: Int?,
    val ovDpi: Int?,
) {
    val curW: Int get() = ovW ?: physW
    val curH: Int get() = ovH ?: physH
    val curDpi: Int get() = ovDpi ?: physDpi
    val hasOverride: Boolean get() = ovW != null || ovDpi != null
    val smallestDp: Int get() = min(curW, curH) * 160 / curDpi
}

/** Preset resolusi/density. Nilai null berarti "pakai bawaan". */
data class Preset(val name: String, val w: Int?, val h: Int?, val dpi: Int?)

object DisplayTools {
    private val sizePhysical = Regex("""Physical size:\s*(\d+)x(\d+)""")
    private val sizeOverride = Regex("""Override size:\s*(\d+)x(\d+)""")
    private val densPhysical = Regex("""Physical density:\s*(\d+)""")
    private val densOverride = Regex("""Override density:\s*(\d+)""")

    suspend fun read(): DisplayState? {
        val size = Shell.run("wm size")
        val dens = Shell.run("wm density")
        val ps = sizePhysical.find(size) ?: return null
        val pd = densPhysical.find(dens) ?: return null
        val os = sizeOverride.find(size)
        val od = densOverride.find(dens)
        return DisplayState(
            physW = ps.groupValues[1].toInt(),
            physH = ps.groupValues[2].toInt(),
            physDpi = pd.groupValues[1].toInt(),
            ovW = os?.groupValues?.get(1)?.toInt(),
            ovH = os?.groupValues?.get(2)?.toInt(),
            ovDpi = od?.groupValues?.get(1)?.toInt(),
        )
    }

    /** Mengembalikan pesan galat, atau null bila nilainya aman dipakai. */
    fun validate(w: Int, h: Int, dpi: Int): String? {
        if (w < 480 || h < 480 || w > 4500 || h > 4500) return "Resolusi harus antara 480 dan 4500 piksel"
        if (dpi < 120 || dpi > 800) return "Density harus antara 120 dan 800"
        if (min(w, h) * 160 / dpi < 300) return "Smallest width di bawah 300dp, layar bisa tidak terpakai"
        return null
    }

    suspend fun apply(cur: DisplayState, w: Int, h: Int, dpi: Int) {
        val sizeCmd = if (w == cur.physW && h == cur.physH) "wm size reset" else "wm size ${w}x$h"
        val densCmd = if (dpi == cur.physDpi) "wm density reset" else "wm density $dpi"
        Shell.runChecked(sizeCmd)
        Shell.runChecked(densCmd)
    }

    suspend fun reset() {
        Shell.runChecked("wm size reset")
        Shell.runChecked("wm density reset")
    }

    /** Perintah untuk memulihkan layar ke kondisi [s]. */
    fun revertCommand(s: DisplayState): String {
        val size = if (s.ovW != null && s.ovH != null) "wm size ${s.ovW}x${s.ovH}" else "wm size reset"
        val dens = if (s.ovDpi != null) "wm density ${s.ovDpi}" else "wm density reset"
        return "$size; $dens"
    }
}
