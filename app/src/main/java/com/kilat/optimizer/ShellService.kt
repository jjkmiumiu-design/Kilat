package com.kilat.optimizer

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

    private val injector = TouchInjector()

    override fun canInject(): Boolean = injector.canInject()

    override fun injectStatus(): String = injector.status()

    override fun injectTouch(action: Int, pointerId: Int, x: Float, y: Float, downTime: Long, eventTime: Long, deviceId: Int) {
        injector.touch(action, pointerId, x, y, downTime, eventTime, deviceId)
    }
}
