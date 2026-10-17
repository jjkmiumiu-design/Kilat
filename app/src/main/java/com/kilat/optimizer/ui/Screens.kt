package com.kilat.optimizer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.kilat.optimizer.LicenseResult
import com.kilat.optimizer.Preset
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kilat.optimizer.Group
import com.kilat.optimizer.MainViewModel
import com.kilat.optimizer.Shell
import com.kilat.optimizer.ShizukuStatus
import com.kilat.optimizer.TWEAKS
import com.kilat.optimizer.Ui

// ---------- akar ----------

@Composable
fun App(vm: MainViewModel, license: LicenseResult.Valid) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val k = LocalK.current
    var screen by rememberSaveable { mutableStateOf("home") }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }
    LaunchedEffect(screen, ui.status) {
        if (screen == "display") vm.loadDisplay()
        if (screen == "bloat") vm.loadBloat()
        if (screen == "games" || screen == "gametools") vm.loadGames()
    }
    BackHandler(enabled = screen != "home") { screen = if (screen == "gametools") "games" else "home" }

    Box(Modifier.fillMaxSize().background(k.bg).systemBarsPadding()) {
        val back = { screen = "home" }
        when (screen) {
            "display" -> TweakScreen("Tampilan", ui, vm, Group.DISPLAY, back)
            "touch" -> TweakScreen("Sentuhan", ui, vm, Group.TOUCH, back)
            "bloat" -> BloatScreen(ui, vm, back)
            "games" -> GamesScreen(ui, vm, back, openTools = { screen = "gametools" })
            "gametools" -> GameToolsScreen(ui, back = { screen = "games" })
            else -> Home(ui, vm, license) { screen = it }
        }
    }

}

// ---------- komponen dasar ----------

@Composable
internal fun Txt(
    text: String,
    size: Int = 15,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalK.current.fg,
    align: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    Text(text, modifier = modifier, color = color, fontSize = size.sp, fontWeight = weight, textAlign = align)
}

@Composable
internal fun KCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val k = LocalK.current
    val shape = RoundedCornerShape(18.dp)
    val base = modifier.clip(shape).background(k.surface).border(1.dp, k.line, shape)
    Column(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(14.dp),
        content = content,
    )
}

@Composable
internal fun Label(text: String) {
    Txt(text, 12, FontWeight.Medium, LocalK.current.muted, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
}

@Composable
internal fun SwitchRow(title: String, desc: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    val k = LocalK.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, 15, FontWeight.SemiBold)
            if (desc.isNotEmpty()) Txt(desc, 13, color = k.muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = k.accent, checkedThumbColor = k.accentInk,
                uncheckedTrackColor = k.track, uncheckedThumbColor = k.fg,
                uncheckedBorderColor = k.line,
            ),
        )
    }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    val k = LocalK.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(k.surface2).border(1.dp, k.line, shape)
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, 15, FontWeight.SemiBold) }
}

@Composable
internal fun ScreenFrame(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val k = LocalK.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Kembali", tint = k.fg)
            }
            Txt(title, 22, FontWeight.Bold)
        }
        content()
    }
}

// ---------- beranda ----------

@Composable
private fun Home(ui: Ui, vm: MainViewModel, license: LicenseResult.Valid, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val k = LocalK.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(k.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Bolt, null, tint = k.accentInk, modifier = Modifier.size(20.dp)) }
            Txt("Kilat", 22, FontWeight.Bold)
        }

        StatusBanner(ui.status, onPermission = { Shell.requestPermission() }, onOpen = { openShizuku(ctx) })
        LicenseChip(license)
        Hero(ui, onOptimize = vm::optimizeAll, onRevert = vm::revertAll)
        StatsGrid(ui)

        Label("MODUL")
        val display = TWEAKS.filter { it.group == Group.DISPLAY }
        val touch = TWEAKS.filter { it.group == Group.TOUCH }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModuleTile(
                Modifier.weight(1f).fillMaxHeight(), Icons.Outlined.DeleteSweep, "Bloat",
                if (ui.bloat.isEmpty()) "Pilih aplikasi" else "${ui.bloat.count { it.on }} dimatikan",
            ) { go("bloat") }
            ModuleTile(
                Modifier.weight(1f).fillMaxHeight(), Icons.Outlined.Smartphone, "Tampilan",
                "${display.count { it.id in ui.active }} dari ${display.size} aktif",
            ) { go("display") }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModuleTile(
                Modifier.weight(1f).fillMaxHeight(), Icons.Outlined.TouchApp, "Sentuhan",
                "${touch.count { it.id in ui.active }} dari ${touch.size} aktif",
            ) { go("touch") }
            ModuleTile(
                Modifier.weight(1f).fillMaxHeight(), Icons.Outlined.SportsEsports, "Game",
                if (ui.games.isEmpty()) "Pilih game" else "${ui.games.count { it.on }} mode performa",
            ) { go("games") }
        }
    }
}

