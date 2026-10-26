package com.kilat.optimizer

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Logika suntik sentuh yang berjalan di proses ber-uid shell (UserService Shizuku atau [InjectDaemon]).
 * Dipakai bersama oleh kedua jalur supaya perilakunya sama.
 */
class TouchInjector {

    private class Injector(val name: String, val fn: (InputEvent) -> Boolean)

    @Volatile private var injector: Injector? = null
    @Volatile private var status: String = ""
    private val lock = Any()
    private var setDisplayId: Method? = null

    /** Membuka bungkus InvocationTargetException supaya pesan aslinya (mis. SecurityException) terbaca. */
    private fun root(t: Throwable): Throwable {
        var e = t
        while (e is InvocationTargetException && e.targetException != null) e = e.targetException
        return e
    }

    private fun describe(t: Throwable): String {
        val r = root(t)
        return "${r.javaClass.simpleName}: ${r.message ?: "-"}".take(160)
    }

    /** Memanggil method injectInputEvent pada [target]. Mendukung bentuk (event, mode) dan (event, mode, targetUid). */
    private fun methodInjector(name: String, target: Any, cls: Class<*>): Injector? {
        val all = cls.methods.filter {
            (it.name == "injectInputEvent" || it.name == "injectInputEventToTarget") &&
                it.parameterTypes.isNotEmpty() && InputEvent::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        // Utamakan bentuk 2 parameter, lalu 3 parameter (targetUid = -1: semua).
        val m = all.firstOrNull { it.parameterTypes.size == 2 && it.name == "injectInputEvent" }
            ?: all.firstOrNull { it.parameterTypes.size == 3 && it.name == "injectInputEventToTarget" }
            ?: all.firstOrNull { it.parameterTypes.size == 3 }
            ?: return null
        m.isAccessible = true
        val three = m.parameterTypes.size == 3
        return Injector(name) { ev ->
            val r = if (three) m.invoke(target, ev, 0, -1) else m.invoke(target, ev, 0)
            (r as? Boolean) ?: true
        }
    }

    private fun candidates(errors: MutableList<String>): List<Injector> {
        val out = ArrayList<Injector>()
        fun attempt(label: String, block: () -> Injector?) {
            try {
                val inj = block()
                if (inj != null) out.add(inj) else errors.add("$label: method tidak ditemukan")
            } catch (t: Throwable) {
                errors.add("$label: ${describe(t)}")
            }
        }
        // Android 14+: InputManagerGlobal (jalur yang dipakai scrcpy).
        attempt("InputManagerGlobal") {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val inst = cls.getMethod("getInstance").invoke(null)
            methodInjector("InputManagerGlobal", inst!!, cls)
        }
        // Android 13 ke bawah: InputManager.getInstance().
        attempt("InputManager") {
            val cls = Class.forName("android.hardware.input.InputManager")
            val gi = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }
            methodInjector("InputManager", gi.invoke(null)!!, cls)
        }
        // Langsung ke layanan "input". Method dicari lewat interface IInputManager (publik),
        // bukan kelas Proxy-nya yang privat, kalau tidak invoke() ditolak IllegalAccessException.
        attempt("IInputManager") {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as? IBinder
                ?: error("layanan input tidak ditemukan")
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val svc = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            methodInjector("IInputManager", svc, Class.forName("android.hardware.input.IInputManager"))
        }
        return out
    }

