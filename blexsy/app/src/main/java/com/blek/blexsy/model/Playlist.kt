package com.blek.blexsy.model

import kotlinx.serialization.Serializable

fun String?.normalizeSpotifyImageUrl(): String? {
    val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
        raw.startsWith("spotify:image:", ignoreCase = true) -> {
            val hash = raw.substringAfterLast(':').trim()
            if (hash.isNotBlank()) "https://i.scdn.co/image/$hash" else null
        }
        raw.startsWith("spotify:mosaic:", ignoreCase = true) -> {
            val firstHash = raw.substringAfter("mosaic:", "")
                .split(':')
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
            if (!firstHash.isNullOrBlank()) "https://i.scdn.co/image/$firstHash" else null
        }
        else -> null
    }
}

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "Playlist",
    val image: String? = null,
    val tracks: List<Track> = emptyList(),
    val updatedAt: Long = 0L
) {
    val coverImage: String?
        get() = image.normalizeSpotifyImageUrl()
            ?: tracks.firstNotNullOfOrNull { it.image.normalizeSpotifyImageUrl() }
}
