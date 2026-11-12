package com.blek.blexsy.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One card inside a home carousel. [type] tells the UI how to render it and where a tap
 * should navigate: a song plays, an album/playlist/artist opens its detail screen.
 *
 * The transient fields below exist only at runtime: [InnerTubeService] emits a flat [items]
 * list, and the home screen folds it into the row shapes its carousels already render.
 */
@Serializable
data class HomeSectionItem(
    val type: String = "song", // song | album | playlist | artist | item
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val image: String? = null,
    val track: Track? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val playlistId: String? = null,
    @Transient val variant: String = "",
    @Transient val tracks: List<Track> = emptyList(),
    @Transient val artists: List<ArtistSummary> = emptyList(),
    @Transient val albums: List<AlbumSummary> = emptyList(),
    @Transient val playlists: List<Playlist> = emptyList(),
    @Transient val featuredItem: FeaturedItem? = null
)

@Serializable
data class HomeSection(
    val id: String = "",
    val title: String = "",
    val variant: String = "", // song | album | playlist | artist | mixed
    val items: List<HomeSectionItem> = emptyList()
)
