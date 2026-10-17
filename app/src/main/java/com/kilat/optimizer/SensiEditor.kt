package com.kilat.optimizer

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Editor zona sensitivitas yang tampil di atas game: seret zona untuk memindahkan, ketuk untuk
 * memilih, atur ukuran, dan buka pengaturan X dan Y. Perubahan baru disimpan saat tombol ✓ diketuk.
 * Dipasang oleh [GameOverlay] sebagai satu jendela seukuran layar.
 */
class SensiEditor(
    private val ctx: Context,
    private val screenW: Int,
    private val screenH: Int,
    private val selectId: Int = -1,
    private val onDone: () -> Unit,
) {
    val root = FrameLayout(ctx)

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()

    private val zones = ArrayList<SensiZone>()
    private var gx: Float
    private var gy: Float
    private var selected = -1
    private var nextId = 1

    private val canvas = EditorCanvas(ctx)
    private val card = LinearLayout(ctx)
    private val cardName = TextView(ctx)
    private val sizeBar = SeekBar(ctx)
    private val dialogLayer = FrameLayout(ctx)

    // Pengaturan zona (dialog), disalin dulu supaya BATAL tidak mengubah apa pun.
    private val dialogTitle = TextView(ctx)
    private val dialogSwitch = Switch(ctx)
    private lateinit var sliderX: StepSlider
    private lateinit var sliderY: StepSlider
    private var dGlobal = false
    private var dgx = SensiPrefs.DEFAULT_VALUE
    private var dgy = SensiPrefs.DEFAULT_VALUE
    private var dsx = SensiPrefs.DEFAULT_VALUE
    private var dsy = SensiPrefs.DEFAULT_VALUE

    init {
        val saved = SensiPrefs.read(ctx)
        zones.addAll(saved.zones)
        gx = saved.gx
        gy = saved.gy
        nextId = (zones.maxOfOrNull { it.id } ?: 0) + 1
        if (zones.any { it.id == selectId }) selected = selectId

        root.addView(canvas, FrameLayout.LayoutParams(screenW, screenH))
        root.addView(toolbar(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = dp(12) })
        buildCard()
        root.addView(card, FrameLayout.LayoutParams(
            min(dp(300), (screenW * 0.7f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = dp(68) })
        buildDialog()
        root.addView(dialogLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialogLayer.visibility = View.GONE
        refreshCard()
    }

    // ---------- tampilan ----------

    private fun nameOf(z: SensiZone): String = "Sensi ${zones.indexOf(z) + 1}"

    private fun box(radiusDp: Int, fill: Int, strokeColor: Int = LINE, strokeDp: Int = 1) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        setStroke(dp(strokeDp), strokeColor)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun outlineButton(text: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = box(14, Color.argb(0x33, 255, 122, 69), ACCENT, 1)
        setOnClickListener { onClick() }
    }

    private fun toolbar(): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = box(26, Color.argb(0xEE, 0x0F, 0x16, 0x1D), ACCENT, 1)
        }
        val items = listOf<Pair<String, () -> Unit>>(
            "+" to { addZone() },
            "✕" to { onDone() },
            "✓" to { save() },
        )
        items.forEachIndexed { i, (t, action) ->
            val b = roundButton(ctx, t, action)
            bar.addView(b, LinearLayout.LayoutParams(dp(42), dp(42)).apply { if (i > 0) leftMargin = dp(10) })
        }
        return bar
    }

    private fun buildCard() {
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(10), dp(12), dp(12))
        card.background = box(18, Color.argb(0xEE, 0x0F, 0x16, 0x1D))

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cardName.textSize = 14f
        cardName.typeface = Typeface.DEFAULT_BOLD
        cardName.setTextColor(TEXT)
        cardName.gravity = Gravity.CENTER
        cardName.setPadding(dp(14), dp(8), dp(14), dp(8))
        cardName.background = box(14, Color.argb(0x33, 255, 122, 69), ACCENT, 1)
        row1.addView(cardName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(outlineButton("Hapus") { deleteSelected() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        row1.addView(outlineButton("Atur") { openDialog() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        card.addView(row1)

        card.addView(label("Ukuran zona", 12f, MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sizeBar.max = (SensiPrefs.SIZE_MAX - SensiPrefs.SIZE_MIN) / SensiPrefs.SIZE_STEP
        sizeBar.progressTintList = ColorStateList.valueOf(ACCENT)
        sizeBar.thumbTintList = ColorStateList.valueOf(ACCENT)
        sizeBar.progressBackgroundTintList = ColorStateList.valueOf(LINE)
        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) setSize(SensiPrefs.SIZE_MIN + progress * SensiPrefs.SIZE_STEP)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        row2.addView(roundButton(ctx, "−") { bumpSize(-SensiPrefs.SIZE_STEP) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        row2.addView(sizeBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(6); rightMargin = dp(6)
        })
        row2.addView(roundButton(ctx, "+") { bumpSize(SensiPrefs.SIZE_STEP) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        card.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
    }

    private fun buildDialog() {
        dialogLayer.setBackgroundColor(Color.argb(0xAA, 0, 0, 0))
        dialogLayer.isClickable = true // tangkap sentuhan supaya tidak tembus ke editor

        val scroll = MaxHeightScrollView(ctx)
        scroll.maxHeightPx = (screenH * 0.92f).toInt()
        scroll.isVerticalScrollBarEnabled = false
        scroll.background = box(20, Color.argb(0xF6, 0x0F, 0x16, 0x1D))

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialogTitle.textSize = 16f
        dialogTitle.typeface = Typeface.DEFAULT_BOLD
        dialogTitle.setTextColor(TEXT)
        col.addView(dialogTitle)
        col.addView(
            label("Sensitivity X dan Y melebarkan gerakan jari di dalam zona: nilai lebih besar, kamera bergerak lebih jauh.", 12f, MUTED),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
        )

        val gRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        gRow.addView(label("Sensitivity Global", 14f, TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        dialogSwitch.thumbTintList = ColorStateList(states, intArrayOf(ACCENT, Color.rgb(0xCC, 0xD3, 0xD9)))
        dialogSwitch.trackTintList = ColorStateList(states, intArrayOf(Color.argb(0x88, 255, 122, 69), Color.argb(0x55, 255, 255, 255)))
        dialogSwitch.setOnCheckedChangeListener { _, on ->
            dGlobal = on
            showDialogValues()
        }
        gRow.addView(dialogSwitch)
        col.addView(gRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        sliderY = StepSlider(ctx, "Sensitivity Y") { if (dGlobal) dgy = it else dsy = it }
        sliderX = StepSlider(ctx, "Sensitivity X") { if (dGlobal) dgx = it else dsx = it }
        col.addView(sliderY.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        col.addView(sliderX.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        col.addView(
            label("Min 1,00, maks 5,00. Nilai global dipakai bersama oleh semua zona yang memakai global.", 11f, ACCENT),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )

        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btns.addView(outlineButton("BATAL") { dialogLayer.visibility = View.GONE })
        btns.addView(outlineButton("SIMPAN") { applyDialog() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        col.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        dialogLayer.addView(scroll, FrameLayout.LayoutParams(
            min(dp(380), (screenW * 0.8f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    // ---------- aksi ----------

    private fun selectedIndex(): Int = zones.indexOfFirst { it.id == selected }

    private fun refreshCard() {
        val i = selectedIndex()
        if (i < 0) {
            card.visibility = View.GONE
        } else {
            card.visibility = View.VISIBLE
            cardName.text = nameOf(zones[i])
            sizeBar.progress = (zones[i].sizeDp - SensiPrefs.SIZE_MIN) / SensiPrefs.SIZE_STEP
        }
        canvas.invalidate()
    }

    private fun select(id: Int) {
        selected = id
        refreshCard()
    }

    private fun addZone() {
        if (zones.size >= SensiPrefs.MAX_ZONES) {
            Toast.makeText(ctx, "Maksimal ${SensiPrefs.MAX_ZONES} zona", Toast.LENGTH_SHORT).show()
            return
        }
        val off = 0.06f * zones.size
        val z = SensiZone(
            id = nextId++,
            cx = (0.5f + off).coerceAtMost(0.9f),
            cy = (0.5f + off).coerceAtMost(0.9f),
            sizeDp = SensiPrefs.SIZE_DEFAULT,
            useGlobal = false,
            sx = SensiPrefs.DEFAULT_VALUE,
            sy = SensiPrefs.DEFAULT_VALUE,
        )
        zones += z
        select(z.id)
    }

    private fun deleteSelected() {
        val i = selectedIndex()
        if (i < 0) return
        zones.removeAt(i)
        select(-1)
    }

    private fun setSize(dpValue: Int) {
        val i = selectedIndex()
        if (i < 0) return
        zones[i] = zones[i].copy(sizeDp = dpValue.coerceIn(SensiPrefs.SIZE_MIN, SensiPrefs.SIZE_MAX))
        canvas.invalidate()
    }

    private fun bumpSize(delta: Int) {
        val i = selectedIndex()
        if (i < 0) return
        setSize(zones[i].sizeDp + delta)
        sizeBar.progress = (zones[i].sizeDp - SensiPrefs.SIZE_MIN) / SensiPrefs.SIZE_STEP
    }

    private fun save() {
        SensiPrefs.write(ctx, SensiData(zones.toList(), gx, gy))
        onDone()
    }

    private fun openDialog() {
        val i = selectedIndex()
        if (i < 0) return
        val z = zones[i]
        dGlobal = z.useGlobal
        dgx = gx
        dgy = gy
        dsx = z.sx
        dsy = z.sy
        dialogTitle.text = "Pengaturan ${nameOf(z)}"
        dialogSwitch.isChecked = dGlobal
        showDialogValues()
        dialogLayer.visibility = View.VISIBLE
    }

    private fun showDialogValues() {
        sliderX.set(if (dGlobal) dgx else dsx)
        sliderY.set(if (dGlobal) dgy else dsy)
    }

    private fun applyDialog() {
        val i = selectedIndex()
        if (i >= 0) {
            zones[i] = zones[i].copy(useGlobal = dGlobal, sx = dsx, sy = dsy)
            gx = dgx
            gy = dgy
        }
        dialogLayer.visibility = View.GONE
        canvas.invalidate()
    }

    // ---------- kanvas zona ----------

    private inner class EditorCanvas(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private var dragId = -1
        private var offX = 0f
        private var offY = 0f

        init {
            setBackgroundColor(Color.argb(0x73, 0, 0, 0))
        }

        private fun hit(x: Float, y: Float): SensiZone? {
            for (z in zones.asReversed()) {
                val r = dp(z.sizeDp) / 2f
                val dx = x - z.cx * width
                val dy = y - z.cy * height
                if (dx * dx + dy * dy <= r * r) return z
            }
            return null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val z = hit(e.x, e.y)
                    if (z != null) {
                        dragId = z.id
                        offX = e.x - z.cx * width
                        offY = e.y - z.cy * height
                        select(z.id)
                    } else {
                        dragId = -1
                        select(-1)
                    }
                }
                MotionEvent.ACTION_MOVE -> if (dragId >= 0) {
                    val i = zones.indexOfFirst { it.id == dragId }
                    if (i >= 0) {
                        zones[i] = zones[i].copy(
                            cx = ((e.x - offX) / width).coerceIn(0.03f, 0.97f),
                            cy = ((e.y - offY) / height).coerceIn(0.03f, 0.97f),
                        )
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragId = -1
            }
            return true
        }

        private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x55, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDraw(c: Canvas) {
            // Pembagi layar: KIRI | TENGAH | KANAN, supaya posisi zona terbaca di layar HP sendiri.
            line.color = Color.argb(0x40, 255, 255, 255)
            line.strokeWidth = dp(1).toFloat()
            c.drawLine(width / 3f, 0f, width / 3f, height.toFloat(), line)
            c.drawLine(width * 2f / 3f, 0f, width * 2f / 3f, height.toFloat(), line)
            guide.textSize = dp(13).toFloat()
            for (i in 0..2) c.drawText(SensiPrefs.PRESET_NAMES[i].uppercase(), width * (2 * i + 1) / 6f, height - dp(18).toFloat(), guide)
            for (z in zones) {
                val cx = z.cx * width
                val cy = z.cy * height
                val r = dp(z.sizeDp) / 2f
                val sel = z.id == selected
                fill.color = Color.argb(if (sel) 0x4D else 0x2E, 255, 122, 69)
                c.drawCircle(cx, cy, r, fill)
                line.color = ACCENT
                line.strokeWidth = dp(if (sel) 3 else 2).toFloat()
                c.drawCircle(cx, cy, r, line)

                // ikon pindah: dua garis silang dengan kepala panah
                line.color = Color.WHITE
                line.strokeWidth = dp(2).toFloat()
                val s = min(r * 0.4f, dp(26).toFloat())
                c.drawLine(cx - s, cy, cx + s, cy, line)
                c.drawLine(cx, cy - s, cx, cy + s, line)
                val a = s * 0.35f
                val dirs = arrayOf(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, -1f))
                for (dv in dirs) {
                    val tx = cx + dv[0] * s
                    val ty = cy + dv[1] * s
                    val px = -dv[1]
                    val py = dv[0]
                    c.drawLine(tx, ty, tx - dv[0] * a + px * a, ty - dv[1] * a + py * a, line)
                    c.drawLine(tx, ty, tx - dv[0] * a - px * a, ty - dv[1] * a - py * a, line)
                }

                text.textSize = dp(12).toFloat()
                c.drawText(nameOf(z), cx, cy + s + dp(18), text)
            }
        }
    }
}
