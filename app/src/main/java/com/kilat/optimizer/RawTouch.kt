package com.kilat.optimizer

import android.os.Handler
import android.view.WindowManager
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Membaca posisi jari asli langsung dari layar sentuh (getevent, hak shell lewat Shizuku).
 *
 * Dipakai sebagai jalur cadangan untuk zona sensitivitas: di sebagian versi Android, begitu
 * sentuhan suntikan masuk, sistem berhenti mengirim gerakan jari asli ke jendela zona. Pembacaan
 * mentah ini tidak terpengaruh oleh hal itu.
 */
class RawTouch(
    private val main: Handler,
    private val wm: WindowManager,
    private val screenSize: () -> Pair<Int, Int>,
) {
    @Volatile var listener: ((List<RawPt>) -> Unit)? = null
    @Volatile var status: String = "mati"
        private set

    @Volatile private var gen = 0
    @Volatile private var proc: Process? = null

    fun start() {
        val g = ++gen
        status = "mulai"
        Thread { loop(g) }.also { it.isDaemon = true }.start()
    }

    fun stop() {
        gen++
        status = "mati"
        try { proc?.destroy() } catch (e: Throwable) { }
        proc = null
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int = try { wm.defaultDisplay.rotation } catch (e: Throwable) { 0 }

    private fun loop(g: Int) {
        try {
            val info = EvdevParser.findTouchscreen(runBlocking { Shell.run("getevent -lp") })
            if (info == null) {
                status = "layar sentuh tidak ditemukan"
                return
            }
            if (g != gen) return
            val p = Shell.openStream("sh", "-c", "exec getevent -l ${info.path} 2>&1")
            if (p == null) {
                status = "gagal membuka getevent"
                return
            }
            if (g != gen) {
                p.destroy()
                return
            }
            proc = p
            status = "aktif"
            val parser = EvdevParser()
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            while (g == gen) {
                val line = reader.readLine() ?: break
                if (!parser.feed(line)) continue
                val (sw, sh) = screenSize()
                val rot = rotation()
                val pts = parser.active().map {
                    val (x, y) = RawMath.toScreen(it.x, it.y, info.maxX, info.maxY, sw, sh, rot)
                    RawPt(it.id, x, y)
                }
                main.post { if (g == gen) listener?.invoke(pts) }
            }
            if (g == gen) status = "berhenti"
        } catch (e: Throwable) {
            if (g == gen) status = "galat: ${e.javaClass.simpleName}"
        } finally {
            if (g == gen) {
                try { proc?.destroy() } catch (e: Throwable) { }
                proc = null
            }
        }
    }
}
