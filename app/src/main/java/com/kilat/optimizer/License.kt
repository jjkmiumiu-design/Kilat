package com.kilat.optimizer

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

sealed class LicenseResult {
    data class Valid(val name: String, val expiresAtMs: Long) : LicenseResult()
    data class Invalid(val reason: String) : LicenseResult()

    /** Key sah dan berdurasi, tetapi belum pernah diaktifkan di HP ini. */
    data class NeedsActivation(val name: String, val kid: String, val durationSec: Long) : LicenseResult()
}

/**
 * Verifikasi license key secara offline dengan tanda tangan digital ECDSA (P-256).
 * Key hanya bisa dibuat oleh pemegang private key (paket kilat-keygen, terpisah dari source code).
 *
 * Dua jenis masa berlaku:
 *  - "dur": durasi dalam detik. Waktu mulai berjalan saat key pertama kali dimasukkan (diaktifkan).
 *  - "exp": waktu habis mutlak (epoch detik), dihitung sejak key diterbitkan. 0 = tanpa batas.
 */
object License {

    /** Public key. Ganti dengan hasil `python make_key.py init` milikmu sendiri. */
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5TwW5DMb31BSnrd27e9C4yIKSUuOAxL/osZ5vm/0Fqoj0DajvQy3pHRzyWVv+hyqj+y7rIaIUhAUOrk+gd3HZA=="
    private const val PREFS = "kilat_license"
    private const val KEY_SAVED = "key"

    private val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    /** Nama HP yang mudah dibaca, contoh "Xiaomi Redmi 12". */
    fun deviceModel(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return if (model.startsWith(maker, ignoreCase = true)) model
        else "${maker.replaceFirstChar { it.uppercase() }} $model".trim()
    }

    /**
     * HWID: 16 karakter hex, dihitung dari ID Android dan data perangkat keras.
     * Dipakai untuk key yang diikat ke satu HP.
     */
    fun hwid(ctx: Context): String {
        val aid = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val raw = "$aid|${Build.MANUFACTURER}|${Build.MODEL}|${Build.BOARD}"
        return sha256Hex(raw.toByteArray(Charsets.UTF_8)).take(16).uppercase()
    }

    /** HWID untuk ditampilkan, contoh 1A90-8960-0BA0-3F2C. */
    fun hwidDisplay(ctx: Context): String = hwid(ctx).chunked(4).joinToString("-")

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ---------- verifikasi ----------

    /**
     * Memeriksa key tanpa mengubah apa pun. Untuk key berdurasi yang belum aktif,
     * hasilnya [LicenseResult.NeedsActivation]. Gunakan [verifyAndActivate] saat pengguna memasukkan key.
     */
    fun verify(ctx: Context, input: String): LicenseResult {
        val key = input.trim()
        val parts = key.split(".")
        if (parts.size != 3 || parts[0] != "KILAT") return LicenseResult.Invalid("Format key tidak dikenali")

        val body: ByteArray
        val sig: ByteArray
        try {
            body = Base64.decode(parts[1], flags)
            sig = Base64.decode(parts[2], flags)
        } catch (e: IllegalArgumentException) {
            return LicenseResult.Invalid("Key rusak atau terpotong")
        }

        val okSig = try {
            val pub = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(body)
                verify(sig)
            }
        } catch (e: Exception) {
            false
        }
        if (!okSig) return LicenseResult.Invalid("Key tidak valid")

