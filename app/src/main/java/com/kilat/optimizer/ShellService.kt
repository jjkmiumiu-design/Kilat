package com.kilat.optimizer

import android.os.IBinder
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import kotlin.system.exitProcess

/**
 * Berjalan di proses terpisah milik Shizuku dengan hak akses "shell" (uid 2000).
 * Di sinilah perintah seperti `settings put` dan `pm disable-user` dieksekusi,
 * dan sentuhan disuntikkan untuk zona sensitivitas.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output.trim()
    }

    // ---------- suntik sentuh ----------

    /** Fungsi yang memasukkan event ke sistem input, atau null bila tidak ada jalur yang bisa dipakai. */
    private val injector: ((InputEvent) -> Boolean)? by lazy { buildInjector() }

    private fun buildInjector(): ((InputEvent) -> Boolean)? {
        // Jalur 1: InputManager.getInstance().injectInputEvent(event, mode). Mode 0 = async.
        try {
            val cls = Class.forName("android.hardware.input.InputManager")
            val im = cls.getDeclaredMethod("getInstance").invoke(null)
            val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            return { ev: InputEvent -> (m.invoke(im, ev, 0) as? Boolean) ?: false }
        } catch (e: Throwable) {
            // lanjut ke jalur 2
        }
        // Jalur 2: langsung ke layanan "input" lewat ServiceManager.
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val svc = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val m = svc.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            return { ev: InputEvent -> (m.invoke(svc, ev, 0) as? Boolean) ?: false }
        } catch (e: Throwable) {
            return null
        }
    }

    override fun canInject(): Boolean = injector != null

    override fun injectTouch(action: Int, pointerId: Int, x: Float, y: Float, downTime: Long, eventTime: Long, deviceId: Int) {
        val inject = injector ?: return
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        )
        val ev = MotionEvent.obtain(
            downTime, eventTime, action, 1, props, coords,
            0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            inject(ev)
        } catch (e: Throwable) {
            // Satu event gagal tidak boleh mematikan layanan.
        } finally {
            ev.recycle()
        }
    }
}
