package com.blek.blexsy.model

/**
 * Spotify catalog ids are 22 base62 characters. Every other id in this app is a YouTube Music
 * one: channel ids start with `UC`, browse ids with `FEmusic_`, playlists with `VL`/`PL`, and
 * video ids are 11 characters.
 */
private val SPOTIFY_CATALOG_ID = Regex("[0-9A-Za-z]{22}")

fun String.isSpotifyCatalogId(): Boolean = SPOTIFY_CATALOG_ID.matches(this)

/** True when [this] is an id an artist page can actually be opened with. */
fun String.isCatalogArtistId(): Boolean =
    startsWith("UC") || startsWith("FEmusic_artist") || isSpotifyCatalogId()
