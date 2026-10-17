package com.kilat.optimizer;

interface IShellService {
    // Kode transaksi 16777114 wajib untuk UserService Shizuku.
    void destroy() = 16777114;

    // Menjalankan perintah shell sebagai pengguna "shell" (uid 2000) dan mengembalikan keluarannya.
    String exec(String command) = 1;

    // Menyuntikkan satu event sentuh (satu jari) ke layar. oneway: tidak menunggu hasil, urutan tetap terjaga.
    oneway void injectTouch(int action, int pointerId, float x, float y, long downTime, long eventTime, int deviceId) = 2;

    // true bila proses ini bisa menyuntikkan event sentuh.
    boolean canInject() = 3;

    // Hasil pemeriksaan suntik sentuh: "OK|<jalur>" bila berhasil, "ERR|<alasan>" bila gagal.
    String injectStatus() = 4;
}
