package com.blek.blexsy.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import com.blek.blexsy.model.CreditPerson
import com.blek.blexsy.model.CreditRole
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.TrackCredits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// Public models (search results are normalized; detail endpoints are mapped
// down from Spotify's raw pathfinder unions to the fields the UI cares about).
// ---------------------------------------------------------------------------

/** Discography cap for `/v1/artist/{id}`: albums and singles/EPs each come back up to this many. */
private const val ARTIST_DISCOGRAPHY_LIMIT = 100

@Serializable
data class SpotifySearchResults(
    val query: String = "",
    val tracks: List<SpotifyTrack> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    val albums: List<SpotifyAlbum> = emptyList(),
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val podcasts: List<SpotifyPodcast> = emptyList()
)

@Serializable
data class SpotifyTrack(
    val id: String,
    val uri: String = "",
    val name: String,
    val artists: List<String> = emptyList(),
    /** Per-artist Spotify ids, index-aligned with [artists] when the payload provides them. */
    val artistIds: List<String> = emptyList(),
    val album: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val image: String? = null
)

@Serializable
data class SpotifyArtist(
    val id: String,
    val uri: String = "",
    val name: String,
    val image: String? = null
)

@Serializable
data class SpotifyAlbum(
    val id: String,
    val uri: String = "",
    val name: String,
    val artists: List<String> = emptyList(),
    val image: String? = null,
    val year: Int? = null
)

@Serializable
data class SpotifyPlaylist(
    val id: String,
    val uri: String = "",
    val name: String,
    val owner: String? = null,
    val image: String? = null
)

@Serializable
data class SpotifyPodcast(
    val id: String,
    val uri: String = "",
    val name: String,
    val publisher: String? = null,
    val image: String? = null
)

data class SpotifyTrackDetail(
    val track: SpotifyTrack,
    val explicit: Boolean = false,
    val playable: Boolean = true
)

data class SpotifyAlbumDetail(
    val id: String,
    val name: String,
    val uri: String = "",
    val type: String? = null,
    val label: String? = null,
    val releaseYear: Int? = null,
    val artists: List<SpotifyArtist> = emptyList(),
    val image: String? = null,
    val totalTracks: Int = 0,
    val tracks: List<SpotifyTrack> = emptyList()
)

data class SpotifyArtistDetail(
    val id: String,
    val name: String,
    val uri: String = "",
    val image: String? = null,
    val monthlyListeners: Long? = null,
    val followers: Long? = null,
    val biography: String? = null,
    val topTracks: List<SpotifyTrack> = emptyList(),
    val albums: List<SpotifyAlbum> = emptyList(),
    val singles: List<SpotifyAlbum> = emptyList(),
    val relatedArtists: List<SpotifyArtist> = emptyList()
)

data class SpotifyPlaylistDetail(
    val id: String,
    val name: String,
    val uri: String = "",
    val description: String? = null,
    val owner: String? = null,
    val image: String? = null,
    val followers: Long? = null,
    val totalTracks: Int = 0,
    val tracks: List<SpotifyTrack> = emptyList()
)

@Serializable
data class SpotifyChartEntry(
    val position: Int = 0,
    val previous: Int? = null,
    val plays: Long? = null,
    val track: SpotifyTrack
)

@Serializable
data class SpotifyChart(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val image: String? = null,
    val frequency: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val entries: List<SpotifyChartEntry> = emptyList()
)

/**
 * The market's charts plus its trending songs, albums and artists. Only one chart carries entries
 * per request — the one [top] selected.
 */
@Serializable
data class SpotifyCharts(
    val country: String = "",
    val color: String? = null,
    val charts: List<SpotifyChart> = emptyList(),
    val trending: List<SpotifyTrack> = emptyList(),
    @SerialName("popular_albums") val popularAlbums: List<SpotifyAlbum> = emptyList(),
    @SerialName("popular_artists") val popularArtists: List<SpotifyArtist> = emptyList()
) {
    /** The chart the server actually expanded, if any. */
    val primary: SpotifyChart?
        get() = charts.firstOrNull { it.entries.isNotEmpty() }
}

