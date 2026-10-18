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
import androidx.compose.foundation.layout.offset
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
    display: Boolean = false,
) {
    Text(
        text, modifier = modifier, color = color, fontSize = size.sp, fontWeight = weight, textAlign = align,
        fontFamily = if (display) KFont.display else KFont.body,
    )
}

/** Bagian layar: garis di atas, isi di bawahnya. Bukan kartu. */
@Composable
internal fun KCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        Rule()
        val inner = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()
        Column(modifier = inner.padding(vertical = 14.dp), content = content)
    }
}

@Composable
internal fun Label(text: String) {
    Txt(text, 12, FontWeight.SemiBold, LocalK.current.muted, modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
}

@Composable
internal fun SwitchRow(title: String, desc: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    val k = LocalK.current
    Column {
        Rule()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Txt(title, 16)
                if (desc.isNotEmpty()) Txt(desc, 13, color = k.muted)
            }
            KToggle(checked = checked, onChange = { onToggle() }, enabled = enabled)
        }
    }
}

@Composable
internal fun ScreenFrame(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val k = LocalK.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = back) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Kembali", tint = k.fg)
            }
            Txt(title, 20, FontWeight.Medium, display = true, modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

// ---------- beranda ----------

@Composable
private fun Home(ui: Ui, vm: MainViewModel, license: LicenseResult.Valid, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val k = LocalK.current
    val total = TWEAKS.size
    val on = TWEAKS.count { it.id in ui.active }
    val display = TWEAKS.filter { it.group == Group.DISPLAY }
    val touch = TWEAKS.filter { it.group == Group.TOUCH }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BoltMark(20.dp, k.accent)
            Spacer(Modifier.width(8.dp))
            Txt("Kilat", 22, FontWeight.Medium, display = true, modifier = Modifier.weight(1f))
            ShizukuState(ui.status)
        }
        ShizukuAction(ui.status, onPermission = { Shell.requestPermission() }, onOpen = { openShizuku(ctx) })

        Spacer(Modifier.height(24.dp))
        Txt("$on dari $total tweak aktif", 20, FontWeight.Medium, display = true)
        Txt(ago(ui.lastAt), 13, color = k.muted)
        Spacer(Modifier.height(14.dp))
        KButton(
            if (ui.busy && ui.progress.isNotEmpty()) ui.progress else "Optimalkan semua",
            vm::optimizeAll, Modifier.fillMaxWidth(), enabled = !ui.busy,
        )
        Spacer(Modifier.height(8.dp))
        KButton("Kembalikan semua", vm::revertAll, Modifier.fillMaxWidth(), primary = false, enabled = !ui.busy)

        Label("Modul")
        NavRow(
            "Aplikasi bawaan",
            if (ui.bloat.isEmpty()) "Pilih aplikasi yang dimatikan" else "${ui.bloat.count { it.on }} dimatikan",
            Icons.Outlined.DeleteSweep,
        ) { go("bloat") }
        NavRow("Tampilan", "${display.count { it.id in ui.active }} dari ${display.size} aktif", Icons.Outlined.Smartphone) { go("display") }
        NavRow("Sentuhan", "${touch.count { it.id in ui.active }} dari ${touch.size} aktif", Icons.Outlined.TouchApp) { go("touch") }
        NavRow(
            "Game",
            if (ui.games.isEmpty()) "Pilih game" else "${ui.games.count { it.on }} mode performa",
            Icons.Outlined.SportsEsports,
        ) { go("games") }
        Rule()

        Label("Perangkat")
        Facts(ui)

        Spacer(Modifier.height(24.dp))
        LicenseLine(license)
    }
}

private fun openShizuku(ctx: Context) {
    val intent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
    ctx.startActivity(intent)
}

@Composable
private fun ShizukuState(status: ShizukuStatus) {
    val k = LocalK.current
    val (text, color) = when (status) {
        ShizukuStatus.READY -> "Shizuku siap" to k.good
        ShizukuStatus.CONNECTING -> "Menyambung…" to k.warn
        ShizukuStatus.NEEDS_PERMISSION -> "Butuh izin" to k.warn
        ShizukuStatus.NOT_RUNNING -> "Belum jalan" to k.bad
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Lamp(color)
        Txt(text, 13, FontWeight.Medium, k.muted)
    }
}

/** Tombol tindakan bila Shizuku belum siap. Tidak tampil sama sekali saat semuanya beres. */
@Composable
private fun ShizukuAction(status: ShizukuStatus, onPermission: () -> Unit, onOpen: () -> Unit) {
    val k = LocalK.current
    when (status) {
        ShizukuStatus.NEEDS_PERMISSION -> TextButton(onClick = onPermission, modifier = Modifier.offset(x = (-12).dp)) {
            Text("Beri izin Shizuku", color = k.link, fontWeight = FontWeight.Bold)
        }
        ShizukuStatus.NOT_RUNNING -> TextButton(onClick = onOpen, modifier = Modifier.offset(x = (-12).dp)) {
            Text("Buka Shizuku", color = k.link, fontWeight = FontWeight.Bold)
        }
        else -> Unit
    }
}

