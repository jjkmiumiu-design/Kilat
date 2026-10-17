package com.kilat.optimizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kilat.optimizer.ui.App
import com.kilat.optimizer.ui.KilatTheme
import com.kilat.optimizer.ui.LicenseScreen
import kotlinx.coroutines.delay

/** Nilai khusus: belum ada lisensi aktif, tampilkan layar login. */
private const val LOCKED = -1L

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shell.init(this)
        setContent {
            KilatTheme {
                val ctx = this@MainActivity
                val restored = remember { License.restore(ctx) }

                // exp: LOCKED = terkunci, 0 = tanpa batas waktu, lainnya = waktu habis (epoch ms)
                var exp by rememberSaveable { mutableLongStateOf(restored.first?.expiresAtMs ?: LOCKED) }
                var name by rememberSaveable { mutableStateOf(restored.first?.name ?: "") }
                var notice by rememberSaveable { mutableStateOf(restored.second) }

                if (exp == LOCKED) {
                    // Terkunci: matikan Game Tools supaya overlay tidak tertinggal.
                    LaunchedEffect(Unit) { GameToolsService.stop(ctx) }
                    LicenseScreen(notice = notice) { r ->
                        name = r.name
                        exp = r.expiresAtMs
                        notice = null
                    }
                } else {
                    // Memeriksa tiap detik. Begitu waktu habis (atau jam HP diputar mundur),
                    // key dihapus dan aplikasi kembali ke layar login.
                    LaunchedEffect(exp) {
                        while (true) {
                            if (!License.clockOk(ctx)) {
                                License.clear(ctx)
                                notice = "Jam HP tidak sesuai. Perbaiki tanggal dan jam, lalu masuk lagi"
                                exp = LOCKED
                                return@LaunchedEffect
                            }
                            if (exp != 0L && System.currentTimeMillis() >= exp) {
                                License.clear(ctx)
                                notice = "License sudah habis. Beli key baru untuk lanjut"
                                exp = LOCKED
                                return@LaunchedEffect
                            }
                            delay(1000)
                        }
                    }
                    App(vm, LicenseResult.Valid(name, exp))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Shell.refresh()
        vm.refreshAll()
    }
}
