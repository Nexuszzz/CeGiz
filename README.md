# CeGiz — Backend Gemini untuk Railway

Server Java ini menerima foto JPEG dari aplikasi CeGiz, meminta estimasi makanan dan gizi ke Gemini, memvalidasi hasil, lalu mengembalikannya ke HP. API Gemini hanya berada pada server.

## Menyiapkan repository

Unggah seluruh isi paket backend ini ke root repository `Nexuszzz/CeGiz`. `Dockerfile`, `src`, dan `lib` harus terlihat langsung pada halaman utama repository. Ekstrak ZIP terlebih dahulu agar source dan Dockerfile menjadi berkas repository.

Paket ini merupakan source backend. Aplikasi Android tetap dibangun dari project Java CeGiz yang terpisah.

## Railway

1. Login Railway, pilih **New Project → GitHub Repository → Nexuszzz/CeGiz**.
2. Pilih **Add Variables** dan masukkan variabel berikut sebagai pengelola:

   | Nama | Isi |
   | --- | --- |
   | `GEMINI_API_KEY` | API key Gemini milik pengelola |
   | `CEGIZ_CLIENT_TOKEN` | Kode akses aplikasi 32–128 karakter, sama dengan konfigurasi APK |
   | `GEMINI_MODEL` | Opsional; default `gemini-3.1-flash-lite` |

   Gunakan **Variables** Railway untuk nilai rahasia. Source, Dockerfile dan APK tidak memuat API key Gemini.

3. Biarkan **Root Directory** di root repository. Railway mendeteksi `Dockerfile`; perintah Start sudah ada di dalamnya.
4. Pada Settings → Deploy, atur **Healthcheck Path** ke `/health`. Deploy layanan.
5. Pada Settings → Networking → Public Networking, klik **Generate Domain**. Gunakan port dari `PORT` Railway; bila memilih port sendiri, isikan `PORT` yang sama pada Variables dan target domain.
6. Buka `https://DOMAIN-ANDA/health`. Respons sehat: `{"status":"ready","mode":"hosted"}`. Ini memeriksa proses server, belum memanggil Gemini.
7. Gunakan domain HTTPS tersebut untuk membangun APK CeGiz. APK lokal lama tetap memakai alamat komputer sampai diganti dengan build cloud.

`PORT` disediakan Railway. Server otomatis bind `0.0.0.0` ketika variabel `PORT` ada. `CEGIZ_BIND_HOST` dan `CEGIZ_PORT` tersedia untuk pengujian/lokal; tidak perlu disetel di Railway.

## Kode akses aplikasi

Kode `CEGIZ_CLIENT_TOKEN` berbeda dari API key Gemini. Request analisis memerlukan header `Authorization: Bearer KODE_AKSES`; endpoint `/health` bisa diperiksa tanpa kode.

Pada komputer pengelola Windows, script `Copy-Railway-Variable.ps1` dapat membuat kode acak dan menyalinnya ke clipboard. Nilainya disimpan terenkripsi untuk akun Windows saat ini di `%LOCALAPPDATA%/CeGiz/owner/railway-client-token.dpapi`.

```powershell
.\Copy-Railway-Variable.ps1 -Name CEGIZ_CLIENT_TOKEN
```

Tempel ke Variables Railway. Untuk API Gemini, gunakan nilai dari Google AI Studio atau script yang sama dengan `-Name GEMINI_API_KEY`. Script membaca berkas pengelola lokal atau meminta input tersamarkan; nilai tidak dicetak.

Kode akses bersama di APK membantu membatasi akses langsung, tetapi dapat diekstrak dari APK. Ini bukan autentikasi per pengguna. Server juga membatasi dua analisis bersamaan dan 20 permintaan per menit per proses. Untuk penggunaan publik luas, tambahkan autentikasi pengguna/attestation dan pengendalian kuota. Tetapkan kuota/batas pemakaian pada akun provider pengelola sesuai kebutuhan.

## Endpoint

- `GET /health`: kesiapan proses, tanpa data rahasia.
- `POST /v1/food:analyze`: `Content-Type: image/jpeg`, maksimal 3 MiB, kode akses aplikasi diperlukan pada mode hosted.
- Foto hanya berada dalam memori permintaan, tidak disimpan sebagai berkas. Server tidak menerima profil/jurnal anak dan tidak mencetak foto, kode akses, API key atau respons Gemini ke log.
- Nilai gizi foto merupakan estimasi Gemini. Respons kosong/tidak valid ditolak; tidak ada penggantian ke data katalog.

## Pengujian

Docker menjalankan tes HTTP dengan provider fixture saat build. Fixture hanya masuk tahap build; image runtime berisi class produksi. Build tidak memakai API key dan tidak mengirim foto ke Gemini. Tes deteksi live dilakukan setelah deployment dan APK memakai URL hosting yang sama.

Dokumentasi resmi: [Dockerfile](https://docs.railway.com/builds/dockerfiles), [Variables](https://docs.railway.com/variables), [domain HTTPS](https://docs.railway.com/networking/public-networking), [healthcheck](https://docs.railway.com/deployments/healthchecks).