private fun openShizuku(ctx: Context) {
    val intent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
    ctx.startActivity(intent)
}

@Composable
private fun StatusBanner(status: ShizukuStatus, onPermission: () -> Unit, onOpen: () -> Unit) {
    val k = LocalK.current
    val (text, color) = when (status) {
        ShizukuStatus.READY -> "Shizuku siap" to k.good
        ShizukuStatus.CONNECTING -> "Menyambungkan ke Shizuku…" to k.warn
        ShizukuStatus.NEEDS_PERMISSION -> "Shizuku butuh izin" to k.warn
        ShizukuStatus.NOT_RUNNING -> "Shizuku belum berjalan" to k.accent
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(k.surface).border(1.dp, k.line, shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Txt(text, 14, FontWeight.SemiBold, modifier = Modifier.weight(1f))
        when (status) {
            ShizukuStatus.NEEDS_PERMISSION -> TextButton(onClick = onPermission) { Text("Beri izin", color = k.accent) }
            ShizukuStatus.NOT_RUNNING -> TextButton(onClick = onOpen) { Text("Buka Shizuku", color = k.accent) }
            else -> Spacer(Modifier.height(36.dp))
        }
    }
}

/** Status lisensi: sisa waktu berjalan. Hanya bagian ini yang diperbarui tiap detik. */
@Composable
private fun LicenseChip(info: LicenseResult.Valid) {
    val k = LocalK.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val unlimited = info.expiresAtMs == 0L
    val remain = if (unlimited) Long.MAX_VALUE else (info.expiresAtMs - now).coerceAtLeast(0L)
    val color = when {
        unlimited -> k.good
        remain < 3_600_000L -> k.accent
        remain < 86_400_000L -> k.warn
        else -> k.good
    }
    val title = if (info.name.isBlank()) "Lisensi aktif" else "Lisensi aktif · ${info.name}"
    val sub = if (unlimited) "Tanpa batas waktu"
    else "Sisa ${formatRemaining(remain)} · ${formatExpiry(info.expiresAtMs)}"

    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(k.surface).border(1.dp, k.line, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f)) {
            Txt(title, 14, FontWeight.SemiBold)
            Txt(sub, 12, color = if (remain < 86_400_000L) color else k.muted)
        }
    }
}

private fun ago(ms: Long): String {
    if (ms == 0L) return "Belum dioptimalkan"
    val m = (System.currentTimeMillis() - ms) / 60000
    return when {
        m < 1 -> "Dioptimalkan baru saja"
        m < 60 -> "Dioptimalkan $m menit lalu"
        m < 1440 -> "Dioptimalkan ${m / 60} jam lalu"
        else -> "Dioptimalkan ${m / 1440} hari lalu"
    }
}

@Composable
private fun Hero(ui: Ui, onOptimize: () -> Unit, onRevert: () -> Unit) {
    val k = LocalK.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(k.surface).border(1.dp, k.line, shape).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScoreRing(ui.score)
        Txt(ago(ui.lastAt), 13, color = k.muted)

        val ctaShape = RoundedCornerShape(16.dp)
        Box(
            Modifier.fillMaxWidth().clip(ctaShape).background(k.accent)
                .clickable(enabled = !ui.busy, onClick = onOptimize).padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Txt(if (ui.busy && ui.progress.isNotEmpty()) ui.progress else "Optimalkan semua", 18, FontWeight.Bold, k.accentInk)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onRevert, enabled = !ui.busy) {
                Icon(Icons.Outlined.Replay, null, tint = k.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Kembalikan semua", color = k.muted, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ScoreRing(score: Int) {
    val k = LocalK.current
    val sweep by animateFloatAsState(360f * score / 100f, tween(900), label = "ring")
    val color = when {
        score < 70 -> k.accent
        score < 85 -> k.warn
        else -> k.good
    }
    Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 10.dp.toPx()
            drawArc(color = k.track, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = Stroke(stroke))
            drawArc(
                color = color, startAngle = -90f, sweepAngle = sweep, useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Txt("$score", 50, FontWeight.Bold)
                Txt("/100", 16, color = k.muted, modifier = Modifier.padding(bottom = 8.dp))
            }
            Txt("SKOR TWEAK", 11, FontWeight.Medium, k.muted)
        }
    }
}

