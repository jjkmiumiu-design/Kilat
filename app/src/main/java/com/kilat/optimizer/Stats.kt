package com.kilat.optimizer

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

data class Stats(
    val ramTotalGb: Double,
    val ramUsedGb: Double,
    val battery: Int,
    val tempC: Double,
    val storageFreeGb: Double,
    val storageUsedPct: Int,
)

/** Membaca RAM, baterai, dan penyimpanan langsung dari sistem. Tidak butuh Shizuku. */
fun readStats(ctx: Context): Stats {
    val gb = 1024.0 * 1024.0 * 1024.0

    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mem)

    val bat: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

    val fs = StatFs(Environment.getDataDirectory().path)
    val total = fs.blockCountLong * fs.blockSizeLong
    val free = fs.availableBlocksLong * fs.blockSizeLong
    val usedPct = if (total == 0L) 0 else (100 - free * 100 / total).toInt()

    return Stats(
        ramTotalGb = mem.totalMem / gb,
        ramUsedGb = (mem.totalMem - mem.availMem) / gb,
        battery = if (level < 0 || scale == 0) 0 else level * 100 / scale,
        tempC = temp,
        storageFreeGb = free / gb,
        storageUsedPct = usedPct,
    )
}
