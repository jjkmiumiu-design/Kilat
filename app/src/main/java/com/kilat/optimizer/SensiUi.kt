package com.kilat.optimizer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

internal val GOOD = Color.rgb(0x4C, 0xC3, 0x8A)
internal val BAD = Color.rgb(0xFF, 0x6B, 0x6B)

/** Tombol bulat kecil bertuliskan satu karakter, dipakai untuk − dan +. */
internal fun roundButton(ctx: Context, text: String, onClick: () -> Unit): TextView {
    val d = ctx.resources.displayMetrics.density
    return TextView(ctx).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = 18f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(CHIP_BG)
            setStroke((1 * d).roundToInt().coerceAtLeast(1), LINE)
        }
        setOnClickListener { onClick() }
    }
}

/** Baris pengatur nilai 1,00 sampai 5,00: judul dan nilai di atas, lalu tombol −, slider, tombol +. */
internal class StepSlider(private val ctx: Context, label: String, private val onChange: (Float) -> Unit) {

    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val valueText = TextView(ctx)
    private val bar = SeekBar(ctx)
    private var v = SensiPrefs.MIN
    private val d = ctx.resources.displayMetrics.density
    private fun dp(x: Int) = (x * d).roundToInt()

    init {
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            TextView(ctx).apply {
                text = label
                setTextColor(MUTED)
                textSize = 13f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        valueText.setTextColor(TEXT)
        valueText.textSize = 13f
        valueText.typeface = Typeface.MONOSPACE
        head.addView(valueText)
        root.addView(head)

        bar.max = SensiPrefs.STEPS
        bar.progressTintList = ColorStateList.valueOf(ACCENT)
        bar.thumbTintList = ColorStateList.valueOf(ACCENT)
        bar.progressBackgroundTintList = ColorStateList.valueOf(LINE)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                show(SensiPrefs.MIN + progress * SensiPrefs.STEP)
                onChange(v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(roundButton(ctx, "−") { nudge(-SensiPrefs.STEP) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
        })
        row.addView(roundButton(ctx, "+") { nudge(SensiPrefs.STEP) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        show(v)
    }

    /** Mengubah tampilan tanpa memanggil [onChange]. */
    fun set(value: Float) = show(value)

    val value: Float get() = v

    private fun show(nv: Float) {
        v = SensiPrefs.snap(nv)
        bar.progress = ((v - SensiPrefs.MIN) / SensiPrefs.STEP).roundToInt()
        valueText.text = String.format(Locale.US, "%.2f", v)
    }

    private fun nudge(delta: Float) {
        show(v + delta)
        onChange(v)
    }
}