@Composable
private fun StatsGrid(ui: Ui) {
    val k = LocalK.current
    val s = ui.stats
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KCard(Modifier.weight(1f).fillMaxHeight()) {
            Txt("RAM", 13, color = k.muted)
            Txt(s?.let { "%.1f GB".format(it.ramTotalGb) } ?: "-", 22, FontWeight.Bold)
            Txt(s?.let { "%.1f GB terpakai".format(it.ramUsedGb) } ?: "", 13, color = k.muted)
            if (s != null) Meter((s.ramUsedGb / s.ramTotalGb).toFloat(), k.good)
        }
        KCard(Modifier.weight(1f).fillMaxHeight()) {
            Txt("CPU", 13, color = k.muted)
            Txt(ui.governor, 22, FontWeight.Bold)
            Txt("governor", 13, color = k.muted)
        }
    }
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KCard(Modifier.weight(1f).fillMaxHeight()) {
            Txt("Baterai", 13, color = k.muted)
            Txt(s?.let { "${it.battery}%" } ?: "-", 22, FontWeight.Bold)
            Txt(s?.let { "%.1f °C".format(it.tempC) } ?: "", 13, color = k.muted)
        }
        KCard(Modifier.weight(1f).fillMaxHeight()) {
            Txt("Penyimpanan", 13, color = k.muted)
            Txt(s?.let { "%.0f GB".format(it.storageFreeGb) } ?: "-", 22, FontWeight.Bold)
            Txt(s?.let { "${it.storageUsedPct}% terpakai" } ?: "", 13, color = k.muted)
            if (s != null) Meter(s.storageUsedPct / 100f, if (s.storageUsedPct > 85) k.warn else k.good)
        }
    }
}

@Composable
private fun Meter(fraction: Float, color: Color) {
    val k = LocalK.current
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(k.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
    }
}

@Composable
private fun ModuleTile(modifier: Modifier, icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val k = LocalK.current
    KCard(modifier, onClick) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(k.surface2),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = k.accent, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(10.dp))
        Txt(title, 17, FontWeight.Bold)
        Txt(sub, 12, color = k.muted)
    }
}

// ---------- layar modul ----------

@Composable
private fun TweakScreen(title: String, ui: Ui, vm: MainViewModel, group: Group, back: () -> Unit) {
    val k = LocalK.current
    ScreenFrame(title, back) {
        TWEAKS.filter { it.group == group }.forEach { t ->
            SwitchRow(t.title, t.desc, t.id in ui.active, enabled = !ui.busy) { vm.toggleTweak(t.id) }
        }
        Spacer(Modifier.height(8.dp))
        Txt("Nilai asli disimpan dan dipulihkan saat tweak dimatikan.", 12, color = k.muted)
        if (group == Group.DISPLAY) DisplayEditor(ui, vm)
    }
}

@Composable
private fun DisplayField(value: String, onChange: (String) -> Unit, hint: String, number: Boolean) {
    val k = LocalK.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(hint, color = k.muted) },
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Ascii),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = k.fg,
            unfocusedTextColor = k.fg,
            focusedBorderColor = k.accent,
            unfocusedBorderColor = k.line,
            cursorColor = k.accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DisplayEditor(ui: Ui, vm: MainViewModel) {
    val k = LocalK.current
    val d = ui.display
    var res by rememberSaveable { mutableStateOf("") }
    var dpi by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

    Spacer(Modifier.height(12.dp))
    Label("RESOLUSI DAN DENSITY")

    // Peringatan
    val warnShape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxWidth().clip(warnShape).background(k.accent.copy(alpha = 0.12f))
            .border(1.dp, k.accent.copy(alpha = 0.4f), warnShape).padding(14.dp),
    ) {
        Txt(
            "Perubahan langsung berlaku saat kamu ketuk Terapkan. Bila layar jadi sulit dipakai, " +
                "buka lagi menu ini dan ketuk Kembalikan bawaan perangkat.",
            13, color = k.accent,
        )
    }

    // Info layar
    KCard(Modifier.fillMaxWidth()) {
        if (d == null) {
            Txt("Hubungkan Shizuku untuk membaca ukuran layar.", 14, color = k.muted)
        } else {
            Txt("FISIK", 11, FontWeight.Medium, k.muted)
            Txt("${d.physW}x${d.physH} · ${d.physDpi} dpi", 18, FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Txt("SAAT INI", 11, FontWeight.Medium, k.muted)
            Txt(
                if (d.hasOverride) "${d.curW}x${d.curH} · ${d.curDpi} dpi" else "Bawaan perangkat",
                16, FontWeight.SemiBold,
            )
            Txt("Smallest width ~${d.smallestDp}dp", 13, color = k.muted)
        }
    }

    // Editor
    KCard(Modifier.fillMaxWidth()) {
        Txt("Isi nilai, lalu terapkan ke perangkat. Kolom kosong berarti tidak diubah.", 14, color = k.muted)
        Spacer(Modifier.height(10.dp))
        DisplayField(res, { res = it }, "Resolusi, contoh 1080x2400", false)
        Spacer(Modifier.height(8.dp))
        DisplayField(dpi, { dpi = it }, "Density, contoh 420", true)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(k.accent)
                .clickable(enabled = d != null) { vm.applyDisplay(res, dpi) }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Txt("Terapkan", 15, FontWeight.Bold, k.accentInk) }
        Spacer(Modifier.height(12.dp))
        DisplayField(name, { name = it }, "Nama preset", false)
        TextButton(onClick = { vm.savePreset(name, res, dpi) }, modifier = Modifier.fillMaxWidth()) {
            Text("Simpan sebagai preset", color = k.muted, fontWeight = FontWeight.SemiBold)
        }
        Txt("Menyimpan atau memuat preset tidak langsung menerapkannya.", 12, color = k.muted)
    }

    // Preset
    KCard(Modifier.fillMaxWidth()) {
        Txt("PRESET TERSIMPAN", 11, FontWeight.Medium, k.muted)
        PresetRow("Bawaan perangkat", "Resolusi dan density bawaan", onLoad = {
            if (d != null) {
                res = "${d.physW}x${d.physH}"
                dpi = d.physDpi.toString()
            }
        }, onDelete = null)
        ui.presets.forEach { p ->
            val detail = listOfNotNull(
                p.w?.let { "${p.w}x${p.h}" },
                p.dpi?.let { "$it dpi" },
            ).joinToString(" · ")
            PresetRow(p.name, detail, onLoad = {
                res = if (p.w != null && p.h != null) "${p.w}x${p.h}" else ""
                dpi = p.dpi?.toString() ?: ""
                name = p.name
            }, onDelete = { vm.deletePreset(p.name) })
        }
    }

    ActionButton("Kembalikan bawaan perangkat", vm::resetDisplay)
    Txt("Mengembalikan resolusi dan density ke bawaan langsung tanpa konfirmasi.", 12, color = k.muted)
}

