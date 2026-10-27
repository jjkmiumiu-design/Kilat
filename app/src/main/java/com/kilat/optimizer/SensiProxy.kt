package com.kilat.optimizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Zona sensi mode proxy. Jari lu menyentuh layar/game langsung. Zona hanya penanda gambar yang tidak
 * bisa disentuh, jadi tidak pernah menangkap jari. Layar sentuh asli diambil alih oleh [ProxyDaemon]
 * (hak shell lewat Shizuku), yang meneruskan semua sentuhan ke game dan melebarkan geseran jari yang
 * mendarat di dalam zona.
 *
 * Semua pemanggilan publik dari thread utama.
 */
class SensiProxy(
    private val ctx: Context,
    private val wm: WindowManager,
    private val screenSize: () -> Pair<Int, Int>,
) {
    private val main = Handler(Looper.getMainLooper())

    /** true bila proxy tidak bisa dipakai di HP ini. Hub lalu memakai cara lama. */
    @Volatile var failed = false
        private set
    @Volatile var failReason = ""
        private set
    @Volatile var state = "mati"
        private set

    var debugOn = false
    var onDebug: ((String) -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    @Volatile private var ready = false
    private var starting = false
    private var startAttempts = 0
    private var errCount = 0
    private var everGrabbed = false
    private var grabSent = false
    private var sig = ""
    private var sentDebug = false
    private var sentEdge = -1

    private var marker: MarkerView? = null
    private var markerLp: WindowManager.LayoutParams? = null
    private var markerSize = ""

    private val beat = object : Runnable {
        override fun run() {
            if (grabSent) {
                send("H")
                main.postDelayed(this, 2000)
            }
        }
    }
    private var idleScheduled = false
    private val idleKill = Runnable {
        idleScheduled = false
        shutdown()
    }
    @Volatile private var startingProc: Process? = null

    // ---------- API ----------

    fun apply(want: Boolean, data: SensiData) {
        if (!want || data.zones.isEmpty()) {
            hide()
            return
        }
        if (failed) return
        main.removeCallbacks(idleKill)
        idleScheduled = false
        if (!ready) {
            ensureDaemon()
            return
        }
        val (w, h) = screenSize()
        val rot = rotation()
        val density = ctx.resources.displayMetrics.density
        val zoneStr = data.zones.joinToString(";") {
            String.format(
                Locale.US, "%d,%.1f,%.1f,%.1f,%.3f,%.3f,%d,%.1f,%.1f,%d,%d",
                it.id, it.cx * w, it.cy * h, it.sizeDp * density / 2f, data.xOf(it), data.yOf(it),
                it.tap, it.tx * w, it.ty * h, it.repeatMs, it.holdMs,
            )
        }
        val newSig = "${w}x$h r$rot|$zoneStr"
        if (newSig != sig) {
            send("S $w $h $rot")
            send("Z $zoneStr")
            sig = newSig
            updateMarker(w, h, data, density)
        }
        val edge = if (GameToolsPrefs.prefs(ctx).getBoolean("sensi_edge", false)) 1 else 0
        if (edge != sentEdge) {
            send("M $edge")
            sentEdge = edge
        }
        if (debugOn != sentDebug) {
            send("D ${if (debugOn) 1 else 0}")
            sentDebug = debugOn
        }
        if (!grabSent) {
            send("G 1")
            grabSent = true
            main.removeCallbacks(beat)
            main.postDelayed(beat, 2000)
        }
    }

    /** Melepas layar sentuh dan menghapus penanda. Prosesnya dibiarkan hidup sebentar supaya cepat dinyalakan lagi. */
    fun hide() {
        if (grabSent) {
            send("G 0")
            grabSent = false
        }
        main.removeCallbacks(beat)
        removeMarker()
        sig = ""
        if (proc != null && !idleScheduled) {
            idleScheduled = true
            main.postDelayed(idleKill, 30_000)
        }
    }

    /** Mematikan proses proxy sepenuhnya. */
    fun shutdown() {
        main.removeCallbacks(idleKill)
        idleScheduled = false
        hide()
        val p = proc
        val w = writer
        proc = null
        writer = null
        ready = false
        starting = false
        sentEdge = -1
        sentDebug = false
        Thread {
            try { w?.write("Q\n"); w?.flush() } catch (e: Throwable) { }
            try { w?.close() } catch (e: Throwable) { }
            try { p?.destroy() } catch (e: Throwable) { }
        }.start()
    }

    fun statusLine(): String = "proxy $state${if (failed) " (gagal: $failReason)" else ""}"

    // ---------- proses proxy ----------

    @Suppress("DEPRECATION")
    private fun rotation(): Int = try { wm.defaultDisplay.rotation } catch (e: Throwable) { 0 }

    private fun send(line: String) {
        val w = writer ?: return
        // Satu thread pendek per kiriman bisa mengacak urutan, jadi tulis langsung: pesannya kecil dan jarang.
        try {
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (e: Throwable) {
            onDaemonGone()
        }
    }

    private fun ensureDaemon() {
        if (starting || ready || failed) return
        starting = true
        state = "mulai"
        val apk = ctx.applicationInfo.sourceDir
        main.postDelayed({
            // Proses tidak menjawab dalam 12 detik: matikan supaya pembacaan baris pertama selesai dan gagal dengan jelas.
            if (starting) try { startingProc?.destroy() } catch (e: Throwable) { }
        }, 12_000)
        Thread {
            val p = Shell.openStream(
                "app_process", "-Djava.class.path=$apk", "/system/bin",
                "--nice-name=kilat_proxy", ProxyDaemon::class.java.name,
            )
            if (p == null) {
                main.post {
                    starting = false
                    state = "mati"
                    startAttempts++
                    if (startAttempts >= 3) fail("Shizuku tidak bisa menjalankan proses proxy")
                }
                return@Thread
            }
            startingProc = p
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val first = try { reader.readLine() } catch (e: Throwable) { null }
            if (first == null || !first.startsWith("OK|")) {
                try { p.destroy() } catch (e: Throwable) { }
                main.post {
                    starting = false
                    fail("suntik: ${first?.substringAfter('|')?.take(120) ?: "proses tidak menjawab"}")
                }
                return@Thread
            }
            main.post {
                proc = p
                writer = BufferedWriter(OutputStreamWriter(p.outputStream))
                ready = true
                starting = false
                state = "siap"
            }
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    handleLine(line)
                }
            } catch (e: Throwable) {
                // putus
            }
            main.post { if (proc === p) onDaemonGone() }
        }.also { it.isDaemon = true }.start()
    }

    private fun handleLine(line: String) {
        when {
            line.startsWith("L ") -> main.post { if (debugOn) onDebug?.invoke(line.substring(2)) }
            line.startsWith("F ") -> {
                val p = line.split(' ')
                val id = p.getOrNull(1)?.toIntOrNull() ?: return
                val x = p.getOrNull(2)?.toFloatOrNull() ?: 0f
                val y = p.getOrNull(3)?.toFloatOrNull() ?: 0f
                main.post { marker?.follow(id, x, y) }
            }
            line.startsWith("R ") -> main.post {
                if (line.startsWith("R grab aktif")) {
                    everGrabbed = true
                    errCount = 0
                    state = "aktif"
                }
                if (debugOn) onDebug?.invoke(line.substring(2))
            }
            line.startsWith("E ") -> main.post {
                val msg = line.substring(2)
                onDebug?.invoke("Proxy: $msg")
                grabSent = false
                state = "siap"
                errCount++
                if (!everGrabbed || errCount >= 3) fail(msg)
            }
        }
    }

    private fun onDaemonGone() {
        if (proc == null && !ready) return
        proc = null
        writer = null
        ready = false
        starting = false
        grabSent = false
        sig = ""
        sentEdge = -1
        sentDebug = false
        state = "mati"
        main.removeCallbacks(beat)
        removeMarker()
        errCount++
        if (!everGrabbed || errCount >= 3) fail("proses proxy mati")
    }

    private fun fail(reason: String) {
        failed = true
        failReason = reason
        state = "gagal"
        shutdown()
        onFailed?.invoke()
    }

    // ---------- penanda ----------

    private fun updateMarker(w: Int, h: Int, data: SensiData, density: Float) {
        val rings = data.zones.map {
            Ring(it.id, it.cx * w, it.cy * h, it.sizeDp * density / 2f)
        }
        val key = "${w}x$h"
        var v = marker
        if (v == null || markerSize != key) {
            removeMarker()
            v = MarkerView(ctx, density)
            val lp = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).also {
                it.gravity = Gravity.TOP or Gravity.START
                it.x = 0
                it.y = 0
                if (Build.VERSION.SDK_INT >= 30) {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= 28) {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                wm.addView(v, lp)
                marker = v
                markerLp = lp
                markerSize = key
            } catch (e: Exception) {
                // Izin overlay dicabut. Service yang menangani.
                return
            }
        }
        v.rings = rings
        v.follow(-1, 0f, 0f)
    }

    private fun removeMarker() {
        val v = marker ?: return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            // sudah dilepas
        }
        marker = null
        markerLp = null
        markerSize = ""
    }
}

