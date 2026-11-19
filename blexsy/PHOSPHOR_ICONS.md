# Phosphor Icons — daftar yang perlu diunduh

Sumber: https://phosphoricons.com

Pola URL langsung:

```
https://raw.githubusercontent.com/phosphor-icons/core/main/assets/fill/<nama>-fill.svg
https://raw.githubusercontent.com/phosphor-icons/core/main/assets/regular/<nama>.svg
```

Simpan hasil unduhan ke:

```
blexsy/app/src/main/assets/icons/
```

Centang setelah file tersedia. Nama file di kolom terakhir adalah nama yang diharapkan.

Total: **52 file** — 50 nama ikon unik + 2 varian `regular` (heart, trash).

---

## Playback / player

| ✓ | Material sekarang | Phosphor | Weight | File |
|---|---|---|---|---|
| [ ] | PlayArrow | play | fill | `play-fill.svg` |
| [ ] | Pause | pause | fill | `pause-fill.svg` |
| [ ] | SkipNext | skip-forward | fill | `skip-forward-fill.svg` |
| [ ] | SkipPrevious | skip-back | fill | `skip-back-fill.svg` |
| [ ] | Repeat | repeat | fill | `repeat-fill.svg` |
| [ ] | RepeatOne | repeat-once | fill | `repeat-once-fill.svg` |
| [ ] | QueueMusic | queue | fill | `queue-fill.svg` |
| [ ] | PlaylistPlay | playlist | fill | `playlist-fill.svg` |
| [ ] | PlaylistAdd | playlist | fill | `playlist-fill.svg` *(sama)* |
| [ ] | MusicNote | music-note | fill | `music-note-fill.svg` |
| [ ] | LibraryMusic | music-notes | fill | `music-notes-fill.svg` |
| [ ] | VolumeUp | speaker-high | fill | `speaker-high-fill.svg` |
| [ ] | VolumeDown | speaker-low | fill | `speaker-low-fill.svg` |
| [ ] | VolumeMute | speaker-slash | fill | `speaker-slash-fill.svg` |
| [ ] | GraphicEq | equalizer | fill | `equalizer-fill.svg` |
| [ ] | Lyrics | subtitles | fill | `subtitles-fill.svg` |
| [ ] | Timer | timer | fill | `timer-fill.svg` |
| [ ] | TimerOff | bell-slash | fill | `bell-slash-fill.svg` |
| [ ] | Radio | radio | fill | `radio-fill.svg` |

## Navigasi / aksi umum

| ✓ | Material sekarang | Phosphor | Weight | File |
|---|---|---|---|---|
| [ ] | ArrowBack | arrow-left | fill | `arrow-left-fill.svg` |
| [ ] | ArrowForwardIos | caret-right | fill | `caret-right-fill.svg` |
| [ ] | ArrowDropDown | caret-down | fill | `caret-down-fill.svg` |
| [ ] | ArrowDropUp | caret-up | fill | `caret-up-fill.svg` |
| [ ] | Menu | list | fill | `list-fill.svg` |
| [ ] | MoreHoriz | dots-three | fill | `dots-three-fill.svg` |
| [ ] | MoreVert | dots-three-vertical | fill | `dots-three-vertical-fill.svg` |
| [ ] | Search | magnifying-glass | fill | `magnifying-glass-fill.svg` |
| [ ] | Add | plus | fill | `plus-fill.svg` |
| [ ] | Close | x | fill | `x-fill.svg` |
| [ ] | Check | check | fill | `check-fill.svg` |
| [ ] | Home | house | fill | `house-fill.svg` |
| [ ] | Person | user | fill | `user-fill.svg` |
| [ ] | Favorite | heart | **fill** | `heart-fill.svg` |
| [ ] | FavoriteBorder | heart | **regular** | `heart.svg` |
| [ ] | Info | info | fill | `info-fill.svg` |
| [ ] | Verified | seal-check | fill | `seal-check-fill.svg` |
| [ ] | History | clock-counter-clockwise | fill | `clock-counter-clockwise-fill.svg` |
| [ ] | Refresh | arrows-clockwise | fill | `arrows-clockwise-fill.svg` |
| [ ] | Delete | trash | **fill** | `trash-fill.svg` |
| [ ] | DeleteOutline | trash | **regular** | `trash.svg` |
| [ ] | Edit | pencil-simple | fill | `pencil-simple-fill.svg` |
| [ ] | CloudOff | cloud-slash | fill | `cloud-slash-fill.svg` |
| [ ] | Album | disc | fill | `disc-fill.svg` |

## Settings / tema

| ✓ | Material sekarang | Phosphor | Weight | File |
|---|---|---|---|---|
| [ ] | DarkMode | moon | fill | `moon-fill.svg` |
| [ ] | LightMode | sun | fill | `sun-fill.svg` |
| [ ] | Palette | palette | fill | `palette-fill.svg` |
| [ ] | HighQuality | high-definition | fill | `high-definition-fill.svg` |
| [ ] | Language | globe | fill | `globe-fill.svg` |
| [ ] | Translate | translate | fill | `translate-fill.svg` |
| [ ] | Settings | gear-six | fill | `gear-six-fill.svg` |
| [ ] | Storage | database | fill | `database-fill.svg` |
| [ ] | Login | sign-in | fill | `sign-in-fill.svg` |
| [ ] | Logout | sign-out | fill | `sign-out-fill.svg` |

---

## Catatan

- `playlist` dipakai untuk dua ikon berbeda: `PlaylistAdd` dan `PlaylistPlay`.
- `heart` dan `trash` butuh dua weight: `fill` untuk state aktif/terisi, `regular` untuk outline.
- Phosphor tidak punya `timer-off`. Dipilih `bell-slash`; alternatif: `x-circle` atau `prohibit`.
- Tidak ada ikon "lyrics" khusus. Dipilih `subtitles`; alternatif: `text-aa`.
- Semua ikon memakai viewBox `256x256`, jadi aman dikonversi ke VectorDrawable (`viewportWidth/Height = 256`).
- Lisensi Phosphor: MIT.