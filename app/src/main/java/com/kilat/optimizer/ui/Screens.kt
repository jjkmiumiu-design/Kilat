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
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
fun App(vm: MainViewModel, license: LicenseResult.Valid, onLogout: () -> Unit = {}) {
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

    Column(Modifier.fillMaxSize().background(k.bg).systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val back = { screen = "home" }
            when (screen) {
                "display" -> TweakScreen("Tampilan", ui, vm, Group.DISPLAY)
                "touch" -> TweakScreen("Sentuhan", ui, vm, Group.TOUCH)
                "bloat" -> BloatScreen(ui, vm, back)
                "games" -> GamesScreen(ui, vm, openTools = { screen = "gametools" })
                "gametools" -> GameToolsScreen(ui, back = { screen = "games" })
                else -> Home(ui, vm, license, onLogout) { screen = it }
            }
        }
        if (screen != "gametools") {
            val current = when (screen) {
                "display", "touch", "games" -> screen
                else -> "home"
            }
            BottomBar(current) { screen = it }
        }
    }
}

private class Dest(val route: String, val label: String, val icon: ImageVector)

private val DESTS = listOf(
    Dest("home", "Beranda", Icons.Outlined.Home),
    Dest("display", "Tampilan", Icons.Outlined.Smartphone),
    Dest("touch", "Sentuhan", Icons.Outlined.TouchApp),
    Dest("games", "Game", Icons.Outlined.SportsEsports),
)

/** Bilah navigasi bawah: tab terpilih berupa kapsul 3D tipis dengan garis cahaya putih. */
@Composable
private fun BottomBar(current: String, onSelect: (String) -> Unit) {
    val k = LocalK.current
    Row(
        Modifier.fillMaxWidth().background(k.surface).drawBehind {
            drawLine(k.line, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1.dp.toPx())
        }.padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DESTS.forEach { d ->
            val sel = d.route == current
            Column(
                (if (sel) Modifier.glossy(RoundedCornerShape(20.dp)) else Modifier.clip(RoundedCornerShape(20.dp)))
                    .clickable { onSelect(d.route) }
                    .padding(horizontal = if (sel) 22.dp else 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(d.icon, contentDescription = null, tint = if (sel) k.link else k.muted, modifier = Modifier.size(26.dp))
                Txt(d.label, 12, if (sel) FontWeight.Bold else FontWeight.Medium, if (sel) k.link else k.muted)
            }
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

/** Bagian layar berbentuk kartu membulat. */
@Composable
internal fun KCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(vertical = 5.dp)) {
        Panel(onClick = onClick, content = content)
    }
}

@Composable
internal fun Label(text: String) {
    Txt(text, 13, FontWeight.Bold, LocalK.current.link, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 6.dp))
}

/** Satu tweak berbentuk kartu: judul ungu tebal, keterangan, dan saklar di kanan. [tag] tampil sebagai label kecil. */
@Composable
internal fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean = true,
    tag: String? = null,
    onToggle: () -> Unit,
) {
    val k = LocalK.current
    Column(Modifier.padding(vertical = 5.dp)) {
        Panel {
            if (tag != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt(title, 18, FontWeight.Bold, k.link, display = true, modifier = Modifier.weight(1f))
                    KChip(tag)
                }
            } else {
                Txt(title, 18, FontWeight.Bold, k.link, display = true)
            }
            if (desc.isNotEmpty()) Txt(desc, 14, color = k.muted, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                KToggle(checked = checked, onChange = { onToggle() }, enabled = enabled)
            }
        }
    }
}

/** Kerangka layar. Dengan [back] muncul tombol kembali; tanpa itu (tab utama) hanya judul. */
@Composable
internal fun ScreenFrame(title: String, back: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val k = LocalK.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = if (back != null) 4.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (back != null) {
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Kembali", tint = k.link)
                }
            }
            Txt(title, 24, FontWeight.Bold, k.link, display = true, modifier = Modifier.padding(start = if (back != null) 4.dp else 0.dp))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            content = content,
        )
    }
}

// ---------- beranda ----------

