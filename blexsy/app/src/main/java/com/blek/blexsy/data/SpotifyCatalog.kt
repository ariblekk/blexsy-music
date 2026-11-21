package com.blek.blexsy.data

import com.blek.blexsy.model.AlbumDetail
import com.blek.blexsy.model.AlbumSummary
import com.blek.blexsy.model.ArtistDetail
import com.blek.blexsy.model.ArtistPinnedPick
import com.blek.blexsy.model.ArtistSocialLink
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.ArtistTopCity
import com.blek.blexsy.model.ChartFeed
import com.blek.blexsy.model.ChartRank
import com.blek.blexsy.model.GenrePlaylist
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.withChartRank

// ---------------------------------------------------------------------------
// Spotify models -> the app's catalog models. These are catalog metadata only:
// playback still resolves audio through YouTube, so mapped tracks carry no
// youtubeId and fall back to a title/artist search at play time.
// ---------------------------------------------------------------------------

internal fun SpotifyTrack.toTrack(
    artistIdsFallback: List<String> = emptyList(),
    albumFallback: String? = null,
    imageFallback: String? = null,
    idFallback: String? = null
): Track = Track(
    id = id.ifBlank { idFallback.orEmpty() },
    title = name,
    artist = SpotifyMetadata.artistLine(artists).ifBlank { "Unknown artist" },
    album = album?.takeIf { it.isNotBlank() } ?: albumFallback.orEmpty(),
    image = image ?: imageFallback,
    // The track's own cast wins; the album/artist shelf ids are only a fallback.
    artistIds = artistIds.ifEmpty { artistIdsFallback },
    durationMs = durationMs,
    isPlayable = playable
)

internal fun SpotifyArtist.toArtistSummary(): ArtistSummary = ArtistSummary(
    id = id,
    name = name,
    image = image
)

internal fun SpotifyAlbum.toAlbumSummary(): AlbumSummary = AlbumSummary(
    id = id,
    name = name,
    artist = artists.joinToString(", ").takeIf { it.isNotBlank() },
    image = image,
    year = year?.toString(),
    type = "ALBUM"
)

internal fun SpotifyPlaylist.toGenrePlaylist(): GenrePlaylist = GenrePlaylist(
    id = id,
    name = name,
    image = image,
    description = owner?.takeIf { it.isNotBlank() }?.let { "By $it" }.orEmpty()
)

internal fun SpotifyAlbumDetail.toAlbumDetail(): AlbumDetail {
    val artistIds = artists.map { it.id }
    return AlbumDetail(
        id = id,
        name = name,
        artist = artists.joinToString(", ") { it.name }.ifBlank { "Unknown artist" },
        image = image,
        year = releaseYear?.toString(),
        type = type?.uppercase() ?: "ALBUM",
        tracks = tracks.map {
            it.toTrack(artistIdsFallback = artistIds, albumFallback = name, imageFallback = image)
        }
    )
}

internal fun SpotifyArtistDetail.toArtistDetail(): ArtistDetail = ArtistDetail(
    id = id,
    name = name,
    uri = uri,
    image = image,
    banner = banner,
    gallery = gallery,
    verified = verified,
    biography = biography,
    followers = followers?.toCatalogInt(),
    monthlyListeners = monthlyListeners?.toCatalogInt(),
    worldRank = worldRank,
    topCities = topCities.map {
        ArtistTopCity(
            city = it.city,
            country = it.country,
            region = it.region,
            listeners = it.numberOfListeners.toCatalogInt()
        )
    },
    externalLinks = externalLinks.map {
        ArtistSocialLink(name = it.name, url = it.url)
    },
    pinnedPick = pinnedPick?.let {
        ArtistPinnedPick(
            title = it.title,
            subtitle = it.subtitle,
            comment = it.comment,
            type = it.type,
            id = it.id,
            uri = it.uri,
            image = it.image
        )
    },
    topTracks = topTracks.map { it.toTrack(artistIdsFallback = listOf(id), imageFallback = image) },
    albums = albums.map { it.toAlbumSummary() },
    singlesAndEps = singles.map { it.toAlbumSummary() },
    playlists = playlists.map {
        AlbumSummary(
            id = it.id,
            name = it.name,
            artist = it.owner,
            image = it.image,
            year = it.owner?.let { owner -> "By $owner" },
            type = "PLAYLIST"
        )
    },
    similarArtists = relatedArtists.map { it.toArtistSummary() }
)

internal fun SpotifyPlaylistDetail.toPlaylist(): Playlist = Playlist(
    id = id,
    name = name,
    image = image,
    tracks = tracks.map { it.toTrack() }
)

/**
 * Flattens a market chart into the app's [ChartFeed]. Only the chart the server expanded carries
 * rows; the others come back empty and are ignored. Each track is stamped with its own rank so any
 * list that renders [Track.rank] shows the real position and movement, and [ChartFeed.ranks] keeps
 * the same data keyed by track id for lookups.
 *
 * [trending] and [popularArtists] ride along because a single request already paid for them: the
 * home feed shows both shelves from the same response.
 */
internal fun SpotifyCharts.toChartFeed(): ChartFeed {
    val chart = primary ?: return emptyChartFeed()
    val rows = chart.entries
        .sortedBy { it.position }
        .map { entry ->
            val rank = ChartRank(position = entry.position, previous = entry.previous, plays = entry.plays)
            val track = entry.track.toTrack(imageFallback = chart.image).withChartRank(rank)
            track.id to (rank to track)
        }
    return ChartFeed(
        title = chart.name.ifBlank { "Top 50" },
        tracks = rows.map { it.second.second },
        id = chart.id.ifBlank { null },
        image = chart.image,
        frequency = chart.frequency,
        updatedAt = chart.updatedAt,
        country = country.ifBlank { null },
        ranks = rows.associate { (id, pair) -> id to pair.first },
        trending = trending.map { it.toTrack() },
        popularArtists = popularArtists.map { it.toArtistSummary() }
    )
}

/** No ranked chart came back: the shelves that ride along still travel, the chart does not. */
internal fun SpotifyCharts.emptyChartFeed(): ChartFeed = ChartFeed(
    tracks = emptyList(),
    country = country.ifBlank { null },
    trending = trending.map { it.toTrack() },
    popularArtists = popularArtists.map { it.toArtistSummary() }
)

/** Spotify reports counts as Long; the UI models use Int, so saturate instead of wrapping. */
private fun Long.toCatalogInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
