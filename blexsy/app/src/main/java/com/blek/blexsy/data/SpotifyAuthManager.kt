package com.blek.blexsy.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.blek.blexsy.model.AlbumSummary
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.isSpotifyCatalogId
import com.blek.blexsy.model.normalizeSpotifyImageUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SpotifySession(
    val spDc: String,
    val accessToken: String,
    val clientToken: String? = null,
    val expiresAtMs: Long,
    val userId: String,
    val displayName: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val product: String? = null,
    val lastSyncedAtMs: Long = 0L
)

data class SpotifySyncSummary(
    val likedTracksCount: Int,
    val playlistsCount: Int,
    val savedAlbumsCount: Int = 0,
    val followedArtistsCount: Int = 0,
    val madeForYouCount: Int = 0,
    val recentTracksCount: Int = 0
)

sealed class SpotifySyncState {
    object Idle : SpotifySyncState()
    data class Syncing(val status: String) : SpotifySyncState()
    data class Success(val summary: SpotifySyncSummary) : SpotifySyncState()
    data class Error(val message: String) : SpotifySyncState()
}

internal data class ExtractedWebTokens(
    val accessToken: String,
    val clientToken: String? = null,
    val expiresAtMs: Long = System.currentTimeMillis() + 3600_000L,
    val usernameHint: String? = null,
    val displayNameHint: String? = null,
    val avatarUrlHint: String? = null
)

/**
 * Handles Spotify user authentication via `sp_dc` cookie (or WebView login / Bearer token),
 * two-way library mutations (like/unlike, playlist add/create), and syncing:
 * - User profile
 * - Liked Songs
 * - Private & Saved Playlists
 * - "Made For You" mixes (Daily Mix, Discover Weekly, Release Radar, On Repeat)
 * - Followed Artists & Top Artists
 * - Saved Albums
 */
object SpotifyAuthManager {

    private const val PREFS_NAME = "blexsy_spotify_auth"
    private const val KEY_SESSION_JSON = "spotify_session_json"
    private const val KEY_MADE_FOR_YOU_JSON = "spotify_made_for_you_json"
    private const val KEY_FOLLOWED_ARTISTS_JSON = "spotify_followed_artists_json"
    private const val KEY_SAVED_ALBUMS_JSON = "spotify_saved_albums_json"
    private const val KEY_HISTORY_PLAYLIST_ID = "spotify_history_playlist_id"
    private const val BLEXSY_HISTORY_PLAYLIST_NAME = ".blexsy-history"

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val HASH_PROFILE_ATTRIBUTES =
        "53bcb064f6cd18c23f752bc324a791194d20df612d8e1239c735144ab0399ced"
    private const val HASH_FETCH_LIBRARY_TRACKS =
        "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    private const val HASH_ADD_TO_LIBRARY =
        "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915"
    private const val HASH_REMOVE_FROM_LIBRARY =
        "a3c1ff58e6a36fec5fe1e3a193dc95d9071d96b9ba53c5ba9c1494fb1ee73915"
    private const val HASH_ADD_ITEMS_TO_PLAYLIST =
        "47b2a1234b17c48d332dd0ffad2f34f2e80c806a3d5f1d32c920602a8981b448"
    private const val HASH_REMOVE_ITEMS_FROM_PLAYLIST =
        "7233b3b9f5b9c0e60871660b88f13a6e088196e595e3a32425600a7099310668"

    private val SECRET_DICT_URLS = listOf(
        "https://git.thetadev.de/ThetaDev/spotify-secrets/raw/branch/main/secrets/secretDict.json",
        "https://raw.githubusercontent.com/Thereallo1026/spotify-secrets/refs/heads/main/secrets/secretDict.json"
    )

    private val FALLBACK_SECRETS: Map<Int, List<Int>> = mapOf(
        61 to listOf(44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78),
        60 to listOf(52, 56, 45, 76, 91, 69, 114, 73, 33, 96, 32, 99, 45, 97, 33, 62, 62, 112),
        59 to listOf(123, 105, 79, 70, 110, 59, 52, 125, 60, 49, 80, 70, 89, 75, 80, 86, 63, 53, 123, 37, 117, 49, 52, 93, 77, 62, 47, 86, 48, 104, 68)
    )

    private val MADE_FOR_YOU_KEYWORDS = listOf(
        "daily mix",
        "discover weekly",
        "release radar",
        "on repeat",
        "repeat rewind",
        "daylist",
        "time capsule"
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val playlistsSerializer = ListSerializer(Playlist.serializer())
    private val artistsSerializer = ListSerializer(ArtistSummary.serializer())
    private val albumsSerializer = ListSerializer(AlbumSummary.serializer())

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 25_000
            connectTimeoutMillis = 15_000
        }
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var isSyncingNow = false
    @Volatile
    private var cachedDynamicSecrets: Map<Int, List<Int>>? = null

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private val _session = MutableStateFlow<SpotifySession?>(null)
    val session: StateFlow<SpotifySession?> = _session.asStateFlow()

    private val _syncState = MutableStateFlow<SpotifySyncState>(SpotifySyncState.Idle)
    val syncState: StateFlow<SpotifySyncState> = _syncState.asStateFlow()

    private val _madeForYouPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val madeForYouPlaylists: StateFlow<List<Playlist>> = _madeForYouPlaylists.asStateFlow()

    private val _followedArtists = MutableStateFlow<List<ArtistSummary>>(emptyList())
    val followedArtists: StateFlow<List<ArtistSummary>> = _followedArtists.asStateFlow()

    private val _savedAlbums = MutableStateFlow<List<AlbumSummary>>(emptyList())
    val savedAlbums: StateFlow<List<AlbumSummary>> = _savedAlbums.asStateFlow()

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (prefs == null) {
            prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        val p = prefs ?: return
        p.getString(KEY_SESSION_JSON, null)?.takeIf { it.isNotBlank() }?.let { raw ->
            _session.value = runCatching { json.decodeFromString(SpotifySession.serializer(), raw) }.getOrNull()
        }
        p.getString(KEY_MADE_FOR_YOU_JSON, null)?.takeIf { it.isNotBlank() }?.let { raw ->
            _madeForYouPlaylists.value = runCatching { json.decodeFromString(playlistsSerializer, raw) }.getOrDefault(emptyList())
        }
        p.getString(KEY_FOLLOWED_ARTISTS_JSON, null)?.takeIf { it.isNotBlank() }?.let { raw ->
            _followedArtists.value = runCatching { json.decodeFromString(artistsSerializer, raw) }.getOrDefault(emptyList())
        }
        p.getString(KEY_SAVED_ALBUMS_JSON, null)?.takeIf { it.isNotBlank() }?.let { raw ->
            _savedAlbums.value = runCatching { json.decodeFromString(albumsSerializer, raw) }.getOrDefault(emptyList())
        }
    }

    private fun saveSession(newSession: SpotifySession?) {
        _session.value = newSession
        val editor = prefs?.edit() ?: return
        if (newSession == null) {
            editor.remove(KEY_SESSION_JSON).apply()
        } else {
            editor.putString(
                KEY_SESSION_JSON,
                json.encodeToString(SpotifySession.serializer(), newSession)
            ).apply()
        }
    }

    private fun savePersonalizedCollections(
        madeForYou: List<Playlist>,
        artists: List<ArtistSummary>,
        albums: List<AlbumSummary>
    ) {
        _madeForYouPlaylists.value = madeForYou
        _followedArtists.value = artists
        _savedAlbums.value = albums
        prefs?.edit()?.apply {
            putString(KEY_MADE_FOR_YOU_JSON, json.encodeToString(playlistsSerializer, madeForYou))
            putString(KEY_FOLLOWED_ARTISTS_JSON, json.encodeToString(artistsSerializer, artists))
            putString(KEY_SAVED_ALBUMS_JSON, json.encodeToString(albumsSerializer, albums))
            apply()
        }
    }

    // -----------------------------------------------------------------------
    // Feature #1: Two-Way Sync Mutations (Like/Unlike, Follow Artist, Save Album, & Playlists)
    // -----------------------------------------------------------------------

    /**
     * Live-checks whether [artist] is followed on the user's Spotify account when opening
     * an artist page, and reconciles [_followedArtists] if out of sync.
     */
    suspend fun verifyArtistFollowedLive(artist: ArtistSummary) = withContext(Dispatchers.IO) {
        if (_session.value == null || !artist.id.isSpotifyCatalogId()) return@withContext
        val (accessToken, clientToken) = getValidTokens(appContext) ?: return@withContext

        // 1. Try Web API `/v1/me/following/contains?type=artist&ids={id}`
        val webApiContains: Boolean? = runCatching {
            val res = client.get("https://api.spotify.com/v1/me/following/contains") {
                parameter("type", "artist")
                parameter("ids", artist.id)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching null
            val arr = json.parseToJsonElement(res.bodyAsText()).jsonArray
            arr.firstOrNull()?.jsonPrimitive?.booleanOrNull
        }.getOrNull()

        // 2. Fallback: query Pathfinder `libraryV3` filtered to Artists with textFilter = artist.name
        val isFollowedOnSpotify = webApiContains ?: runCatching {
            val found = fetchLibraryV3ArtistsFiltered(
                accessToken = accessToken,
                clientToken = clientToken,
                textFilter = artist.name,
                limit = 20
            )
            if (found == null) null
            else found.any { it.id == artist.id || it.name.equals(artist.name, ignoreCase = true) }
        }.getOrNull() ?: return@withContext

        val current = _followedArtists.value
        val currentlyInList = current.any { it.id == artist.id }
        if (isFollowedOnSpotify && !currentlyInList) {
            val updated = listOf(artist) + current
            _followedArtists.value = updated
            prefs?.edit()?.putString(KEY_FOLLOWED_ARTISTS_JSON, json.encodeToString(artistsSerializer, updated))?.apply()
        } else if (!isFollowedOnSpotify && currentlyInList) {
            val updated = current.filterNot { it.id == artist.id }
            _followedArtists.value = updated
            prefs?.edit()?.putString(KEY_FOLLOWED_ARTISTS_JSON, json.encodeToString(artistsSerializer, updated))?.apply()
        }
    }

    /**
     * Toggles following an artist locally and pushes Follow / Unfollow to Spotify (`/v1/me/following?type=artist`).
     */
    fun toggleFollowArtist(artist: ArtistSummary) {
        val current = _followedArtists.value
        val shouldFollow = current.none { it.id == artist.id }
        val updated = if (shouldFollow) {
            listOf(artist) + current.filterNot { it.id == artist.id }
        } else {
            current.filterNot { it.id == artist.id }
        }
        _followedArtists.value = updated
        prefs?.edit()?.putString(KEY_FOLLOWED_ARTISTS_JSON, json.encodeToString(artistsSerializer, updated))?.apply()

        if (_session.value == null || !artist.id.isSpotifyCatalogId()) return
        syncScope.launch {
            val (accessToken, clientToken) = getValidTokens(appContext) ?: return@launch
            val webApiSuccess = runCatching {
                val res = if (shouldFollow) {
                    client.put("https://api.spotify.com/v1/me/following") {
                        parameter("type", "artist")
                        parameter("ids", artist.id)
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("ids", buildJsonArray { add(JsonPrimitive(artist.id)) }) }.toString())
                    }
                } else {
                    client.delete("https://api.spotify.com/v1/me/following") {
                        parameter("type", "artist")
                        parameter("ids", artist.id)
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                    }
                }
                res.status.isSuccess()
            }.getOrDefault(false)

            if (!webApiSuccess) {
                runCatching {
                    val opName = if (shouldFollow) "addToLibrary" else "removeFromLibrary"
                    val hash = if (shouldFollow) HASH_ADD_TO_LIBRARY else HASH_REMOVE_FROM_LIBRARY
                    val body = buildJsonObject {
                        put("variables", buildJsonObject {
                            put("uris", buildJsonArray { add(JsonPrimitive("spotify:artist:${artist.id}")) })
                        })
                        put("operationName", opName)
                        put("extensions", buildJsonObject {
                            put("persistedQuery", buildJsonObject {
                                put("version", 1)
                                put("sha256Hash", hash)
                            })
                        })
                    }
                    client.post("https://api-partner.spotify.com/pathfinder/v1/query") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
            }
        }
    }

