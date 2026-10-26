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

/** ID jari untuk ketukan aksi tap, berbeda dari jari geser zona. */
private const val TAP_ID = 21
private const val DOUBLE_GAP_MS = 60L

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

/** Jari mentah dianggap milik gestur zona bila posisinya sedekat ini (piksel) dari titik sentuh jendela. */
private const val RAW_BIND_PX = 160f

/** Gerakan jari mentah (piksel) tanpa kabar dari jendela sebelum jalur mentah mengambil alih. */
private const val RAW_TAKEOVER_PX = 24f

/** Setelah jendela dibatalkan: batas menunggu jari mentah terikat, dan batas keamanan lama gestur (ms). */
private const val DEAD_BIND_MS = 400L
private const val DEAD_MAX_MS = 30_000L

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
    private val raw = RawTouch(main, wm, screenSize)
    private var signature = ""
    private var strikes = 0

    /** Mode uji tersembunyi (tahan lama tulisan "Injeksi sentuh" di panel). Menampilkan toast diagnosis tiap gestur. */
    var debugOn = false
    var onDebug: ((String) -> Unit)? = null

    fun debug(msg: String) {
        if (debugOn) onDebug?.invoke(msg)
    }

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
            "${it.id},${it.cx},${it.cy},${it.sizeDp},${data.xOf(it)},${data.yOf(it)},${it.tap},${it.tx},${it.ty},${it.repeatMs},${it.holdMs}"
        }
        if (sig == signature && entries.isNotEmpty()) {
            maybeRestartRaw()
            return
        }
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
            view.tapMode = z.tap
            view.tapX = z.tx * w
            view.tapY = z.ty * h
            view.tapRepeatMs = z.repeatMs.toLong()
            view.tapHoldMs = z.holdMs.toLong()
            try {
                wm.addView(view, lp)
                entries += Entry(view, lp, lp.x, lp.y)
            } catch (e: Exception) {
                // Izin overlay dicabut. Service yang menangani.
            }
        }
        signature = sig
        if (entries.isNotEmpty()) startRaw()
    }

    private var rawStartedAt = 0L

    private fun startRaw() {
        rawStartedAt = SystemClock.uptimeMillis()
        raw.listener = { pts -> for (e in entries) e.view.onRaw(pts) }
        raw.start()
    }

    /** Bila pembacaan mentah berhenti sendiri, nyalakan lagi (paling cepat tiap 10 detik). */
    private fun maybeRestartRaw() {
        val st = raw.status
        if ((st == "berhenti" || st.startsWith("galat")) && SystemClock.uptimeMillis() - rawStartedAt > 10_000) startRaw()
    }

    /** Status pembacaan mentah, untuk mode uji. */
    val rawStatus: String get() = raw.status

    /** true bila pembacaan mentah layar sentuh sedang jalan. */
    val rawActive: Boolean get() = raw.status == "aktif"

    /** Melepas semua jendela zona. Jari suntik yang masih turun diangkat dulu. */
    fun hide() {
        raw.listener = null
        raw.stop()
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

    /**
     * Satu ketukan singkat di (x, y) sebagai jari terpisah (id [TAP_ID], perangkat 0), tanpa mengganggu
     * jari zona yang sedang turun. Dilepas otomatis setelah [holdMs] milidetik.
     */
    fun tap(x: Float, y: Float, deviceId: Int, holdMs: Long) {
        val dev = injectDeviceId(deviceId)
        val t = SystemClock.uptimeMillis()
        if (!Shell.inject(MotionEvent.ACTION_DOWN, TAP_ID, x, y, t, t, dev)) return
        main.postDelayed({
            Shell.inject(MotionEvent.ACTION_UP, TAP_ID, x, y, t, SystemClock.uptimeMillis(), dev)
        }, holdMs)
    }

    /** Dipanggil sebelum jari suntik pertama: lupakan sisa jari lama di penyuntik, kecuali zona lain sedang aktif. */
    fun resetInjectState(self: ZoneView, deviceId: Int) {
        if (entries.any { it.view !== self && it.view.busy }) return
        Shell.inject(TouchInjector.ACTION_RESET, 0, 0f, 0f, 0L, SystemClock.uptimeMillis(), injectDeviceId(deviceId))
    }

    /** Ringkasan keadaan zona untuk mode uji. */
    fun statusLine(): String = "zona terpasang ${entries.size} | suntik ${Shell.injectReady} | mentah ${raw.status}"

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

    // Aksi tap (lihat SensiPrefs.TAP_*). Koordinat dalam piksel layar.
    var tapMode = 0
    var tapX = 0f
    var tapY = 0f
    var tapRepeatMs = 100L
    var tapHoldMs = 30L

    private val repeatRun = object : Runnable {
        override fun run() {
            if (state != S.ACTIVE) return
            host.tap(tapX, tapY, deviceId, tapHoldMs)
            // Jarak tidak boleh lebih pendek dari lama tekan, kalau tidak ketukan saling menumpuk.
            postDelayed(this, maxOf(tapRepeatMs, tapHoldMs + 2))
        }
    }

    private fun startTap() {
        if (tapMode != SensiPrefs.TAP_OFF) host.debug("Aksi tap ${SensiPrefs.TAP_NAMES[tapMode]} di (${tapX.toInt()}, ${tapY.toInt()}), tekan ${tapHoldMs} ms")
        when (tapMode) {
            SensiPrefs.TAP_ONCE -> host.tap(tapX, tapY, deviceId, tapHoldMs)
            SensiPrefs.TAP_DOUBLE -> {
                host.tap(tapX, tapY, deviceId, tapHoldMs)
                postDelayed({ host.tap(tapX, tapY, deviceId, tapHoldMs) }, tapHoldMs + DOUBLE_GAP_MS)
            }
            SensiPrefs.TAP_REPEAT -> {
                host.tap(tapX, tapY, deviceId, tapHoldMs)
                postDelayed(repeatRun, maxOf(tapRepeatMs, tapHoldMs + 2))
            }
        }
    }

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

    // Jendela sudah dibatalkan sistem tapi jari masih di layar; gerakan diikuti dari jalur mentah.
    private var winDead = false
    private val deadCheck = Runnable { if (winDead && state != S.IDLE && rawId < 0) finish(true) }
    private val deadCap = Runnable { if (winDead && state != S.IDLE) finish(true) }

    // Jalur cadangan: posisi jari asli dari pembacaan mentah layar sentuh. Dipakai bila sistem
    // berhenti mengirim gerakan jari asli ke jendela ini setelah sentuhan suntikan masuk.
    private var rawId = -1
    private var rawSeen = 0
    private var useRaw = false
    private var rawBindX = 0f
    private var rawBindY = 0f

    // Penghitung untuk mode uji.
    private var dbgMoves = 0
    private var dbgAfter = 0
    private var dbgInj = 0
    private var dbgAnchor = 0
    private val snap = Runnable {
        host.debug("1 dtk setelah suntik: gerak jari asli yang diterima=$dbgAfter, gerak disuntik=$dbgInj")
    }

    // Koordinat layar untuk pointer ke-i. rawX milik pointer pertama dipakai untuk mencari posisi jendela.
    private fun screenX(e: MotionEvent, i: Int) = e.getX(i) + (e.rawX - e.getX(0))
    private fun screenY(e: MotionEvent, i: Int) = e.getY(i) + (e.rawY - e.getY(0))

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // ACTION_DOWN hanya datang bila tidak ada jari lain di jendela ini, jadi gestur sebelumnya pasti
                // sudah selesai. Bila statusnya masih tersangkut (mis. UP tidak pernah sampai), akhiri dulu.
                if (state != S.IDLE) forceEnd()
                host.debug("Zona tersentuh di (${e.rawX.toInt()}, ${e.rawY.toInt()})")
                begin(e)
            }
            MotionEvent.ACTION_MOVE -> if (state != S.IDLE) {
                dbgMoves++
                if (state == S.ACTIVE) dbgAfter++
                val i = e.findPointerIndex(trackedId)
                if (i >= 0 && !useRaw) move(screenX(e, i), screenY(e, i))
            }
            MotionEvent.ACTION_POINTER_UP -> if (state != S.IDLE && e.getPointerId(e.actionIndex) == trackedId) finish(false)
            MotionEvent.ACTION_UP -> if (state != S.IDLE) finish(false)
            MotionEvent.ACTION_CANCEL -> if (state != S.IDLE) {
                // Sistem yang hanya punya satu keadaan sentuh membatalkan gestur asli begitu sentuhan
                // suntikan masuk. Jari di layar belum tentu terangkat: kalau pembacaan mentah jalan,
                // lanjutkan dari sana dan akhiri gestur saat jari mentah terangkat.
                if (state == S.ACTIVE && !winDead && host.rawActive) {
                    winDead = true
                    useRaw = true
                    rawId = -1
                    rawSeen = 0
                    removeCallbacks(deadCheck)
                    postDelayed(deadCheck, DEAD_BIND_MS)
                    postDelayed(deadCap, DEAD_MAX_MS)
                } else if (!winDead) finish(true)
            }
        }
        return true
    }

    /** Mengakhiri gestur yang tersangkut tanpa menghitungnya sebagai pembatalan dini. */
    private fun forceEnd() {
        removeCallbacks(activate)
        if (state == S.ACTIVE) host.inject(MotionEvent.ACTION_UP, ipX, ipY, injDownTime, deviceId)
        reset()
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
        dbgMoves = 0
        dbgAfter = 0
        dbgInj = 0
        dbgAnchor = 0
        rawId = -1
        rawSeen = 0
        useRaw = false
        winDead = false
        state = S.WAIT
        // Singkirkan jendela ini dari bawah jari supaya sentuhan suntikan langsung jatuh ke game.
        host.setAway(this, true)
        postDelayed(activate, ACTIVATE_DELAY_MS)
    }

    private fun onActivate() {
        if (state != S.WAIT) return
        host.resetInjectState(this, deviceId)
        injDownTime = SystemClock.uptimeMillis()
        if (!host.inject(MotionEvent.ACTION_DOWN, ipX, ipY, injDownTime, deviceId)) {
            host.debug("Suntik DOWN GAGAL: jalur suntik tidak merespons")
            state = S.IDLE
            host.setAway(this, false)
            return
        }
        state = S.ACTIVE
        startTap()
        if (host.debugOn) postDelayed(snap, 1000)
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

    /** Dari pembacaan mentah layar sentuh (thread utama). Lihat penjelasan jalur cadangan di atas. */
    fun onRaw(pts: List<RawPt>) {
        if (state == S.IDLE) return
        if (rawId == -1) {
            var best: RawPt? = null
            var bestD = RAW_BIND_PX * RAW_BIND_PX
            for (p in pts) {
                val dx = p.x - (if (winDead) curX else downX)
                val dy = p.y - (if (winDead) curY else downY)
                val d = dx * dx + dy * dy
                if (d <= bestD) {
                    bestD = d
                    best = p
                }
            }
            val b = best ?: return
            rawId = b.id
            rawBindX = b.x
            rawBindY = b.y
            if (winDead) {
                prevX = b.x
                prevY = b.y
                return
            }
        }
        val me = pts.firstOrNull { it.id == rawId }
        if (me == null) {
            // Jari asli terangkat. Jendela mungkin tidak pernah diberi tahu (sistem berhenti meneruskan
            // sentuhan asli setelah suntikan masuk), jadi akhiri gestur dari sini. Baru dipercaya setelah
            // jari itu terbaca stabil di dua frame, supaya salah baca tidak memutus gestur.
            if (useRaw || rawSeen >= 2) finish(false)
            return
        }
        rawSeen++
        if (!useRaw) {
            if (state != S.ACTIVE || dbgAfter > 0) return
            val dx = me.x - rawBindX
            val dy = me.y - rawBindY
            if (dx * dx + dy * dy < RAW_TAKEOVER_PX * RAW_TAKEOVER_PX) return
            // Jari sudah bergerak tetapi jendela diam: ambil alih dari titik awal jari mentah.
            useRaw = true
            prevX = rawBindX
            prevY = rawBindY
        }
        move(me.x, me.y)
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
            dbgInj++
            host.inject(MotionEvent.ACTION_MOVE, ipX, ipY, injDownTime, deviceId)
        } else {
            dbgAnchor++
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
        removeCallbacks(repeatRun)
        if (tapMode == SensiPrefs.TAP_RELEASE) host.tap(tapX, tapY, deviceId, tapHoldMs)
        host.debug(
            "A${Build.VERSION.SDK_INT} dev ${deviceId}>${injectDeviceId(deviceId)} | " +
                (if (cancel) "AKHIR=CANCEL" else "AKHIR=UP") +
                " ${SystemClock.uptimeMillis() - realDownAt}ms | gerak asli=$dbgMoves (sesudah suntik $dbgAfter) | disuntik=$dbgInj, pasang ulang=$dbgAnchor | mentah: ${host.rawStatus}, ${if (rawId < 0) "tidak terikat" else if (useRaw) "DIPAKAI" else "terikat"}"
        )
        reset()
    }

    private fun reset() {
        state = S.IDLE
        trackedId = -1
        removeCallbacks(activate)
        removeCallbacks(snap)
        removeCallbacks(repeatRun)
        removeCallbacks(deadCheck)
        removeCallbacks(deadCap)
        winDead = false
        rawId = -1
        useRaw = false
        host.setAway(this, false)
    }

    /** Dipanggil saat jendela dilepas: angkat jari suntik bila masih turun. */
    fun release() {
        removeCallbacks(activate)
        removeCallbacks(snap)
        removeCallbacks(repeatRun)
        if (state == S.ACTIVE) {
            Shell.inject(MotionEvent.ACTION_UP, INJECT_ID, ipX, ipY, injDownTime, SystemClock.uptimeMillis(), injectDeviceId(deviceId))
        }
        state = S.IDLE
        trackedId = -1
    }
}
