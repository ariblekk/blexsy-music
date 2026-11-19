package com.blek.blexsy.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A ranked song chart plus the market shelves that arrive with it. Spotify market charts fill
 * [ranks]; the YouTube fallback has none, so every reader must treat it as optional rather than
 * assume position == list index.
 */
@Serializable
data class ChartFeed(
    val title: String = "Top 50 Indonesia",
    val tracks: List<Track> = emptyList(),
    /** Chart metadata; null when the chart came from YouTube. */
    val id: String? = null,
    val image: String? = null,
    /** `daily` / `weekly`. */
    val frequency: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** Market the chart belongs to (`id`, `global`); null for YouTube. */
    val country: String? = null,
    /** Rank per track id: position, previous position (null when new) and play count. */
    @SerialName("ranks") val ranks: Map<String, ChartRank> = emptyMap(),
    /** Songs rising in the market right now; empty for the YouTube fallback. */
    val trending: List<Track> = emptyList(),
    /** Artists trending in the market right now; empty for the YouTube fallback. */
    @SerialName("popular_artists") val popularArtists: List<ArtistSummary> = emptyList()
) {
    fun rankOf(trackId: String): ChartRank? = ranks[trackId]
}

@Serializable
data class ChartRank(
    val position: Int,
    /** Previous position; null when the track is new to the chart. */
    val previous: Int? = null,
    val plays: Long? = null
) {
    /** True when this chart is the first to rank the track. */
    val isNew: Boolean get() = previous == null

    /** Positions gained since the last chart; negative means it fell. */
    val delta: Int? get() = previous?.let { it - position }
}
