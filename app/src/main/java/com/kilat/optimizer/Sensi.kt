package com.kilat.optimizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Zona sensitivitas: area bundar di layar. Gerakan jari yang dimulai di dalam zona dilebarkan
 * sesuai [sx] dan [sy], jadi gerak kamera di game terasa lebih cepat.
 * Posisi disimpan sebagai pecahan 0..1 dari lebar dan tinggi layar.
 */
data class SensiZone(
    val id: Int,
    val cx: Float,
    val cy: Float,
    val sizeDp: Int,
    val useGlobal: Boolean,
    val sx: Float,
    val sy: Float,
    /** Aksi tap saat jari menyentuh zona: [SensiPrefs.TAP_OFF] dan seterusnya. */
    val tap: Int = 0,
    /** Titik yang diketuk, pecahan lebar dan tinggi layar. */
    val tx: Float = 0.82f,
    val ty: Float = 0.5f,
    /** Jarak antar ketukan untuk mode berulang (ms). */
    val repeatMs: Int = 100,
    /** Lama jari menekan pada tiap ketukan (ms). Boleh sangat singkat. */
    val holdMs: Int = 30,
)

data class SensiData(val zones: List<SensiZone>, val gx: Float, val gy: Float) {
    fun xOf(z: SensiZone): Float = if (z.useGlobal) gx else z.sx
    fun yOf(z: SensiZone): Float = if (z.useGlobal) gy else z.sy
}

object SensiPrefs {
    const val MIN = 1.0f
    const val MAX = 5.0f
    const val STEP = 0.05f
    val STEPS: Int = ((MAX - MIN) / STEP).roundToInt()

    const val SIZE_MIN = 60
    const val SIZE_MAX = 260
    const val SIZE_STEP = 10
    const val SIZE_DEFAULT = 140
    const val MAX_ZONES = 6
    const val DEFAULT_VALUE = 1.2f

    /** Aksi tap: 0 mati, 1 sekali saat jari turun, 2 berulang selama jari ditahan, 3 dua kali, 4 saat jari diangkat. */
    const val TAP_OFF = 0
    const val TAP_ONCE = 1
    const val TAP_REPEAT = 2
    const val TAP_DOUBLE = 3
    const val TAP_RELEASE = 4
    val TAP_NAMES = arrayOf("Mati", "Tap", "Ulang", "Dobel", "Lepas")
    const val REPEAT_MIN = 10
    const val REPEAT_MAX = 1000
    const val REPEAT_STEP = 5
    const val HOLD_MIN = 5
    const val HOLD_MAX = 200
    const val HOLD_STEP = 5

    /** Membulatkan ke kelipatan [STEP] dan membatasi ke [MIN]..[MAX]. */
    fun snap(v: Float): Float {
        val n = ((v.coerceIn(MIN, MAX) - MIN) / STEP).roundToInt()
        return (MIN + n * STEP).times(100f).roundToInt() / 100f
    }

