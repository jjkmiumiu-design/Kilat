package com.kilat.optimizer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kilat.optimizer.GameBoost
import com.kilat.optimizer.GameToolsPrefs
import com.kilat.optimizer.GameToolsService
import com.kilat.optimizer.Shell
import com.kilat.optimizer.ShizukuStatus
import com.kilat.optimizer.Ui

/**
 * Layar Game Tools di dalam menu Game: saklar, izin overlay, status Shizuku, mode target,
 * dan daftar game. Pengaturan crosshair dan FPS ada di panel yang muncul di dalam game
 * (ketuk pegangan di tepi kanan layar game).
 */
@Composable
fun GameToolsScreen(ui: Ui, back: () -> Unit) {
    val ctx = LocalContext.current
    val k = LocalK.current
    val running by GameToolsService.running.collectAsStateWithLifecycle()

    var st by remember { mutableStateOf(GameToolsPrefs.read(ctx)) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var picking by rememberSaveable { mutableStateOf(false) }

    // Setelah kembali dari layar izin, periksa ulang.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canOverlay = Settings.canDrawOverlays(ctx)
        st = GameToolsPrefs.read(ctx)
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun openOverlaySettings() {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
    }

    fun toggle() {
        when {
            running -> GameToolsService.stop(ctx)
            !canOverlay -> {
                Toast.makeText(ctx, "Beri izin tampil di atas aplikasi lain dulu", Toast.LENGTH_LONG).show()
                openOverlaySettings()
            }
            ui.status != ShizukuStatus.READY ->
                Toast.makeText(ctx, "Jalankan Shizuku dan beri izin dulu", Toast.LENGTH_LONG).show()
            else -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                GameToolsService.start(ctx)
            }
        }
    }

    ScreenFrame("Game Tools", back) {
        SwitchRow("Game Tools", "Crosshair dan FPS di atas game", running) { toggle() }

        // Izin overlay
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Izin overlay", 15, FontWeight.SemiBold)
                Txt(
                    if (canOverlay) "Diizinkan" else "Wajib",
                    13, color = if (canOverlay) k.good else k.warn,
                )
            }
            if (!canOverlay) PillButton("Izinkan") { openOverlaySettings() }
        }

        // Shizuku
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Txt("Shizuku", 15, FontWeight.SemiBold)
            val (text, color) = when (ui.status) {
                ShizukuStatus.READY -> "Siap" to k.good
                ShizukuStatus.CONNECTING -> "Menyambungkan…" to k.warn
                ShizukuStatus.NEEDS_PERMISSION -> "Butuh izin" to k.warn
                ShizukuStatus.NOT_RUNNING -> "Belum berjalan" to k.warn
            }
            Txt(text, 13, color = color)
        }

        // Mode target
        Label("MODE TARGET")
        Segmented(listOf("Game pilihan", "Semua aplikasi"), if (st.targetAny) 1 else 0) {
            GameToolsPrefs.setTargetAny(ctx, it == 1)
            st = GameToolsPrefs.read(ctx)
        }
        Txt(
            if (st.targetAny) "Overlay tampil di aplikasi apa pun yang sedang dibuka, kecuali layar utama."
            else "Overlay hanya tampil saat salah satu game di bawah dibuka.",
            12, color = k.muted,
        )

        // Daftar game
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Txt("Game pilihan", 18, FontWeight.Bold) }
            PillButton("Tambah game", icon = true) { picking = true }
        }
        if (st.games.isEmpty()) {
            Txt("Belum ada game. Ketuk Tambah game.", 14, color = k.muted)
        } else {
            val pm = ctx.packageManager
            val rows = remember(st.games) {
                st.games.map { pkg ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    pkg to label
                }.sortedBy { it.second.lowercase() }
            }
            rows.forEach { (pkg, label) ->
                GameRow(pkg, label, onRemove = {
                    GameToolsPrefs.removeGame(ctx, pkg)
                    st = GameToolsPrefs.read(ctx)
                })
            }
        }

        Spacer(Modifier.height(8.dp))
        Txt(
            "Saat game terbuka, ketuk pegangan kecil di tepi kanan layar untuk membuka panel crosshair dan FPS. " +
                "Perubahan di panel langsung terlihat.",
            12, color = k.muted,
        )
        Txt(
            "FPS dibaca dari frame yang tampil di layar lewat Shizuku. Beberapa game atau ROM bisa menolak pembacaan ini, " +
                "dan angkanya tampil sebagai -- FPS.",
            12, color = k.muted,
        )
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = k.surface,
            title = { Txt("Tambah game", 18, FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    if (ui.games.isEmpty()) Txt("Tidak ada aplikasi ditemukan.", 14, color = k.muted)
                    ui.games.forEach { row ->
                        val on = row.pkg in st.games
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                GameToolsPrefs.toggleGame(ctx, row.pkg)
                                st = GameToolsPrefs.read(ctx)
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = k.accent, checkmarkColor = k.accentInk, uncheckedColor = k.muted,
                                ),
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Txt(row.label, 15, FontWeight.SemiBold)
                                Txt(row.pkg, 12, color = k.muted)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) {
                    Text("Selesai", color = k.accent, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}

@Composable
private fun PillButton(text: String, icon: Boolean = false, onClick: () -> Unit) {
    val k = LocalK.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.clip(shape).background(k.surface2).border(1.dp, k.line, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon) Icon(Icons.Outlined.Add, null, tint = k.fg, modifier = Modifier.size(18.dp))
        Txt(text, 14, FontWeight.SemiBold)
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val k = LocalK.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(k.surface2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, text ->
            val sel = i == selected
            val shape = RoundedCornerShape(11.dp)
            Box(
                Modifier.weight(1f).clip(shape)
                    .background(if (sel) k.surface else Color.Transparent)
                    .border(1.dp, if (sel) k.accent else Color.Transparent, shape)
                    .clickable { onSelect(i) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(text, 14, if (sel) FontWeight.Bold else FontWeight.Medium, if (sel) k.fg else k.muted)
            }
        }
    }
}

@Composable
private fun GameRow(pkg: String, label: String, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val k = LocalK.current
    val scope = rememberCoroutineScope()
    val icon = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    var menu by remember { mutableStateOf(false) }
    var boosted by remember(pkg) { mutableStateOf(GameBoost.isBoosted(ctx, pkg)) }

    fun say(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun boostNow() {
        if (Shell.status.value != ShizukuStatus.READY) {
            say("Jalankan Shizuku dan beri izin dulu")
            return
        }
        scope.launch {
            runCatching { GameBoost.apply(ctx, pkg) }
                .onSuccess { say("Boost aktif untuk $label (mode=2, fps=90)") }
                .onFailure { say("Boost gagal: ${it.message}") }
            boosted = GameBoost.isBoosted(ctx, pkg)
        }
    }

    fun unboost() {
        if (Shell.status.value != ShizukuStatus.READY) {
            say("Jalankan Shizuku dan beri izin dulu")
            return
        }
        scope.launch {
            runCatching { GameBoost.restore(ctx, pkg) }
                .onSuccess { say("Boost dikembalikan") }
                .onFailure { say("Gagal mengembalikan: ${it.message}") }
            boosted = GameBoost.isBoosted(ctx, pkg)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val iconShape = RoundedCornerShape(12.dp)
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(44.dp).clip(iconShape))
        } else {
            Box(Modifier.size(44.dp).clip(iconShape).background(k.surface2))
        }
        Column(Modifier.weight(1f)) {
            Txt(label, 16, FontWeight.SemiBold)
            Txt(pkg, 12, color = k.muted)
            if (boosted) Txt("Boost aktif", 12, FontWeight.SemiBold, k.good)
        }
        IconButton(onClick = {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) }
        }) { Icon(Icons.Outlined.PlayArrow, contentDescription = "Buka game", tint = k.fg) }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Menu", tint = k.muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (boosted) "Kembalikan boost" else "Boost sekarang") },
                    onClick = {
                        menu = false
                        if (boosted) unboost() else boostNow()
                    },
                )
                DropdownMenuItem(text = { Text("Hapus dari daftar") }, onClick = {
                    menu = false
                    onRemove()
                })
            }
        }
    }
}
