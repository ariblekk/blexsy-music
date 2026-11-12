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
    @SerialName("played_at") val playedAt: String? = null,
    @SerialName("play_count") val playCount: Int? = null,
    val rank: Int? = null,
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

    /**
     * Artist references as (id, name) pairs. Ids are only ever real YouTube Music channel ids;
     * when a track has no channel id the name is still returned as a label, but [artistRefs]
     * keeps the id equal to the name, so callers must check [primaryArtistRef] before opening
     * an artist page.
     */
    val artistRefs: List<Pair<String, String>>
        get() {
            val names = artist.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val channelIds = artistIds.filter { it.startsWith("UC") || it.startsWith("FEmusic_artist") }
            if (channelIds.size > 1) {
                return channelIds.mapIndexed { index, id ->
                    id to (names.getOrNull(index) ?: id)
                }
            }
            if (names.size > 1) {
                return names.mapIndexed { index, name ->
                    (channelIds.getOrNull(index) ?: name) to name
                }
            }
            val singleId = primaryArtistRef
            val singleName = names.firstOrNull() ?: artist
            return if (singleId != null) listOf(singleId to singleName) else emptyList()
        }

    /** A real YouTube Music channel id for the primary artist, or null when unknown. */
    val primaryArtistRef: String?
        get() = artistIds.firstOrNull { it.startsWith("UC") || it.startsWith("FEmusic_artist") }

    val highResImage: String?
        get() = image.toHighResArtwork()
}

/**
 * YouTube/Google artwork URLs carry their size in the query string, so asking for a larger
 * variant is just a rewrite. Unknown hosts are returned unchanged.
 */
fun String?.toHighResArtwork(): String? = this
    ?.replace(Regex("=w\\d+-h\\d+"), "=w1024-h1024")
    ?.replace(Regex("=s\\d+"), "=s1200")
