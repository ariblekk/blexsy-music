# Changelog

Semua perubahan dan riwayat pembaruan penting pada proyek **Blexsy** didokumentasikan di berkas ini.

Format berkas mengikuti prinsip [Keep a Changelog](https://keepachangelog.com/id/1.0.0/) dan mematuhi aturan [Semantic Versioning](https://semver.org/lang/id/).

---

## [1.0.4] - 2026-11-24

### Added

- **Hybrid Spotify Metadata Backend + Direct InnerTube Audio Architecture**: Seluruh pencarian katalog lagu (`/v1/search`), tangga lagu (`/v1/charts`), kategori genre (`/v1/browse`, `/v1/genre`), detail album (`/v1/album`), detail artis (`/v1/artist`), detail playlist (`/v1/playlist`), dan kredit lagu diambil dari backend metadata Spotify (`SpotifyMetadata` / `spotifree-rs` di `blexsy.blek.my.id`), sementara resolusi stream audio dan rekomendasi radio dijalankan langsung dari client ke YouTube Music InnerTube (`VISIONOS` & `WEB_REMIX`) serta lirik tersinkronisasi melalui `lrc.red` / `LRCLIB`.
- **Spotify Charts, Trending & Popular Feeds**: Integrasi tangga lagu harian/mingguan Spotify, daftar lagu trending, album populer, dan artis populer dengan dukungan _disk cache_ lokal (`ApiCache`) untuk pemuatan instan saat _cold-start_.
- **Direct VISIONOS Audio Stream Engine**: Resolusi stream audio langsung via client YouTube InnerTube `VISIONOS` dengan header `Referer: https://www.youtube.com/`, menjamin bebas error 403 chunk URL tanpa backend redirect audio dan tanpa decipher JS cipher.
- **Pure Studio Audio Stream Matching (`MUSIC_VIDEO_TYPE_ATV`)**: Saat memutar lagu dari katalog Spotify maupun mengambil rekomendasi radio, mesin pencocok `InnerTubeService` otomatis memprioritaskan rekaman studio resmi (`MUSIC_VIDEO_TYPE_ATV`) dan memfilter video klip (OMV), Shorts, live concert, maupun cover amatir (UGC) agar audio bebas dari dialog intro video klip.
- **Mochy Parametric Equalizer**: Porting lengkap DSP equalizer biquad RBJ dari Mochy (`Biquad`, `PeakLimiter`, dan `CustomEqualizerAudioProcessor`) terintegrasi langsung ke audio sink ExoPlayer via Media3 `DefaultRenderersFactory`.
- **10-Band Interactive EQ & Presets**: Menyediakan 14 profil preset bawaan (Flat, Bass Booster, Bass Reducer, Acoustic, Rock, Pop, Hip-Hop, Classical, Electronic, Jazz, Vocal Booster, dsb), kontrol Preamp (-12 dB s/d +12 dB), dan visualizer canvas interaktif (sweep-to-draw gesture dengan Catmull-Rom curve) yang tersimpan secara persisten pada SharedPreferences.
- **Mochy-Style InnerTube Automix Queue & Infinite Radio**: Pengambilan rekomendasi radio dan infinite autoplay langsung menggunakan InnerTube YouTube Music `/youtubei/v1/next` (`RDAMVM...` automix playlist dan token continuation) dengan aturan `QueuePolicy`, mendukung `playNext`, `addToQueue`, reorder drag-and-drop, dan penghapusan item antrean.
- **Physical Audio Quality Selector**: Kontrol kualitas audio (Data Saver 48-64kbps Opus/AAC, Balanced 128kbps AAC/Opus, Crisp 160kbps Opus itag 251/140) aktif secara fisik dengan direct stream itags.
- **Custom Lyrics Share Card**: Tekan dan tahan (_long-press_) baris lirik pada halaman Synced Lyrics untuk membuat kartu lirik (ekspor PNG full-card) dengan seleksi hingga 5 baris berurutan, 9 palet warna latar, opsi latar gambar dari Artwork Lagu / Banner Artis / Foto Profil Artis, serta 4 pilihan gaya font (_Klasik_, _Lebar_, _Ramping_, _Miring_) dan dukungan terjemahan.
- **SoundCloud-Style Waveform Progress Bar**: Scrubber di Full Player kini menampilkan visualisasi _dual-layer waveform_ interaktif (bar utama + pantulan bawah) dengan efek _magnification_ dan respons _haptic_ saat digeser.
- **Crossfade & Gapless Playback**: Pengaturan transisi antar lagu (`Off`, `3s`, `5s`, `8s`, `12s`) dengan _fade-out / fade-in_ halus yang terintegrasi penuh bersama fitur _Lewati Bagian Hening (Skip Dead Air)_ tanpa memicu _double-skip_.
- **Interactive Swipe MiniPlayer & Ambient Artwork Tint**: Geser ke kiri atau kanan langsung pada bar MiniPlayer untuk berpindah lagu dengan animasi _spring_ mulus beserta sampul lagunya, dilengkapi aksen gradasi warna halus (_ambient glow_) dari sampul lagu aktif pada MiniPlayer dan Bottom Navbar.
- **Lucide Vector Icon System**: Mengganti seluruh set ikon aplikasi ke _Lucide Icons_ berbasis Android Vector Drawable XML.
- **Spotify-Style Save System (`+` / `✓`)**: Mengganti ikon hati dengan tombol `+` dan centang hijau `✓` yang terhubung langsung ke _Liked Bangers_ serta pengelolaan multi-playlist dalam satu bottom sheet.
- **Real-Time Two-Way Spotify Sync**: Sinkronisasi otomatis (_auto-sync_) saat aplikasi dibuka untuk _Liked Songs_, _Playlists_, _Saved Albums_, dan _Followed Artists_, serta sinkronisasi dua arah langsung saat menambah/menghapus lagu playlist dan _follow/unfollow_ artis.
- **Standardized Aligned Track Numbers (`TrackTile`)**: Penyelarasan tampilan nomor urut lagu di semua daftar lagu (Playlist, Album, Artis, dan Koleksi) dengan slot nomor lebar tetap (28 dp) dan visualisasi status lagu aktif berwarna aksen hijau.
- **Curated Home Feed Rails**: Halaman Home menampilkan rail _Made For You_, _Top 50 Chart_, _Trending Songs_, _Popular Artists_, _Your Favorite Artists_ (khusus artis yang diikuti), dan _Playlist Collection_.
- **Unified Left-Aligned Segmented Tabs (`BlexsyTabs`)**: Segmented pill tabs pada halaman Equalizer dan Rilis Diskografi Artis (`All`, `Albums`, `Singles & EPs`) distandarisasi rata kiri (_align-left_) dan membungkus lebar konten (_wrap-width_).
- **Standardized Aligned Track Numbers (`TrackTile`)**: Penyelarasan tampilan nomor urut lagu di semua daftar lagu (Playlist, Album, Artis, dan Koleksi) dengan slot nomor lebar tetap (26 dp) dan visualisasi status lagu aktif berwarna aksen hijau.
- **New Releases Carousel & "Show all" Action**: Pembatasan carousel rilis baru di Home Feed maksimal 10 album, dilengkapi aksi _"Show all"_ menuju tampilan daftar rilis lengkap (`PlaylistDetail`).
- **Unified Left-Aligned Segmented Tabs (`BlexsyTabs`)**: Segmented pill tabs pada halaman Profile, Equalizer, dan Artist distandarisasi rata kiri (_align-left_) dan membungkus lebar konten (_wrap-width_).
- **Per-ABI Release APK Splits**: Dukungan pembuatan APK rilis terpisah per arsitektur (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, dan `universal`) melalui opsi Gradle `-PsplitAbi`.

### Fixed & Improved

- **Artist Discography & Detail Page Cleanup**: Menambahkan tab `"All"` pada bagian rilis diskografi artis (gabungan Album dan Singles & EPs terurut berdasarkan tahun), menyeragamkan warna latar halaman Artis, Playlist, dan Album ke warna tema gelap bawaan, serta menghapus teks label _kicker_ berlebih di atas judul halaman.
- **Streamlined Profile Page**: Menyederhanakan tampilan profil menjadi header akun, 3 statistik utama, dan daftar _Riwayat Diputar (Listening History)_ secara langsung.
- **Spotify Mosaic & Image URI Normalization**: Memperbaiki pembacaan URL cover playlist Spotify (`spotify:mosaic:...` dan `spotify:image:...`) agar sampul playlist selalu tampil.
- **Unplayable / Taken-Down Track Filtering**: Lagu yang terkena _takedown_ atau tidak tersedia (`isPlayable = false`) kini otomatis diredupkan, dinonaktifkan dari klik, dan dilewati otomatis dari antrean pemutaran.
- **Hardware Bitmap Palette Extraction Fix**: Memperbaiki ekstraksi warna dominan sampul album pada Coil 3 (`allowHardware(false)`) agar warna aksen MiniPlayer, Navbar, dan Player selalu terdeteksi akurat.
- **Dock Spacing Refinement**: Mengurangi celah vertikal antara floating `MiniPlayer` dan `BottomNav` menjadi lebih rapat dan terpadu (4 dp).
- **Prevented YouTube Video Audio Leaks**: Mengganti sumber tangga lagu Home dari carousel video `FEmusic_explore` ke kurasi Top Hits studio murni, menghilangkan suara percakapan intro video, tepuk tangan live concert, dan rekaman cover amatir.
- **Cleaned Video Tag Titles**: Pembersihan otomatis tag judul video (`(Official Music Video)`, `[Official Video]`, `(Live Session)`, dsb) pada UI pemutar dan daftar lagu.
- **Restored Standard Playback Behavior on Tap**: Mengetuk lagu pada hasil pencarian atau daftar kini memutar antrean lagu konteks aslinya secara natural layaknya Spotify/Apple Music, bukan otomatis memaksa masuk ke mode radio.
- **Full Queue State Recovery**: Memulihkan hingga 100 lagu lengkap beserta jenis antrean (`QueueSource.CONTEXT` atau `RADIO`) dan data seed aslinya saat aplikasi dibuka kembali.
- **Player & ExoPlayer Desynchronization Fix**: Memperbaiki kondisi desync ketika memulai radio pada lagu yang sedang aktif serta penanganan otomatis penyelarasan _media items_ saat manipulasi antrean.

---

## [1.0.3] - 2026-10-26

### ✨ Added

- **Synchronized Lyrics with `lrc.red`**: Integrasi lirik tersinkronisasi dengan parser LRC multi-timestamp, penyorotan baris aktif secara real-time, dan fitur tap-to-seek langsung ke posisi waktu baris lirik.
- # **Interactive Lyrics Controls**: Seekbar interaktif dengan scrubber responsif dan tombol play/pause di bagian bawah layar lirik untuk kontrol pemutaran langsung.
- **Dynamic Auto-Hiding Lyrics Controls**: Seekbar dan kontrol player kini otomatis tersembunyi saat pengguna menggulir ke bawah untuk membaca lirik, dan otomatis muncul kembali saat menggulir ke atas, berada di sekitar lirik aktif, mengetuk baris lirik, atau saat kembali ke auto-scroll aktif.
- **Keep Screen Awake on Lyrics**: Layar lirik secara otomatis menjaga layar perangkat tetap menyala (_keep screen awake_) selama lagu diputar melalui Android native platform channel.
- **Smooth Lyrics Transitions**: Transisi cross-fade lembut (`AnimatedSwitcher`) antar-lagu di layar lirik dan auto-scroll presisi berbasis kurva kubik (`easeInOutCubic`) bebas jitter tipografi.
- **Provider Credits & Attribution**: Atribusi teks untuk penyedia lirik sesuai pedoman lrc.red serta pembaruan section Credits di dialog About Settings (lrc.red, Spotify, YouTube Music).

### ⚡ Performance & Optimization

- **Optimized Artwork Resolution**: Menurunkan resolusi cover Spotify ke ukuran medium (200px–450px) dan membatasi ukuran decoding memori (`cacheWidth`) di Flutter untuk mempercepat pemuatan halaman dan efisiensi kuota.

### 🐛 Fixed & Improved

- **LRC Timestamp Sanitization**: Pembersihan menyeluruh kebocoran format waktu `[mm:ss.xx]`, `[mm:ss:xx]`, tag metadata `[ar:...]`, dan tag word-level `<...>` dari teks baris yang ditampilkan ke pengguna.
- **Dynamic Track Change Synchronization**: Memperbaiki bug di mana layar lirik terkunci di lagu sebelumnya saat antrean berpindah lagu secara otomatis maupun manual.
- **Player Modal Cleanup**: Menghilangkan kartu foto dan nama artis berulang di bawah card cuplikan lirik pada modal pemutar utama.

---

## [1.0.2] - 2026-10-25

### ✨ Added

- **Queue Rebuild Action**: Tombol aksi `REBUILD` di samping tombol clear pada header antrean pemutaran untuk membuat ulang antrean rekomendasi lagu secara dinamis dari lagu yang sedang diputar.
- **Clear Queue Confirmation Dialog**: Dialog konfirmasi peringatan (`AppAlertDialog`) sebelum mengosongkan antrean lagu untuk mencegah aksi hapus yang tidak disengaja.

### 🐛 Fixed & Improved

- **Queue & Audio Player Synchronization**: Mengatasi desinkronisasi `ConcatenatingAudioSource` dengan antrean saat `clearQueue()`, penghapusan lagu aktif pada `removeFromQueue()`, serta penanganan `addToQueue()` saat player belum aktif (_reported by Ridwan Saidi_).
- **Seamless Queue Item Seeking**: Mengetuk lagu di bottom sheet antrean kini langsung melompat (_seek_) ke indeks lagu tanpa menghancurkan dan memuat ulang seluruh playlist audio source dari awal.
- **Batch Refill & Shuffle Prefetch**: Pengisian ulang antrean rekomendasi radio kini menggunakan operasi batch `addAll`, dan penentuan prefetch lagu berikutnya kini mengikuti indeks pemutar saat mode shuffle aktif.

---

## [1.0.1] - 2026-10-25

### ✨ Added

- **Chart Rank Movements**: Indikator pergerakan peringkat pada Top 50 (naik `▲`, turun `▼`, tetap `➖`, dan lagu baru `NEW`) yang dikalkulasi otomatis tiap pergantian hari di database.
- **Smart "For You" Recommendation Engine**: Sistem rekomendasi mandiri berjenjang berdasarkan aktivitas riil pendengar (`recent` → `favorites` → `search` → `top chart`) tanpa terhapus saat riwayat pencarian dibersihkan.
- **Granular Search History Removal**: Fitur hapus satu per satu kata kunci pencarian pada tab Explore via tombol close pada chip riwayat.
- **Persistent Song Credits Caching**: Penyimpanan lokal untuk song credits di memori & penyimpanan perangkat, menghilangkan kebutuhan scraping berulang kali.
- **Dynamic Home Quotes**: Koleksi teks jenaka dan jokes musik acak pada halaman Home yang otomatis berganti saat ditarik (_pull-to-refresh_).
- **In-App Update Checker & About Dialog**: Pengecekan otomatis rilis terbaru via GitHub Releases API serta peluncuran browser eksternal via native Android Intent.
- **In-App Contributors Viewer**: Integrasi tampilan kontributor komunitas langsung dari berkas markdown GitHub via native Material widget.
- **Persistent Playback State Recovery**: Pemulihan antrean lagu (_queue_), lagu terakhir, dan detik posisi pemutaran saat aplikasi dibuka kembali.

### 🐛 Fixed & Improved

- **Guest Mode Authentication**: Refactoring middleware otentikasi server agar mode tamu dapat mencari katalog lagu, melihat tangga lagu, dan streaming audio tanpa hambatan HTTP 401 (_reported by Romy Jatmiko_).
- **Task Removal Lifecycle**: Terminasi pemutar audio, notifikasi media, dan Android Foreground Service seketika aplikasi ditutup dari Recent Apps (_suggested by Ridwan Saidi_).
- **Daily Midnight Chart Cache**: Kebijakan refresh cache Top 50 di database PostgreSQL diubah menjadi harian tepat pukul 00:00 waktu server dengan proteksi fallback data lokal.
- **Optimized Home History**: Rail riwayat putar (_Recently played_) di halaman Home dibatasi 10 lagu terbaru untuk performa render yang lebih ringan dan konsisten dengan rail Top 10 Chart.

---

## [1.0.0] - 2026-10-25

### 🚀 Initial Release

- Integrasi katalog dan metadata musik Spotify.
- Resolusi dan streaming audio native dari YouTube Music.
- Pemutar audio latar belakang (_background audio_) dengan notifikasi kontrol media dan lockscreen.
- Fitur pencarian lagu, artis, album, dan genre.
- Pengelolaan pustaka musik pribadi, daftar putar (_playlist_), dan lagu yang disukai (_favorites_).
- Otentikasi sesi berbasis email dan kode verifikasi (OTP).
