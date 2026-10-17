package com.kilat.optimizer

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Proses penyuntik sentuh mandiri, dijalankan Shizuku sebagai uid shell lewat newProcess:
 * `app_process -Djava.class.path=<apk> /system/bin com.kilat.optimizer.InjectDaemon`.
 * Dipakai bila UserService Shizuku tidak mau tersambung. Tidak butuh binder: perintah dibaca dari stdin.
 *
 * Protokol (satu baris per perintah):
 *   keluar pertama : "OK|<jalur>" atau "ERR|<alasan>"
 *   masuk          : "T <action> <pointerId> <x> <y> <downTime> <eventTime> <deviceId>"
 *   masuk          : "Q" untuk berhenti. Bila stdin tertutup (aplikasi mati), proses ikut berhenti.
 */
object InjectDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        val inj = TouchInjector()
        inj.canInject()
        println(inj.status())
        System.out.flush()
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            if (line == "Q") break
            if (!line.startsWith("T ")) continue
            try {
                val p = line.split(' ')
                inj.touch(
                    p[1].toInt(), p[2].toInt(), p[3].toFloat(), p[4].toFloat(),
                    p[5].toLong(), p[6].toLong(), p[7].toInt(),
                )
            } catch (e: Throwable) {
                // baris rusak diabaikan
            }
        }
        exitProcess(0)
    }
}
