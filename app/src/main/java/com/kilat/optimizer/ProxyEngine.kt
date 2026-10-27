package com.kilat.optimizer

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Zona sensi dalam piksel layar. [tap] memakai nilai SensiPrefs.TAP_*. */
class ProxyZone(
    val id: Int,
    val cx: Float, val cy: Float, val r: Float,
    val sx: Float, val sy: Float,
    val tap: Int, val tx: Float, val ty: Float,
    val repeatMs: Long, val holdMs: Long,
)

/** Tujuan semua sentuhan yang sudah diproses. Di perangkat: penyuntik sentuh. Di uji: perekam. */
interface TouchSink {
    fun touch(action: Int, id: Int, x: Float, y: Float, downTime: Long, eventTime: Long)
    fun now(): Long
    fun log(msg: String)

    /** Penanda zona mengikuti jari. [zoneId] = -1 berarti jari zona sudah diangkat. */
    fun follow(zoneId: Int, x: Float, y: Float)
}

/**
 * Otak mode proxy. Layar sentuh asli diambil alih (EVIOCGRAB), jadi game tidak pernah menerima sentuhan
 * asli. Engine ini membaca event mentahnya dan meneruskan SEMUA jari ke game sebagai satu aliran sentuhan:
 *  - jari yang mendarat di luar zona diteruskan apa adanya;
 *  - jari yang mendarat di dalam zona diteruskan dengan geseran dilebarkan (sx, sy), dan memicu Aksi tap.
 * Karena game hanya melihat satu aliran, tidak ada jari yang dibatalkan sistem.
 *
 * Murni Kotlin (tanpa API Android) supaya bisa diuji di komputer.
 */
class ProxyEngine(private val sink: TouchSink, private val sched: ScheduledExecutorService) {

    @Volatile var sw = 1080
    @Volatile var sh = 2400
    @Volatile var rot = 0
    @Volatile var maxX = 1
    @Volatile var maxY = 1
    @Volatile var zones: List<ProxyZone> = emptyList()

    private class Finger(val zone: ProxyZone?, x: Float, y: Float) {
        var curX = x          // posisi jari asli di layar
        var curY = y
        var anchorX = x       // posisi jari asli saat titik suntik terakhir diletakkan
        var anchorY = y
        val startX = x        // tempat jari suntik pertama kali diletakkan
        val startY = y
        var baseX = x         // posisi suntik saat jari asli ada di anchor
        var baseY = y
        var outX = x
        var outY = y
        var repeat: ScheduledFuture<*>? = null
        var lastFollow = 0L
    }

    private val tid = IntArray(SLOTS) { -1 }
    private val rx = IntArray(SLOTS)
    private val ry = IntArray(SLOTS)
    private var slot = 0
    private val fingers = arrayOfNulls<Finger>(SLOTS)
    private var downCount = 0
    private var gestureDown = 0L
    var debug = false

    private fun dlog(m: String) {
        if (debug) sink.log(m)
    }

    /** Satu event mentah dari layar sentuh. Dipanggil dari satu thread pembaca. */
    @Synchronized
    fun event(type: Int, code: Int, value: Int) {
        when (type) {
            EV_SYN -> when (code) {
                SYN_REPORT -> frame()
                SYN_DROPPED -> {
                    // Kernel membuang event: keadaan jari tidak bisa dipercaya lagi.
                    releaseAllLocked()
                    for (i in 0 until SLOTS) tid[i] = -1
                }
            }
            EV_ABS -> when (code) {
                ABS_MT_SLOT -> slot = if (value in 0 until SLOTS) value else -1
                ABS_MT_TRACKING_ID -> if (slot >= 0) tid[slot] = value
                ABS_MT_POSITION_X -> if (slot >= 0) rx[slot] = value
                ABS_MT_POSITION_Y -> if (slot >= 0) ry[slot] = value
            }
        }
    }

    @Synchronized
    fun releaseAll() = releaseAllLocked()

    private fun releaseAllLocked() {
        for (i in 0 until SLOTS) {
            val f = fingers[i] ?: continue
            f.repeat?.cancel(false)
            sink.touch(A_UP, i, f.outX, f.outY, gestureDown, sink.now())
            fingers[i] = null
        }
        downCount = 0
        sink.follow(-1, 0f, 0f)
    }

    private fun frame() {
        val w = sw
        val h = sh
        val r = rot
        for (i in 0 until SLOTS) {
            val f = fingers[i]
            if (tid[i] >= 0) {
                val (px, py) = RawMath.toScreen(rx[i], ry[i], maxX, maxY, w, h, r)
                if (f == null) down(i, px, py) else if (px != f.curX || py != f.curY) move(i, f, px, py, w, h)
            } else if (f != null) {
                up(i, f)
            }
        }
    }

