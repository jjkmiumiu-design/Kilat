package com.kilat.optimizer

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Proses proxy sentuh, dijalankan Shizuku sebagai uid shell:
 * `app_process -Djava.class.path=<apk> /system/bin com.kilat.optimizer.ProxyDaemon`.
 *
 * Tugasnya: menjalankan `kgrab` (mengambil alih layar sentuh asli), membaca event mentahnya, lalu
 * meneruskan semua jari ke game lewat [TouchInjector] sambil menjalankan logika zona ([ProxyEngine]).
 *
 * Protokol, satu baris per pesan.
 *   keluar : "OK|<jalur suntik>" atau "ERR|<alasan>" (baris pertama)
 *            "R <teks>" info, "E <teks>" galat (grab dilepas), "L <teks>" log uji,
 *            "F <idZona> <x> <y>" posisi jari zona untuk penanda (idZona -1 = lepas)
 *   masuk  : "S <lebar> <tinggi> <rotasi>"   ukuran layar saat ini
 *            "Z <id,cx,cy,r,sx,sy,tap,tx,ty,rep,hold;...>"  daftar zona (piksel), kosong = tanpa zona
 *            "D <0|1>"  log uji
 *            "G <0|1>"  ambil alih layar sentuh (1) atau lepas (0)
 *            "H"        detak: tanpa detak 8 detik, grab dilepas sendiri
 *            "Q"        berhenti
 * Bila stdin tertutup (aplikasi mati) grab dilepas dan proses berhenti. Kernel juga melepas grab
 * sendiri begitu kgrab mati, jadi layar sentuh asli tidak pernah tertinggal terkunci.
 */
object ProxyDaemon {
    private const val BIN_PATH = "/data/local/tmp/kilat_grab"
    private const val BEAT_TIMEOUT_MS = 8000L

    private val outLock = Any()
    private fun say(line: String) {
        synchronized(outLock) {
            println(line)
            System.out.flush()
        }
    }

    private val sched = Executors.newScheduledThreadPool(2) { r -> Thread(r).also { it.isDaemon = true } }
    private val inj = TouchInjector()

    private val sink = object : TouchSink {
        override fun touch(action: Int, id: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
            inj.touch(action, id, x, y, downTime, eventTime, 0)
        }

        override fun now(): Long = SystemClock.uptimeMillis()
        override fun log(msg: String) = say("L " + msg.replace('\n', ' '))
        override fun follow(zoneId: Int, x: Float, y: Float) = say("F $zoneId ${x.toInt()} ${y.toInt()}")
    }

    private val engine = ProxyEngine(sink, sched)

    @Volatile private var grab: Process? = null
    @Volatile private var grabbing = false
    @Volatile private var lastBeat = 0L
    private val grabLock = Any()

