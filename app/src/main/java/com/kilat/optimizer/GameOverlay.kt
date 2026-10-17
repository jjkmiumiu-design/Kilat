package com.kilat.optimizer

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Jendela-jendela yang tampil di atas game:
 *  - crosshair di tengah layar (tidak bisa disentuh, sentuhan tembus ke game)
 *  - angka FPS di salah satu dari empat pojok (tidak bisa disentuh)
 *  - pegangan kecil di tepi kanan, bila diketuk membuka panel pengaturan
 *  - panel pengaturan
 *
 * Semua dipanggil dari thread utama.
 */
class GameOverlay(context: Context) {

    private val ctx = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private var settings = GameToolsPrefs.read(ctx)
    private var active = false
    private var panelOpen = false
    private val attached = HashSet<View>()

    // ---------- tampilan ----------

    private val crosshair = CrosshairView(ctx)

    private val fpsView = TextView(ctx).apply {
        text = "-- FPS"
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(0xB0, 0, 0, 0))
        }
    }

    private val handle = TextView(ctx).apply {
        text = "‹"
        setTextColor(Color.WHITE)
        textSize = 22f
        gravity = Gravity.CENTER
        alpha = 0.7f
        background = GradientDrawable().apply {
            val r = dp(14).toFloat()
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            setColor(Color.argb(0xB0, 0x12, 0x19, 0x23))
        }
        setOnClickListener {
            panelOpen = true
            sync()
        }
    }

    private val panel = GamePanel(ctx, onClose = {
        panelOpen = false
        sync()
    }, onEditZones = { openEditor() }, onPickPosition = { pos ->
        // Pilih Kiri/Tengah/Kanan: simpan zona di posisi itu, lalu tampilkan layar HP dengan zona terpilih.
        openEditor(SensiPrefs.applyPreset(ctx, pos))
    })

    private var editing = false
    private var editor: SensiEditor? = null

    private val sensi = SensiOverlay(ctx, wm, { screenSize() }) {
        // Sistem menolak pola sentuhan ini berulang kali: matikan fitur supaya game tetap bisa dimainkan.
        GameToolsPrefs.setSensiOn(ctx, false)
        Toast.makeText(ctx, "Zona sensi dimatikan: ROM ini tidak meneruskan sentuhan dengan benar", Toast.LENGTH_LONG).show()
    }

    init {
        sensi.onDebug = { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------- parameter jendela ----------

    private fun params(touchable: Boolean, width: Int, height: Int, gravity: Int): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        flags = flags or if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).also {
            it.gravity = gravity
            // Android 12+ menolak sentuhan ke game yang melewati overlay dengan opasitas di atas 0,8.
            // Jendela yang tembus sentuh dibuat 0,75 supaya sentuhan di bawah crosshair tetap sampai.
            if (!touchable) it.alpha = 0.75f
            if (Build.VERSION.SDK_INT >= 30) {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private val crossLp = params(false, dp(48), dp(48), Gravity.CENTER)
    private val fpsLp = params(false, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
    private val handleLp = params(true, dp(22), dp(56), Gravity.END or Gravity.CENTER_VERTICAL)
    private val panelLp = params(true, dp(300), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }

    // ---------- kendali dari service ----------

    /** Dipanggil setiap pengaturan berubah. */
    fun update(s: GameToolsSettings) {
        settings = s
        crosshair.set(s.crossStyle, GameToolsPrefs.COLORS[s.crossColor], s.crossOpacity)
        val px = dp(s.crossSize)
        crossLp.width = px
        crossLp.height = px

        fpsLp.gravity = when (s.fpsCorner) {
            0 -> Gravity.TOP or Gravity.START
            1 -> Gravity.TOP or Gravity.END
            2 -> Gravity.BOTTOM or Gravity.START
            else -> Gravity.BOTTOM or Gravity.END
        }
        fpsLp.x = dp(16)
        fpsLp.y = dp(16)

        panel.bind(s)
        sync()
    }

    /** Tampilkan atau sembunyikan seluruh overlay (mis. game sedang dibuka atau tidak). */
    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        if (!on) {
            panelOpen = false
            closeEditor()
        }
        sync()
    }

    /** Dipanggil tiap putaran service: menyesuaikan zona bila ukuran layar berubah (rotasi). */
    fun refreshSensi() {
        sensi.debugOn = GameToolsPrefs.prefs(ctx).getBoolean("sensi_debug", false)
        sensi.apply(sensiWanted(), SensiPrefs.read(ctx))
    }

    private fun sensiWanted(): Boolean =
        active && settings.sensiOn && !editing && Shell.injectReady == true

    private fun openEditor(selectId: Int = -1) {
        if (!active || editing) return
        val (w, h) = screenSize()
        val ed = SensiEditor(ctx, w, h, selectId) { closeEditor() }
        try {
            wm.addView(ed.root, params(true, w, h, Gravity.TOP or Gravity.START))
        } catch (e: Exception) {
            return
        }
        editor = ed
        editing = true
        panelOpen = false
        sync()
    }

    private fun closeEditor() {
        val ed = editor ?: return
        try {
            wm.removeView(ed.root)
        } catch (e: Exception) {
            // sudah dilepas
        }
        editor = null
        editing = false
        sync()
    }

    fun setFps(value: Double?) {
        fpsView.text = if (value == null) "-- FPS" else String.format(Locale.US, "%.2f FPS", value)
    }

    fun destroy() {
        active = false
        panelOpen = false
        closeEditor()
        sensi.hide()
        sync()
    }

    // ---------- pasang dan lepas jendela ----------

    private fun sync() {
        ensure(crosshair, crossLp, active && settings.crossOn, relayout = true)
        ensure(fpsView, fpsLp, active && settings.fpsOn, relayout = true)
        ensure(handle, handleLp, active && !panelOpen && !editing, relayout = false)
        sensi.apply(sensiWanted(), SensiPrefs.read(ctx))

        if (active && panelOpen && panel.root !in attached) {
            val (w, h) = screenSize()
            panelLp.width = min(dp(300), (w * 0.62f).toInt())
            panel.root.maxHeightPx = (h * 0.94f).toInt()
        }
        ensure(panel.root, panelLp, active && panelOpen, relayout = false)
    }

    private fun ensure(v: View, lp: WindowManager.LayoutParams, want: Boolean, relayout: Boolean) {
        val has = v in attached
        try {
            when {
                want && !has -> {
                    wm.addView(v, lp)
                    attached += v
                }
                !want && has -> {
                    wm.removeView(v)
                    attached -= v
                }
                want && has && relayout -> wm.updateViewLayout(v, lp)
            }
        } catch (e: Exception) {
            // Izin overlay dicabut saat service jalan, atau jendela sudah dilepas sistem.
            attached -= v
        }
    }
}
