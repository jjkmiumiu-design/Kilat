package com.kilat.optimizer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

internal val ACCENT = Color.rgb(242, 190, 12)
internal val PANEL_BG = Color.argb(0xF2, 0x12, 0x13, 0x15)
internal val CHIP_BG = Color.rgb(0x24, 0x25, 0x28)
internal val LINE = Color.argb(0x55, 0xFF, 0xFF, 0xFF)
internal val TEXT = Color.rgb(0xEC, 0xEB, 0xE7)
internal val MUTED = Color.rgb(0x9B, 0x9D, 0xA2)

/** ScrollView yang tingginya dibatasi, supaya panel tidak melebihi layar. */
class MaxHeightScrollView(ctx: Context) : ScrollView(ctx) {
    var maxHeightPx = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}

/** Ikon pilihan gaya crosshair. */
private class StyleIcon(ctx: Context, private val style: Int) : View(ctx) {
    private var selected = false

    fun setSelectedStyle(sel: Boolean, radiusPx: Float, strokePx: Int) {
        selected = sel
        background = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(CHIP_BG)
            setStroke(strokePx, if (sel) ACCENT else LINE)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height) * 0.7f
        CrosshairDrawer.draw(
            canvas, style, width / 2f, height / 2f, size,
            if (selected) Color.WHITE else MUTED, 100, outline = false,
        )
    }
}

/**
 * Panel pengaturan yang muncul di atas game. Setiap perubahan langsung ditulis ke
 * [GameToolsPrefs], overlay mendengarkannya, jadi crosshair dan FPS berubah seketika.
 */