@Composable
private fun PresetRow(title: String, detail: String, onLoad: () -> Unit, onDelete: (() -> Unit)?) {
    val k = LocalK.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, 15, FontWeight.SemiBold)
            Txt(detail, 13, color = k.muted)
        }
        TextButton(onClick = onLoad) { Text("Muat", color = k.accent) }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("Hapus", color = k.muted) }
        }
    }
}

@Composable
private fun BloatScreen(ui: Ui, vm: MainViewModel, back: () -> Unit) {
    val k = LocalK.current
    ScreenFrame("Aplikasi bawaan", back) {
        ActionButton("Tutup proses latar belakang", vm::killBackground)
        Spacer(Modifier.height(8.dp))
        ActionButton("Bersihkan cache semua aplikasi", vm::trimCaches)
        Label("APLIKASI TERDETEKSI")
        if (ui.status != ShizukuStatus.READY) {
            Txt("Hubungkan Shizuku untuk melihat daftar.", 14, color = k.muted)
        } else if (ui.bloat.isEmpty()) {
            Txt("Tidak ada aplikasi dari daftar ini yang terpasang di HP kamu.", 14, color = k.muted)
        } else {
            ui.bloat.forEach { row -> SwitchRow(row.label, row.pkg, row.on) { vm.toggleBloat(row.pkg) } }
        }
        Spacer(Modifier.height(8.dp))
        Txt(
            "Menonaktifkan aplikasi sistem bisa memengaruhi fitur tertentu. Tombol Kembalikan di beranda mengaktifkan semuanya lagi.",
            12, color = k.muted,
        )
    }
}

@Composable
private fun GamesScreen(ui: Ui, vm: MainViewModel, back: () -> Unit, openTools: () -> Unit) {
    val k = LocalK.current
    ScreenFrame("Game", back) {
        KCard(Modifier.fillMaxWidth(), onClick = openTools) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(k.surface2),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.SportsEsports, null, tint = k.accent, modifier = Modifier.size(22.dp)) }
                Column(Modifier.weight(1f)) {
                    Txt("Game Tools", 16, FontWeight.Bold)
                    Txt("Crosshair, FPS, dan boost game", 13, color = k.muted)
                }
                Txt("›", 24, color = k.muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (Build.VERSION.SDK_INT < 31) {
            Txt("Mode performa game butuh Android 12 atau lebih baru.", 14, color = k.muted)
        } else {
            Txt("Aktifkan mode performa untuk game yang kamu pilih.", 14, color = k.muted)
            Spacer(Modifier.height(4.dp))
            ui.games.forEach { row -> SwitchRow(row.label, row.pkg, row.on) { vm.toggleGame(row.pkg) } }
        }
    }
}
