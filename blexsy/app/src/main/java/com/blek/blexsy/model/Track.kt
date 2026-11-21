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
    @SerialName("is_playable") val isPlayable: Boolean = true,
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
     * Artist references as (id, name) pairs. Ids are YouTube Music channel ids or Spotify artist
     * ids; when a track has no catalog id the name is still returned as a label, so callers must
     * check [primaryArtistRef] before opening an artist page.
     */
    val artistRefs: List<Pair<String, String>>
        get() {
            val names = artist.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (names.isEmpty()) return artistIds.filter { it.isCatalogArtistId() }.map { it to it }
            // Ids and names come from the same ordered list, so pair them by index without
            // filtering (filtering would shift the ids). A name without an id keeps the name as
            // its placeholder; callers resolve it before navigating.
            return names.mapIndexed { index, name ->
                (artistIds.getOrNull(index)?.takeIf { it.isCatalogArtistId() } ?: name) to name
            }
        }

    /** A real YouTube Music channel id or Spotify artist id for the primary artist. */
    val primaryArtistRef: String?
        get() = artistIds.firstOrNull { it.isCatalogArtistId() }

    val highResImage: String?
        get() = image.toHighResArtwork()
}

/**
 * Stamps a chart rank onto a track for list rendering: [rank] is the position, [rankStatus] the
 * up/down/new marker. Both are YouTube's own fields, so a Spotify chart entry needs them written
 * in. Tracks without a rank are returned untouched.
 */
internal fun Track.withChartRank(rank: ChartRank): Track = copy(
    rank = rank.position,
    rankStatus = when {
        rank.isNew -> "new"
        (rank.delta ?: 0) > 0 -> "up"
        (rank.delta ?: 0) < 0 -> "down"
        else -> "same"
    }
)

/**
 * YouTube/Google artwork URLs carry their size in the query string, so asking for a larger
 * variant is just a rewrite. Unknown hosts are returned unchanged.
 */
fun String?.toHighResArtwork(): String? = this
    .normalizeSpotifyImageUrl()
    ?.replace(Regex("=w\\d+-h\\d+"), "=w1024-h1024")
    ?.replace(Regex("=s\\d+"), "=s1200")