class GamePanel(
    private val ctx: Context,
    private val onClose: () -> Unit,
    private val onEditZones: () -> Unit,
    private val onPickPosition: (Int) -> Unit,
) {

    val root = MaxHeightScrollView(ctx)

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()

    private var tab = 0
    private var binding = false
    private var cur = GameToolsPrefs.read(ctx)

    private val title = TextView(ctx)
    private val toggle = Switch(ctx)
    private val tabViews = listOf(TextView(ctx), TextView(ctx), TextView(ctx))
    private val crossBox = LinearLayout(ctx)
    private val fpsBox = LinearLayout(ctx)
    private val sensiBox = LinearLayout(ctx)
    private val sensiStatus = TextView(ctx)
    private val sensiCount = TextView(ctx)
    private val posChips = ArrayList<TextView>()
    private lateinit var globalX: StepSlider
    private lateinit var globalY: StepSlider
    private val styleIcons = ArrayList<StyleIcon>()
    private val colorDots = ArrayList<View>()
    private val sizeBar = SeekBar(ctx)
    private val sizeVal = TextView(ctx)
    private val opacityBar = SeekBar(ctx)
    private val opacityVal = TextView(ctx)
    private val cornerChips = ArrayList<TextView>()

    init {
        root.background = GradientDrawable().apply {
            val r = dp(20).toFloat()
            // Sudut membulat hanya di sisi kiri, sisi kanan menempel ke tepi layar.
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            setColor(PANEL_BG)
            setStroke(dp(1), LINE)
        }
        root.isVerticalScrollBarEnabled = false

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        root.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(header())
        col.addView(tabs(), lp(top = 12))

        buildCrosshairSection()
        buildFpsSection()
        buildSensiSection()
        col.addView(crossBox)
        col.addView(fpsBox)
        col.addView(sensiBox)

        bind(cur)
    }

    // ---------- bagian tampilan ----------

    private fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun header(): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val close = TextView(ctx).apply {
            text = "✕"
            setTextColor(TEXT)
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { onClose() }
        }
        row.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        title.setTextColor(TEXT)
        title.textSize = 17f
        title.typeface = Typeface.DEFAULT_BOLD
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        toggle.thumbTintList = ColorStateList(states, intArrayOf(ACCENT, Color.rgb(0xCC, 0xD3, 0xD9)))
        toggle.trackTintList = ColorStateList(states, intArrayOf(Color.argb(0x88, 242, 190, 12), Color.argb(0x55, 255, 255, 255)))
        toggle.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            when (tab) {
                0 -> GameToolsPrefs.setCrossOn(ctx, on)
                1 -> GameToolsPrefs.setFpsOn(ctx, on)
                else -> GameToolsPrefs.setSensiOn(ctx, on)
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun tabs(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(CHIP_BG) }
        }
        listOf("Crosshair", "FPS", "Sensi").forEachIndexed { i, name ->
            val t = tabViews[i]
            t.text = name
            t.gravity = Gravity.CENTER
            t.textSize = 14f
            t.setOnClickListener {
                tab = i
                bind(cur)
            }
            row.addView(t, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) leftMargin = dp(4) })
        }
        return row
    }

    private fun label(text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(MUTED)
        textSize = 13f
    }

    private fun valueText(v: TextView) = v.apply {
        setTextColor(TEXT)
        textSize = 13f
        typeface = Typeface.MONOSPACE
    }

    private fun labelWithValue(text: String, value: TextView): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(label(text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueText(value))
        return row
    }

    private fun tint(bar: SeekBar) {
        bar.progressTintList = ColorStateList.valueOf(ACCENT)
        bar.thumbTintList = ColorStateList.valueOf(ACCENT)
        bar.progressBackgroundTintList = ColorStateList.valueOf(LINE)
    }

    private fun seekListener(onValue: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser && !binding) onValue(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    private fun buildCrosshairSection() {
        crossBox.orientation = LinearLayout.VERTICAL

        crossBox.addView(label("Gaya"), lp(top = 14))
        val styles = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until GameToolsPrefs.STYLE_COUNT) {
            val icon = StyleIcon(ctx, i)
            icon.setOnClickListener { GameToolsPrefs.setCrossStyle(ctx, i) }
            styleIcons += icon
            styles.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { if (i > 0) leftMargin = dp(8) })
        }
        crossBox.addView(styles, lp(top = 8))

        crossBox.addView(label("Warna"), lp(top = 14))
        val colors = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        GameToolsPrefs.COLORS.forEachIndexed { i, _ ->
            val dot = View(ctx)
            dot.setOnClickListener { GameToolsPrefs.setCrossColor(ctx, i) }
            colorDots += dot
            colors.addView(dot, LinearLayout.LayoutParams(dp(36), dp(36)).apply { if (i > 0) leftMargin = dp(12) })
        }
        crossBox.addView(colors, lp(top = 8))

        crossBox.addView(labelWithValue("Ukuran", sizeVal), lp(top = 16))
        tint(sizeBar)
        sizeBar.max = GameToolsPrefs.SIZE_MAX - GameToolsPrefs.SIZE_MIN
        sizeBar.setOnSeekBarChangeListener(seekListener { GameToolsPrefs.setCrossSize(ctx, GameToolsPrefs.SIZE_MIN + it) })
        crossBox.addView(sizeBar, lp(top = 4))

        crossBox.addView(labelWithValue("Opasitas", opacityVal), lp(top = 10))
        tint(opacityBar)
        opacityBar.max = GameToolsPrefs.OPACITY_MAX - GameToolsPrefs.OPACITY_MIN
        opacityBar.setOnSeekBarChangeListener(seekListener { GameToolsPrefs.setCrossOpacity(ctx, GameToolsPrefs.OPACITY_MIN + it) })
        crossBox.addView(opacityBar, lp(top = 4))
    }

    private fun buildFpsSection() {
        fpsBox.orientation = LinearLayout.VERTICAL
        fpsBox.addView(label("Posisi"), lp(top = 14))

        val grid = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(0x33, 0, 0, 0))
                setStroke(dp(1), LINE)
            }
        }
        val gravities = intArrayOf(
            Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END,
            Gravity.BOTTOM or Gravity.START, Gravity.BOTTOM or Gravity.END,
        )
        for (i in 0..3) {
            val chip = TextView(ctx).apply {
                text = "FPS"
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setOnClickListener { GameToolsPrefs.setFpsCorner(ctx, i) }
            }
            cornerChips += chip
            grid.addView(chip, FrameLayout.LayoutParams(dp(72), dp(44), gravities[i]).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        }
        fpsBox.addView(grid, lp(top = 8, h = dp(130)))

        fpsBox.addView(
            TextView(ctx).apply {
                text = "Angka FPS dihitung dari frame game yang benar-benar tampil di layar."
                setTextColor(MUTED)
                textSize = 12f
            },
            lp(top = 12),
        )
    }

    private fun buildSensiSection() {
        sensiBox.orientation = LinearLayout.VERTICAL

        sensiBox.addView(label("Injeksi sentuh"), lp(top = 14))
        sensiStatus.textSize = 13f
        sensiStatus.typeface = Typeface.DEFAULT_BOLD
        sensiBox.addView(sensiStatus, lp(top = 2))
        // Tahan lama untuk menyalakan mode uji (toast diagnosis tiap gestur zona).
        sensiStatus.setOnLongClickListener {
            val p = GameToolsPrefs.prefs(ctx)
            val on = !p.getBoolean("sensi_debug", false)
            p.edit().putBoolean("sensi_debug", on).apply()
            android.widget.Toast.makeText(ctx, if (on) "Mode uji sensi: HIDUP" else "Mode uji sensi: MATI", android.widget.Toast.LENGTH_SHORT).show()
            true
        }

        sensiBox.addView(label("Posisi zona"), lp(top = 14))
        val posRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        SensiPrefs.PRESET_NAMES.forEachIndexed { i, name ->
            val chip = TextView(ctx).apply {
                text = name.uppercase()
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { onPickPosition(i) }
            }
            posChips += chip
            posRow.addView(chip, LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        sensiBox.addView(posRow, lp(top = 8))
        sensiBox.addView(
            TextView(ctx).apply {
                text = "Ketuk salah satu: layar HP tampil dengan zona di posisi itu, lalu geser atau ubah ukurannya dan simpan dengan ✓."
                setTextColor(MUTED)
                textSize = 12f
            },
            lp(top = 6),
        )

        sensiBox.addView(label("Sensitivity global"), lp(top = 14))
        globalX = StepSlider(ctx, "Sensitivity X") { SensiPrefs.setGlobal(ctx, x = it) }
        globalY = StepSlider(ctx, "Sensitivity Y") { SensiPrefs.setGlobal(ctx, y = it) }
        sensiBox.addView(globalX.root, lp(top = 8))
        sensiBox.addView(globalY.root, lp(top = 8))

        sensiCount.setTextColor(MUTED)
        sensiCount.textSize = 13f
        sensiBox.addView(sensiCount, lp(top = 14))
        val edit = TextView(ctx).apply {
            text = "Edit zona"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(0x33, 242, 190, 12))
                setStroke(dp(1), ACCENT)
            }
            setOnClickListener { onEditZones() }
        }
        sensiBox.addView(edit, lp(top = 6, h = dp(44)))

        sensiBox.addView(
            TextView(ctx).apply {
                text = "Gerakan jari yang dimulai di dalam zona dilebarkan sesuai nilai X dan Y, jadi kamera game bergerak lebih jauh. " +
                    "Sentuhan di luar zona tidak diubah.\n\n" +
                    "Aksi tap: pilih zona di Edit zona, lalu atur Aksi tap. Tap = ketuk titik T sekali saat jari menyentuh zona, " +
                    "Ulang = ketuk berulang selama jari ditahan, Dobel = ketuk dua kali, Lepas = ketuk saat jari diangkat. " +
                    "Seret titik T ke tempat yang mau diketuk."
                setTextColor(MUTED)
                textSize = 12f
            },
            lp(top = 12),
        )
    }

    // ---------- sinkron dengan pengaturan ----------

    private fun pill(sel: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(11).toFloat()
        setColor(if (sel) Color.argb(0x33, 242, 190, 12) else Color.TRANSPARENT)
        setStroke(dp(1), if (sel) ACCENT else Color.TRANSPARENT)
    }

    /** Memperbarui seluruh tampilan panel sesuai pengaturan. Tidak menulis balik ke pengaturan. */
    fun bind(s: GameToolsSettings) {
        cur = s
        binding = true
        try {
            title.text = when (tab) { 0 -> "Crosshair"; 1 -> "FPS"; else -> "Sensi" }
            toggle.isChecked = when (tab) { 0 -> s.crossOn; 1 -> s.fpsOn; else -> s.sensiOn }

            tabViews.forEachIndexed { i, t ->
                t.background = pill(i == tab)
                t.setTextColor(if (i == tab) TEXT else MUTED)
                t.typeface = if (i == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            crossBox.visibility = if (tab == 0) View.VISIBLE else View.GONE
            fpsBox.visibility = if (tab == 1) View.VISIBLE else View.GONE
            sensiBox.visibility = if (tab == 2) View.VISIBLE else View.GONE

            val sd = SensiPrefs.read(ctx)
            globalX.set(sd.gx)
            globalY.set(sd.gy)
            val tapN = sd.zones.count { it.tap != SensiPrefs.TAP_OFF }
            sensiCount.text = "${sd.zones.size} zona" + if (tapN > 0) " · $tapN dengan aksi tap" else ""
            val (ready, msg) = Shell.injectMessage()
            sensiStatus.text = msg
            sensiStatus.setTextColor(when (ready) { true -> GOOD; false -> BAD; null -> MUTED })
            val cur0 = SensiPrefs.presetOf(sd)
            posChips.forEachIndexed { i, chip ->
                val sel = i == cur0
                chip.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (sel) Color.argb(0x33, 242, 190, 12) else CHIP_BG)
                    setStroke(dp(if (sel) 2 else 1), if (sel) ACCENT else LINE)
                }
                chip.setTextColor(if (sel) TEXT else MUTED)
            }

            styleIcons.forEachIndexed { i, icon -> icon.setSelectedStyle(i == s.crossStyle, dp(12).toFloat(), dp(if (i == s.crossStyle) 2 else 1)) }
            colorDots.forEachIndexed { i, dot ->
                val sel = i == s.crossColor
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(GameToolsPrefs.COLORS[i])
                    setStroke(dp(if (sel) 3 else 1), if (sel) ACCENT else LINE)
                }
            }
            sizeBar.progress = s.crossSize - GameToolsPrefs.SIZE_MIN
            sizeVal.text = "${s.crossSize} dp"
            opacityBar.progress = s.crossOpacity - GameToolsPrefs.OPACITY_MIN
            opacityVal.text = "${s.crossOpacity}%"

            cornerChips.forEachIndexed { i, chip ->
                val sel = i == s.fpsCorner
                chip.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (sel) Color.argb(0x33, 242, 190, 12) else CHIP_BG)
                    setStroke(dp(if (sel) 2 else 1), if (sel) ACCENT else LINE)
                }
                chip.setTextColor(if (sel) TEXT else MUTED)
            }
        } finally {
            binding = false
        }
    }
}