private class Ring(val id: Int, val cx: Float, val cy: Float, val r: Float)

/**
 * Gambar penanda zona. Tidak pernah menerima sentuhan (jendelanya FLAG_NOT_TOUCHABLE). Saat jari berada
 * di zona, lingkaran kedua ikut bergerak di bawah jari.
 */
private class MarkerView(ctx: Context, density: Float) : View(ctx) {
    var rings: List<Ring> = emptyList()
        set(v) { field = v; invalidate() }
    private var activeId = -1
    private var fx = 0f
    private var fy = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(34, 138, 63, 252)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(150, 196, 155, 255)
    }
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 171, 120, 255)
    }
    private val hotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.argb(230, 220, 190, 255)
    }

    fun follow(id: Int, x: Float, y: Float) {
        if (id == activeId && x == fx && y == fy) return
        activeId = id
        fx = x
        fy = y
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        for (r in rings) {
            c.drawCircle(r.cx, r.cy, r.r, fill)
            c.drawCircle(r.cx, r.cy, r.r, stroke)
        }
        val a = rings.firstOrNull { it.id == activeId } ?: return
        c.drawCircle(fx, fy, a.r, hot)
        c.drawCircle(fx, fy, a.r, hotStroke)
    }
}

/**
 * Pintu masuk zona sensi untuk [GameOverlay]: memakai mode proxy bila bisa, dan cara lama
 * ([SensiOverlay], jendela penangkap sentuh) bila proxy gagal di HP ini.
 */
