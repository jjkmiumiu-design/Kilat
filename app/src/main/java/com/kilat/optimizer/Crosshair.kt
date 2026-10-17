package com.kilat.optimizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Menggambar lima gaya crosshair. Dipakai di layar game (CrosshairView) dan di ikon pilihan
 * gaya pada panel, jadi tampilannya selalu sama.
 *  0 silang bercelah, 1 lingkaran dengan silang, 2 titik, 3 sudut kurung, 4 cincin putus-putus dengan titik.
 */
object CrosshairDrawer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    fun draw(
        c: Canvas,
        style: Int,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        opacityPercent: Int,
        outline: Boolean = true,
    ) {
        val r = size / 2f * 0.9f
        val w = max(2f, size * 0.05f)
        val a = opacityPercent.coerceIn(0, 100) * 255 / 100
        if (outline) {
            // Garis tepi gelap tipis supaya crosshair tetap terlihat di latar terang.
            paint.color = Color.argb((a * 0.55f).toInt(), 0, 0, 0)
            shape(c, style, cx, cy, r, w, grow = max(2f, w))
        }
        paint.color = (color and 0x00FFFFFF) or (a shl 24)
        shape(c, style, cx, cy, r, w, grow = 0f)
    }

    private fun shape(c: Canvas, style: Int, cx: Float, cy: Float, r: Float, w: Float, grow: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w + grow
        paint.pathEffect = null
        when (style) {
            0 -> ticks(c, cx, cy, r * 0.25f, r * 0.85f)
            1 -> {
                c.drawCircle(cx, cy, r * 0.55f, paint)
                ticks(c, cx, cy, r * 0.3f, r * 0.9f)
            }
            2 -> {
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, cy, r * 0.16f + grow / 2f, paint)
            }
            3 -> brackets(c, cx, cy, r * 0.75f, r * 0.35f)
            else -> {
                paint.pathEffect = DashPathEffect(floatArrayOf(w * 2.2f, w * 2.2f), 0f)
                c.drawCircle(cx, cy, r * 0.75f, paint)
                paint.pathEffect = null
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, cy, r * 0.1f + grow / 2f, paint)
            }
        }
        paint.pathEffect = null
        paint.style = Paint.Style.STROKE
    }

    private fun ticks(c: Canvas, cx: Float, cy: Float, from: Float, to: Float) {
        c.drawLine(cx, cy - from, cx, cy - to, paint)
        c.drawLine(cx, cy + from, cx, cy + to, paint)
        c.drawLine(cx - from, cy, cx - to, cy, paint)
        c.drawLine(cx + from, cy, cx + to, cy, paint)
    }

    private fun brackets(c: Canvas, cx: Float, cy: Float, half: Float, len: Float) {
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                val x = cx + sx * half
                val y = cy + sy * half
                c.drawLine(x, y, x - sx * len, y, paint)
                c.drawLine(x, y, x, y - sy * len, paint)
            }
        }
    }
}

/** View crosshair di tengah layar. Jendelanya tidak bisa disentuh, jadi sentuhan tetap sampai ke game. */
class CrosshairView(ctx: Context) : View(ctx) {
    private var style = 0
    private var color = Color.WHITE
    private var opacity = 100

    fun set(style: Int, color: Int, opacity: Int) {
        if (style == this.style && color == this.color && opacity == this.opacity) return
        this.style = style
        this.color = color
        this.opacity = opacity
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        CrosshairDrawer.draw(canvas, style, width / 2f, height / 2f, size, color, opacity)
    }
}