    private fun down(i: Int, px: Float, py: Float) {
        val z = zones.firstOrNull {
            val dx = px - it.cx
            val dy = py - it.cy
            dx * dx + dy * dy <= it.r * it.r
        }
        val f = Finger(z, px, py)
        fingers[i] = f
        val t = sink.now()
        if (downCount == 0) gestureDown = t
        downCount++
        sink.touch(A_DOWN, i, px, py, gestureDown, t)
        if (z == null) {
            dlog("Jari ${i + 1} di (${px.toInt()}, ${py.toInt()}): luar zona, diteruskan")
        } else {
            dlog("Jari ${i + 1} di (${px.toInt()}, ${py.toInt()}): masuk zona ${z.id}")
            sink.follow(z.id, px, py)
            startTap(f, z)
        }
    }

    private fun move(i: Int, f: Finger, px: Float, py: Float, w: Int, h: Int) {
        f.curX = px
        f.curY = py
        val z = f.zone
        if (z == null) {
            f.outX = px
            f.outY = py
            sink.touch(A_MOVE, i, px, py, gestureDown, sink.now())
            return
        }
        var ox = f.baseX + (px - f.anchorX) * z.sx
        var oy = f.baseY + (py - f.anchorY) * z.sy
        if (z.sx <= 0f || z.sy <= 0f) return
        // Titik suntik menyentuh tepi layar: tahan di tepi (jari suntik tidak diangkat, tombol tetap ditekan).
        // Titik acuan ikut digeser supaya saat jari berbalik arah, geseran langsung bereaksi tanpa jarak mati.
        val maxX = w - MARGIN
        val maxY = h - MARGIN
        if (ox < MARGIN) { ox = MARGIN; f.anchorX = px - (ox - f.baseX) / z.sx }
        else if (ox > maxX) { ox = maxX; f.anchorX = px - (ox - f.baseX) / z.sx }
        if (oy < MARGIN) { oy = MARGIN; f.anchorY = py - (oy - f.baseY) / z.sy }
        else if (oy > maxY) { oy = maxY; f.anchorY = py - (oy - f.baseY) / z.sy }
        f.outX = ox
        f.outY = oy
        val t = sink.now()
        sink.touch(A_MOVE, i, ox, oy, gestureDown, t)
        if (t - f.lastFollow >= FOLLOW_MS) {
            f.lastFollow = t
            sink.follow(z.id, px, py)
        }
    }

    private fun up(i: Int, f: Finger) {
        f.repeat?.cancel(false)
        sink.touch(A_UP, i, f.outX, f.outY, gestureDown, sink.now())
        fingers[i] = null
        downCount--
        val z = f.zone
        if (z != null) {
            dlog("Jari ${i + 1} lepas dari zona ${z.id}")
            if (z.tap == SensiPrefs.TAP_RELEASE) tapAt(z)
            if (fingers.none { it?.zone != null }) sink.follow(-1, 0f, 0f)
        }
    }

    // ---------- Aksi tap ----------

    private fun tapAt(z: ProxyZone) {
        val t = sink.now()
        sink.touch(A_DOWN, TAP_ID, z.tx, z.ty, t, t)
        sched.schedule({ sink.touch(A_UP, TAP_ID, z.tx, z.ty, t, sink.now()) }, z.holdMs, TimeUnit.MILLISECONDS)
    }

    private fun startTap(f: Finger, z: ProxyZone) {
        if (z.tap != SensiPrefs.TAP_OFF) {
            dlog("Aksi tap ${SensiPrefs.TAP_NAMES.getOrElse(z.tap) { "?" }} di (${z.tx.toInt()}, ${z.ty.toInt()}), tekan ${z.holdMs} ms")
        }
        when (z.tap) {
            SensiPrefs.TAP_ONCE -> tapAt(z)
            SensiPrefs.TAP_DOUBLE -> {
                tapAt(z)
                sched.schedule({ tapAt(z) }, z.holdMs + DOUBLE_GAP_MS, TimeUnit.MILLISECONDS)
            }
            SensiPrefs.TAP_REPEAT -> {
                tapAt(z)
                // Jarak tidak boleh lebih pendek dari lama tekan, kalau tidak ketukan saling menumpuk.
                val every = maxOf(z.repeatMs, z.holdMs + 2)
                f.repeat = sched.scheduleAtFixedRate({ tapAt(z) }, every, every, TimeUnit.MILLISECONDS)
            }
        }
    }

    companion object {
        const val SLOTS = 16
        const val TAP_ID = 30
        const val A_DOWN = 0
        const val A_UP = 1
        const val A_MOVE = 2
        const val MARGIN = 12f
        const val DOUBLE_GAP_MS = 60L
        const val FOLLOW_MS = 33L

        const val EV_SYN = 0
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val SYN_DROPPED = 3
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_TRACKING_ID = 0x39
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
    }
}
