package com.kilat.optimizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Menjalankan overlay Game Tools. Berjalan sebagai foreground service supaya tidak dimatikan
 * sistem saat pengguna sedang bermain. Setiap detik ia memeriksa aplikasi yang sedang di depan
 * (lewat Shizuku). Bila itu game target, overlay muncul dan FPS-nya diukur.
 */
class GameToolsService : Service() {

    companion object {
        const val ACTION_STOP = "com.kilat.optimizer.GAME_TOOLS_STOP"
        private const val CHANNEL = "kilat_gametools"
        private const val NOTIF_ID = 7

        private val _running = MutableStateFlow(false)

        /** true selama service berjalan. Dipakai layar Game Tools untuk saklar. */
        val running: StateFlow<Boolean> = _running

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GameToolsService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GameToolsService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: GameOverlay? = null
    private var settings: GameToolsSettings? = null
    private var lastNotice = ""

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val s = GameToolsPrefs.read(this)
        settings = s
        overlay?.update(s)
    }

    /** Launcher dan SystemUI, tidak dianggap game pada mode "Semua aplikasi". */
    private val ignored: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val set = packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toMutableSet()
        set += "com.android.systemui"
        set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Harus segera dipanggil setelah startForegroundService.
        startForeground(NOTIF_ID, notification("Menunggu game"))

        if (!Settings.canDrawOverlays(this) || !licensed()) {
            stopSelf()
            return
        }
        val s = GameToolsPrefs.read(this)
        settings = s
        val o = GameOverlay(this)
        o.update(s)
        overlay = o

        GameToolsPrefs.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        _running.value = true
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        GameToolsPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        overlay?.destroy()
        overlay = null
        _running.value = false
        super.onDestroy()
    }

    // ---------- putaran utama ----------

    private suspend fun loop() {
        val meter = FpsMeter()
        var fg: String? = null
        var tick = 0
        var wasActive = false

        while (currentCoroutineContext().isActive) {
            // Fitur ini berlisensi: berhenti sendiri bila key habis, dihapus, atau jam diputar mundur.
            // Service bisa hidup tanpa Activity, jadi pemeriksaannya harus ada di sini juga.
            if (tick % 5 == 0 && !licensed()) {
                stopSelf()
                return
            }
            try {
                val s = settings ?: GameToolsPrefs.read(this)
                val o = overlay ?: return

                if (Shell.status.value == ShizukuStatus.READY && Shell.injectReady == null) {
                    Shell.probeInject()
                    o.update(s) // perbarui status injeksi di panel
                }
                if (Shell.status.value != ShizukuStatus.READY) {
                    o.setActive(false)
                    wasActive = false
                    fg = null
                    meter.reset()
                    notice("Menunggu Shizuku")
                } else {
                    // Pemeriksaan aplikasi depan lebih jarang daripada FPS supaya ringan saat bermain.
                    val every = if (wasActive) 3 else 2
                    if (fg == null || tick % every == 0) fg = ForegroundApp.packageName()

                    val active = isTarget(fg, s)
                    if (!active && wasActive) meter.reset()
                    wasActive = active
                    o.setActive(active)

                    if (active && fg != null) {
                        notice("Aktif di ${labelOf(fg)}")
                        if (s.fpsOn) o.setFps(meter.sample(fg))
                    } else {
                        notice("Menunggu game")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Perintah sesaat gagal (mis. Shizuku terputus). Coba lagi di putaran berikutnya.
            }
            overlay?.refreshSensi()
            tick++
            delay(1000)
        }
    }

    private fun licensed(): Boolean =
        License.clockOk(this) && License.restore(this).first != null

    private fun isTarget(pkg: String?, s: GameToolsSettings): Boolean {
        if (pkg == null || pkg == packageName) return false
        return if (s.targetAny) pkg !in ignored else pkg in s.games
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ---------- notifikasi ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Game Tools", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notice(text: String) {
        if (text == lastNotice) return
        lastNotice = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GameToolsService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle("Kilat Game Tools")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_bolt), "Matikan", stop).build())
            .build()
    }
}
