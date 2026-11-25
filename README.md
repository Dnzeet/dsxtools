# DS Tools Android

DualShock Calibration GUI (`web/`) dibungkus jadi APK. WebHID tidak ada di Android,
jadi `navigator.hid` ditiru lewat jembatan ke Android USB Host API
(`UsbHidBridge.java` + `res/raw/hid_polyfill.js`). Kode tool asli tidak diubah.

## Build lewat GitHub
1. Buat repo baru, upload seluruh isi folder ini (termasuk `.github/`).
2. Tab **Actions** -> **Build APK** -> **Run workflow** (atau otomatis saat push).
3. Setelah selesai, unduh artifact **DSTools-debug-apk** (zip berisi `app-debug.apk`).

## Cara pakai
1. Install APK, buka aplikasinya.
2. Colok kontroler lewat kabel USB/OTG, izinkan akses USB.
3. Tekan **Connect** di tool. Awalnya **Mode baca-saja** (bar hijau): info, tes stick, tes tombol.
4. Untuk kalibrasi, nyalakan switch **Tulis** di bar atas dan konfirmasi.
   Mode tulis otomatis mati kalau kabel dicabut atau koneksi ditutup.
5. Tombol **Log** menampilkan semua report tulis (bisa disalin).

## Catatan
- Butuh internet: Bootstrap, jQuery, dan FontAwesome dimuat dari CDN seperti situs aslinya.
- Hanya USB (Bluetooth tidak didukung oleh tool ini).
- Update tool: ganti isi `web/` dengan versi terbaru lalu build ulang.