@Serializable
data class SpotifyBrowseCategory(
    val id: String = "",
    val title: String = "",
    val color: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val href: String? = null
)

@Serializable
data class SpotifyGenrePage(
    val id: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val sections: List<SpotifyGenreSection> = emptyList()
)

@Serializable
data class SpotifyGenreSection(
    val title: String = "",
    val items: List<SpotifyGenreItem> = emptyList()
)

@Serializable
data class SpotifyGenreItem(
    val title: String = "",
    val description: String? = null,
    val followers: Long? = null,
    val href: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val artists: List<String> = emptyList()
)

/**
 * Spotify catalog metadata served by the spotifree-rs instance at
 * <https://blexsy.blek.my.id>. Search returns normalized JSON; track/album/artist/
 * playlist return Spotify's raw pathfinder unions, mapped here to flat models.
 *
 * See <https://spotifree-rs.vercel.app/docs> for the endpoint contract. These are
 * metadata only — the app plays audio through YouTube Music, never Spotify.
 */
object SpotifyMetadata {

    private const val BASE_URL = "https://blexsy.blek.my.id"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "Blexsy/1.0 (Android)")
        }
    }

    /**
     * Runs `searchDesktop` upstream. Omit [limit] to accept the server default, otherwise it is
     * clamped to 1..50 (the API's real per-category ceiling). [offset] pages the result.
     */
    suspend fun search(query: String, limit: Int? = null, offset: Int? = null): SpotifySearchResults =
        fetch<SpotifySearchResults>("/v1/search") {
            parameter("q", query)
            limit?.let { parameter("limit", it.coerceIn(1, 50)) }
            offset?.let { parameter("offset", it.coerceAtLeast(0)) }
        }

    suspend fun getTrack(trackId: String): SpotifyTrackDetail {
        val id = normalizeId(trackId)
        val dto = fetch<RawWrapper<TrackDto>>("/v1/track/$id").trackUnion
        return dto?.toDetail() ?: throw ApiException("Track not found: $id", 404)
    }

    suspend fun getAlbum(albumId: String): SpotifyAlbumDetail {
        val id = normalizeId(albumId)
        val dto = fetch<RawWrapper<AlbumUnionDto>?>("/v1/album/$id")?.albumUnion
        return dto?.toDetail(id) ?: throw ApiException("Album not found: $id", 404)
    }

    suspend fun getArtist(artistId: String): SpotifyArtistDetail {
        val id = normalizeId(artistId)
        val dto = fetch<RawWrapper<ArtistUnionDto>?>("/v1/artist/$id") {
            // The discography shelves are capped by the server; ask for the full 100 so the
            // Singles/Albums filters on the artist page are not silently truncated.
            parameter("limit", ARTIST_DISCOGRAPHY_LIMIT)
        }?.artistUnion
        return dto?.toDetail(id) ?: throw ApiException("Artist not found: $id", 404)
    }

    suspend fun getPlaylist(playlistId: String): SpotifyPlaylistDetail {
        val id = normalizeId(playlistId)
        val dto = fetch<RawWrapper<PlaylistV2Dto>>("/v1/playlist/$id").playlistV2
        return dto?.toDetail(id) ?: throw ApiException("Playlist not found: $id", 404)
    }

    /**
     * Market charts. [country] is `id` or `global` (any case) — the server accepts nothing else and
     * answers 400 for other markets. [top] picks which chart is expanded with its ranked rows
     * (`daily` or `alltime`; note `alltime` expands the weekly chart).
     */
    suspend fun getCharts(country: String = "id", top: String = "daily"): SpotifyCharts =
        fetch<SpotifyCharts>("/v1/charts/${country.trim().lowercase()}") {
            parameter("top", top)
        }

    /**
     * Track credits from Spotify: songwriters, producers, performers, publishers, and record labels.
     */
    suspend fun getCredits(trackId: String): TrackCredits? {
        val id = normalizeId(trackId)
        return try {
            val dto = fetch<RawWrapper<TrackCreditsUnionDto>?>("/v1/track/$id/credits")?.trackUnion
            dto?.toCredits(id)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Autocomplete suggestions and query chips matching [query] as the user types.
     */
    suspend fun searchSuggest(query: String): List<String> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return try {
            val dto = fetch<SpotifySuggestDto>("/v1/search/suggest") {
                parameter("q", q)
            }
            dto.toSuggestions()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Seeded recommended tracks related to this track.
     */
    suspend fun getRecommendations(trackId: String): List<SpotifyTrack> {
        val id = normalizeId(trackId)
        return try {
            val dto = fetch<RecommendationsWrapperDto>("/v1/track/$id/recommendations")
            dto.seoRecommendedTrack?.items?.mapNotNull { it.data?.toTrack() }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Browse / explore categories and genres (title, card color, icon image, ID).
     */
    suspend fun getBrowse(): List<SpotifyBrowseCategory> =
        fetch<List<SpotifyBrowseCategory>>("/v1/browse")

    /**
     * Curated genre page with playlists, new releases, and subcategories.
     */
    suspend fun getGenre(genreId: String): SpotifyGenrePage =
        fetch<SpotifyGenrePage>("/v1/genre/${genreId.trim()}")

    /** Convenience: "artist1, artist2" for UI display. */
    fun artistLine(artists: List<String>): String = artists.joinToString(", ")

    private suspend inline fun <reified T> fetch(path: String, crossinline params: HttpRequestBuilder.() -> Unit = {}): T =
        withContext(Dispatchers.IO) {
            val res = client.get("$BASE_URL$path") { params() }
            if (!res.status.isSuccess()) {
                throw ApiException("Spotify metadata request failed", res.status.value)
            }
            res.body()
        }

    private fun normalizeId(raw: String): String = raw.trim().substringAfterLast(':')
}

/** Runs a Spotify call and captures failures (excluding cancellation) into a [Result]. */
suspend fun <T> spotifyResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

// ---------------------------------------------------------------------------
// Raw DTOs — minimal view of Spotify's pathfinder payloads. Every field is
// nullable/defaulted so a renamed or missing key degrades instead of throwing.
// ---------------------------------------------------------------------------

@Serializable
internal data class RawWrapper<T>(val trackUnion: T? = null, val albumUnion: T? = null, val artistUnion: T? = null, val playlistV2: T? = null)

private fun String.idFromUri(): String = substringAfterLast(':')

private fun List<ImageSourceDto>?.best(): String? =
    this?.maxByOrNull { it.width ?: 0 }?.url ?: this?.firstOrNull()?.url

private fun ImageGroupDto?.best(): String? = this?.sources.best()

@Serializable
internal data class ImageSourceDto(val url: String? = null, val width: Int? = null, val height: Int? = null)

@Serializable
internal data class ImageGroupDto(val sources: List<ImageSourceDto> = emptyList())

@Serializable
internal data class ProfileDto(val name: String? = null, val biography: BiographyDto? = null)

@Serializable
internal data class BiographyDto(val text: String? = null)

@Serializable
internal data class VisualsDto(val avatarImage: ImageGroupDto? = null)

@Serializable
internal data class ItemsDto<T>(val items: List<T> = emptyList(), val totalCount: Int? = null)

@Serializable
internal data class DurationDto(val totalMilliseconds: Long? = null)

@Serializable
internal data class ContentRatingDto(val label: String? = null)

@Serializable
internal data class PlayabilityDto(val playable: Boolean? = null)

@Serializable
internal data class DateDto(val year: Int? = null, val month: Int? = null, val day: Int? = null, val isoString: String? = null)

@Serializable
internal data class ArtistRefDto(
    val id: String? = null,
    val uri: String? = null,
    val profile: ProfileDto? = null,
    val visuals: VisualsDto? = null
)

@Serializable
internal data class AlbumRefDto(
    val id: String? = null,
    val uri: String? = null,
    val name: String? = null,
    val coverArt: ImageGroupDto? = null,
    val date: DateDto? = null
)

@Serializable
internal data class TrackDto(
    val id: String? = null,
    val uri: String? = null,
    val name: String? = null,
    val duration: DurationDto? = null,
    val trackDuration: DurationDto? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val playcount: Long? = null,
    val contentRating: ContentRatingDto? = null,
    val playability: PlayabilityDto? = null,
    val firstArtist: ItemsDto<ArtistRefDto>? = null,
    val otherArtists: ItemsDto<ArtistRefDto>? = null,
    val artists: ItemsDto<ArtistRefDto>? = null,
    val albumOfTrack: AlbumRefDto? = null
)

@Serializable
internal data class AlbumUnionDto(
    val id: String? = null,
    val uri: String? = null,
    val name: String? = null,
    val type: String? = null,
    val label: String? = null,
    val date: DateDto? = null,
    val coverArt: ImageGroupDto? = null,
    val artists: ItemsDto<ArtistRefDto>? = null,
    val playability: PlayabilityDto? = null,
    val tracksV2: ItemsDto<AlbumTrackItemDto>? = null
)

@Serializable
internal data class AlbumTrackItemDto(val track: TrackDto? = null, val uid: String? = null)

@Serializable
internal data class ArtistStatsDto(val monthlyListeners: Long? = null, val followers: Long? = null)

@Serializable
internal data class TopTrackItemDto(val track: TrackDto? = null, val uid: String? = null)

@Serializable
internal data class ReleaseGroupDto(val releases: ItemsDto<AlbumUnionDto>? = null)

@Serializable
internal data class DiscographyDto(
    val topTracks: ItemsDto<TopTrackItemDto>? = null,
    val albums: ItemsDto<ReleaseGroupDto>? = null,
    val singles: ItemsDto<ReleaseGroupDto>? = null
)

@Serializable
internal data class RelatedContentDto(val relatedArtists: ItemsDto<ArtistRefDto>? = null)

@Serializable
internal data class ArtistUnionDto(
    val id: String? = null,
    val uri: String? = null,
    val profile: ProfileDto? = null,
    val visuals: VisualsDto? = null,
    val stats: ArtistStatsDto? = null,
    val discography: DiscographyDto? = null,
    val relatedContent: RelatedContentDto? = null
)

@Serializable
internal data class OwnerDto(val name: String? = null, val username: String? = null)

@Serializable
internal data class OwnerWrapperDto(val data: OwnerDto? = null)

@Serializable
internal data class PlaylistItemDto(val itemV2: TrackItemWrapperDto? = null, val uid: String? = null)

@Serializable
internal data class TrackItemWrapperDto(val data: TrackDto? = null)

@Serializable
internal data class RecommendationsWrapperDto(
    val seoRecommendedTrack: ItemsDto<TrackItemWrapperDto>? = null
)

@Serializable
internal data class PlaylistContentDto(val items: List<PlaylistItemDto> = emptyList(), val totalCount: Int? = null)

@Serializable
internal data class PlaylistV2Dto(
    val uri: String? = null,
    val name: String? = null,
    val description: String? = null,
    val followers: Long? = null,
    val images: ItemsDto<ImageGroupDto>? = null,
    val ownerV2: OwnerWrapperDto? = null,
    val content: PlaylistContentDto? = null
)

// ---------------------------------------------------------------------------
// DTO -> model mappers.
// ---------------------------------------------------------------------------

internal fun ArtistRefDto.toArtist(): SpotifyArtist = SpotifyArtist(
    id = id ?: uri?.idFromUri().orEmpty(),
    uri = uri.orEmpty(),
    name = profile?.name.orEmpty(),
    image = visuals?.avatarImage?.best()
)

private fun ArtistRefDto.toName(): String? = profile?.name?.takeIf { it.isNotBlank() }

internal fun TrackDto.toTrack(albumName: String? = null, cover: String? = null): SpotifyTrack? {
    val displayName = name?.takeIf { it.isNotBlank() } ?: return null
    val artistRefs = artists?.items
        ?: (firstArtist?.items.orEmpty() + otherArtists?.items.orEmpty())
    val artistEntries = artistRefs.orEmpty().mapNotNull { ref ->
        ref.toName()?.let { name -> name to (ref.id ?: ref.uri?.idFromUri()) }
    }
    val artistNames = artistEntries.map { it.first }
    // Same order and length as artistNames, so a name can be traced back to its Spotify id.
    val artistIds = artistEntries.mapNotNull { it.second }
    val album = albumOfTrack?.name ?: albumName
    val image = albumOfTrack?.coverArt?.best() ?: cover
    return SpotifyTrack(
        id = id ?: uri?.idFromUri().orEmpty(),
        uri = uri.orEmpty(),
        name = displayName,
        artists = artistNames,
        artistIds = artistIds,
        album = album,
        durationMs = duration?.totalMilliseconds ?: trackDuration?.totalMilliseconds,
        image = image
    )
}

internal fun AlbumUnionDto.toAlbum(): SpotifyAlbum? {
    val displayName = name?.takeIf { it.isNotBlank() } ?: return null
    return SpotifyAlbum(
        id = id ?: uri?.idFromUri().orEmpty(),
        uri = uri.orEmpty(),
        name = displayName,
        artists = artists?.items?.mapNotNull { it.toName() }.orEmpty(),
        image = coverArt?.best(),
        year = date?.year
    )
}

internal fun TrackDto.toDetail(fallbackId: String? = null): SpotifyTrackDetail? {
    val track = toTrack() ?: return null
    return SpotifyTrackDetail(
        track = track.copy(id = track.id.ifBlank { fallbackId.orEmpty() }),
        explicit = contentRating?.label.orEmpty().startsWith("EXPLICIT"),
        playable = playability?.playable != false
    )
}

internal fun AlbumUnionDto.toDetail(fallbackId: String? = null): SpotifyAlbumDetail? {
    val displayName = name?.takeIf { it.isNotBlank() } ?: return null
    val cover = coverArt?.best()
    return SpotifyAlbumDetail(
        id = id ?: uri?.idFromUri() ?: fallbackId.orEmpty(),
        name = displayName,
        uri = uri.orEmpty(),
        type = type,
        label = label?.takeIf { it.isNotBlank() },
        releaseYear = date?.year,
        artists = artists?.items?.map { it.toArtist() }.orEmpty(),
        image = cover,
        totalTracks = tracksV2?.totalCount ?: tracksV2?.items?.size ?: 0,
        tracks = tracksV2?.items
            ?.mapNotNull { it.track?.toTrack(albumName = displayName, cover = cover) }
            .orEmpty()
    )
}

internal fun ArtistUnionDto.toDetail(fallbackId: String? = null): SpotifyArtistDetail? {
    val displayName = profile?.name?.takeIf { it.isNotBlank() } ?: return null
    return SpotifyArtistDetail(
        id = id ?: uri?.idFromUri() ?: fallbackId.orEmpty(),
        name = displayName,
        uri = uri.orEmpty(),
        image = visuals?.avatarImage?.best(),
        monthlyListeners = stats?.monthlyListeners,
        followers = stats?.followers,
        biography = profile.biography?.text?.takeIf { it.isNotBlank() },
        topTracks = discography?.topTracks?.items?.mapNotNull { it.track?.toTrack() }.orEmpty(),
        albums = discography?.albums?.items
            ?.mapNotNull { it.releases?.items?.firstOrNull()?.toAlbum() }
            .orEmpty(),
        singles = discography?.singles?.items
            ?.mapNotNull { it.releases?.items?.firstOrNull()?.toAlbum() }
            .orEmpty(),
        relatedArtists = relatedContent?.relatedArtists?.items?.map { it.toArtist() }.orEmpty()
    )
}

internal fun PlaylistV2Dto.toDetail(fallbackId: String? = null): SpotifyPlaylistDetail? {
    val displayName = name?.takeIf { it.isNotBlank() } ?: return null
    return SpotifyPlaylistDetail(
        id = uri?.idFromUri() ?: fallbackId.orEmpty(),
        name = displayName,
        uri = uri.orEmpty(),
        description = description?.takeIf { it.isNotBlank() },
        owner = ownerV2?.data?.name ?: ownerV2?.data?.username,
        image = images?.items?.firstOrNull()?.best(),
        followers = followers,
        totalTracks = content?.totalCount ?: content?.items?.size ?: 0,
        tracks = content?.items?.mapNotNull { it.itemV2?.data?.toTrack() }.orEmpty()
    )
}

@Serializable
internal data class TrackCreditsUnionDto(
    val creditsTrait: CreditsTraitDto? = null
)

@Serializable
internal data class CreditsTraitDto(
    val contributors: ItemsDto<CreditContributorDto>? = null,
    val sources: ItemsDto<CreditSourceDto>? = null
)

@Serializable
internal data class CreditContributorDto(
    val name: String? = null,
    val role: String? = null,
    val roleGroup: CreditRoleGroupDto? = null,
    val uri: String? = null,
    val url: String? = null
)

@Serializable
internal data class CreditRoleGroupDto(
    val name: String? = null
)

@Serializable
internal data class CreditSourceDto(
    val name: String? = null
)

@Serializable
internal data class SpotifySuggestDto(
    val searchV2: SearchV2Dto? = null
)

@Serializable
internal data class SearchV2Dto(
    val query: String? = null,
    val topResultsV2: TopResultsV2Dto? = null
)

@Serializable
internal data class TopResultsV2Dto(
    val itemsV2: List<TopResultHitDto> = emptyList()
)

@Serializable
internal data class TopResultHitDto(
    val item: JsonObject? = null
)

internal fun TrackCreditsUnionDto.toCredits(trackId: String): TrackCredits? {
    val trait = creditsTrait ?: return null
    val contributors = trait.contributors?.items.orEmpty()
    if (contributors.isEmpty() && trait.sources?.items.isNullOrEmpty()) return null

    val roles = contributors
        .filter { !it.name.isNullOrBlank() }
        .groupBy {
            it.role?.trim()?.ifBlank { null }
                ?: it.roleGroup?.name?.trim()?.ifBlank { null }
                ?: "Credits"
        }
        .map { (roleTitle, people) ->
            CreditRole(
                title = roleTitle,
                people = people.mapNotNull { p ->
                    val name = p.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    CreditPerson(
                        name = name,
                        url = p.url ?: p.uri?.idFromUri()
                    )
                }.distinctBy { it.name }
            )
        }

    val sources = trait.sources?.items.orEmpty()
        .mapNotNull { it.name?.trim()?.takeIf { s -> s.isNotBlank() } }
        .distinct()

    return TrackCredits(
        trackId = trackId,
        title = "",
        roles = roles,
        sources = sources
    )
}

internal fun SpotifySuggestDto.toSuggestions(): List<String> {
    val items = searchV2?.topResultsV2?.itemsV2.orEmpty()
    val autoCompletes = items.mapNotNull { hit ->
        val obj = hit.item ?: return@mapNotNull null
        if (obj["__typename"]?.jsonPrimitive?.contentOrNull == "SearchAutoCompleteEntity") {
            obj["data"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        } else null
    }
    val others = items.mapNotNull { hit ->
        val obj = hit.item ?: return@mapNotNull null
        if (obj["__typename"]?.jsonPrimitive?.contentOrNull != "SearchAutoCompleteEntity") {
            val dataObj = obj["data"]?.jsonObject
            val name = dataObj?.get("name")?.jsonPrimitive?.contentOrNull
                ?: dataObj?.get("profile")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            name?.trim()?.takeIf { it.isNotBlank() }
        } else null
    }
    return (autoCompletes + others).distinctBy { it.lowercase() }
}
