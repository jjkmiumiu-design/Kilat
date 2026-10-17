# Kilat

Aplikasi Android (Kotlin + Jetpack Compose) untuk mengoptimalkan HP lewat [Shizuku](https://shizuku.rikka.app/). Tanpa root. Semua perubahan bisa dikembalikan.

## Cara kerjanya

Kilat menjalankan perintah sebagai pengguna `shell` lewat Shizuku, sama seperti `adb shell`. Yang dilakukan:

| Modul | Perintah | Dikembalikan dengan |
|---|---|---|
| Tampilan | `settings put global *_animation_scale 0.5`, `peak_refresh_rate`, `disable_window_blurs` | Nilai asli disimpan lalu dipulihkan |
| Tampilan: resolusi dan density | `wm size`, `wm density` (konfirmasi 10 detik, preset, kembalikan bawaan) | Otomatis bila tidak dikonfirmasi, atau tombol Kembalikan bawaan |
| Sentuhan | `settings put secure long_press_timeout / multi_press_timeout`, `haptic_feedback_enabled`; `setprop debug.input.touch_slop 12`, `setprop touch.screen.delay 0.00` | Nilai asli disimpan lalu dipulihkan. Setprop hilang saat HP restart, dan dibaca ulang dengan `getprop` untuk memastikan berhasil |
| Bloat | `am kill-all`, `pm trim-caches`, `pm disable-user` (daftar kecil, hanya yang terpasang) | `pm enable` |
| Game | `cmd game mode performance <paket>` (Android 12+) | `cmd game mode standard <paket>` |
| Game: Zona sensitivitas | Overlay penangkap sentuh + `injectInputEvent` lewat UserService Shizuku | Matikan saklar di tab Sensi |
| Game: Boost game | `device_config put game_overlay <paket> mode=2,fps=90` | Kembalikan boost (nilai awal dipulihkan) |
| Game: Game Tools | Overlay crosshair dan FPS di atas game, aplikasi depan dibaca lewat `dumpsys window`, FPS lewat `dumpsys SurfaceFlinger --latency` | Matikan saklar Game Tools (tidak mengubah pengaturan sistem) |

RAM, baterai, dan penyimpanan dibaca langsung dari sistem. Governor CPU dibaca lewat Shizuku.
Skor adalah jumlah tweak yang sedang aktif (55 sampai 100), bukan benchmark.

## Cara memakai

1. Pasang Shizuku dari Play Store atau [situsnya](https://shizuku.rikka.app/download/).
2. Aktifkan Shizuku: Opsi pengembang → Debugging nirkabel → ikuti panduan "Start via Wireless debugging" di aplikasi Shizuku. Perlu diulang setelah HP restart.
3. Pasang APK Kilat, buka, tekan **Beri izin** pada banner, lalu **Optimalkan semua**.

## Zona sensitivitas (Game → Game Tools → panel → tab Sensi)

Zona bundar di layar. Gerakan jari yang dimulai di dalam zona dilebarkan sesuai Sensitivity X dan Y (1,00 sampai 5,00), jadi kamera game bergerak lebih jauh. Tombol **Edit zona** membuka editor di atas game: tambah, seret, ubah ukuran, hapus, dan dialog **Atur** (Sensitivity Global, X, Y). Maksimal 6 zona. Perubahan tersimpan saat tombol centang diketuk.

Cara kerja (tanpa root):
- Tiap zona adalah jendela overlay transparan seukuran zona yang menangkap sentuhan di areanya.
- Begitu jari menyentuh, jendela dibuat tembus sentuh, lalu sentuhan yang sudah dilebarkan disuntikkan ke game lewat `InputManager.injectInputEvent` di proses UserService Shizuku (uid shell). Titik suntik yang hampir keluar layar diangkat dan diletakkan lagi di titik awal.
- Butuh Shizuku lewat **UserService**. Jalur cadangan tidak bisa menyuntik, dan statusnya tampil "Tidak didukung" di tab Sensi.
- Bila sistem membatalkan gestur zona 3 kali berturut-turut di awal sentuhan, fitur mati sendiri dan menampilkan pemberitahuan, supaya game tetap bisa dimainkan.
- Crosshair dan angka FPS kini memakai alpha jendela 0,75. Android 12+ menolak sentuhan yang menembus overlay dengan opasitas di atas 0,8.

**Belum diuji di perangkat nyata.** Hasilnya bergantung ROM (HyperOS/MIUI, One UI, dll.) dan game. Uji dulu di beberapa HP sebelum dijual.

## Boost game (titik tiga pada game)

Di daftar game, titik tiga di samping tombol putar punya pilihan **Boost sekarang**. Perintahnya lewat Shizuku:

```
device_config put game_overlay <paket> mode=2,fps=90
```

Contoh: Free Fire MAX `com.dts.freefiremax`, Free Fire `com.dts.freefireth`. **Kembalikan boost** memulihkan nilai awal (atau menghapus entrinya bila dulu kosong). Boost hanya manual, tidak ada pengaturan otomatis.

## Game Tools (di dalam menu Game)

Menu **Game → Game Tools** menampilkan crosshair dan angka FPS di atas game, dan pengaturannya berubah langsung (realtime).

Cara pakai:
1. Buka **Game → Game Tools**, tekan **Izinkan** pada Izin overlay (izin "tampil di atas aplikasi lain"), lalu pastikan Shizuku Siap.
2. Pilih mode target: **Game pilihan** (hanya game yang kamu tambahkan lewat **Tambah game**) atau **Semua aplikasi** (kecuali layar utama).
3. Nyalakan saklar **Game Tools**. Overlay muncul otomatis begitu game target dibuka, dan hilang saat game ditutup.
4. Di dalam game, ketuk pegangan kecil di tepi kanan layar untuk membuka panel:
   - **Crosshair**: 5 gaya, 5 warna, ukuran (16 sampai 128 dp), opasitas (10 sampai 100%), dan saklar tampil atau sembunyi.
   - **FPS**: saklar tampil atau sembunyi dan pilihan posisi di 4 pojok.
   Setiap perubahan langsung terlihat karena panel, layar aplikasi, dan overlay memakai satu tempat penyimpanan yang sama.

Cara kerja:
- `GameToolsService` berjalan sebagai foreground service (ada notifikasi dengan tombol **Matikan**). Tiap beberapa detik ia membaca aplikasi yang sedang fokus lewat Shizuku.
- Jendela crosshair dan FPS bertipe overlay yang **tidak bisa disentuh**, jadi sentuhan tetap sampai ke game. Hanya pegangan dan panel yang menerima sentuhan.
- FPS dihitung dari waktu frame yang benar-benar ditampilkan oleh SurfaceFlinger untuk layer game, dalam jendela 1 detik terakhir. Pemeriksaan dibuat jarang supaya tidak membebani HP saat bermain.

Batasan:
- Butuh Shizuku yang berjalan. Tanpa itu, game tidak terdeteksi dan overlay tidak muncul.
- Pembacaan FPS bergantung pada ROM dan game. Bila layar game tidak ditemukan, angkanya tampil `-- FPS`.
- Sebagian ROM (misalnya dengan pembatasan tambahan dari vendor) bisa menghentikan service latar belakang. Kunci Kilat di aplikasi terbaru dan matikan penghemat baterai untuknya bila overlay sering hilang.
- Kode ini belum dicoba di perangkat nyata, jadi posisi crosshair dan akurasi FPS perlu diperiksa saat pengujian pertama.

## License key

Kilat meminta license key sebelum masuk. Key diverifikasi offline dengan tanda tangan digital (ECDSA P-256), jadi hanya key yang diterbitkan pemegang private key yang diterima. Teks sembarang ditolak.

Di proyek ini hanya ada **public key** (`PUBLIC_KEY_B64` di `app/src/main/java/com/kilat/optimizer/License.kt`). Public key tidak bisa dipakai untuk membuat key. Pembuat key (`make_key.py`) dan private key sengaja dipisah dari source code, ada di paket `kilat-keygen` yang hanya kamu simpan sendiri.

Public key bawaan di proyek ini hanya untuk uji coba. Sebelum dipakai sungguhan, buat pasangan kunci sendiri lewat `kilat-keygen`, tempel public key barunya ke `License.kt`, lalu bangun ulang APK.

Key bisa diikat ke satu HP lewat **HWID** (tampil di layar license bersama model HP, ada tombol Salin). HWID dihitung dari ID Android dan data perangkat keras. ID Android bergantung pada tanda tangan APK, jadi HWID bisa berubah bila APK ditandatangani dengan keystore lain atau HP di-reset pabrik. Pakai keystore yang sama untuk setiap build bila memakai key yang terikat perangkat. Build debug di GitHub Actions memakai kunci debug baru, jadi sebelum dijual sebaiknya tanda tangani APK dengan keystore milikmu sendiri.

**Masa berlaku.** Beranda menampilkan status lisensi dengan sisa waktu yang berjalan (hari dan jam, lalu jam:menit:detik di hari terakhir). Warna berubah menjadi kuning di bawah 24 jam dan oranye di bawah 1 jam. Begitu waktu habis, key dihapus dan aplikasi otomatis kembali ke layar login dengan pesan "License sudah habis. Beli key baru untuk lanjut". Pemeriksaan berjalan tiap detik dan juga saat aplikasi dibuka lagi. Waktu mulai berjalan saat pelanggan **pertama kali memasukkan key** ke aplikasi, bukan saat key diterbitkan. Key lama dengan waktu habis mutlak (`--from-issue`) tetap didukung.

Cara aktivasi: saat key berdurasi dimasukkan pertama kali, Kilat mencatat waktu aktivasi di dua tempat, yaitu penyimpanan aplikasi dan pengaturan sistem (`Settings.Global`, ditulis lewat Shizuku). Pengaturan sistem tidak ikut terhapus ketika data aplikasi dihapus atau aplikasi dipasang ulang, jadi memasukkan key yang sama lagi melanjutkan sisa waktunya, bukan mengulang dari awal. Karena itu aktivasi pertama butuh Shizuku siap. Key juga punya batas waktu aktivasi (bawaan 30 hari sejak diterbitkan), setelah itu key tidak bisa diaktifkan lagi.

Batasan: orang yang paham bisa menghapus catatan itu lewat Shizuku atau ADB, atau mereset HP ke pengaturan pabrik, lalu mengaktifkan ulang. Karena itu ikat key ke HWID dan beri batas waktu aktivasi yang pendek untuk mempersempit celahnya. Pencegahan penuh hanya bisa dengan server.

Jam HP yang diputar mundur lebih dari 5 menit dari waktu terakhir yang tercatat ditolak. Ini bisa dilewati dengan menghapus data aplikasi, jadi bukan perlindungan penuh.

Batasan: verifikasi offline tidak bisa mencabut key yang sudah terbit, dan orang yang mengubah jam HP atau membongkar APK bisa melewatinya. Untuk perlindungan yang lebih kuat perlu server yang memeriksa key.

## Membangun APK

**Lewat GitHub (tanpa Android Studio):**
1. Buat repositori baru di GitHub dan unggah seluruh isi folder ini.
2. Buka tab **Actions** → **Build APK** → **Run workflow**.
3. Setelah selesai, unduh artefak `kilat-debug-apk`, ekstrak, lalu pasang `app-debug.apk`.

**Lewat Android Studio:** buka folder ini, tunggu sinkronisasi Gradle, lalu Build → Build APK(s).

Persyaratan: JDK 17, Android SDK 34, minSdk 26 (Android 8.0).

## Struktur

- `ShellService.kt` dan `IShellService.aidl`: layanan yang berjalan di proses shell Shizuku
- `Shell.kt`: koneksi Shizuku dan penjalan perintah
- `Engine.kt`: daftar tweak, simpan dan pulihkan nilai asli, helper paket
- `License.kt` dan `ui/LicenseScreen.kt`: verifikasi dan layar license key
- `GameToolsService.kt`, `GameOverlay.kt`, `GamePanel.kt`, `Crosshair.kt`: overlay Game Tools (service, jendela di atas game, panel pengaturan, gambar crosshair)
- `Fps.kt`: pembacaan aplikasi depan dan hitungan FPS
- `GameToolsPrefs.kt`: pengaturan Game Tools
- `ui/GameToolsScreen.kt`: layar Game Tools di aplikasi
- `Stats.kt`: pembaca RAM, baterai, penyimpanan
- `MainViewModel.kt`: logika dan state
- `ui/`: tema dan layar Compose

## Catatan

- Proyek ini belum pernah dikompilasi di lingkungan pembuatnya. Bila build gagal, kirim log galatnya untuk diperbaiki.
- Beberapa pengaturan bergantung pada vendor dan versi Android. Bila sebuah perintah ditolak, Kilat menampilkan pesan galatnya.
- Status tweak disimpan di aplikasi. Bila kamu mengubah pengaturan yang sama secara manual, tampilannya bisa tidak sinkron.