    /**
     * Toggles saving an album locally and pushes Save / Unsave to Spotify (`/v1/me/albums`).
     */
    fun toggleSaveAlbum(album: AlbumSummary) {
        val current = _savedAlbums.value
        val shouldSave = current.none { it.id == album.id }
        val updated = if (shouldSave) {
            listOf(album) + current.filterNot { it.id == album.id }
        } else {
            current.filterNot { it.id == album.id }
        }
        _savedAlbums.value = updated
        prefs?.edit()?.putString(KEY_SAVED_ALBUMS_JSON, json.encodeToString(albumsSerializer, updated))?.apply()

        if (_session.value == null || !album.id.isSpotifyCatalogId()) return
        syncScope.launch {
            val (accessToken, clientToken) = getValidTokens(appContext) ?: return@launch
            val webApiSuccess = runCatching {
                val res = if (shouldSave) {
                    client.put("https://api.spotify.com/v1/me/albums") {
                        parameter("ids", album.id)
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("ids", buildJsonArray { add(JsonPrimitive(album.id)) }) }.toString())
                    }
                } else {
                    client.delete("https://api.spotify.com/v1/me/albums") {
                        parameter("ids", album.id)
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                    }
                }
                res.status.isSuccess()
            }.getOrDefault(false)

            if (!webApiSuccess) {
                runCatching {
                    val opName = if (shouldSave) "addToLibrary" else "removeFromLibrary"
                    val hash = if (shouldSave) HASH_ADD_TO_LIBRARY else HASH_REMOVE_FROM_LIBRARY
                    val body = buildJsonObject {
                        put("variables", buildJsonObject {
                            put("uris", buildJsonArray { add(JsonPrimitive("spotify:album:${album.id}")) })
                        })
                        put("operationName", opName)
                        put("extensions", buildJsonObject {
                            put("persistedQuery", buildJsonObject {
                                put("version", 1)
                                put("sha256Hash", hash)
                            })
                        })
                    }
                    client.post("https://api-partner.spotify.com/pathfinder/v1/query") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
            }
        }
    }

    /**
     * Pushes a track Like / Unlike action to the user's linked Spotify account in the background.
     */
    fun pushTrackLikeStateAsync(trackId: String, liked: Boolean) {
        if (_session.value == null || !trackId.isSpotifyCatalogId()) return
        syncScope.launch {
            val (accessToken, clientToken) = getValidTokens(appContext) ?: return@launch
            val webApiSuccess = runCatching {
                val res = if (liked) {
                    client.put("https://api.spotify.com/v1/me/tracks") {
                        parameter("ids", trackId)
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("ids", buildJsonArray { add(JsonPrimitive(trackId)) }) }.toString())
                    }
                } else {
                    client.delete("https://api.spotify.com/v1/me/tracks") {
                        parameter("ids", trackId)
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                    }
                }
                res.status.isSuccess()
            }.getOrDefault(false)

            if (!webApiSuccess) {
                // Fallback: Pathfinder `addToLibrary` / `removeFromLibrary`
                runCatching {
                    val opName = if (liked) "addToLibrary" else "removeFromLibrary"
                    val hash = if (liked) HASH_ADD_TO_LIBRARY else HASH_REMOVE_FROM_LIBRARY
                    val body = buildJsonObject {
                        put("variables", buildJsonObject {
                            put("uris", buildJsonArray { add(JsonPrimitive("spotify:track:$trackId")) })
                        })
                        put("operationName", opName)
                        put("extensions", buildJsonObject {
                            put("persistedQuery", buildJsonObject {
                                put("version", 1)
                                put("sha256Hash", hash)
                            })
                        })
                    }
                    client.post("https://api-partner.spotify.com/pathfinder/v1/query") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
            }
        }
    }

    /**
     * Pushes a track addition to a linked Spotify playlist (`sp-{spotifyId}`) in the background.
     */
    fun pushAddTrackToPlaylistAsync(playlistId: String, trackId: String) {
        if (_session.value == null || !trackId.isSpotifyCatalogId()) return
        val rawSpotifyPlaylistId = playlistId.removePrefix("sp-")
        if (!rawSpotifyPlaylistId.isSpotifyCatalogId()) return

        syncScope.launch {
            val (accessToken, clientToken) = getValidTokens(appContext) ?: return@launch
            val webApiSuccess = runCatching {
                val payload = buildJsonObject {
                    put("uris", buildJsonArray { add(JsonPrimitive("spotify:track:$trackId")) })
                }
                val res = client.post("https://api.spotify.com/v1/playlists/$rawSpotifyPlaylistId/tracks") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }
                res.status.isSuccess()
            }.getOrDefault(false)

            if (!webApiSuccess) {
                runCatching {
                    val body = buildJsonObject {
                        put("variables", buildJsonObject {
                            put("playlistUri", "spotify:playlist:$rawSpotifyPlaylistId")
                            put("playlistItemUris", buildJsonArray { add(JsonPrimitive("spotify:track:$trackId")) })
                            put("newPosition", buildJsonObject {
                                put("moveType", "BOTTOM_OF_PLAYLIST")
                                put("fromUid", null as String?)
                            })
                        })
                        put("operationName", "addItemsToPlaylist")
                        put("extensions", buildJsonObject {
                            put("persistedQuery", buildJsonObject {
                                put("version", 1)
                                put("sha256Hash", HASH_ADD_ITEMS_TO_PLAYLIST)
                            })
                        })
                    }
                    client.post("https://api-partner.spotify.com/pathfinder/v1/query") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
            }
        }
    }

    /**
     * Pushes a track removal from a linked Spotify playlist (`sp-{spotifyId}`) in the background.
     */
    fun pushRemoveTrackFromPlaylistAsync(playlistId: String, trackId: String) {
        if (_session.value == null || !trackId.isSpotifyCatalogId()) return
        val rawSpotifyPlaylistId = playlistId.removePrefix("sp-")
        if (!rawSpotifyPlaylistId.isSpotifyCatalogId()) return

        syncScope.launch {
            val (accessToken, clientToken) = getValidTokens(appContext) ?: return@launch
            val webApiSuccess = runCatching {
                val payload = buildJsonObject {
                    put(
                        "tracks",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("uri", JsonPrimitive("spotify:track:$trackId"))
                                }
                            )
                        }
                    )
                }
                val res = client.delete("https://api.spotify.com/v1/playlists/$rawSpotifyPlaylistId/tracks") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }
                res.status.isSuccess()
            }.getOrDefault(false)

            if (!webApiSuccess) {
                runCatching {
                    val body = buildJsonObject {
                        put("variables", buildJsonObject {
                            put("playlistUri", "spotify:playlist:$rawSpotifyPlaylistId")
                            put("uids", buildJsonArray {})
                            put("uris", buildJsonArray { add(JsonPrimitive("spotify:track:$trackId")) })
                        })
                        put("operationName", "removeItemsFromPlaylist")
                        put("extensions", buildJsonObject {
                            put("persistedQuery", buildJsonObject {
                                put("version", 1)
                                put("sha256Hash", HASH_REMOVE_ITEMS_FROM_PLAYLIST)
                            })
                        })
                    }
                    client.post("https://api-partner.spotify.com/pathfinder/v1/query") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
            }
        }
    }

    /**
     * Creates a new playlist on the user's Spotify account when logged in, adds any initial
     * Spotify tracks, and reports back the `"sp-{spotifyId}"` identifier.
     */
    fun pushCreatePlaylistAsync(
        name: String,
        tracks: List<Track>,
        onCreated: (spotifyPrefixedId: String) -> Unit
    ) {
        val currentSession = _session.value ?: return
        syncScope.launch {
            val (accessToken, clientToken) = getValidTokens(appContext) ?: return@launch
            runCatching {
                val payload = buildJsonObject {
                    put("name", name)
                    put("public", false)
                    put("description", "Created with Blexsy")
                }
                val res = client.post("https://api.spotify.com/v1/users/${currentSession.userId}/playlists") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }
                if (!res.status.isSuccess()) return@runCatching
                val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
                val newId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching

                val spotifyUris = tracks.filter { it.id.isSpotifyCatalogId() }.map { "spotify:track:${it.id}" }
                if (spotifyUris.isNotEmpty()) {
                    val tracksPayload = buildJsonObject {
                        put("uris", buildJsonArray { spotifyUris.forEach { add(JsonPrimitive(it)) } })
                    }
                    client.post("https://api.spotify.com/v1/playlists/$newId/tracks") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(tracksPayload.toString())
                    }
                }
                onCreated("sp-$newId")
            }
        }
    }

    /**
     * Unfollows / deletes a playlist on the user's Spotify account when deleted in Blexsy.
     */
    fun pushUnfollowPlaylistAsync(playlistId: String) {
        if (_session.value == null || !playlistId.startsWith("sp-")) return
        val rawSpotifyPlaylistId = playlistId.removePrefix("sp-")
        if (!rawSpotifyPlaylistId.isSpotifyCatalogId()) return

        syncScope.launch {
            val (accessToken, clientToken) = getValidTokens(appContext) ?: return@launch
            val webApiSuccess = runCatching {
                val res = client.delete("https://api.spotify.com/v1/playlists/$rawSpotifyPlaylistId/followers") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                }
                res.status.isSuccess()
            }.getOrDefault(false)

            if (!webApiSuccess) {
                runCatching {
                    val body = buildJsonObject {
                        put("variables", buildJsonObject {
                            put("uris", buildJsonArray { add(JsonPrimitive("spotify:playlist:$rawSpotifyPlaylistId")) })
                        })
                        put("operationName", "removeFromLibrary")
                        put("extensions", buildJsonObject {
                            put("persistedQuery", buildJsonObject {
                                put("version", 1)
                                put("sha256Hash", HASH_REMOVE_FROM_LIBRARY)
                            })
                        })
                    }
                    client.post("https://api-partner.spotify.com/pathfinder/v1/query") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                        header("app-platform", "WebPlayer")
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
            }
        }
    }

    /**
     * Client-side cross-device "Terakhir Didengar" sync:
     * Pushes the latest 50 recently played tracks to the hidden private `.blexsy-history`
     * playlist on the user's Spotify account so Phone A and Phone B stay in sync without a custom backend.
     */
    fun pushRecentTracksAsync(recentTracks: List<Track>) {
        val currentSession = _session.value ?: return
        val spotifyUris = recentTracks
            .filter { it.id.isSpotifyCatalogId() }
            .distinctBy { it.id }
            .take(50)
            .map { "spotify:track:${it.id}" }
        if (spotifyUris.isEmpty()) return

        syncScope.launch {
            val (accessToken, clientToken) = getValidTokens(appContext) ?: return@launch
            val historyPlaylistId = ensureHistoryPlaylistId(
                accessToken = accessToken,
                clientToken = clientToken,
                userId = currentSession.userId
            ) ?: return@launch
            replaceHistoryPlaylistTracks(
                accessToken = accessToken,
                clientToken = clientToken,
                historyPlaylistId = historyPlaylistId,
                trackUris = spotifyUris
            )
        }
    }

    /**
     * Lightweight background pull of `.blexsy-history` on app launch so Phone B immediately
     * receives tracks recently played on Phone A.
     */
    fun pullCloudRecentTracksAsync(
        library: LibraryRepository,
        context: Context? = null
    ) {
        val currentSession = _session.value ?: return
        if (isSyncingNow) return
        syncScope.launch {
            if (context != null) appContext = context.applicationContext
            val (accessToken, clientToken) = getValidTokens(context ?: appContext) ?: return@launch
            val historyPlaylistId = ensureHistoryPlaylistId(
                accessToken = accessToken,
                clientToken = clientToken,
                userId = currentSession.userId
            ) ?: return@launch
            val cloudTracks = fetchHistoryPlaylistTracks(
                accessToken = accessToken,
                clientToken = clientToken,
                historyPlaylistId = historyPlaylistId
            )
            if (cloudTracks.isNotEmpty()) {
                val merged = library.mergeCloudRecentTracks(cloudTracks)
                val mergedUris = merged
                    .filter { it.id.isSpotifyCatalogId() }
                    .take(50)
                    .map { "spotify:track:${it.id}" }
                val cloudUris = cloudTracks
                    .filter { it.id.isSpotifyCatalogId() }
                    .take(50)
                    .map { "spotify:track:${it.id}" }
                if (mergedUris != cloudUris && mergedUris.isNotEmpty()) {
                    replaceHistoryPlaylistTracks(accessToken, clientToken, historyPlaylistId, mergedUris)
                }
            } else {
                val localUris = library.recentTracks.value
                    .filter { it.id.isSpotifyCatalogId() }
                    .distinctBy { it.id }
                    .take(50)
                    .map { "spotify:track:${it.id}" }
                if (localUris.isNotEmpty()) {
                    replaceHistoryPlaylistTracks(accessToken, clientToken, historyPlaylistId, localUris)
                }
            }

            // One-time self-heal for cached playlists where track[0].image was previously overwritten by the playlist cover
            healCachedPlaylistFirstTrackArtwork(library)
        }
    }

    private suspend fun healCachedPlaylistFirstTrackArtwork(library: LibraryRepository) {
        suspend fun healList(list: List<Playlist>): Pair<List<Playlist>, Boolean> {
            var changed = false
            val healed = list.map { pl ->
                val normalizedImage = pl.image.normalizeSpotifyImageUrl()
                val firstTrack = pl.tracks.firstOrNull()
                val rawSpId = pl.id.removePrefix("sp-")

                if (pl.image != null && normalizedImage != pl.image) {
                    changed = true
                }

                val resolvedImage = normalizedImage
                    ?: pl.tracks.firstNotNullOfOrNull { it.image.normalizeSpotifyImageUrl() }
                    ?: if (pl.id.startsWith("sp-") && rawSpId.isSpotifyCatalogId()) {
                        runCatching { SpotifyMetadata.getPlaylist(rawSpId).image.normalizeSpotifyImageUrl() }.getOrNull()
                    } else null

                if (resolvedImage != pl.image) {
                    changed = true
                }

                val needsTrackHeal = pl.image == null && pl.id.startsWith("sp-") && firstTrack != null && firstTrack.id.isSpotifyCatalogId()
                val updatedTracks = if (needsTrackHeal && firstTrack != null) {
                    val realTrackImage = runCatching { SpotifyMetadata.getTrack(firstTrack.id).track.image.normalizeSpotifyImageUrl() }.getOrNull()
                    if (!realTrackImage.isNullOrBlank() && realTrackImage != firstTrack.image) {
                        changed = true
                        listOf(firstTrack.copy(image = realTrackImage)) + pl.tracks.drop(1)
                    } else pl.tracks
                } else pl.tracks

                pl.copy(
                    image = resolvedImage ?: updatedTracks.firstNotNullOfOrNull { it.image.normalizeSpotifyImageUrl() },
                    tracks = updatedTracks
                )
            }
            return healed to changed
        }

        val (healedMfy, mfyChanged) = healList(_madeForYouPlaylists.value)
        if (mfyChanged) {
            savePersonalizedCollections(
                madeForYou = healedMfy,
                artists = _followedArtists.value,
                albums = _savedAlbums.value
            )
        }

        val (healedUserPl, userPlChanged) = healList(library.playlists.value)
        if (userPlChanged) {
            library.replacePlaylists(healedUserPl)
        }
    }

    // -----------------------------------------------------------------------
    // Login & Full Library Sync
    // -----------------------------------------------------------------------

    fun extractSpDcOrToken(rawInput: String): Pair<String?, String?> {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return null to null

        val regex = Regex("""(?:^|;\s*)sp_dc=([^;\s]+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(trimmed)
        if (match != null) {
            return match.groupValues[1].trim().trim('"') to null
        }

        if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            return null to trimmed.removePrefix("Bearer ").removePrefix("bearer ").trim()
        }
        if (trimmed.startsWith("BQ") && trimmed.length > 80 && !trimmed.startsWith("AQ")) {
            return null to trimmed
        }

        return trimmed.removePrefix("sp_dc=").trim().trim('"') to null
    }

    suspend fun loginWithCookieOrToken(
        rawInput: String,
        directAccessToken: String? = null,
        context: Context? = null
    ): Result<SpotifySession> = withContext(Dispatchers.IO) {
        try {
            if (context != null) appContext = context.applicationContext
            val (parsedSpDc, parsedToken) = extractSpDcOrToken(rawInput)
            val spDc = parsedSpDc.orEmpty()
            var extracted: ExtractedWebTokens? = null

            if (!directAccessToken.isNullOrBlank() || !parsedToken.isNullOrBlank()) {
                val tok = (directAccessToken?.takeIf { it.isNotBlank() } ?: parsedToken)!!
                extracted = ExtractedWebTokens(accessToken = tok)
            }

            if (extracted == null) {
                if (spDc.isBlank()) {
                    return@withContext Result.failure(IllegalArgumentException("Cookie sp_dc kosong atau tidak valid."))
                }
                extracted = exchangeSpDcForAccessToken(spDc, context)
            }

            var profile = extracted?.let {
                fetchUserProfile(it.accessToken, it.clientToken, spDc, it)
            }

            if (profile == null && spDc.isNotBlank() && context != null) {
                val webExtracted = runCatching { extractTokenViaHeadlessWebView(context, spDc) }.getOrNull()
                if (webExtracted != null) {
                    extracted = webExtracted
                    profile = fetchUserProfile(
                        webExtracted.accessToken,
                        webExtracted.clientToken,
                        spDc,
                        webExtracted
                    )
                }
            }

            if (extracted == null) {
                return@withContext Result.failure(
                    IllegalStateException("Gagal menukar cookie sp_dc ke access token. Pastikan cookie sp_dc lengkap (biasanya diawali AQ...) atau gunakan tab Web Login.")
                )
            }

            if (profile == null) {
                return@withContext Result.failure(
                    IllegalStateException("Cookie sp_dc tidak valid atau sudah kedaluwarsa. Pastikan menyalin seluruh nilai sp_dc dari open.spotify.com (diawali AQ...) atau login lewat tab Web Login.")
                )
            }

            val newSession = SpotifySession(
                spDc = spDc,
                accessToken = extracted.accessToken,
                clientToken = extracted.clientToken,
                expiresAtMs = extracted.expiresAtMs,
                userId = profile.id,
                displayName = profile.displayName.ifBlank { profile.id },
                email = profile.email,
                avatarUrl = profile.avatarUrl,
                product = profile.product,
                lastSyncedAtMs = _session.value?.lastSyncedAtMs ?: 0L
            )
            saveSession(newSession)
            Result.success(newSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getValidTokens(context: Context? = null): Pair<String, String?>? {
        val current = _session.value ?: return null
        val now = System.currentTimeMillis()
        if (current.accessToken.isNotBlank() && now < current.expiresAtMs - 60_000L) {
            return current.accessToken to current.clientToken
        }
        if (current.spDc.isBlank()) {
            return current.accessToken.takeIf { it.isNotBlank() }?.let { it to current.clientToken }
        }
        val refreshed = exchangeSpDcForAccessToken(current.spDc, context ?: appContext)
            ?: return current.accessToken.takeIf { it.isNotBlank() }?.let { it to current.clientToken }
        val updated = current.copy(
            accessToken = refreshed.accessToken,
            clientToken = refreshed.clientToken ?: current.clientToken,
            expiresAtMs = refreshed.expiresAtMs
        )
        saveSession(updated)
        return updated.accessToken to updated.clientToken
    }

    suspend fun syncLibrary(
        library: LibraryRepository,
        context: Context? = null
    ): Result<SpotifySyncSummary> = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        if (isSyncingNow) {
            return@withContext Result.failure(IllegalStateException("Sinkronisasi sedang berjalan."))
        }
        isSyncingNow = true
        try {
            if (context != null) appContext = context.applicationContext
            _syncState.value = SpotifySyncState.Syncing("Menyiapkan sesi Spotify...")
            val tokenPair = getValidTokens(context ?: appContext)
                ?: return@withContext Result.failure<SpotifySyncSummary>(
                    IllegalStateException("Belum login ke Spotify.")
                ).also {
                    _syncState.value = SpotifySyncState.Error("Belum login ke Spotify.")
                }

            val accessToken = tokenPair.first
            val clientToken = tokenPair.second
            val userId = _session.value?.userId

            // 1. Fetch unified libraryV3 & user-profile-view in parallel (fast)
            _syncState.value = SpotifySyncState.Syncing("Mengambil Library & Playlist Spotify...")
            val (libraryV3Result, profileViewResult) = coroutineScope {
                val libJob = async {
                    withTimeoutOrNull(7_000L) { fetchLibraryV3Items(accessToken, clientToken) }
                        ?: LibraryV3Parsed()
                }
                val profJob = async {
                    if (!userId.isNullOrBlank()) {
                        withTimeoutOrNull(6_000L) { fetchUserProfileView(accessToken, clientToken, userId) }
                            ?: (emptyList<PlaylistMetaSummary>() to emptyList<ArtistSummary>())
                    } else {
                        emptyList<PlaylistMetaSummary>() to emptyList<ArtistSummary>()
                    }
                }
                libJob.await() to profJob.await()
            }

            val knownPlaylistMetas = libraryV3Result.playlists + profileViewResult.first

            // 2. Fetch Liked Songs, User Playlists, Followed Artists, Saved Albums, and History in parallel!
            _syncState.value = SpotifySyncState.Syncing("Menyinkronkan Liked Songs, Playlist & Library...")
            var likedTracks: List<Track> = emptyList()
            var userPlaylists: List<Playlist> = emptyList()
            var madeForYouFromLibrary: List<Playlist> = emptyList()
            var explicitFollowedRaw: List<ArtistSummary> = emptyList()
            var savedAlbumsRaw: List<AlbumSummary> = emptyList()
            var historyPlaylistId: String? = null
            var cloudHistoryTracks: List<Track> = emptyList()
            var nativeRecentTracks: List<Track> = emptyList()

            coroutineScope {
                val likedJob = async {
                    withTimeoutOrNull(10_000L) {
                        fetchLikedTracks(accessToken, clientToken, maxTracks = 200)
                    }.orEmpty()
                }
                val playlistsJob = async {
                    withTimeoutOrNull(14_000L) {
                        fetchUserAndMadeForYouPlaylists(
                            accessToken = accessToken,
                            clientToken = clientToken,
                            userId = userId,
                            extraMetas = knownPlaylistMetas,
                            maxPlaylists = 24
                        )
                    } ?: (emptyList<Playlist>() to emptyList<Playlist>())
                }
                val artistsJob = async {
                    withTimeoutOrNull(9_000L) {
                        fetchFollowedAndTopArtists(
                            accessToken = accessToken,
                            clientToken = clientToken,
                            userId = userId,
                            includeTopFallback = false
                        )
                    }.orEmpty()
                }
                val albumsJob = async {
                    withTimeoutOrNull(7_000L) {
                        fetchSavedAlbums(accessToken, clientToken)
                    }.orEmpty()
                }
                val historyJob = async {
                    val histId = withTimeoutOrNull(5_000L) {
                        ensureHistoryPlaylistId(
                            accessToken = accessToken,
                            clientToken = clientToken,
                            userId = userId,
                            knownMetas = knownPlaylistMetas
                        )
                    }
                    val cloudHist = if (histId != null) {
                        withTimeoutOrNull(5_000L) {
                            fetchHistoryPlaylistTracks(accessToken, clientToken, histId)
                        }.orEmpty()
                    } else emptyList()
                    val nativeRec = withTimeoutOrNull(5_000L) {
                        fetchRecentlyPlayed(accessToken, clientToken, limit = 30)
                    }.orEmpty()
                    Triple(histId, cloudHist, nativeRec)
                }

                likedTracks = likedJob.await()
                val plPair = playlistsJob.await()
                userPlaylists = plPair.first
                madeForYouFromLibrary = plPair.second
                explicitFollowedRaw = artistsJob.await()
                savedAlbumsRaw = albumsJob.await()
                val histTriple = historyJob.await()
                historyPlaylistId = histTriple.first
                cloudHistoryTracks = histTriple.second
                nativeRecentTracks = histTriple.third
            }

            _syncState.value = SpotifySyncState.Syncing("Menyiapkan Made For You...")
            val discoveredMadeForYou = withTimeoutOrNull(10_000L) {
                fetchPersonalizedMadeForYouMixes(
                    accessToken = accessToken,
                    clientToken = clientToken,
                    alreadyFound = madeForYouFromLibrary
                )
            } ?: madeForYouFromLibrary

            val explicitFollowedArtists = (libraryV3Result.artists + explicitFollowedRaw).distinctBy { it.id }
            val followedArtistsList = explicitFollowedArtists.ifEmpty {
                profileViewResult.second.distinctBy { it.id }
            }

            val savedAlbumsList = (libraryV3Result.albums + savedAlbumsRaw).distinctBy { it.id }
            val recentTracks = (cloudHistoryTracks + nativeRecentTracks).distinctBy { it.id }

            library.mergeSpotifySync(
                spotifyLikes = likedTracks,
                spotifyPlaylists = userPlaylists,
                spotifyRecents = recentTracks
            )

            val histIdToPush = historyPlaylistId
            if (histIdToPush != null) {
                val mergedUris = library.recentTracks.value
                    .filter { it.id.isSpotifyCatalogId() }
                    .distinctBy { it.id }
                    .take(50)
                    .map { "spotify:track:${it.id}" }
                if (mergedUris.isNotEmpty()) {
                    syncScope.launch {
                        replaceHistoryPlaylistTracks(accessToken, clientToken, histIdToPush, mergedUris)
                    }
                }
            }

            savePersonalizedCollections(
                madeForYou = discoveredMadeForYou,
                artists = followedArtistsList,
                albums = savedAlbumsList
            )

            val summary = SpotifySyncSummary(
                likedTracksCount = likedTracks.size,
                playlistsCount = userPlaylists.size,
                savedAlbumsCount = savedAlbumsList.size,
                followedArtistsCount = followedArtistsList.size,
                madeForYouCount = discoveredMadeForYou.size,
                recentTracksCount = recentTracks.size
            )

            _session.value?.let { current ->
                saveSession(current.copy(lastSyncedAtMs = System.currentTimeMillis()))
            }

            _syncState.value = SpotifySyncState.Success(summary)
            Result.success(summary)
        } catch (e: Exception) {
            val msg = e.message ?: "Gagal sinkronisasi library Spotify."
            _syncState.value = SpotifySyncState.Error(msg)
            Result.failure(e)
        } finally {
            isSyncingNow = false
        }
    }

    fun logout() {
        saveSession(null)
        savePersonalizedCollections(emptyList(), emptyList(), emptyList())
        prefs?.edit()?.remove(KEY_HISTORY_PLAYLIST_ID)?.apply()
        _syncState.value = SpotifySyncState.Idle
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }

    // -----------------------------------------------------------------------
    // Token exchange: (1) TOTP HTTP endpoint -> (2) WebView HTML/JS fallback
    // -----------------------------------------------------------------------

    private suspend fun exchangeSpDcForAccessToken(
        spDc: String,
        context: Context?
    ): ExtractedWebTokens? {
        val totpResult = runCatching { exchangeViaTotpHttp(spDc) }.getOrNull()
        if (totpResult != null) return totpResult

        if (context != null) {
            val webViewResult = runCatching { extractTokenViaHeadlessWebView(context, spDc) }.getOrNull()
            if (webViewResult != null) return webViewResult
        }

        return null
    }

    private suspend fun exchangeViaTotpHttp(spDc: String): ExtractedWebTokens? {
        val serverTimeSeconds = fetchSpotifyServerTimeSeconds() ?: (System.currentTimeMillis() / 1000L)
        val secrets = fetchDynamicSecrets().ifEmpty { FALLBACK_SECRETS }

        val sortedVersions = secrets.keys.sortedDescending().take(3)
        for (version in sortedVersions) {
            val secretBytes = secrets[version] ?: continue
            val otp = generateSpotifyTotp(secretBytes, serverTimeSeconds)
            val clientOtp = generateSpotifyTotp(secretBytes, System.currentTimeMillis() / 1000L)

            val res = runCatching {
                client.get("https://open.spotify.com/api/token") {
                    parameter("reason", "init")
                    parameter("productType", "web-player")
                    parameter("totp", clientOtp)
                    parameter("totpServer", otp)
                    parameter("totpVer", version.toString())
                    header(HttpHeaders.Cookie, "sp_dc=$spDc")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                    header(HttpHeaders.Accept, "application/json")
                    header(HttpHeaders.Referrer, "https://open.spotify.com/")
                }
            }.getOrNull() ?: continue

            if (res.status.isSuccess()) {
                val bodyText = res.bodyAsText()
                val obj = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull() ?: continue
                val isAnonymous = obj["isAnonymous"]?.jsonPrimitive?.booleanOrNull ?: true
                val token = obj["accessToken"]?.jsonPrimitive?.contentOrNull
                val expMs = obj["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.longOrNull
                    ?: (System.currentTimeMillis() + 3600_000L)
                if (!token.isNullOrBlank() && !isAnonymous) {
                    return ExtractedWebTokens(
                        accessToken = token,
                        expiresAtMs = expMs
                    )
                }
            }
        }
        return null
    }

    private suspend fun fetchSpotifyServerTimeSeconds(): Long? {
        return runCatching {
            val res = client.get("https://open.spotify.com/api/server-time") {
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return null
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            obj["serverTime"]?.jsonPrimitive?.longOrNull
        }.getOrNull()
    }

    private suspend fun fetchDynamicSecrets(): Map<Int, List<Int>> {
        cachedDynamicSecrets?.takeIf { it.isNotEmpty() }?.let { return it }
        for (url in SECRET_DICT_URLS) {
            val parsed = withTimeoutOrNull(3_000L) {
                runCatching {
                    val res = client.get(url) {
                        header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                    }
                    if (!res.status.isSuccess()) return@runCatching null
                    val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
                    buildMap<Int, List<Int>> {
                        for ((k, v) in root) {
                            val ver = k.toIntOrNull() ?: continue
                            val arr = (v as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: continue
                            if (arr.isNotEmpty()) put(ver, arr)
                        }
                    }
                }.getOrNull()
            }
            if (!parsed.isNullOrEmpty()) {
                cachedDynamicSecrets = parsed
                return parsed
            }
        }
        return FALLBACK_SECRETS
    }

    internal fun generateSpotifyTotp(cipherBytes: List<Int>, timestampSeconds: Long): String {
        val transformedDigits = buildString {
            cipherBytes.forEachIndexed { index, byteVal ->
                val xored = byteVal xor ((index % 33) + 9)
                append(xored)
            }
        }
        val secretKeyBytes = transformedDigits.toByteArray(Charsets.UTF_8)
        val counter = timestampSeconds / 30L
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKeyBytes, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % 1_000_000
        return otp.toString().padStart(6, '0')
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractTokenViaHeadlessWebView(
        context: Context,
        spDc: String
    ): ExtractedWebTokens? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<ExtractedWebTokens?>()
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setCookie(
            "https://open.spotify.com",
            "sp_dc=$spDc; Path=/; Secure; HttpOnly"
        )
        cookieManager.setCookie(
            "https://spotify.com",
            "sp_dc=$spDc; Domain=.spotify.com; Path=/; Secure; HttpOnly"
        )
        cookieManager.setCookie(
            "https://accounts.spotify.com",
            "sp_dc=$spDc; Domain=.spotify.com; Path=/; Secure; HttpOnly"
        )
        cookieManager.flush()

        var capturedClientToken: String? = null
        var capturedUsername: String? = null
        var capturedDisplayName: String? = null
        var capturedAvatar: String? = null

        val webView = WebView(context.applicationContext)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = DESKTOP_USER_AGENT

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onSessionData(
                    token: String,
                    expMs: Long,
                    isAnon: Boolean,
                    username: String?,
                    displayName: String?,
                    avatar: String?
                ) {
                    if (!username.isNullOrBlank()) capturedUsername = username
                    if (!displayName.isNullOrBlank()) capturedDisplayName = displayName
                    if (!avatar.isNullOrBlank()) capturedAvatar = avatar

                    if (!isAnon && token.startsWith("BQ") && !deferred.isCompleted) {
                        val validExp = if (expMs > System.currentTimeMillis()) expMs else System.currentTimeMillis() + 3600_000L
                        deferred.complete(
                            ExtractedWebTokens(
                                accessToken = token,
                                clientToken = capturedClientToken,
                                expiresAtMs = validExp,
                                usernameHint = capturedUsername,
                                displayNameHint = capturedDisplayName,
                                avatarUrlHint = capturedAvatar
                            )
                        )
                    }
                }

                @JavascriptInterface
                fun onAnonymousDetected() {
                    if (!deferred.isCompleted) {
                        deferred.complete(null)
                    }
                }
            },
            "BlexsyAuthBridge"
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val headers = request?.requestHeaders.orEmpty()
                val ct = headers.entries.firstOrNull { it.key.equals("client-token", ignoreCase = true) }?.value
                if (!ct.isNullOrBlank()) {
                    capturedClientToken = ct.trim()
                }

                val urlStr = request?.url?.toString().orEmpty()
                val auth = headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
                if (auth != null && auth.startsWith("Bearer BQ", ignoreCase = true)) {
                    val token = auth.substringAfter(" ").trim()
                    val isUserEndpoint = urlStr.contains("pathfinder") ||
                        urlStr.contains("/v1/me") ||
                        urlStr.contains("spclient.wg.spotify.com")
                    if (isUserEndpoint && !capturedClientToken.isNullOrBlank() && !deferred.isCompleted) {
                        deferred.complete(
                            ExtractedWebTokens(
                                accessToken = token,
                                clientToken = capturedClientToken,
                                expiresAtMs = System.currentTimeMillis() + 3600_000L,
                                usernameHint = capturedUsername,
                                displayNameHint = capturedDisplayName,
                                avatarUrlHint = capturedAvatar
                            )
                        )
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(
                    """
                    (function() {
                        try {
                            var sessionEl = document.getElementById('session');
                            if (sessionEl) {
                                var data = JSON.parse(sessionEl.textContent);
                                if (data && data.accessToken) {
                                    if (data.isAnonymous === true) {
                                        BlexsyAuthBridge.onAnonymousDetected();
                                        return;
                                    }
                                    BlexsyAuthBridge.onSessionData(
                                        data.accessToken,
                                        data.accessTokenExpirationTimestampMs || 0,
                                        false,
                                        data.username || '',
                                        '',
                                        ''
                                    );
                                    return;
                                }
                            }
                            var origFetch = window.fetch;
                            window.fetch = function() {
                                return origFetch.apply(this, arguments).then(function(res) {
                                    try {
                                        var u = res.url || '';
                                        if (u.indexOf('/api/token') !== -1) {
                                            res.clone().json().then(function(d) {
                                                if (d && d.accessToken) {
                                                    if (d.isAnonymous === true) {
                                                        BlexsyAuthBridge.onAnonymousDetected();
                                                    } else {
                                                        BlexsyAuthBridge.onSessionData(
                                                            d.accessToken,
                                                            d.accessTokenExpirationTimestampMs || 0,
                                                            false,
                                                            d.username || '',
                                                            '',
                                                            ''
                                                        );
                                                    }
                                                }
                                            });
                                        }
                                    } catch (e) {}
                                    return res;
                                });
                            };
                        } catch (e) {}
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }

        try {
            webView.loadUrl("https://open.spotify.com/")
            withTimeoutOrNull(15_000L) { deferred.await() }
        } finally {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Spotify Web API + Pathfinder GraphQL calls
    // -----------------------------------------------------------------------

    private data class UserProfileDto(
        val id: String,
        val displayName: String,
        val email: String?,
        val avatarUrl: String?,
        val product: String?
    )

    private suspend fun fetchUserProfile(
        accessToken: String,
        clientToken: String? = null,
        spDc: String = "",
        hints: ExtractedWebTokens? = null
    ): UserProfileDto? {
        val restProfile = runCatching {
            val res = client.get("https://api.spotify.com/v1/me") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                header(HttpHeaders.Origin, "https://open.spotify.com")
                header(HttpHeaders.Referrer, "https://open.spotify.com/")
            }
            if (!res.status.isSuccess()) return@runCatching null
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val displayName = obj["display_name"]?.jsonPrimitive?.contentOrNull ?: id
            val email = obj["email"]?.jsonPrimitive?.contentOrNull
            val product = obj["product"]?.jsonPrimitive?.contentOrNull
            val avatarUrl = obj["images"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("url")?.jsonPrimitive?.contentOrNull
            UserProfileDto(
                id = id,
                displayName = displayName,
                email = email,
                avatarUrl = avatarUrl,
                product = product
            )
        }.getOrNull()
        if (restProfile != null) return restProfile

        val pathfinderProfile = runCatching {
            val variables = "{}"
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$HASH_PROFILE_ATTRIBUTES"}}"""
            val res = client.get("https://api-partner.spotify.com/pathfinder/v1/query") {
                parameter("operationName", "profileAttributes")
                parameter("variables", variables)
                parameter("extensions", extensions)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                header(HttpHeaders.Origin, "https://open.spotify.com")
                header(HttpHeaders.Referrer, "https://open.spotify.com/")
            }
            if (!res.status.isSuccess()) return@runCatching null
            val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val me = root["data"]?.jsonObject?.get("me")?.jsonObject
                ?: root["data"]?.jsonObject?.get("currentUser")?.jsonObject
            val profile = me?.get("profile")?.jsonObject ?: return@runCatching null
            val username = profile["username"]?.jsonPrimitive?.contentOrNull
                ?: profile["uri"]?.jsonPrimitive?.contentOrNull?.substringAfterLast(':')
                ?: hints?.usernameHint
                ?: return@runCatching null
            val name = profile["name"]?.jsonPrimitive?.contentOrNull ?: username
            val avatar = profile["avatar"]?.jsonObject
                ?.get("sources")?.jsonArray
                ?.lastOrNull()?.jsonObject
                ?.get("url")?.jsonPrimitive?.contentOrNull
            UserProfileDto(
                id = username,
                displayName = name,
                email = null,
                avatarUrl = avatar,
                product = "spotify"
            )
        }.getOrNull()
        if (pathfinderProfile != null) return pathfinderProfile

        val playlistProbe = runCatching {
            val res = client.get("https://api.spotify.com/v1/me/playlists") {
                parameter("limit", 1)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching null
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val firstOwner = obj["items"]?.jsonArray?.firstOrNull()?.jsonObject?.get("owner")?.jsonObject
            val ownerId = firstOwner?.get("id")?.jsonPrimitive?.contentOrNull
                ?: hints?.usernameHint
                ?: "spotify_user"
            val ownerName = firstOwner?.get("display_name")?.jsonPrimitive?.contentOrNull
                ?: hints?.displayNameHint
                ?: ownerId
            UserProfileDto(
                id = ownerId,
                displayName = ownerName,
                email = null,
                avatarUrl = hints?.avatarUrlHint,
                product = "spotify"
            )
        }.getOrNull()
        if (playlistProbe != null) return playlistProbe

        val hintUser = hints?.usernameHint?.takeIf { it.isNotBlank() }
        if (hintUser != null) {
            return UserProfileDto(
                id = hintUser,
                displayName = hints.displayNameHint?.takeIf { it.isNotBlank() } ?: hintUser,
                email = null,
                avatarUrl = hints.avatarUrlHint,
                product = "spotify"
            )
        }

        return null
    }

    private suspend fun fetchLikedTracks(
        accessToken: String,
        clientToken: String? = null,
        maxTracks: Int = 200
    ): List<Track> {
        val result = mutableListOf<Track>()
        var offset = 0
        val pageSize = 50
        while (result.size < maxTracks) {
            val page = runCatching {
                val res = client.get("https://api.spotify.com/v1/me/tracks") {
                    parameter("limit", pageSize)
                    parameter("offset", offset)
                    parameter("market", "from_token")
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                }
                if (!res.status.isSuccess()) return@runCatching emptyList<Track>()
                val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
                val items = obj["items"]?.jsonArray ?: return@runCatching emptyList<Track>()
                items.mapNotNull { item ->
                    val trackObj = item.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
                    parseWebApiTrack(trackObj)
                }
            }.getOrDefault(emptyList())

            if (page.isEmpty()) break
            result.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }

        if (result.isNotEmpty()) return result.distinctBy { it.id }

        return runCatching {
            val body = buildJsonObject {
                put("variables", buildJsonObject {
                    put("offset", 0)
                    put("limit", 100)
                })
                put("operationName", "fetchLibraryTracks")
                put("extensions", buildJsonObject {
                    put("persistedQuery", buildJsonObject {
                        put("version", 1)
                        put("sha256Hash", HASH_FETCH_LIBRARY_TRACKS)
                    })
                })
            }
            val res = client.post("https://api-partner.spotify.com/pathfinder/v1/query") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            if (!res.status.isSuccess()) return@runCatching emptyList<Track>()
            val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val items = root["data"]?.jsonObject
                ?.get("me")?.jsonObject
                ?.get("library")?.jsonObject
                ?.get("tracks")?.jsonObject
                ?.get("items")?.jsonArray ?: return@runCatching emptyList<Track>()
            items.mapNotNull { el ->
                val trkWrapper = el.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
                val data = trkWrapper["data"]?.jsonObject ?: trkWrapper
                val uri = trkWrapper["_uri"]?.jsonPrimitive?.contentOrNull
                    ?: data["uri"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val id = uri.substringAfterLast(':')
                val name = data["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val durMs = data["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.longOrNull
                val artistsItems = data["artists"]?.jsonObject?.get("items")?.jsonArray.orEmpty()
                val artistNames = artistsItems.mapNotNull {
                    it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                }
                val artistIds = artistsItems.mapNotNull {
                    it.jsonObject["uri"]?.jsonPrimitive?.contentOrNull?.substringAfterLast(':')
                }
                val albumData = data["albumOfTrack"]?.jsonObject
                val albumName = albumData?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                val albumId = albumData?.get("uri")?.jsonPrimitive?.contentOrNull?.substringAfterLast(':')
                val img = albumData?.get("coverArt")?.jsonObject
                    ?.get("sources")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull
                val isPlayable = data["playability"]?.jsonObject
                    ?.get("playable")?.jsonPrimitive?.booleanOrNull != false
                Track(
                    id = id,
                    title = name,
                    artist = artistNames.joinToString(", ").ifBlank { "Unknown Artist" },
                    artistIds = artistIds,
                    album = albumName,
                    albumId = albumId,
                    durationMs = durMs,
                    image = img,
                    isPlayable = isPlayable
                )
            }
        }.getOrDefault(emptyList())
    }

    private data class LibraryV3Parsed(
        val playlists: List<PlaylistMetaSummary> = emptyList(),
        val artists: List<ArtistSummary> = emptyList(),
        val albums: List<AlbumSummary> = emptyList()
    )

    /**
     * Queries Spotify Web Player's `libraryV3` Pathfinder GraphQL endpoint, which returns the user's
     * saved Playlists, Followed Artists, and Saved Albums in a single response.
     */
    private suspend fun fetchLibraryV3Items(
        accessToken: String,
        clientToken: String?
    ): LibraryV3Parsed {
        return runCatching {
            val hashes = listOf(
                "173808860f8f4d9fbe0d9a397b81a49c9c7d9e69a1f36ef63880015014b2762c",
                "9f4da031f81274d572cfed46957a645c822a5d1139e9a78663a5f2f92882b909"
            )
            for (hash in hashes) {
                val body = buildJsonObject {
                    put("variables", buildJsonObject {
                        put("filters", buildJsonArray {})
                        put("order", null as String?)
                        put("textFilter", "")
                        put("features", buildJsonArray {
                            add(JsonPrimitive("LIKED_SONGS"))
                            add(JsonPrimitive("YOUR_EPISODES"))
                        })
                        put("limit", 50)
                        put("offset", 0)
                        put("flatten", false)
                        put("expandedFolders", buildJsonArray {})
                        put("folderUri", null as String?)
                        put("includeFoldersWhenFlattening", true)
                    })
                    put("operationName", "libraryV3")
                    put("extensions", buildJsonObject {
                        put("persistedQuery", buildJsonObject {
                            put("version", 1)
                            put("sha256Hash", hash)
                        })
                    })
                }
                val res = client.post("https://api-partner.spotify.com/pathfinder/v1/query") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                if (!res.status.isSuccess()) continue
                val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
                val items = root["data"]?.jsonObject
                    ?.get("me")?.jsonObject
                    ?.get("libraryV3")?.jsonObject
                    ?.get("items")?.jsonArray ?: continue

                val playlists = mutableListOf<PlaylistMetaSummary>()
                val artists = mutableListOf<ArtistSummary>()
                val albums = mutableListOf<AlbumSummary>()

                for (el in items) {
                    val itemObj = el.jsonObject["item"]?.jsonObject ?: continue
                    val data = itemObj["data"]?.jsonObject ?: itemObj
                    val uri = itemObj["_uri"]?.jsonPrimitive?.contentOrNull
                        ?: data["uri"]?.jsonPrimitive?.contentOrNull
                        ?: continue
                    val typeName = data["__typename"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val id = uri.substringAfterLast(':')

                    when {
                        uri.contains(":playlist:") || typeName.contains("Playlist", ignoreCase = true) -> {
                            val name = data["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                            val firstImageItem = data["images"]?.jsonObject
                                ?.get("items")?.jsonArray
                                ?.firstOrNull()?.jsonObject
                            val sources = firstImageItem?.get("sources")?.jsonArray
                                ?: firstImageItem?.get("data")?.jsonObject?.get("sources")?.jsonArray
                                ?: data["coverArt"]?.jsonObject?.get("sources")?.jsonArray
                            val img = sources
                                ?.firstOrNull()?.jsonObject
                                ?.get("url")?.jsonPrimitive?.contentOrNull
                                .normalizeSpotifyImageUrl()
                            val owner = data["ownerV2"]?.jsonObject
                                ?.get("data")?.jsonObject
                                ?.get("username")?.jsonPrimitive?.contentOrNull
                            playlists.add(PlaylistMetaSummary(id, name, img, owner))
                        }
                        uri.contains(":artist:") || typeName.contains("Artist", ignoreCase = true) -> {
                            val name = data["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                                ?: data["name"]?.jsonPrimitive?.contentOrNull
                                ?: continue
                            val img = data["visuals"]?.jsonObject
                                ?.get("avatarImage")?.jsonObject
                                ?.get("sources")?.jsonArray
                                ?.firstOrNull()?.jsonObject
                                ?.get("url")?.jsonPrimitive?.contentOrNull
                            artists.add(ArtistSummary(id = id, name = name, image = img))
                        }
                        uri.contains(":album:") || typeName.contains("Album", ignoreCase = true) -> {
                            val name = data["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                            val artistName = data["artists"]?.jsonObject
                                ?.get("items")?.jsonArray
                                ?.mapNotNull { it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull }
                                ?.joinToString(", ")
                            val img = data["coverArt"]?.jsonObject
                                ?.get("sources")?.jsonArray
                                ?.firstOrNull()?.jsonObject
                                ?.get("url")?.jsonPrimitive?.contentOrNull
                            albums.add(AlbumSummary(id = id, name = name, artist = artistName, image = img))
                        }
                    }
                }
                if (playlists.isNotEmpty() || artists.isNotEmpty() || albums.isNotEmpty()) {
                    val dedicatedArtists = fetchLibraryV3ArtistsFiltered(accessToken, clientToken).orEmpty()
                    val combinedArtists = (dedicatedArtists + artists).distinctBy { it.id }
                    return@runCatching LibraryV3Parsed(playlists, combinedArtists, albums)
                }
            }
            LibraryV3Parsed()
        }.getOrDefault(LibraryV3Parsed())
    }

    /**
     * Queries Pathfinder `libraryV3` specifically with `filters: ["Artists"]` so followed artists
     * are never truncated behind 50+ playlists/albums in the unfiltered library feed.
     */
    private suspend fun fetchLibraryV3ArtistsFiltered(
        accessToken: String,
        clientToken: String?,
        textFilter: String = "",
        limit: Int = 100
    ): List<ArtistSummary>? {
        val hashes = listOf(
            "173808860f8f4d9fbe0d9a397b81a49c9c7d9e69a1f36ef63880015014b2762c",
            "9f4da031f81274d572cfed46957a645c822a5d1139e9a78663a5f2f92882b909"
        )
        for (hash in hashes) {
            val parsed = runCatching {
                val body = buildJsonObject {
                    put("variables", buildJsonObject {
                        put("filters", buildJsonArray { add(JsonPrimitive("Artists")) })
                        put("order", null as String?)
                        put("textFilter", textFilter)
                        put("features", buildJsonArray {
                            add(JsonPrimitive("LIKED_SONGS"))
                            add(JsonPrimitive("YOUR_EPISODES"))
                        })
                        put("limit", limit)
                        put("offset", 0)
                        put("flatten", false)
                        put("expandedFolders", buildJsonArray {})
                        put("folderUri", null as String?)
                        put("includeFoldersWhenFlattening", true)
                    })
                    put("operationName", "libraryV3")
                    put("extensions", buildJsonObject {
                        put("persistedQuery", buildJsonObject {
                            put("version", 1)
                            put("sha256Hash", hash)
                        })
                    })
                }
                val res = client.post("https://api-partner.spotify.com/pathfinder/v1/query") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                if (!res.status.isSuccess()) return@runCatching null
                val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
                val items = root["data"]?.jsonObject
                    ?.get("me")?.jsonObject
                    ?.get("libraryV3")?.jsonObject
                    ?.get("items")?.jsonArray ?: return@runCatching null

                items.mapNotNull { el ->
                    val itemObj = el.jsonObject["item"]?.jsonObject ?: return@mapNotNull null
                    val data = itemObj["data"]?.jsonObject ?: itemObj
                    val uri = itemObj["_uri"]?.jsonPrimitive?.contentOrNull
                        ?: data["uri"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    if (!uri.contains(":artist:")) return@mapNotNull null
                    val id = uri.substringAfterLast(':')
                    val name = data["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                        ?: data["name"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val sources = data["visuals"]?.jsonObject
                        ?.get("avatarImage")?.jsonObject
                        ?.let { av -> av["sources"]?.jsonArray ?: av["data"]?.jsonObject?.get("sources")?.jsonArray }
                    val img = sources
                        ?.firstOrNull()?.jsonObject
                        ?.get("url")?.jsonPrimitive?.contentOrNull
                        .normalizeSpotifyImageUrl()
                    ArtistSummary(id = id, name = name, image = img)
                }
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /**
     * Queries Spotify's internal `spclient.wg.spotify.com/user-profile-view/v3/profile/{userId}`
     * endpoint for public playlists and recently played artists.
     */
    private suspend fun fetchUserProfileView(
        accessToken: String,
        clientToken: String?,
        userId: String
    ): Pair<List<PlaylistMetaSummary>, List<ArtistSummary>> {
        return runCatching {
            val res = client.get("https://spclient.wg.spotify.com/user-profile-view/v3/profile/$userId") {
                parameter("playlist_limit", 50)
                parameter("artist_limit", 50)
                parameter("episode_limit", 10)
                parameter("market", "from_token")
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching emptyList<PlaylistMetaSummary>() to emptyList<ArtistSummary>()
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject

            val playlists = obj["public_playlists"]?.jsonArray.orEmpty().mapNotNull { el ->
                val p = el.jsonObject
                val uri = p["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val id = uri.substringAfterLast(':')
                val name = p["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val rawImg = p["image_url"]?.jsonPrimitive?.contentOrNull
                val img = rawImg.normalizeSpotifyImageUrl()
                val ownerUri = p["owner_uri"]?.jsonPrimitive?.contentOrNull
                PlaylistMetaSummary(id, name, img, ownerUri?.substringAfterLast(':'))
            }

            val artists = obj["recently_played_artists"]?.jsonArray.orEmpty().mapNotNull { el ->
                val a = el.jsonObject
                val uri = a["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val id = uri.substringAfterLast(':')
                val name = a["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val rawImg = a["image_url"]?.jsonPrimitive?.contentOrNull
                val img = rawImg.normalizeSpotifyImageUrl()
                ArtistSummary(id = id, name = name, image = img)
            }

            playlists to artists
        }.getOrDefault(emptyList<PlaylistMetaSummary>() to emptyList<ArtistSummary>())
    }

    private data class PlaylistMetaSummary(
        val id: String,
        val name: String,
        val imageUrl: String?,
        val ownerId: String?
    )

    private fun isMadeForYouTitleOrOwner(name: String, ownerId: String?): Boolean {
        val lower = name.lowercase()
        val isSpotifyOwned = ownerId.equals("spotify", ignoreCase = true)
        val matchesKeyword = MADE_FOR_YOU_KEYWORDS.any { lower.contains(it) }
        return matchesKeyword || (isSpotifyOwned && (lower.contains("mix") || lower.contains("radar") || lower.contains("weekly") || lower.contains("repeat")))
    }

    /**
     * Fetches the user's saved/personal playlists AND separates out any "Made For You" mixes
     * already saved in their library.
     */
    private suspend fun fetchUserAndMadeForYouPlaylists(
        accessToken: String,
        clientToken: String? = null,
        userId: String? = null,
        extraMetas: List<PlaylistMetaSummary> = emptyList(),
        maxPlaylists: Int = 40
    ): Pair<List<Playlist>, List<Playlist>> {
        var summaries = runCatching {
            val res = client.get("https://api.spotify.com/v1/me/playlists") {
                parameter("limit", maxPlaylists.coerceIn(1, 50))
                parameter("offset", 0)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching emptyList<PlaylistMetaSummary>()
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val items = obj["items"]?.jsonArray ?: return@runCatching emptyList<PlaylistMetaSummary>()
            items.mapNotNull { el ->
                val pl = el.jsonObject
                val id = pl["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = pl["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Spotify Playlist"
                val img = pl["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                val ownerId = pl["owner"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                PlaylistMetaSummary(id, name, img, ownerId)
            }
        }.getOrDefault(emptyList())

        if (summaries.isEmpty() && !userId.isNullOrBlank()) {
            summaries = runCatching {
                val res = client.get("https://api.spotify.com/v1/users/$userId/playlists") {
                    parameter("limit", maxPlaylists.coerceIn(1, 50))
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                }
                if (!res.status.isSuccess()) return@runCatching emptyList<PlaylistMetaSummary>()
                val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
                val items = obj["items"]?.jsonArray ?: return@runCatching emptyList<PlaylistMetaSummary>()
                items.mapNotNull { el ->
                    val pl = el.jsonObject
                    val id = pl["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = pl["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Spotify Playlist"
                    val img = pl["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                    val ownerId = pl["owner"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    PlaylistMetaSummary(id, name, img, ownerId)
                }
            }.getOrDefault(emptyList())
        }

        // Merge summaries from Web API (`/v1/me/playlists`) and Pathfinder (`libraryV3` / `user-profile-view`),
        // preserving whichever source carried the non-null cover image URL and owner ID.
        val allCombined = (summaries + extraMetas)
            .groupBy { it.id }
            .map { (_, group) ->
                val primary = group.first()
                PlaylistMetaSummary(
                    id = primary.id,
                    name = group.firstNotNullOfOrNull { it.name.takeIf { n -> n.isNotBlank() } } ?: primary.name,
                    imageUrl = group.firstNotNullOfOrNull { it.imageUrl.normalizeSpotifyImageUrl() },
                    ownerId = group.firstNotNullOfOrNull { it.ownerId?.takeIf { o -> o.isNotBlank() } }
                )
            }
        allCombined.firstOrNull { it.name.trim().equals(BLEXSY_HISTORY_PLAYLIST_NAME, ignoreCase = true) }?.let { hist ->
            prefs?.edit()?.putString(KEY_HISTORY_PLAYLIST_ID, hist.id)?.apply()
        }
        val combinedSummaries = allCombined
            .filterNot { it.name.trim().equals(BLEXSY_HISTORY_PLAYLIST_NAME, ignoreCase = true) }
            .take(maxPlaylists)

        val userPlaylists = mutableListOf<Playlist>()
        val madeForYou = mutableListOf<Playlist>()

        // Hydrate playlists concurrently in chunks of 6 so 24 playlists finish in ~2-3s instead of 40s
        for (chunk in combinedSummaries.chunked(6)) {
            val hydratedChunk = coroutineScope {
                chunk.map { meta ->
                    async {
                        val pl = withTimeoutOrNull(4_500L) {
                            hydratePlaylist(accessToken, clientToken, meta.id, meta.name, meta.imageUrl)
                        }
                        if (pl != null) meta to pl else null
                    }
                }.awaitAll().filterNotNull()
            }
            for ((meta, playlist) in hydratedChunk) {
                userPlaylists.add(playlist)
                if (isMadeForYouTitleOrOwner(meta.name, meta.ownerId)) {
                    madeForYou.add(playlist)
                }
            }
        }
        return userPlaylists to madeForYou
    }

    /**
     * Feature #3: Discovers the user's personalized "Made For You" playlists
     * (Daily Mix 1–6, Discover Weekly, Release Radar, On Repeat) using authenticated search
     * plus the user's short-term Top Tracks ("On Repeat • Top Putaranmu").
     */
    private suspend fun fetchPersonalizedMadeForYouMixes(
        accessToken: String,
        clientToken: String? = null,
        alreadyFound: List<Playlist>
    ): List<Playlist> {
        val mixes = alreadyFound.toMutableList()
        val seenIds = mixes.map { it.id.removePrefix("sp-") }.toMutableSet()

        // 1. Build a guaranteed personal "On Repeat" mix from the user's short-term top tracks
        val topShortTermTracks = runCatching {
            val res = client.get("https://api.spotify.com/v1/me/top/tracks") {
                parameter("time_range", "short_term")
                parameter("limit", 30)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching emptyList<Track>()
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val items = obj["items"]?.jsonArray ?: return@runCatching emptyList<Track>()
            items.mapNotNull { parseWebApiTrack(it.jsonObject) }
        }.getOrDefault(emptyList())

        if (topShortTermTracks.isNotEmpty()) {
            mixes.add(
                0,
                Playlist(
                    id = "sp-top-short-term",
                    name = "On Repeat • Top Putaranmu",
                    tracks = topShortTermTracks,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // 2. Query personalized Spotify mixes via authenticated `/v1/search?type=playlist`
        val queries = listOf("Daily Mix", "Discover Weekly", "Release Radar", "On Repeat")
        val discoveredMetas = mutableListOf<PlaylistMetaSummary>()

        for (query in queries) {
            if (discoveredMetas.size >= 8) break
            val found = runCatching {
                val res = client.get("https://api.spotify.com/v1/search") {
                    parameter("q", query)
                    parameter("type", "playlist")
                    parameter("limit", 8)
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                }
                if (!res.status.isSuccess()) return@runCatching emptyList<PlaylistMetaSummary>()
                val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
                val items = obj["playlists"]?.jsonObject?.get("items")?.jsonArray ?: return@runCatching emptyList<PlaylistMetaSummary>()
                items.mapNotNull { el ->
                    val pl = (el as? JsonObject) ?: return@mapNotNull null
                    val id = pl["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (id in seenIds) return@mapNotNull null
                    val name = pl["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val ownerId = pl["owner"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    val ownerName = pl["owner"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull
                    val isOfficialSpotify = ownerId.equals("spotify", ignoreCase = true) ||
                        ownerName.equals("Spotify", ignoreCase = true) ||
                        id.startsWith("37i9dQZF1E")
                    if (!isOfficialSpotify || !isMadeForYouTitleOrOwner(name, "spotify")) return@mapNotNull null
                    val img = pl["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                    seenIds.add(id)
                    PlaylistMetaSummary(id, name, img, "spotify")
                }
            }.getOrDefault(emptyList())
            val fallbackFound = if (found.isEmpty()) {
                runCatching {
                    SpotifyMetadata.search(query, limit = 6).playlists.mapNotNull { pl ->
                        if (pl.id in seenIds) return@mapNotNull null
                        if (!isMadeForYouTitleOrOwner(pl.name, pl.owner ?: "spotify")) return@mapNotNull null
                        seenIds.add(pl.id)
                        PlaylistMetaSummary(pl.id, pl.name, pl.image, "spotify")
                    }
                }.getOrDefault(emptyList())
            } else emptyList()
            discoveredMetas.addAll(found + fallbackFound)
        }

        val hydratedDiscovered = coroutineScope {
            discoveredMetas.take(6).map { meta ->
                async {
                    withTimeoutOrNull(4_500L) {
                        hydratePlaylist(accessToken, clientToken, meta.id, meta.name, meta.imageUrl)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        mixes.addAll(hydratedDiscovered)

        return mixes.distinctBy { it.id }
    }

    private suspend fun hydratePlaylist(
        accessToken: String,
        clientToken: String?,
        playlistId: String,
        playlistName: String,
        coverImage: String?
    ): Playlist? {
        val normalizedCover = coverImage.normalizeSpotifyImageUrl()
        val webApiTracks = fetchPlaylistTracksWebApi(accessToken, clientToken, playlistId)
        val fallbackDetail = if (webApiTracks.isEmpty() || normalizedCover == null) {
            runCatching { SpotifyMetadata.getPlaylist(playlistId) }.getOrNull()
        } else null

        val resolvedCover = normalizedCover
            ?: fallbackDetail?.image.normalizeSpotifyImageUrl()

        val tracks = webApiTracks.ifEmpty {
            fallbackDetail?.tracks?.map { spTrack ->
                Track(
                    id = spTrack.id,
                    title = spTrack.name,
                    artist = spTrack.artists.joinToString(", "),
                    artistIds = spTrack.artistIds,
                    album = spTrack.album.orEmpty(),
                    durationMs = spTrack.durationMs,
                    image = spTrack.image.normalizeSpotifyImageUrl() ?: resolvedCover,
                    isPlayable = spTrack.playable
                )
            }.orEmpty()
        }

        if (tracks.isEmpty()) return null
        val finalCover = resolvedCover
            ?: tracks.firstNotNullOfOrNull { it.image.normalizeSpotifyImageUrl() }
        return Playlist(
            id = "sp-$playlistId",
            name = playlistName,
            image = finalCover,
            tracks = tracks,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun fetchPlaylistTracksWebApi(
        accessToken: String,
        clientToken: String? = null,
        playlistId: String
    ): List<Track> {
        return runCatching {
            val res = client.get("https://api.spotify.com/v1/playlists/$playlistId/tracks") {
                parameter("limit", 100)
                parameter("offset", 0)
                parameter("market", "from_token")
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching emptyList<Track>()
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val items = obj["items"]?.jsonArray ?: return@runCatching emptyList<Track>()
            items.mapNotNull { item ->
                val trackObj = item.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
                parseWebApiTrack(trackObj)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Feature #4a: Fetches the user's Followed Artists via:
     * 1. Spotify Web API `/v1/me/following?type=artist` (paginated)
     * 2. Spotify internal `spclient.wg.spotify.com/user-profile-view/v3/profile/{userId}/following`
     *    (works with WebPlayer tokens even when `/v1/me/following` returns 403 scope error)
     * 3. Pathfinder `libraryV3` with `filters: ["Artists"]`
     */
    private suspend fun fetchFollowedAndTopArtists(
        accessToken: String,
        clientToken: String? = null,
        userId: String? = null,
        includeTopFallback: Boolean = false
    ): List<ArtistSummary> {
        val followedWebApi = mutableListOf<ArtistSummary>()
        var afterCursor: String? = null
        while (followedWebApi.size < 200) {
            val (pageItems, nextAfter) = runCatching {
                val res = client.get("https://api.spotify.com/v1/me/following") {
                    parameter("type", "artist")
                    parameter("limit", 50)
                    if (!afterCursor.isNullOrBlank()) parameter("after", afterCursor)
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                }
                if (!res.status.isSuccess()) return@runCatching emptyList<ArtistSummary>() to null
                val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
                val artistsObj = obj["artists"]?.jsonObject ?: return@runCatching emptyList<ArtistSummary>() to null
                val items = artistsObj["items"]?.jsonArray ?: return@runCatching emptyList<ArtistSummary>() to null
                val next = artistsObj["cursors"]?.jsonObject?.get("after")?.jsonPrimitive?.contentOrNull
                items.mapNotNull { parseWebApiArtist(it.jsonObject) } to next
            }.getOrDefault(emptyList<ArtistSummary>() to null)

            if (pageItems.isEmpty()) break
            followedWebApi.addAll(pageItems)
            if (nextAfter.isNullOrBlank() || pageItems.size < 50) break
            afterCursor = nextAfter
        }

        // Also query `spclient` profile following endpoint (works reliably with WebPlayer tokens)
        val spclientFollowed = if (!userId.isNullOrBlank()) {
            runCatching {
                val res = client.get("https://spclient.wg.spotify.com/user-profile-view/v3/profile/$userId/following") {
                    parameter("market", "from_token")
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                    header("app-platform", "WebPlayer")
                    header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                }
                if (!res.status.isSuccess()) return@runCatching emptyList<ArtistSummary>()
                val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
                obj["profiles"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val a = el.jsonObject
                    val uri = a["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!uri.contains(":artist:")) return@mapNotNull null
                    val id = uri.substringAfterLast(':')
                    val name = a["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val img = a["image_url"]?.jsonPrimitive?.contentOrNull.normalizeSpotifyImageUrl()
                    val followersCount = a["followers_count"]?.jsonPrimitive?.intOrNull
                    val subLabel = followersCount?.takeIf { it > 0 }?.let { count ->
                        when {
                            count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM followers", count / 1_000_000.0)
                            count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK followers", count / 1_000_000.0)
                            else -> "$count followers"
                        }
                    }
                    ArtistSummary(id = id, name = name, image = img, subscribers = subLabel)
                }
            }.getOrDefault(emptyList())
        } else emptyList()

        val libraryV3Artists = fetchLibraryV3ArtistsFiltered(accessToken, clientToken).orEmpty()
        val followed = (followedWebApi + spclientFollowed + libraryV3Artists).distinctBy { it.id }

        if (!includeTopFallback || followed.isNotEmpty()) {
            return followed
        }

        val topArtists = runCatching {
            val res = client.get("https://api.spotify.com/v1/me/top/artists") {
                parameter("time_range", "medium_term")
                parameter("limit", 30)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching emptyList<ArtistSummary>()
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val items = obj["items"]?.jsonArray ?: return@runCatching emptyList<ArtistSummary>()
            items.mapNotNull { parseWebApiArtist(it.jsonObject) }
        }.getOrDefault(emptyList())

        return (followed + topArtists).distinctBy { it.id }
    }

    private fun parseWebApiArtist(artistObj: JsonObject): ArtistSummary? {
        val id = artistObj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = artistObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val image = artistObj["images"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull
        val followersCount = artistObj["followers"]?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull
        val subLabel = followersCount?.takeIf { it > 0 }?.let { count ->
            when {
                count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM followers", count / 1_000_000.0)
                count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK followers", count / 1_000.0)
                else -> "$count followers"
            }
        }
        return ArtistSummary(
            id = id,
            name = name,
            image = image,
            subscribers = subLabel
        )
    }

    /**
     * Feature #4b: Fetches the user's Saved Albums (`/v1/me/albums`).
     */
    private suspend fun fetchSavedAlbums(
        accessToken: String,
        clientToken: String? = null
    ): List<AlbumSummary> {
        return runCatching {
            val res = client.get("https://api.spotify.com/v1/me/albums") {
                parameter("limit", 50)
                parameter("offset", 0)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching emptyList<AlbumSummary>()
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val items = obj["items"]?.jsonArray ?: return@runCatching emptyList<AlbumSummary>()
            items.mapNotNull { el ->
                val alb = el.jsonObject["album"]?.jsonObject ?: return@mapNotNull null
                val id = alb["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = alb["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artists = alb["artists"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                    .joinToString(", ")
                val image = alb["images"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull
                val year = alb["release_date"]?.jsonPrimitive?.contentOrNull?.take(4)
                val type = alb["album_type"]?.jsonPrimitive?.contentOrNull?.replaceFirstChar { it.uppercase() } ?: "Album"
                AlbumSummary(
                    id = id,
                    name = name,
                    artist = artists.ifBlank { null },
                    image = image,
                    year = year,
                    type = type
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun ensureHistoryPlaylistId(
        accessToken: String,
        clientToken: String? = null,
        userId: String? = null,
        knownMetas: List<PlaylistMetaSummary> = emptyList()
    ): String? {
        knownMetas.firstOrNull { it.name.trim().equals(BLEXSY_HISTORY_PLAYLIST_NAME, ignoreCase = true) }?.let { found ->
            prefs?.edit()?.putString(KEY_HISTORY_PLAYLIST_ID, found.id)?.apply()
            return found.id
        }

        val cachedId = prefs?.getString(KEY_HISTORY_PLAYLIST_ID, null)?.takeIf { it.isNotBlank() }
        if (cachedId != null) return cachedId

        val foundInMe = runCatching {
            val res = client.get("https://api.spotify.com/v1/me/playlists") {
                parameter("limit", 50)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching null
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            obj["items"]?.jsonArray?.firstNotNullOfOrNull { el ->
                val pl = el.jsonObject
                val name = pl["name"]?.jsonPrimitive?.contentOrNull?.trim()
                if (name.equals(BLEXSY_HISTORY_PLAYLIST_NAME, ignoreCase = true)) {
                    pl["id"]?.jsonPrimitive?.contentOrNull
                } else null
            }
        }.getOrNull()

        if (!foundInMe.isNullOrBlank()) {
            prefs?.edit()?.putString(KEY_HISTORY_PLAYLIST_ID, foundInMe)?.apply()
            return foundInMe
        }

        // Create private '.blexsy-history' playlist on the user's Spotify account
        val createUrl = if (!userId.isNullOrBlank()) {
            "https://api.spotify.com/v1/users/$userId/playlists"
        } else {
            "https://api.spotify.com/v1/me/playlists"
        }
        val createdId = runCatching {
            val payload = buildJsonObject {
                put("name", BLEXSY_HISTORY_PLAYLIST_NAME)
                put("public", false)
                put("description", "Blexsy cross-device listening history sync")
            }
            val res = client.post(createUrl) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            if (!res.status.isSuccess()) return@runCatching null
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            obj["id"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        if (!createdId.isNullOrBlank()) {
            prefs?.edit()?.putString(KEY_HISTORY_PLAYLIST_ID, createdId)?.apply()
        }
        return createdId
    }

    private suspend fun fetchHistoryPlaylistTracks(
        accessToken: String,
        clientToken: String? = null,
        historyPlaylistId: String
    ): List<Track> {
        val webApiTracks = runCatching {
            val res = client.get("https://api.spotify.com/v1/playlists/$historyPlaylistId/tracks") {
                parameter("limit", 50)
                parameter("offset", 0)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching emptyList<Track>()
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val items = obj["items"]?.jsonArray ?: return@runCatching emptyList<Track>()
            items.mapNotNull { item ->
                val itemObj = item.jsonObject
                val trackObj = itemObj["track"]?.jsonObject ?: return@mapNotNull null
                val addedAt = itemObj["added_at"]?.jsonPrimitive?.contentOrNull
                parseWebApiTrack(trackObj)?.copy(playedAt = addedAt)
            }
        }.getOrDefault(emptyList())

        if (webApiTracks.isNotEmpty()) return webApiTracks

        return runCatching {
            SpotifyMetadata.getPlaylist(historyPlaylistId).tracks.take(50).map { spTrack ->
                Track(
                    id = spTrack.id,
                    title = spTrack.name,
                    artist = spTrack.artists.joinToString(", "),
                    artistIds = spTrack.artistIds,
                    album = spTrack.album.orEmpty(),
                    durationMs = spTrack.durationMs,
                    image = spTrack.image
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun replaceHistoryPlaylistTracks(
        accessToken: String,
        clientToken: String? = null,
        historyPlaylistId: String,
        trackUris: List<String>
    ) {
        if (trackUris.isEmpty()) return
        runCatching {
            val payload = buildJsonObject {
                put("uris", buildJsonArray { trackUris.take(50).forEach { add(JsonPrimitive(it)) } })
            }
            client.put("https://api.spotify.com/v1/playlists/$historyPlaylistId/tracks") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        }
    }

    private suspend fun fetchRecentlyPlayed(
        accessToken: String,
        clientToken: String? = null,
        limit: Int = 30
    ): List<Track> {
        return runCatching {
            val res = client.get("https://api.spotify.com/v1/me/player/recently-played") {
                parameter("limit", limit.coerceIn(1, 50))
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (!clientToken.isNullOrBlank()) header("client-token", clientToken)
                header("app-platform", "WebPlayer")
                header(HttpHeaders.UserAgent, DESKTOP_USER_AGENT)
            }
            if (!res.status.isSuccess()) return@runCatching emptyList<Track>()
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val items = obj["items"]?.jsonArray ?: return@runCatching emptyList<Track>()
            items.mapNotNull { item ->
                val itemObj = item.jsonObject
                val trackObj = itemObj["track"]?.jsonObject ?: return@mapNotNull null
                val playedAt = itemObj["played_at"]?.jsonPrimitive?.contentOrNull
                parseWebApiTrack(trackObj)?.copy(playedAt = playedAt)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseWebApiTrack(trackObj: JsonObject): Track? {
        val id = trackObj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = trackObj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val durationMs = trackObj["duration_ms"]?.jsonPrimitive?.longOrNull

        val artistsArray = trackObj["artists"]?.jsonArray.orEmpty()
        val artistNames = artistsArray.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        val artistIds = artistsArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }

        val albumObj = trackObj["album"]?.jsonObject
        val albumName = albumObj?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val albumId = albumObj?.get("id")?.jsonPrimitive?.contentOrNull
        val image = albumObj?.get("images")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull

        val explicitPlayable = trackObj["is_playable"]?.jsonPrimitive?.booleanOrNull
        val restrictionReason = trackObj["restrictions"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
        val marketsArray = trackObj["available_markets"]?.jsonArray
        val isLocal = trackObj["is_local"]?.jsonPrimitive?.booleanOrNull == true
        val isPlayable = when {
            explicitPlayable != null -> explicitPlayable
            !restrictionReason.isNullOrBlank() -> false
            !isLocal && marketsArray != null && marketsArray.isEmpty() -> false
            else -> true
        }

        return Track(
            id = id,
            title = name,
            artist = artistNames.joinToString(", ").ifBlank { "Unknown Artist" },
            artistIds = artistIds,
            album = albumName,
            albumId = albumId,
            durationMs = durationMs,
            image = image,
            isPlayable = isPlayable
        )
    }
}