@Composable
private fun Home(ui: Ui, vm: MainViewModel, license: LicenseResult.Valid, onLogout: () -> Unit, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val k = LocalK.current
    val total = TWEAKS.size
    val on = TWEAKS.count { it.id in ui.active }
    var askLogout by rememberSaveable { mutableStateOf(false) }

    if (askLogout) {
        AlertDialog(
            onDismissRequest = { askLogout = false },
            containerColor = k.surface,
            title = { Text("Keluar dari Kilat?", color = k.fg) },
            text = { Text("License key dihapus dari HP ini. Kamu perlu memasukkannya lagi untuk masuk.", color = k.muted) },
            confirmButton = {
                TextButton(onClick = { askLogout = false; onLogout() }) { Text("Keluar", color = k.bad, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { askLogout = false }) { Text("Batal", color = k.link) }
            },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Bilah atas: logo, nama, dan dua tombol
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoDisc(48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt("KILAT", 26, FontWeight.Bold, k.link, display = true)
                Txt("Optimizer tanpa root", 14)
            }
            IconTile(Icons.Outlined.Security, "Status Shizuku") {
                when (ui.status) {
                    ShizukuStatus.NEEDS_PERMISSION -> Shell.requestPermission()
                    ShizukuStatus.NOT_RUNNING -> openShizuku(ctx)
                    else -> Toast.makeText(ctx, if (ui.status == ShizukuStatus.READY) "Shizuku siap" else "Menyambung ke Shizuku…", Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.width(8.dp))
            IconTile(Icons.AutoMirrored.Outlined.Logout, "Keluar") { askLogout = true }
        }

        Spacer(Modifier.height(18.dp))
        Panel {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(6.dp))
                Txt("$on / $total", 44, FontWeight.Bold, k.link, display = true)
                Txt("tweak aktif", 15, color = k.muted)
                Txt(ago(ui.lastAt), 13, color = k.muted, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(10.dp))
                ShizukuState(ui.status)
                Spacer(Modifier.height(16.dp))
                KButton(
                    if (ui.busy && ui.progress.isNotEmpty()) ui.progress else "Optimalkan semua",
                    vm::optimizeAll, Modifier.fillMaxWidth(), enabled = !ui.busy,
                )
                Spacer(Modifier.height(8.dp))
                KButton("Kembalikan semua", vm::revertAll, Modifier.fillMaxWidth(), primary = false, enabled = !ui.busy)
            }
        }

        Spacer(Modifier.height(10.dp))
        Panel {
            ListRow(
                Icons.Outlined.DeleteSweep, "Aplikasi bawaan",
                if (ui.bloat.isEmpty()) "Pilih aplikasi yang dimatikan" else "${ui.bloat.count { it.on }} dimatikan",
            ) { go("bloat") }
        }

        Spacer(Modifier.height(10.dp))
        Panel {
            Facts(ui)
        }

        Spacer(Modifier.height(10.dp))
        Panel {
            LicenseLine(license)
        }
        Spacer(Modifier.height(12.dp))
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

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Txt(title, 17)
            Txt(sub, 14, color = if (remain < 86_400_000L) color else k.muted)
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
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FactRow(
            Icons.Outlined.Memory, "RAM",
            s?.let { "%.1f dari %.1f GB".format(it.ramUsedGb, it.ramTotalGb) } ?: "-",
            s?.let { (it.ramUsedGb / it.ramTotalGb).toFloat() },
        )
        FactRow(
            Icons.Outlined.Storage, "Penyimpanan",
            s?.let { "%.0f GB kosong".format(it.storageFreeGb) } ?: "-",
            s?.let { it.storageUsedPct / 100f },
            if ((s?.storageUsedPct ?: 0) > 85) k.warn else k.good,
        )
        FactRow(Icons.Outlined.BatteryFull, "Baterai", s?.let { "${it.battery}%  ·  " + "%.1f °C".format(it.tempC) } ?: "-")
        FactRow(Icons.Outlined.Speed, "CPU governor", ui.governor)
    }
}

@Composable
private fun FactRow(icon: ImageVector, label: String, value: String, fraction: Float? = null, color: Color = Color.Unspecified) {
    val k = LocalK.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = k.link, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(label, 17)
            Txt(value, 14, color = k.muted)
            if (fraction != null) Meter(fraction, if (color == Color.Unspecified) k.link else color)
        }
    }
}

/** Baris dalam kartu: ikon ungu di kiri, nama dan keterangan, panah di kanan. */
@Composable
private fun ListRow(icon: ImageVector, title: String, sub: String, onClick: (() -> Unit)? = null) {
    val k = LocalK.current
    val base = Modifier.fillMaxWidth()
    Row(
        if (onClick != null) base.clickable(onClick = onClick) else base,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = k.link, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, 17)
            Txt(sub, 14, color = k.muted)
        }
        if (onClick != null) Txt("›", 24, color = k.muted)
    }
}

// ---------- layar modul ----------

@Composable
private fun TweakScreen(title: String, ui: Ui, vm: MainViewModel, group: Group) {
    val k = LocalK.current
    ScreenFrame(title, null) {
        TWEAKS.filter { it.group == group }.forEach { t ->
            SwitchRow(t.title, t.desc, t.id in ui.active, enabled = !ui.busy, tag = title) { vm.toggleTweak(t.id) }
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
private fun PresetRow(title: String, detail: String, onLoad: () -> Unit, onDelete: (() -> Unit)?) {
    val k = LocalK.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, 15, FontWeight.Medium)
            Txt(detail, 13, color = k.muted)
        }
        TextButton(onClick = onLoad) { Text("Muat", color = k.link, fontWeight = FontWeight.Bold) }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("Hapus", color = k.muted) }
        }
    }
}

@Composable
private fun BloatScreen(ui: Ui, vm: MainViewModel, back: () -> Unit) {
    val k = LocalK.current
    ScreenFrame("Aplikasi bawaan", back) {
        KButton("Tutup proses latar belakang", vm::killBackground, Modifier.fillMaxWidth(), primary = false)
        Spacer(Modifier.height(8.dp))
        KButton("Bersihkan cache semua aplikasi", vm::trimCaches, Modifier.fillMaxWidth(), primary = false)
        Label("Aplikasi terdeteksi")
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
private fun GamesScreen(ui: Ui, vm: MainViewModel, openTools: () -> Unit) {
    val k = LocalK.current
    ScreenFrame("Game", null) {
        Panel {
            ListRow(Icons.Outlined.SportsEsports, "Game Tools", "Crosshair, FPS, zona sensi, dan boost game", openTools)
        }
        Spacer(Modifier.height(12.dp))
        if (Build.VERSION.SDK_INT < 31) {
            Txt("Mode performa game butuh Android 12 atau lebih baru.", 14, color = k.muted)
        } else {
            Txt("Aktifkan mode performa untuk game yang kamu pilih.", 14, color = k.muted)
            Spacer(Modifier.height(4.dp))
            ui.games.forEach { row -> SwitchRow(row.label, row.pkg, row.on) { vm.toggleGame(row.pkg) } }
        }
    }
}
