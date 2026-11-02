package com.blek.blexsy.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class QueueTier {
    USER_QUEUE,
    CONTEXT,
    AUTOPLAY
}

@Serializable
data class Track(
    val id: String = "",
    val title: String = "",
    val name: String? = null,
    val artist: String = "Unknown artist",
    val album: String = "",
    val image: String? = null,
    @SerialName("album_id") val albumId: String? = null,
    @SerialName("artist_ids") val artistIds: List<String> = emptyList(),
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("youtube_id") val youtubeId: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    @SerialName("spotify_url") val spotifyUrl: String? = null,
    @SerialName("played_at") val playedAt: String? = null,
    @SerialName("play_count") val playCount: Int? = null,
    val rank: Int? = null,
    @SerialName("previous_rank") val previousRank: Int? = null,
    @SerialName("rank_change") val rankChange: Int? = null,
    @SerialName("rank_status") val rankStatus: String? = null,
    val queueTier: QueueTier = QueueTier.CONTEXT,
    val queueEntryId: String? = null
) {
    val displayTitle: String
        get() = title.ifBlank { name ?: "Unknown track" }

    val durationLabel: String
        get() {
            val totalSeconds = ((durationMs ?: 0L) / 1000).toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "$minutes:${seconds.toString().padStart(2, '0')}"
        }

    val artistRefs: List<Pair<String, String>>
        get() {
            val names = artist.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (artistIds.size > 1) {
                return artistIds.mapIndexed { index, id ->
                    id to (names.getOrNull(index) ?: id)
                }
            }
            if (names.size > 1) {
                return names.mapIndexed { index, name ->
                    val id = artistIds.getOrNull(index)?.takeIf { it.isNotBlank() } ?: name
                    id to name
                }
            }
            val singleId = primaryArtistRef
            val singleName = names.firstOrNull() ?: artist
            return if (singleId != null) listOf(singleId to singleName) else emptyList()
        }

    val primaryArtistRef: String?
        get() = artistIds.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: artist.takeIf { it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true) }

    val highResImage: String?
        get() = image.toHighResArtwork()
}

/**
 * Spotify CDN stores album art in 3 sizes:
 * - 64x64 (ab67616d00004851)
 * - 300x300 (ab67616d00001e02) -> default sent by backend
 * - 640x640 (ab67616d0000b273) -> crisp HD
 * And artist photos in:
 * - 160x160 (ab6761610000f178), 320x320 (ab67616100005174), 640x640 (ab6761610000e5eb)
 */
fun String?.toHighResArtwork(): String? = this
    ?.replace("ab67616d00001e02", "ab67616d0000b273")
    ?.replace("ab67616d00004851", "ab67616d0000b273")
    ?.replace("ab67616100005174", "ab6761610000e5eb")
    ?.replace("ab6761610000f178", "ab6761610000e5eb")
    ?.replace(Regex("=w\\d+-h\\d+"), "=w1024-h1024")
    ?.replace(Regex("=s\\d+"), "=s1200")
    ?.replace(Regex("/\\d+x\\d+-"), "/1000x1000-")