class SensiHub(
    private val ctx: Context,
    wm: WindowManager,
    screenSize: () -> Pair<Int, Int>,
    onUnsupported: () -> Unit,
    private val onNote: (String) -> Unit,
) {
    private val classic = SensiOverlay(ctx, wm, screenSize, onUnsupported)
    private val proxy = SensiProxy(ctx, wm, screenSize)
    private val main = Handler(Looper.getMainLooper())

    init {
        proxy.onFailed = {
            main.post { onNote("Mode proxy gagal (${proxy.failReason}). Pakai cara lama.") }
        }
    }

    var debugOn: Boolean
        get() = classic.debugOn
        set(v) {
            classic.debugOn = v
            proxy.debugOn = v
        }

    var onDebug: ((String) -> Unit)?
        get() = classic.onDebug
        set(v) {
            classic.onDebug = v
            proxy.onDebug = v
        }

    private fun useProxy(): Boolean =
        !proxy.failed && GameToolsPrefs.prefs(ctx).getBoolean("sensi_proxy", true)

    fun apply(want: Boolean, data: SensiData) {
        if (useProxy()) {
            classic.hide()
            proxy.apply(want, data)
        } else {
            proxy.hide()
            classic.apply(want, data)
        }
    }

    fun hide() {
        proxy.hide()
        classic.hide()
    }

    fun destroy() {
        proxy.shutdown()
        classic.hide()
    }

    fun statusLine(): String = if (useProxy()) proxy.statusLine() else "klasik | " + classic.statusLine()
}