/** Status lisensi: sisa waktu berjalan. Hanya bagian ini yang diperbarui tiap detik. */
@Composable
private fun LicenseLine(info: LicenseResult.Valid) {
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
        remain < 3_600_000L -> k.bad
        remain < 86_400_000L -> k.warn
        else -> k.good
    }
    val title = if (info.name.isBlank()) "Lisensi aktif" else "Lisensi aktif untuk ${info.name}"
    val sub = if (unlimited) "Tanpa batas waktu"
    else "Sisa ${formatRemaining(remain)}, ${formatExpiry(info.expiresAtMs)}"

    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 6.dp)) { Lamp(color) }
        Column {
            Txt(title, 14, FontWeight.Medium)
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
private fun Facts(ui: Ui) {
    val k = LocalK.current
    val s = ui.stats
    FactRow(
        "RAM",
        s?.let { "%.1f dari %.1f GB".format(it.ramUsedGb, it.ramTotalGb) } ?: "-",
        s?.let { (it.ramUsedGb / it.ramTotalGb).toFloat() },
    )
    FactRow(
        "Penyimpanan",
        s?.let { "%.0f GB kosong".format(it.storageFreeGb) } ?: "-",
        s?.let { it.storageUsedPct / 100f },
        if ((s?.storageUsedPct ?: 0) > 85) k.warn else k.good,
    )
    FactRow("Baterai", s?.let { "${it.battery}%  ·  " + "%.1f °C".format(it.tempC) } ?: "-")
    FactRow("CPU governor", ui.governor)
    Rule()
}

@Composable
private fun FactRow(label: String, value: String, fraction: Float? = null, color: Color = Color.Unspecified) {
    val k = LocalK.current
    Column {
        Rule()
        Column(Modifier.padding(vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(label, 14, color = k.muted, modifier = Modifier.weight(1f))
                Txt(value, 15, FontWeight.Medium)
            }
            if (fraction != null) Meter(fraction, if (color == Color.Unspecified) k.good else color)
        }
    }
}

/** Baris menu: ikon di kiri, nama modul dan keterangannya, panah di kanan. */
@Composable
private fun NavRow(title: String, sub: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val k = LocalK.current
    Column {
        Rule()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = k.muted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Txt(title, 16)
                Txt(sub, 13, color = k.muted)
            }
            Txt("›", 22, color = k.muted)
        }
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
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = k.fg,
            unfocusedTextColor = k.fg,
            focusedBorderColor = k.fg,
            unfocusedBorderColor = k.line,
            cursorColor = k.fg,
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
    Label("Resolusi dan density")

    // Peringatan: garis merah di kiri, teks biasa.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(k.surface)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(k.warn))
        Txt(
            "Perubahan langsung berlaku saat kamu ketuk Terapkan. Bila layar jadi sulit dipakai, " +
                "buka lagi menu ini dan ketuk Kembalikan bawaan perangkat.",
            13, modifier = Modifier.padding(14.dp),
        )
    }

    // Info layar
    KCard(Modifier.fillMaxWidth()) {
        if (d == null) {
            Txt("Hubungkan Shizuku untuk membaca ukuran layar.", 14, color = k.muted)
        } else {
            Txt("Fisik", 13, color = k.muted)
            Txt("${d.physW}x${d.physH}, ${d.physDpi} dpi", 18, FontWeight.Medium, display = true)
            Spacer(Modifier.height(10.dp))
            Txt("Saat ini", 13, color = k.muted)
            Txt(
                if (d.hasOverride) "${d.curW}x${d.curH}, ${d.curDpi} dpi" else "Bawaan perangkat",
                18, FontWeight.Medium, display = true,
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
        KButton("Terapkan", { vm.applyDisplay(res, dpi) }, Modifier.fillMaxWidth(), enabled = d != null)
        Spacer(Modifier.height(12.dp))
        DisplayField(name, { name = it }, "Nama preset", false)
        TextButton(onClick = { vm.savePreset(name, res, dpi) }, modifier = Modifier.fillMaxWidth()) {
            Text("Simpan sebagai preset", color = k.muted, fontWeight = FontWeight.SemiBold)
        }
        Txt("Menyimpan atau memuat preset tidak langsung menerapkannya.", 12, color = k.muted)
    }

    // Preset
    KCard(Modifier.fillMaxWidth()) {
        Txt("Preset tersimpan", 13, color = k.muted)
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

    KButton("Kembalikan bawaan perangkat", vm::resetDisplay, Modifier.fillMaxWidth(), primary = false)
    Txt("Mengembalikan resolusi dan density ke bawaan langsung tanpa konfirmasi.", 12, color = k.muted)
}

@Composable
private fun PresetRow(title: String, detail: Str