    /** Mengirim event uji yang tidak berefek (tombol KEYCODE_UNKNOWN turun lalu naik). true bila sistem menerimanya. */
    private fun selfTest(inj: Injector): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN, 0)
        return inj.fn(down) && inj.fn(up)
    }

    private fun resolve() {
        synchronized(lock) {
            if (injector != null) return
            val errors = ArrayList<String>()
            for (c in candidates(errors)) {
                try {
                    if (selfTest(c)) {
                        injector = c
                        status = "OK|${c.name}"
                        try {
                            setDisplayId = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                        } catch (t: Throwable) {
                            setDisplayId = null
                        }
                        return
                    }
                    errors.add("${c.name}: sistem menolak event (INJECT_EVENTS?)")
                } catch (t: Throwable) {
                    errors.add("${c.name}: ${describe(t)}")
                }
            }
            status = "ERR|" + errors.joinToString(" | ").ifEmpty { "tidak ada jalur suntik" }.take(300)
        }
    }

    fun canInject(): Boolean {
        if (injector == null) resolve()
        return injector != null
    }

    fun status(): String {
        if (injector == null) resolve()
        return status
    }

    /** Jari yang sedang turun pada satu perangkat. Urutan = indeks pointer di MotionEvent. */
    private class Dev {
        val ids = ArrayList<Int>()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        var downTime = 0L
        var lastAt = 0L

        fun clear() {
            ids.clear(); xs.clear(); ys.clear()
        }

        fun indexOf(id: Int) = ids.indexOf(id)

        fun put(id: Int, x: Float, y: Float) {
            val i = ids.indexOf(id)
            if (i >= 0) {
                xs[i] = x; ys[i] = y
            } else {
                ids.add(id); xs.add(x); ys.add(y)
            }
        }

        fun removeAt(i: Int) {
            ids.removeAt(i); xs.removeAt(i); ys.removeAt(i)
        }
    }

    companion object {
        /** Perintah khusus: lupakan semua jari yang tercatat pada perangkat ini (tanpa mengirim event). */
        const val ACTION_RESET = -1
    }

    private val devs = HashMap<Int, Dev>()
    private val stateLock = Any()

    /**
     * Mengirim satu perubahan jari. Semua jari yang sedang turun pada perangkat yang sama digabung
     * dalam satu aliran MotionEvent (DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP), seperti jari asli.
     * Dengan begitu jari kedua (mis. ketukan aksi tap) tidak menggantikan atau membatalkan jari
     * pertama (geseran zona sensi), baik di Android yang melacak satu sentuhan per layar maupun per perangkat.
     */
    fun touch(action: Int, pointerId: Int, x: Float, y: Float, downTime: Long, eventTime: Long, deviceId: Int) {
        val inject = (injector ?: run { resolve(); injector })?.fn ?: return
        synchronized(stateLock) {
            val d = devs.getOrPut(deviceId) { Dev() }
            if (action == ACTION_RESET) {
                d.clear()
                return
            }
            val now = SystemClock.uptimeMillis()
            // Sisa keadaan lama (mis. UP tidak pernah datang): buang supaya gestur baru bersih.
            if (d.ids.isNotEmpty() && now - d.lastAt > 60_000L) d.clear()
            d.lastAt = now

            val act: Int
            var removeIndex = -1
            var clearAll = false
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    val old = d.indexOf(pointerId)
                    if (old >= 0) d.removeAt(old)
                    if (d.ids.isEmpty()) {
                        d.downTime = downTime
                        d.put(pointerId, x, y)
                        act = MotionEvent.ACTION_DOWN
                    } else {
                        d.put(pointerId, x, y)
                        act = MotionEvent.ACTION_POINTER_DOWN or (d.indexOf(pointerId) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (d.indexOf(pointerId) < 0) return
                    d.put(pointerId, x, y)
                    act = MotionEvent.ACTION_MOVE
                }
                MotionEvent.ACTION_UP -> {
                    val i = d.indexOf(pointerId)
                    if (i < 0) return
                    d.put(pointerId, x, y)
                    removeIndex = i
                    act = if (d.ids.size == 1) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (d.ids.isEmpty()) return
                    clearAll = true
                    act = MotionEvent.ACTION_CANCEL
                }
                else -> return
            }

            val n = d.ids.size
            val props = Array(n) { i ->
                MotionEvent.PointerProperties().apply {
                    id = d.ids[i]
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(n) { i ->
                MotionEvent.PointerCoords().apply {
                    this.x = d.xs[i]
                    this.y = d.ys[i]
                    pressure = 1f
                    size = 1f
                }
            }
            val ev = MotionEvent.obtain(
                d.downTime, eventTime, act, n, props, coords,
                0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            try {
                try {
                    setDisplayId?.invoke(ev, 0)
                } catch (t: Throwable) {
                    // tidak kritis
                }
                inject(ev)
            } catch (e: Throwable) {
                // Satu event gagal tidak boleh mematikan proses.
            } finally {
                ev.recycle()
            }
            if (clearAll) d.clear() else if (removeIndex >= 0) d.removeAt(removeIndex)
        }
    }
}
