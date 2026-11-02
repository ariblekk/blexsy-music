# PocketBase Database Schema — Blexsy

Database schema untuk PocketBase backend (`https://pb.blek.my.id`).

File skema siap-import resmi: **`pb_schema.json`** (bisa langsung di-import di menu _Settings > Sync > Import collections_ pada dashboard PocketBase).

---

## 1. `users` (Auth Collection)

Menyimpan data pengguna dan counter putar lagu denormalisasi.

| Field         | Type     | Rules                 | Keterangan                                                                                   |
| :------------ | :------- | :-------------------- | :------------------------------------------------------------------------------------------- |
| `id`          | `text`   | Primary Key (15 char) | ID unik user PocketBase                                                                      |
| `email`       | `email`  | Required, Unique      | Email akun user (login via OTP)                                                              |
| `name`        | `text`   | Optional              | Nama tampilan pengguna                                                                       |
| `avatar`      | `file`   | Optional              | Gambar profil pengguna                                                                       |
| `total_spins` | `number` | Integer, Min 0        | **Denormalized total plays** (bertambah atomik `total_spins+` jika lagu didengar ≥ 20 detik) |

**API Rules**:

- List / View / Update / Delete: `id = @request.auth.id`
- Create: Public (pendaftaran akun baru)

---

## 2. `favorites` (Base Collection)

Menyimpan lagu-lagu yang disukai (Liked Bangers / Hearts).

| Field        | Type       | Rules                    | Keterangan                       |
| :----------- | :--------- | :----------------------- | :------------------------------- |
| `id`         | `text`     | Primary Key              | ID record                        |
| `user`       | `relation` | Required, Cascade Delete | Relasi ke `users.id`             |
| `track_id`   | `text`     | Required                 | ID unik lagu (Spotify / YouTube) |
| `track_data` | `json`     | Required                 | Metadata lengkap objek `Track`   |

**Index**:

- `CREATE UNIQUE INDEX idx_favorites_user_track ON favorites (user, track_id)`

**API Rules**:

- List / View / Create / Update / Delete: `@request.auth.id != '' && user = @request.auth.id`

---

## 3. `recents` (Base Collection)

Menyimpan riwayat lagu yang diputar (Spins).

| Field        | Type       | Rules                    | Keterangan                               |
| :----------- | :--------- | :----------------------- | :--------------------------------------- |
| `id`         | `text`     | Primary Key              | ID record                                |
| `user`       | `relation` | Required, Cascade Delete | Relasi ke `users.id`                     |
| `track_id`   | `text`     | Required                 | ID unik lagu                             |
| `track_data` | `json`     | Required                 | Metadata lengkap objek `Track`           |
| `play_count` | `number`   | Optional                 | Berapa kali lagu ini diputar oleh user   |
| `played_at`  | `text`     | Optional                 | Timestamp ISO UTC waktu terakhir diputar |

**Index**:

- `CREATE UNIQUE INDEX idx_recents_user_track ON recents (user, track_id)`

**API Rules**:

- List / View / Create / Update / Delete: `@request.auth.id != '' && user = @request.auth.id`

---

## 4. `playlists` (Base Collection)

Menyimpan playlist kustom buatan pengguna (Crates).

| Field    | Type       | Rules                    | Keterangan                              |
| :------- | :--------- | :----------------------- | :-------------------------------------- |
| `id`     | `text`     | Primary Key              | ID record playlist                      |
| `user`   | `relation` | Required, Cascade Delete | Relasi ke `users.id`                    |
| `name`   | `text`     | Required                 | Nama playlist (contoh: _Chill Vibes_)   |
| `tracks` | `json`     | Required                 | Array list objek `Track` dalam playlist |

**API Rules**:

- List / View / Create / Update / Delete: `@request.auth.id != '' && user = @request.auth.id`

---

## 5. `playback_states` (Base Collection)

Menyimpan status pemutaran untuk fitur cross-device hand-off (Spotify Connect style).

| Field         | Type       | Rules                    | Keterangan                        |
| :------------ | :--------- | :----------------------- | :-------------------------------- |
| `id`          | `text`     | Primary Key              | ID record                         |
| `user`        | `relation` | Required, Cascade Delete | Relasi ke `users.id`              |
| `device_id`   | `text`     | Required                 | ID unik perangkat                 |
| `device_name` | `text`     | Optional                 | Nama perangkat (misal: _Pixel 7_) |
| `track_data`  | `json`     | Optional                 | Data lagu yang sedang aktif       |
| `position_ms` | `number`   | Optional                 | Posisi play terakhir (milidetik)  |
| `is_playing`  | `bool`     | Optional                 | Status sedang pause atau playing  |

**Index**:

- `CREATE UNIQUE INDEX idx_playback_user_device ON playback_states (user, device_id)`

**API Rules**:

- List / View / Create / Update / Delete: `@request.auth.id != '' && user = @request.auth.id`

---

## Cara Import ke PocketBase Baru

1. Jalankan server PocketBase baru:
   ```bash
   ./pocketbase serve --http="0.0.0.0:8090"
   ```
2. Buka dashboard admin PocketBase: `http://localhost:8090/_/`
3. Masuk ke **Settings > Sync collections**.
4. Upload file **`pb_schema.json`** yang ada di root direktori ini dan klik **Confirm**.
5. Di menu **Settings > Mail settings**, atur SMTP (contoh: Brevo) agar fitur OTP email aktif.