        val json = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            return LicenseResult.Invalid("Isi key rusak")
        }

        val dev = json.optString("dev", "*").replace("-", "").replace(" ", "").uppercase()
        if (dev != "*" && dev != hwid(ctx)) return LicenseResult.Invalid("Key ini untuk perangkat lain")

        if (!clockOk(ctx)) {
            return LicenseResult.Invalid("Jam HP tidak sesuai. Perbaiki tanggal dan jam, lalu coba lagi")
        }

        val name = json.optString("name", "")
        val now = System.currentTimeMillis()

        // Key berdurasi: waktu mulai berjalan saat diaktifkan.
        val durSec = json.optLong("dur", 0L)
        if (durSec > 0) {
            val kid = sha256Hex(sig).take(12)
            val started = readActivation(ctx, kid)
            if (started == null) {
                val actBy = json.optLong("act_by", 0L) * 1000L
                if (actBy != 0L && now > actBy) {
                    return LicenseResult.Invalid("Batas waktu untuk mengaktifkan key ini sudah lewat")
                }
                return LicenseResult.NeedsActivation(name, kid, durSec)
            }
            if (started > now + CLOCK_TOLERANCE_MS) {
                return LicenseResult.Invalid("Data aktivasi tidak sesuai jam HP. Perbaiki tanggal dan jam")
            }
            val end = started + durSec * 1000L
            if (now >= end) return LicenseResult.Invalid("Key sudah kedaluwarsa")
            return LicenseResult.Valid(name, end)
        }

        // Key dengan waktu habis mutlak (dihitung sejak diterbitkan).
        val expSec = json.optLong("exp", 0L)
        val expMs = expSec * 1000L
        if (expSec != 0L && now >= expMs) return LicenseResult.Invalid("Key sudah kedaluwarsa")
        return LicenseResult.Valid(name, expMs)
    }

    /**
     * Dipanggil saat pengguna menekan Masuk. Bila key berdurasi belum aktif, waktu mulai
     * berjalan SEKARANG. Catatan aktivasi disimpan di dua tempat:
     *  1. penyimpanan aplikasi, dan
     *  2. pengaturan sistem (Settings.Global) lewat Shizuku, yang tidak ikut terhapus
     *     ketika data aplikasi dihapus atau aplikasi dipasang ulang.
     * Jadi menghapus data aplikasi tidak mengulang masa berlaku key yang sama.
     */
    suspend fun verifyAndActivate(ctx: Context, input: String): LicenseResult {
        val r = verify(ctx, input)
        if (r !is LicenseResult.NeedsActivation) return r

        if (Shell.status.value != ShizukuStatus.READY) {
            return LicenseResult.Invalid("Aktivasi butuh Shizuku. Jalankan Shizuku dan beri izin dulu, lalu coba lagi")
        }

        val record = encodeRecord(r.kid, System.currentTimeMillis())
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("act_${r.kid}", record).apply()
        // Bila pengaturan sistem menolak, aktivasi tetap jalan memakai penyimpanan aplikasi saja.
        runCatching { Shell.runChecked("settings put global kl_${r.kid} $record") }

        val again = verify(ctx, input)
        return if (again is LicenseResult.NeedsActivation) LicenseResult.Invalid("Gagal menyimpan aktivasi") else again
    }

    // ---------- catatan aktivasi ----------

    private fun checksum(kid: String, ts: Long): String =
        sha256Hex("$kid|$ts|kilat-act-v1".toByteArray(Charsets.UTF_8)).take(8)

    private fun encodeRecord(kid: String, ts: Long): String = "$ts.${checksum(kid, ts)}"

    private fun decodeRecord(kid: String, raw: String?): Long? {
        if (raw == null) return null
        val p = raw.split(".")
        if (p.size != 2) return null
        val ts = p[0].toLongOrNull() ?: return null
        return if (p[1] == checksum(kid, ts)) ts else null
    }

    /** Waktu aktivasi paling awal dari dua tempat penyimpanan, atau null bila belum pernah aktif. */
    private fun readActivation(ctx: Context, kid: String): Long? {
        val g = runCatching { Settings.Global.getString(ctx.contentResolver, "kl_$kid") }.getOrNull()
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("act_$kid", null)
        return listOfNotNull(decodeRecord(kid, g), decodeRecord(kid, p)).minOrNull()
    }

    // ---------- key tersimpan ----------

    fun savedKey(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SAVED, null)

    fun save(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SAVED, key.trim()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SAVED).apply()
    }

    /**
     * Dipanggil saat aplikasi dibuka. Mengembalikan (lisensi valid, alasan ditolak).
     * Bila key tersimpan sudah habis atau tidak valid, key dihapus dan alasannya dikembalikan.
     */
    fun restore(ctx: Context): Pair<LicenseResult.Valid?, String?> {
        val k = savedKey(ctx) ?: return null to null
        return when (val r = verify(ctx, k)) {
            is LicenseResult.Valid -> r to null
            is LicenseResult.Invalid -> {
                clear(ctx)
                null to r.reason
            }
            is LicenseResult.NeedsActivation -> {
                clear(ctx)
                null to "Key belum diaktifkan. Masukkan key lagi"
            }
        }
    }

    // ---------- pengaman jam HP ----------

    private const val KEY_LASTSEEN = "lastSeen"
    private const val CLOCK_TOLERANCE_MS = 5 * 60_000L
    private const val PERSIST_EVERY_MS = 60_000L
    private var lastSeen = -1L
    private var lastPersisted = 0L

    /**
     * False bila jam HP terlihat diputar mundur dibanding waktu terakhir yang tercatat.
     * Mencegah masa berlaku key diperpanjang dengan mengubah tanggal HP.
     */
    @Synchronized
    fun clockOk(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (lastSeen < 0) lastSeen = prefs.getLong(KEY_LASTSEEN, 0L)
        if (now + CLOCK_TOLERANCE_MS < lastSeen) return false
        if (now > lastSeen) lastSeen = now
        if (now - lastPersisted > PERSIST_EVERY_MS) {
            prefs.edit().putLong(KEY_LASTSEEN, lastSeen).apply()
            lastPersisted = now
        }
        return true
    }
}
