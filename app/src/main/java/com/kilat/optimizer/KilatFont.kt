package com.kilat.optimizer

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Huruf Kilat (The Bold Font) untuk tampilan berbasis View: panel dan editor di dalam game, FPS, papan uji.
 * Tampilan Compose memakai huruf yang sama lewat KFont di Theme.kt.
 */
object KilatFont {
    @Volatile private var cached: Typeface? = null

    fun get(ctx: Context): Typeface {
        cached?.let { return it }
        val tf = try {
            ctx.applicationContext.resources.getFont(R.font.theboldfont)
        } catch (e: Throwable) {
            Typeface.DEFAULT_BOLD
        }
        cached = tf
        return tf
    }

    /** Memasang huruf Kilat ke semua TextView (termasuk Switch dan Button) di dalam [view]. */
    fun applyTo(view: View) {
        val tf = get(view.context)
        walk(view, tf)
    }

    private fun walk(v: View, tf: Typeface) {
        if (v is TextView && v.typeface !== tf) v.typeface = tf
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), tf)
    }
}