    fun read(ctx: Context): SensiData {
        val p = GameToolsPrefs.prefs(ctx)
        val zones = ArrayList<SensiZone>()
        try {
            val arr = JSONArray(p.getString("sensi_zones", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                zones += SensiZone(
                    id = o.getInt("id"),
                    cx = o.getDouble("cx").toFloat().coerceIn(0f, 1f),
                    cy = o.getDouble("cy").toFloat().coerceIn(0f, 1f),
                    sizeDp = o.getInt("size").coerceIn(SIZE_MIN, SIZE_MAX),
                    useGlobal = o.optBoolean("g", false),
                    sx = snap(o.optDouble("sx", DEFAULT_VALUE.toDouble()).toFloat()),
                    sy = snap(o.optDouble("sy", DEFAULT_VALUE.toDouble()).toFloat()),
                    tap = o.optInt("tap", 0).coerceIn(0, TAP_NAMES.size - 1),
                    tx = o.optDouble("tx", 0.82).toFloat().coerceIn(0f, 1f),
                    ty = o.optDouble("ty", 0.5).toFloat().coerceIn(0f, 1f),
                    repeatMs = o.optInt("rep", 100).coerceIn(REPEAT_MIN, REPEAT_MAX),
                    holdMs = o.optInt("hold", 30).coerceIn(HOLD_MIN, HOLD_MAX),
                )
            }
        } catch (e: Exception) {
            zones.clear()
        }
        return SensiData(
            zones.take(MAX_ZONES),
            snap(p.getFloat("sensi_gx", DEFAULT_VALUE)),
            snap(p.getFloat("sensi_gy", DEFAULT_VALUE)),
        )
    }

    fun write(ctx: Context, d: SensiData) {
        val arr = JSONArray()
        for (z in d.zones) {
            arr.put(
                JSONObject()
                    .put("id", z.id)
                    .put("cx", z.cx.toDouble())
                    .put("cy", z.cy.toDouble())
                    .put("size", z.sizeDp)
                    .put("g", z.useGlobal)
                    .put("sx", z.sx.toDouble())
                    .put("sy", z.sy.toDouble())
                    .put("tap", z.tap)
                    .put("tx", z.tx.toDouble())
                    .put("ty", z.ty.toDouble())
                    .put("rep", z.repeatMs)
                    .put("hold", z.holdMs)
            )
        }
        GameToolsPrefs.prefs(ctx).edit()
            .putString("sensi_zones", arr.toString())
            .putFloat("sensi_gx", d.gx)
            .putFloat("sensi_gy", d.gy)
            .apply()
    }

    /** Posisi bawaan zona: 0 kiri, 1 tengah, 2 kanan (pecahan lebar layar). */
    val PRESET_X = floatArrayOf(0.20f, 0.50f, 0.80f)
    const val PRESET_Y = 0.5f
    val PRESET_NAMES = arrayOf("Kiri", "Tengah", "Kanan")

    /** Preset yang cocok dengan zona pertama, atau -1 bila posisinya sudah digeser sendiri. */
    fun presetOf(d: SensiData): Int {
        val z = d.zones.firstOrNull() ?: return -1
        for (i in PRESET_X.indices) if (kotlin.math.abs(z.cx - PRESET_X[i]) < 0.04f) return i
        return -1
    }

    /**
     * Menaruh zona pertama di posisi preset (membuatnya bila belum ada), mengaktifkan fitur Sensi,
     * lalu mengembalikan id zona itu. Nilai X dan Y tetap, dan zona baru memakai nilai global.
     */
    fun applyPreset(ctx: Context, pos: Int): Int {
        val d = read(ctx)
        val x = PRESET_X[pos.coerceIn(0, 2)]
        val zones = d.zones.toMutableList()
        val id: Int
        if (zones.isEmpty()) {
            id = 1
            zones += SensiZone(id, x, PRESET_Y, SIZE_DEFAULT, true, DEFAULT_VALUE, DEFAULT_VALUE)
        } else {
            id = zones[0].id
            zones[0] = zones[0].copy(cx = x, cy = PRESET_Y)
        }
        write(ctx, SensiData(zones, d.gx, d.gy))
        GameToolsPrefs.setSensiOn(ctx, true)
        return id
    }

    fun setGlobal(ctx: Context, x: Float? = null, y: Float? = null) {
        val e = GameToolsPrefs.prefs(ctx).edit()
        if (x != null) e.putFloat("sensi_gx", snap(x))
        if (y != null) e.putFloat("sensi_gy", snap(y))
        e.apply()
    }
}

/** Hitungan posisi sentuhan yang disuntikkan. Dipisah supaya mudah diuji. */
object SensiMath {
    /** Jarak aman dari tepi layar, supaya titik suntik tidak keluar layar. */
    const val MARGIN = 12f

    fun clampX(v: Float, w: Float): Float = v.coerceIn(MARGIN, w - MARGIN)
    fun clampY(v: Float, h: Float): Float = v.coerceIn(MARGIN, h - MARGIN)

    fun inside(x: Float, y: Float, w: Float, h: Float): Boolean =
        x >= MARGIN && x <= w - MARGIN && y >= MARGIN && y <= h - MARGIN
}
