package com.kilat.optimizer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kilat.optimizer.License
import com.kilat.optimizer.R
import com.kilat.optimizer.LicenseResult
import com.kilat.optimizer.Shell
import com.kilat.optimizer.ShizukuStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Layar masuk dengan license key. [onUnlocked] dipanggil setelah key terbukti valid. */
@Composable
fun LicenseScreen(notice: String? = null, onUnlocked: (LicenseResult.Valid) -> Unit) {
    val ctx = LocalContext.current
    val k = LocalK.current
    val clipboard = LocalClipboardManager.current
    val status by Shell.status.collectAsStateWithLifecycle()

    var key by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    var remember by rememberSaveable { mutableStateOf(true) }
    var error by rememberSaveable { mutableStateOf<String?>(notice) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    val model = License.deviceModel()
    val hwid = License.hwidDisplay(ctx)

    val scope = rememberCoroutineScope()
    var busy by rememberSaveable { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        busy = true
        scope.launch {
            val r = License.verifyAndActivate(ctx, key)
            busy = false
            when (r) {
                is LicenseResult.Valid -> {
                    if (remember) License.save(ctx, key) else License.clear(ctx)
                    onUnlocked(r)
                }
                is LicenseResult.Invalid -> {
                    error = r.reason
                    info = null
                }
                is LicenseResult.NeedsActivation -> {
                    error = "Aktivasi belum selesai. Coba lagi"
                    info = null
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(k.bg).systemBarsPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Logo Kilat di tengah, versi di bawahnya (seperti layar masuk referensi).
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.kilat_logo),
                        contentDescription = "Kilat",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 340.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "V " + com.kilat.optimizer.BuildConfig.VERSION_NAME,
                        color = k.link, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Panel {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                // Status Shizuku
                val (txt, col) = when (status) {
                    ShizukuStatus.READY -> "Shizuku siap" to k.good
                    ShizukuStatus.CONNECTING -> "Menyambungkan ke Shizuku…" to k.warn
                    ShizukuStatus.NEEDS_PERMISSION -> "Shizuku butuh izin" to k.warn
                    ShizukuStatus.NOT_RUNNING -> "Shizuku belum berjalan" to k.bad
                }
                StatusPill(txt, col, ok = status == ShizukuStatus.READY)

                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; error = null },
                    label = { Text("License key") },
                    singleLine = true,
                    isError = error != null,
                    leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = k.muted) },
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(
                                if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (show) "Sembunyikan key" else "Tampilkan key",
                                tint = k.muted,
                            )
                        }
                    },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = k.fg, unfocusedTextColor = k.fg,
                        focusedBorderColor = k.link, unfocusedBorderColor = Color(0xFF3A3A4A),
                        focusedLabelColor = k.link, unfocusedLabelColor = k.muted,
                        cursorColor = k.link, errorBorderColor = k.bad, errorLabelColor = k.bad,
                        focusedContainerColor = k.surface2, unfocusedContainerColor = k.surface2,
                        errorContainerColor = k.surface2,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let { Text(it, color = k.bad, fontSize = 13.sp) }
                info?.let { Text(it, color = k.muted, fontSize = 13.sp) }
                Text(
                    "Untuk key berdurasi, waktu mulai berjalan saat key pertama kali dimasukkan.",
                    color = k.muted, fontSize = 12.sp,
                )

                Row(
                    Modifier.fillMaxWidth().clickable { remember = !remember },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = remember,
                        onCheckedChange = { remember = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = k.accent, checkmarkColor = k.accentInk, uncheckedColor = k.muted,
                        ),
                    )
                    Text("Ingat saya", color = k.muted, fontSize = 15.sp)
                }

                KButton(
                    if (busy) "Memeriksa…" else "Masuk",
                    { submit() },
                    Modifier.fillMaxWidth(),
                    enabled = key.isNotBlank() && !busy,
                )
                }
                }

                // Model dan HWID, untuk key yang diikat ke satu HP
                Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Perangkat", color = k.muted, fontSize = 12.sp)
                        Text(model, color = k.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("HWID", color = k.muted, fontSize = 12.sp)
                        Text(
                            hwid, color = k.fg, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                        )
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString("Perangkat: $model\nHWID: $hwid"))
                        info = "Model dan HWID disalin"
                    }) { Text("Salin", color = k.link, fontWeight = FontWeight.Bold) }
                }
                }
            }
        }
    }
}

/** Contoh: "berakhir 14 Nov 2026 10.20". */
fun formatExpiry(ms: Long): String =
    if (ms == 0L) "tanpa batas waktu"
    else "berakhir " + SimpleDateFormat("d MMM yyyy HH.mm", Locale("id", "ID")).format(Date(ms))

/** Contoh: "29 hari 23 jam" atau, bila kurang dari sehari, "05:12:09". */
fun formatRemaining(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val days = total / 86400
    val hours = (total % 86400) / 3600
    val mins = (total % 3600) / 60
    val secs = total % 60
    return if (days >= 1) "$days hari $hours jam" else "%02d:%02d:%02d".format(hours, mins, secs)
}
