package com.kilat.optimizer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

enum class ShizukuStatus { NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, READY }

/**
 * Jembatan ke Shizuku. Jalur utama: UserService (ShellService).
 * Jalur cadangan: Shizuku.newProcess lewat refleksi, dipakai bila UserService
 * tidak tersambung dalam [BIND_TIMEOUT_MS].
 */
object Shell {
    private const val REQUEST_CODE = 4242
    private const val BIND_TIMEOUT_MS = 6000L

    private var appContext: Context? = null
    private var service: IShellService? = null
    private var userArgs: Shizuku.UserServiceArgs? = null
    private var binding = false
    private var useProcess = false
    private var initialized = false
    private val main = Handler(Looper.getMainLooper())

    /** Hasil pemeriksaan suntik sentuh: null = belum diperiksa. Diulang tiap UserService tersambung. */
    @Volatile
    var injectReady: Boolean? = null
        private set

    private val _status = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuStatus> = _status

    private val timeout = Runnable {
        if (service == null && binding) {
            binding = false
            useProcess = true
            _status.value = ShizukuStatus.READY
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            main.removeCallbacks(timeout)
            service = IShellService.Stub.asInterface(binder)
            injectReady = null
            binding = false
            useProcess = false
            _status.value = ShizukuStatus.READY
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            injectReady = null
            binding = false
            refresh()
        }
    }

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }

    private val onBinderDead = Shizuku.OnBinderDeadListener {
        main.removeCallbacks(timeout)
        service = null
        injectReady = null
        binding = false
        useProcess = false
        _status.value = ShizukuStatus.NOT_RUNNING
    }

    private val onPermissionResult =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                refresh()
            } else {
                _status.value = ShizukuStatus.NEEDS_PERMISSION
            }
        }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermissionResult)
        refresh()
    }

    fun refresh() {
        if (!Shizuku.pingBinder()) {
            main.removeCallbacks(timeout)
            service = null
            binding = false
            useProcess = false
            _status.value = ShizukuStatus.NOT_RUNNING
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            _status.value = ShizukuStatus.NEEDS_PERMISSION
            return
        }
        if (service != null || useProcess) {
            _status.value = ShizukuStatus.READY
            return
        }
        if (binding) return
        bind()
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE)
    }

    private fun bind() {
        val ctx = appContext ?: return
        binding = true
        _status.value = ShizukuStatus.CONNECTING
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, BIND_TIMEOUT_MS)
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(ctx.packageName, ShellService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(BuildConfig.DEBUG)
                .version(2)
            userArgs = args
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            // Gagal memulai UserService: langsung pakai jalur cadangan.
            main.removeCallbacks(timeout)
            binding = false
            useProcess = true
            _status.value = ShizukuStatus.READY
        }
    }

    // ---------- jalur cadangan ----------

    private val newProcessMethod: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    private fun runViaProcess(command: String): String {
        val p = newProcessMethod.invoke(null, arrayOf("sh", "-c", "$command 2>&1"), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    // ---------- eksekusi ----------

    /** Menjalankan perintah dan mengembalikan keluarannya (stdout + stderr). */
    suspend fun run(command: String): String = withContext(Dispatchers.IO) {
        val s = service
        when {
            s != null -> s.exec(command)
            useProcess -> runViaProcess(command)
            else -> throw IllegalStateException("Shizuku belum siap")
        }
    }

    /** Memeriksa sekali apakah UserService bisa menyuntikkan sentuhan. Jalur cadangan (tanpa UserService) tidak bisa. */
    suspend fun probeInject(): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            service?.canInject() == true
        } catch (e: Throwable) {
            false
        }
        injectReady = ok
        ok
    }

    /** Mengirim satu event sentuh. Tidak menunggu hasil. false bila UserService tidak tersedia. */
    fun inject(action: Int, pointerId: Int, x: Float, y: Float, downTime: Long, eventTime: Long, deviceId: Int): Boolean {
        val s = service ?: return false
        return try {
            s.injectTouch(action, pointerId, x, y, downTime, eventTime, deviceId)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** Sama seperti [run], tetapi melempar error bila keluarannya menandakan kegagalan. */
    suspend fun runChecked(command: String): String {
        val out = run(command)
        val bad = out.contains("Exception", ignoreCase = true) ||
            out.contains("Permission denied", ignoreCase = true) ||
            out.startsWith("Error", ignoreCase = true) ||
            out.contains("not found", ignoreCase = true)
        if (bad) throw IllegalStateException(out.lineSequence().first().take(140))
        return out
    }
}