    @JvmStatic
    fun main(args: Array<String>) {
        inj.canInject()
        say(inj.status())
        sched.scheduleAtFixedRate({
            if (grabbing && SystemClock.uptimeMillis() - lastBeat > BEAT_TIMEOUT_MS) {
                stopGrab()
                say("E aplikasi tidak memberi kabar, layar sentuh dilepas")
            }
        }, 1, 1, TimeUnit.SECONDS)

        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            if (line == "Q") break
            try {
                handle(line)
            } catch (e: Throwable) {
                say("E perintah rusak: ${e.javaClass.simpleName}")
            }
        }
        stopGrab()
        exitProcess(0)
    }

    private fun handle(line: String) {
        val cmd = line.firstOrNull() ?: return
        val rest = line.drop(2)
        when (cmd) {
            'H' -> lastBeat = SystemClock.uptimeMillis()
            'S' -> {
                val p = rest.split(' ')
                engine.rot = p[2].toInt()
                engine.sw = p[0].toInt()
                engine.sh = p[1].toInt()
            }
            'D' -> engine.debug = rest.trim() == "1"
            'Z' -> engine.zones = if (rest.isBlank()) emptyList() else rest.split(';').filter { it.isNotBlank() }.map {
                val p = it.split(',')
                ProxyZone(
                    p[0].toInt(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(),
                    p[6].toInt(), p[7].toFloat(), p[8].toFloat(), p[9].toLong(), p[10].toLong(),
                )
            }
            'G' -> if (rest.trim() == "1") startGrab() else stopGrab()
        }
    }

    // ---------- ambil alih layar sentuh ----------

    private fun shell(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", "$cmd 2>&1").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return text
    }

    /** Menyalin kgrab dari dalam APK ke /data/local/tmp (satu-satunya tempat yang boleh dieksekusi uid shell). */
    private fun installBinary(): String? {
        val apk = System.getProperty("java.class.path")?.split(':')?.firstOrNull { it.endsWith(".apk") }
            ?: return "jalur APK tidak ditemukan"
        ZipFile(apk).use { z ->
            val e = z.getEntry("assets/kgrab") ?: return "kgrab tidak ada di APK"
            val bytes = z.getInputStream(e).use { it.readBytes() }
            val dst = File(BIN_PATH)
            if (!dst.exists() || !dst.readBytes().contentEquals(bytes)) {
                val tmp = File("$BIN_PATH.new")
                tmp.writeBytes(bytes)
                tmp.setExecutable(true, false)
                tmp.setReadable(true, false)
                if (!tmp.renameTo(dst)) return "gagal menaruh kgrab di /data/local/tmp"
            }
            dst.setExecutable(true, false)
        }
        return null
    }

    private fun startGrab() {
        synchronized(grabLock) {
            if (grabbing) {
                lastBeat = SystemClock.uptimeMillis()
                return
            }
            if (!inj.canInject()) {
                say("E jalur suntik belum siap")
                return
            }
            val info = EvdevParser.findTouchscreen(shell("getevent -lp"))
            if (info == null) {
                say("E layar sentuh tidak ditemukan")
                return
            }
            installBinary()?.let { say("E $it"); return }
            val p = try {
                ProcessBuilder(BIN_PATH, info.path).start()
            } catch (e: Throwable) {
                say("E kgrab tidak bisa dijalankan: ${e.message?.take(80)}")
                return
            }
            val input = BufferedInputStream(p.inputStream, 4096)
            val ready = CountDownLatch(1)
            val first = java.util.concurrent.atomic.AtomicInteger(-1)
            val t = Thread {
                try {
                    first.set(input.read())
                } catch (e: Throwable) {
                    first.set(-1)
                }
                ready.countDown()
                if (first.get() != 'K'.code) return@Thread
                readLoop(p, input)
            }
            t.isDaemon = true
            t.priority = Thread.MAX_PRIORITY
            t.start()
            if (!ready.await(3, TimeUnit.SECONDS) || first.get() != 'K'.code) {
                val err = if (first.get() == 'E'.code) try {
                    // errno
                    val n = StringBuilder()
                    while (true) {
                        val c = input.read()
                        if (c < 0 || c == '\n'.code) break
                        n.append(c.toChar())
                    }
                    "errno $n"
                } catch (e: Throwable) { "?" } else "tidak menjawab"
                try { p.destroy() } catch (e: Throwable) { }
                say("E kgrab gagal mengambil alih layar sentuh ($err)")
                return
            }
            engine.maxX = info.maxX
            engine.maxY = info.maxY
            grab = p
            grabbing = true
            lastBeat = SystemClock.uptimeMillis()
            say("R grab aktif ${info.path} ${info.maxX}x${info.maxY}")
        }
    }

    private fun readLoop(p: Process, input: BufferedInputStream) {
        val b = ByteArray(8)
        try {
            while (true) {
                var got = 0
                while (got < 8) {
                    val n = input.read(b, got, 8 - got)
                    if (n < 0) throw java.io.EOFException()
                    got += n
                }
                val type = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
                val code = (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8)
                val value = (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8) or
                    ((b[6].toInt() and 0xFF) shl 16) or ((b[7].toInt() and 0xFF) shl 24)
                engine.event(type, code, value)
            }
        } catch (e: Throwable) {
            // kgrab berhenti: lepas jari yang masih tercatat supaya game tidak menahan sentuhan.
        }
        val wasMine = synchronized(grabLock) {
            if (grab === p) {
                grab = null
                grabbing = false
                true
            } else false
        }
        if (wasMine) {
            engine.releaseAll()
            say("E kgrab berhenti, layar sentuh dikembalikan")
        }
    }

    private fun stopGrab() {
        val p: Process?
        synchronized(grabLock) {
            p = grab
            grab = null
            grabbing = false
        }
        if (p != null) {
            try { p.outputStream.close() } catch (e: Throwable) { }
            try { p.destroy() } catch (e: Throwable) { }
        }
        engine.releaseAll()
        if (p != null) say("R grab dilepas")
    }
}
