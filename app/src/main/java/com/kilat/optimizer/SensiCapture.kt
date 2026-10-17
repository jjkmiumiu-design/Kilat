package com.kilat.optimizer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/** ID jari untuk sentuhan yang disuntikkan. Dipilih besar supaya tidak bentrok dengan jari asli (0, 1, 2, ...). */
private const val INJECT_ID = 20

/**
 * Id perangkat untuk sentuhan suntikan. Android 15+ melacak sentuhan per perangkat, jadi jari suntik
 * dibuat sebagai perangkat sendiri (0, seperti scrcpy) supaya tidak bentrok dengan jari asli yang
 * masih turun di perangkat layar sentuh. Android 14 ke bawah hanya mengizinkan satu perangkat
 * sekaligus, jadi di sana id perangkat asli dipakai.
 */
internal fun injectDeviceId(realDeviceId: Int): Int =
    if (Build.VERSION.SDK_INT >= 35) 0 else realDeviceId

/** Jeda setelah jendela zona dibuat tembus sentuh, sebelum jari pertama disuntikkan. */
private const val ACTIVATE_DELAY_MS = 60L

/** Gestur yang dibatalkan sistem secepat ini setelah dimulai dianggap tanda jendela tidak dipakai dengan benar. */
private const val EARLY_CANCEL_MS = 250L
private const val MAX_STRIKES = 3

/**
 * Jendela zona sensitivitas. Setiap zona adalah satu jendela transparan seukuran zona yang
 * menangkap sentuhan di areanya, lalu menyuntikkan sentuhan yang sudah dilebarkan ke game.
 *
 * Cara kerja satu gestur:
 *  1. Jari menyentuh zona: jendela menangkapnya dan langsung digeser ke luar layar (gestur tetap
 *     miliknya), supaya sentuhan yang disuntikkan tidak jatuh ke jendela ini lagi.
 *  2. Setelah [ACTIVATE_DELAY_MS], jari DOWN disuntikkan di titik awal.
 *  3. Setiap gerakan jari asli dikalikan sx dan sy, lalu disuntikkan sebagai gerakan.
 *  4. Jari diangkat: UP disuntikkan, jendela bisa disentuh lagi.
 *
 * Bila titik suntik hampir keluar layar, jari suntik diangkat dan diletakkan lagi di titik awal
 * (kamera game tetap berlanjut). Semua dipanggil dari thread utama.
 */
class SensiOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val screenSize: () -> Pair<Int, Int>,
    private val onUnsupported: () -> Unit,
) {
    private class Entry(val view: ZoneView, val lp: WindowManager.LayoutParams, val homeX: Int, val homeY: Int)

    private val entries = ArrayList<Entry>()
    private val main = Handler(Looper.getMainLooper())
    private var signature = ""
    private var strikes = 0

    var screenW = 0f
        private set
    var screenH = 0f
        private set

    /** Pasang atau lepas jendela zona sesuai [want]. Aman dipanggil berulang. */
    fun apply(want: Boolean, data: SensiData) {
        if (!want || data.zones.isEmpty()) {
            hide()
            return
        }
        val (w, h) = screenSize()
        val sig = "${w}x$h|" + data.zones.joinToString(";") {
            "${it.id},${it.cx},${it.cy},${it.sizeDp},${data.xOf(it)},${data.yOf(it)}"
        }
        if (sig == signature && entries.isNotEmpty()) return
        // Jangan memutus gestur yang sedang berjalan. Dicoba lagi pada pemanggilan berikutnya.
        if (entries.any { it.view.busy }) return

        hide()
        screenW = w.toFloat()
        screenH = h.toFloat()
        val density = ctx.resources.displayMetrics.density
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        for (z in data.zones) {
            val size = (z.sizeDp * density).roundToInt()
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            ).also {
                it.gravity = Gravity.TOP or Gravity.START
                it.x = (z.cx * w - size / 2f).roundToInt()
                it.y = (z.cy * h - size / 2f).roundToInt()
                // Hampir tak terlihat. Android 12+ menolak sentuhan yang melewati overlay yang
                // jumlah opasitasnya di atas 0,8, jadi nilainya dibuat sangat kecil.
                it.alpha = 0.01f
                if (Build.VERSION.SDK_INT >= 30) {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= 28) {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val view = ZoneView(ctx, this)
            view.sx = data.xOf(z)
            view.sy = data.yOf(z)
            try {
                wm.addView(view, lp)
                entries += Entry(view, lp, lp.x, lp.y)
            } catch (e: Exception) {
                // Izin overlay dicabut. Service yang menangani.
            }
        }
        signature = sig
    }

    /** Melepas semua jendela zona. Jari suntik yang masih turun diangkat dulu. */
    fun hide() {
        for (e in entries) {
            e.view.release()
            try {
                wm.removeView(e.view)
            } catch (ex: Exception) {
                // sudah dilepas
            }
        }
        entries.clear()
        signature = ""
    }

    // ---------- dipanggil ZoneView ----------

    /**
     * Menyingkirkan jendela zona dari bawah jari ([away] = true) atau mengembalikannya.
     *
     * Jendela digeser ke luar layar, bukan diberi FLAG_NOT_TOUCHABLE. Mengubah flag itu di tengah
     * gestur membuat sistem bisa memutus aliran gerakan jari asli ke jendela ini, sehingga tombol
     * tertekan tapi seretannya tidak pernah sampai. Menggeser jendela tidak memutus gestur (cara yang
     * sama dipakai gelembung chat yang diseret), dan sentuhan suntikan jatuh ke game di bawahnya.
     */
    fun setAway(v: ZoneView, away: Boolean) {
        val e = entries.firstOrNull { it.view === v } ?: return
        if (away) {
            e.lp.x = -e.lp.width - 400
            e.lp.y = e.homeY
        } else {
            e.lp.x = e.homeX
            e.lp.y = e.homeY
        }
        try {
            wm.updateViewLayout(v, e.lp)
        } catch (ex: Exception) {
            // jendela sudah dilepas
        }
    }

    fun inject(action: Int, x: Float, y: Float, downTime: Long, deviceId: Int): Boolean {
        val ok = Shell.inject(action, INJECT_ID, x, y, downTime, SystemClock.uptimeMillis(), injectDeviceId(deviceId))
        if (!ok) {
            // UserService hilang: lepas semua zona supaya sentuhan tidak tertelan tanpa diteruskan.
            main.post { hide() }
        }
        return ok
    }

    fun strike() {
        strikes++
        if (strikes >= MAX_STRIKES) {
            main.post {
                hide()
                onUnsupported()
            }
        }
    }

    fun resetStrikes() {
        strikes = 0
    }
}

/** Satu zona. Lihat penjelasan alur di [SensiOverlay]. */
@SuppressLint("ViewConstructor")
class ZoneView(ctx: Context, private val host: SensiOverlay) : View(ctx) {

    var sx = 1f
    var sy = 1f

    private enum class S { IDLE, WAIT, ACTIVE }

    private var state = S.IDLE
    private var trackedId = -1
    private var deviceId = 0

    private var curX = 0f
    private var curY = 0f
    private var prevX = 0f
    private var prevY = 0f
    private var downX = 0f
    private var downY = 0f
    private var ipX = 0f
    private var ipY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var injDownTime = 0L
    private var realDownAt = 0L
    private var pendingUp = false
    private var pendingCancel = false

    val busy: Boolean get() = state != S.IDLE

    private val activate = Runnable { onActivate() }

    // Koordinat layar untuk pointer ke-i. rawX milik pointer pertama dipakai untuk mencari posisi jendela.
    private fun screenX(e: MotionEvent, i: Int) = e.getX(i) + (e.rawX - e.getX(0))
    private fun screenY(e: MotionEvent, i: Int) = e.getY(i) + (e.rawY - e.getY(0))

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (state == S.IDLE) begin(e)
            MotionEvent.ACTION_MOVE -> if (state != S.IDLE) {
                val i = e.findPointerIndex(trackedId)
                if (i >= 0) move(screenX(e, i), screenY(e, i))
            }
            MotionEvent.ACTION_POINTER_UP -> if (state != S.IDLE && e.getPointerId(e.actionIndex) == trackedId) finish(false)
            MotionEvent.ACTION_UP -> if (state != S.IDLE) finish(false)
            MotionEvent.ACTION_CANCEL -> if (state != S.IDLE) finish(true)
        }
        return true
    }

    private fun begin(e: MotionEvent) {
        trackedId = e.getPointerId(0)
        deviceId = e.deviceId
        val x = screenX(e, 0)
        val y = screenY(e, 0)
        downX = x
        downY = y
        curX = x
        curY = y
        prevX = x
        prevY = y
        anchorX = SensiMath.clampX(x, host.screenW)
        anchorY = SensiMath.clampY(y, host.screenH)
        ipX = anchorX
        ipY = anchorY
        realDownAt = SystemClock.uptimeMillis()
        pendingUp = false
        pendingCancel = false
        state = S.WAIT
        // Singkirkan jendela ini dari bawah jari supaya sentuhan suntikan langsung jatuh ke game.
        host.setAway(this, true)
        postDelayed(activate, ACTIVATE_DELAY_MS)
    }

    private fun onActivate() {
        if (state != S.WAIT) return
        injDownTime = SystemClock.uptimeMillis()
        if (!host.inject(MotionEvent.ACTION_DOWN, ipX, ipY, injDownTime, deviceId)) {
            state = S.IDLE
            return
        }
        state = S.ACTIVE
        // Gerakan yang sudah terjadi selama menunggu.
        if (curX != downX || curY != downY) {
            ipX = SensiMath.clampX(anchorX + (curX - downX) * sx, host.screenW)
            ipY = SensiMath.clampY(anchorY + (curY - downY) * sy, host.screenH)
            host.inject(MotionEvent.ACTION_MOVE, ipX, ipY, injDownTime, deviceId)
        }
        prevX = curX
        prevY = curY
        if (pendingUp) finish(pendingCancel)
    }

    private fun move(x: Float, y: Float) {
        curX = x
        curY = y
        if (state != S.ACTIVE) return
        val dx = x - prevX
        val dy = y - prevY
        if (dx == 0f && dy == 0f) return
        prevX = x
        prevY = y

        val nx = ipX + dx * sx
        val ny = ipY + dy * sy
        if (SensiMath.inside(nx, ny, host.screenW, host.screenH)) {
            ipX = nx
            ipY = ny
            host.inject(MotionEvent.ACTION_MOVE, ipX, ipY, injDownTime, deviceId)
        } else {
            // Titik suntik hampir keluar layar: angkat, letakkan lagi di titik awal, lalu lanjutkan.
            host.inject(MotionEvent.ACTION_UP, ipX, ipY, injDownTime, deviceId)
            ipX = anchorX
            ipY = anchorY
            injDownTime = SystemClock.uptimeMillis()
            host.inject(MotionEvent.ACTION_DOWN, ipX, ipY, injDownTime, deviceId)
            ipX = SensiMath.clampX(ipX + dx * sx, host.screenW)
            ipY = SensiMath.clampY(ipY + dy * sy, host.screenH)
            host.inject(MotionEvent.ACTION_MOVE, ipX, ipY, injDownTime, deviceId)
        }
    }

    private fun finish(cancel: Boolean) {
        if (state == S.WAIT) {
            // Jari sudah diangkat sebelum suntikan dimulai (ketukan cepat). Selesaikan setelah DOWN.
            pendingUp = true
            pendingCancel = cancel
            if (cancel && SystemClock.uptimeMillis() - realDownAt < EARLY_CANCEL_MS) host.strike()
            return
        }
        if (cancel && SystemClock.uptimeMillis() - realDownAt < EARLY_CANCEL_MS) {
            host.strike()
        } else if (!cancel) {
            host.resetStrikes()
        }
        host.inject(MotionEvent.ACTION_UP, ipX, ipY, injDownTime, deviceId)
        reset()
    }

    private fun reset() {
        state = S.IDLE
        trackedId = -1
        removeCallbacks(activate)
        host.setAway(this, false)
    }

    /** Dipanggil saat jendela dilepas: angkat jari suntik bila masih turun. */
    fun release() {
        removeCallbacks(activate)
        if (state == S.ACTIVE) {
            Shell.inject(MotionEvent.ACTION_UP, INJECT_ID, ipX, ipY, injDownTime, SystemClock.uptimeMillis(), injectDeviceId(deviceId))
        }
        state = S.IDLE
        trackedId = -1
    }
}
