package com.blek.blexsy.data

import com.blek.blexsy.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class ApiException(message: String, val statusCode: Int? = null) : Exception(message)

private const val TTL_CHART_MS = 24 * 60 * 60_000L // 1 day
private const val TTL_NEW_RELEASES_MS = 24 * 60 * 60_000L // 1 day
private const val TTL_GENRES_MS = 7 * 24 * 60 * 60_000L
private const val GENRES_KEY = "genres:all"
private const val TTL_RECENT_MS = 60_000L
private const val TTL_CREDITS_MS = 7 * 24 * 60 * 60_000L
private const val TTL_LYRICS_MS = 14 * 24 * 60 * 60_000L
private val RECENT_SNAPSHOT_LIMITS = listOf(10, 20, 50)

data class SearchResult(
    val tracks: List<Track>,
    val artists: List<ArtistSummary> = emptyList(),
    val hasMore: Boolean = false,
    val offset: Int = 0
)

class BlexsyApiClient(
    pbUrl: String = "https://pb.blek.my.id",
    private val cache: ApiCache? = null,
    val artistDb: ArtistDatabase? = null
) : LibraryApi {
    var pbUrl: String = pbUrl.trimEnd('/')
        set(value) { field = value.trimEnd('/') }
    // ponytail: extract PocketBase user ID from JWT or auth record; ceiling is guest/offline mode.
    var currentUserId: String? = null
    val userId: String?
        get() = currentUserId ?: token?.let { extractUserIdFromJwt(it) }
    var lastOtpId: String? = null

    override var token: String? = null
        set(value) {
            field = value
            if (value != null && value != "guest") {
                val parsedId = extractUserIdFromJwt(value)
                if (parsedId != null) currentUserId = parsedId
            } else {
                currentUserId = null
            }
        }
    var onUnauthorized: (() -> Unit)? = null

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "Blexsy/1.0 (Android)")
        }
    }

    private fun extractUserIdFromJwt(jwt: String): String? {
        return runCatching {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val b64 = parts[1].replace('-', '+').replace('_', '/')
            val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
            val bytes = try {
                java.util.Base64.getDecoder().decode(padded)
            } catch (_: Throwable) {
                android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
            }
            val jsonStr = String(bytes, Charsets.UTF_8)
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            obj["id"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun HttpRequestBuilder.applyAuth() {
        header(HttpHeaders.Accept, "application/json")
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun checkResponse(response: HttpResponse): String {
        if (response.status == HttpStatusCode.Unauthorized) {
            onUnauthorized?.invoke()
        }
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val errorMsg = try {
                val elem = json.parseToJsonElement(bodyText).jsonObject
                elem["message"]?.jsonPrimitive?.content
                    ?: elem["detail"]?.jsonPrimitive?.content
                    ?: "Request failed (${response.status.value})"
            } catch (_: Exception) {
                if (response.status.value >= 500) {
                    "Server tripped up. Give it another shot soon."
                } else {
                    "Server returned an invalid response."
                }
            }
            throw ApiException(errorMsg, response.status.value)
        }
        return bodyText
    }

    // ---------------------------------------------------------------------
    // Disk cache: fresh entries are returned without touching the network, and
    // stale entries are used as a fallback when the server is unreachable. This is
    // what makes a cold start / second visit to an album feel instant.
    // ---------------------------------------------------------------------

    private suspend fun cachedText(key: String, ttlMs: Long, fetch: suspend () -> String): String {
        val cached = cache?.read(key)
        val age = cached?.let { System.currentTimeMillis() - it.savedAt }
        if (cached != null && age != null && age in 0 until ttlMs) {
            return cached.body
        }
        return try {
            val fresh = fetch()
            cache?.write(key, fresh)
            fresh
        } catch (e: Exception) {
            cached?.body ?: throw e
        }
    }

    /** Snapshot helpers used by the home screen to paint before the network answers. */
    suspend fun chartSnapshot(limit: Int = 50): List<Track>? =
        cache?.read(chartKey(limit))?.let { entry ->
            runCatching { parseChart(entry.body) }.getOrNull()
        }

    suspend fun newReleasesSnapshot(limit: Int = 30): List<AlbumSummary>? =
        cache?.read("new_releases:dz:$limit")?.let { entry ->
            runCatching { json.decodeFromString(ListSerializer(AlbumSummary.serializer()), entry.body) }.getOrNull()
        }

    suspend fun genresSnapshot(): List<Genre>? =
        cache?.read(GENRES_KEY)?.let { entry ->
            runCatching { parseGenres(entry.body) }.getOrNull()
        }

    suspend fun recentSnapshot(limit: Int = 50): List<Track>? =
        cache?.read(recentKey(limit))?.let { entry ->
            runCatching { json.decodeFromString<List<Track>>(entry.body) }.getOrNull()
        }

    private fun chartKey(limit: Int) = "chart:$limit"

    private fun recentKey(limit: Int) = "recent:${token ?: "guest"}:$limit"

    private fun parseChart(text: String): List<Track> {
        val obj = json.parseToJsonElement(text).jsonObject
        val arr = obj["tracks"]?.jsonArray ?: return emptyList()
        return arr.map { json.decodeFromJsonElement(Track.serializer(), it) }
    }

    private fun parseGenres(text: String): List<Genre> =
        runCatching { json.decodeFromString(ListSerializer(Genre.serializer()), text) }.getOrDefault(emptyList())

    suspend fun health(): Boolean = try {
        client.get("$pbUrl/api/health").status.isSuccess()
    } catch (_: Exception) {
        false
    }

    suspend fun requestOtp(email: String, name: String? = null): JsonObject {
        val cleanEmail = email.trim()
        val randomPass = java.util.UUID.randomUUID().toString() + "Aa1!"
        // ponytail: pre-register user if not present; PocketBase OTP requires existing auth record.
        runCatching {
            client.post("$pbUrl/api/collections/users/records") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("email", cleanEmail)
                    put("password", randomPass)
                    put("passwordConfirm", randomPass)
                    put("name", name?.trim() ?: cleanEmail.substringBefore("@"))
                })
            }
        }
        val res = client.post("$pbUrl/api/collections/users/request-otp") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("email", cleanEmail)
            })
        }
        val text = checkResponse(res)
        val obj = json.parseToJsonElement(text).jsonObject
        lastOtpId = obj["otpId"]?.jsonPrimitive?.contentOrNull
        return obj
    }

    suspend fun verifyOtp(email: String, code: String): JsonObject {
        val otpId = lastOtpId ?: throw ApiException("Please request an OTP code first.", 400)
        val res = client.post("$pbUrl/api/collections/users/auth-with-otp") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("otpId", otpId)
                put("password", code.trim())
            })
        }
        val text = checkResponse(res)
        val parsed = json.parseToJsonElement(text).jsonObject
        val userRecord = parsed["record"]?.jsonObject ?: buildJsonObject {}
        val authToken = parsed["token"]?.jsonPrimitive?.contentOrNull
        if (authToken != null) {
            token = authToken
        }
        userRecord["id"]?.jsonPrimitive?.contentOrNull?.let {
            currentUserId = it
        }
        return buildJsonObject {
            authToken?.let { put("token", it) }
            put("user", userRecord)
            put("record", userRecord)
        }
    }

    suspend fun me(): JsonObject {
        val res = client.post("$pbUrl/api/collections/users/auth-refresh") { applyAuth() }
        val text = checkResponse(res)
        val parsed = json.parseToJsonElement(text).jsonObject
        val userRecord = parsed["record"]?.jsonObject ?: buildJsonObject {}
        parsed["token"]?.jsonPrimitive?.contentOrNull?.let { refreshed ->
            token = refreshed
        }
        userRecord["id"]?.jsonPrimitive?.contentOrNull?.let {
            currentUserId = it
        }
        return buildJsonObject {
            put("user", userRecord)
            put("record", userRecord)
            parsed["token"]?.let { put("token", it) }
        }
    }

    suspend fun chart(limit: Int = 50): List<Track> {
        val text = cachedText(chartKey(limit), TTL_CHART_MS) {
            val dzTracks = runCatching { DeezerService.getChartTracks(limit) }.getOrNull().orEmpty()
            val tracks = if (dzTracks.isNotEmpty()) dzTracks else {
                runCatching { InnerTubeService.getTrendingTracks(limit) }.getOrNull().orEmpty()
            }
            buildJsonObject {
                put("tracks", json.encodeToJsonElement(ListSerializer(Track.serializer()), tracks))
            }.toString()
        }
        val tracks = parseChart(text)
        return if (tracks.isNotEmpty()) tracks else {
            runCatching { DeezerService.getChartTracks(limit) }.getOrNull()
                ?: runCatching { InnerTubeService.getTrendingTracks(limit) }.getOrNull().orEmpty()
        }
    }

    suspend fun search(query: String, offset: Int = 0, limit: Int = 10): SearchResult = coroutineScope {
        val dzTracksDeferred = async { runCatching { DeezerService.searchTracks(query, limit = limit) }.getOrNull() }
        val ytTracksDeferred = async { runCatching { InnerTubeService.searchTracks(query, limit = limit) }.getOrNull() }
        val dzArtistsDeferred = async { runCatching { DeezerService.searchArtists(query, limit = 3) }.getOrNull() }
        val ytArtistsDeferred = async { runCatching { InnerTubeService.searchArtists(query, limit = 3) }.getOrNull() }

        val dzTracks = dzTracksDeferred.await().orEmpty()
        val ytTracks = ytTracksDeferred.await().orEmpty()
        val tracks = if (dzTracks.isNotEmpty()) dzTracks else ytTracks

        val dzArtists = dzArtistsDeferred.await().orEmpty()
        val ytArtists = ytArtistsDeferred.await().orEmpty()
        val artists = if (dzArtists.isNotEmpty()) dzArtists else ytArtists

        SearchResult(
            tracks = tracks,
            artists = artists,
            hasMore = false,
            offset = offset + tracks.size
        )
    }

    suspend fun newReleases(limit: Int = 30): List<AlbumSummary> {
        val key = "new_releases:dz:$limit"
        val text = cachedText(key, TTL_NEW_RELEASES_MS) {
            val albums = runCatching { DeezerService.getNewReleases(limit) }.getOrNull().orEmpty()
                .ifEmpty { runCatching { InnerTubeService.getNewReleaseAlbums(limit) }.getOrNull().orEmpty() }
            json.encodeToString(ListSerializer(AlbumSummary.serializer()), albums)
        }
        val cached = runCatching {
            json.decodeFromString(ListSerializer(AlbumSummary.serializer()), text)
        }.getOrNull()
        if (!cached.isNullOrEmpty()) return cached
        return runCatching { DeezerService.getNewReleases(limit) }.getOrNull()
            ?: runCatching { InnerTubeService.getNewReleaseAlbums(limit) }.getOrNull().orEmpty()
    }

    private suspend fun scrapeSpotifyEmbed(playlistId: String, limit: Int): List<Track> {
        val res = client.get("https://open.spotify.com/embed/playlist/$playlistId") {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }
        if (!res.status.isSuccess()) return emptyList()
        val html = res.bodyAsText()
        val match = Regex("""<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>""").find(html) ?: return emptyList()
        val root = json.parseToJsonElement(match.groupValues[1]).jsonObject
        val entity = root["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("state")?.jsonObject
            ?.get("data")?.jsonObject?.get("entity")?.jsonObject ?: return emptyList()
        val playlistCover = entity["coverArt"]?.jsonObject?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: entity["visualIdentity"]?.jsonObject?.get("image")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        val trackList = entity["trackList"]?.jsonArray ?: return emptyList()

        val rawTracks = trackList.take(limit).mapIndexed { index, el ->
            val item = el.jsonObject
            val uri = item["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val id = uri.substringAfterLast(":", uri)
            val title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val artist = item["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty().replace('\u00A0', ' ')
            val durationMs = item["duration"]?.jsonPrimitive?.longOrNull
            val previewUrl = item["audioPreview"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

            Track(
                id = id,
                title = title,
                artist = artist,
                album = title,
                image = playlistCover,
                durationMs = durationMs,
                audioUrl = previewUrl,
                spotifyUrl = "https://open.spotify.com/track/$id",
                rank = index + 1,
                rankStatus = "same"
            )
        }

        return rawTracks
    }

    suspend fun album(id: String): AlbumDetail {
        if (id.startsWith("dz_") || id.all { it.isDigit() }) {
            val dzAlbum = runCatching { DeezerService.getAlbumDetail(id) }.getOrNull()
            if (dzAlbum != null) return dzAlbum
        }
        val ytAlbum = runCatching { InnerTubeService.getAlbumDetail(id) }.getOrNull()
        if (ytAlbum != null) return ytAlbum
        val dzAlbum = runCatching { DeezerService.getAlbumDetail(id) }.getOrNull()
        if (dzAlbum != null) return dzAlbum
        return scrapeSpotifyAlbumEmbed(id) ?: throw ApiException("Album not found")
    }

    private suspend fun scrapeSpotifyAlbumEmbed(albumId: String): AlbumDetail? = runCatching {
        val res = client.get("https://open.spotify.com/embed/album/$albumId") {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }
        if (!res.status.isSuccess()) return@runCatching null
        val html = res.bodyAsText()
        val match = Regex("""<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>""").find(html) ?: return@runCatching null
        val root = json.parseToJsonElement(match.groupValues[1]).jsonObject
        val entity = root["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("state")?.jsonObject
            ?.get("data")?.jsonObject?.get("entity")?.jsonObject ?: return@runCatching null
        val name = entity["title"]?.jsonPrimitive?.contentOrNull ?: entity["name"]?.jsonPrimitive?.contentOrNull ?: "Album"
        val artist = entity["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty().replace('\u00A0', ' ')
        val cover = entity["coverArt"]?.jsonObject?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: entity["visualIdentity"]?.jsonObject?.get("image")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        val trackList = entity["trackList"]?.jsonArray ?: return@runCatching null
        val tracks = trackList.mapIndexed { index, el ->
            val item = el.jsonObject
            val uri = item["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val trackId = uri.substringAfterLast(":", uri)
            val title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val trackArtist = item["subtitle"]?.jsonPrimitive?.contentOrNull?.replace('\u00A0', ' ')?.ifBlank { artist } ?: artist
            val durationMs = item["duration"]?.jsonPrimitive?.longOrNull
            val preview = item["audioPreview"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            Track(
                id = trackId,
                title = title,
                artist = trackArtist,
                album = name,
                albumId = albumId,
                image = cover,
                durationMs = durationMs,
                audioUrl = preview,
                spotifyUrl = "https://open.spotify.com/track/$trackId",
                rank = index + 1
            )
        }
        AlbumDetail(
            id = albumId,
            name = name,
            artist = artist,
            image = cover,
            tracks = tracks
        )
    }.getOrNull()

    suspend fun topArtists(limit: Int = 20): List<ArtistSummary> {
        val ytArtists = runCatching { InnerTubeService.getTopArtists(limit) }.getOrNull()
        if (!ytArtists.isNullOrEmpty()) return ytArtists
        return emptyList()
    }

    suspend fun searchArtists(query: String, limit: Int = 10): List<ArtistSummary> {
        val dzArtists = runCatching { DeezerService.searchArtists(query, limit) }.getOrNull().orEmpty()
        if (dzArtists.isNotEmpty()) return dzArtists
        val ytArtists = runCatching { InnerTubeService.searchArtists(query, limit) }.getOrNull()
        if (!ytArtists.isNullOrEmpty()) return ytArtists
        return emptyList()
    }

    suspend fun cachedArtist(id: String): ArtistDetail? = artistDb?.get(id)

    suspend fun artist(id: String): ArtistDetail {
        if (id.startsWith("dz_") || id.all { it.isDigit() }) {
            val dzArtist = runCatching { DeezerService.getArtistDetail(id) }.getOrNull()
            if (dzArtist != null) {
                artistDb?.put(dzArtist)
                return dzArtist
            }
        }
        val ytArtist = runCatching { InnerTubeService.getArtistDetail(id) }.getOrNull()
        if (ytArtist != null) {
            artistDb?.put(ytArtist)
            return ytArtist
        }
        val dzArtist = runCatching { DeezerService.getArtistDetail(id) }.getOrNull()
        if (dzArtist != null) {
            artistDb?.put(dzArtist)
            return dzArtist
        }
        return artistDb?.get(id) ?: throw ApiException("Failed to load artist", 0)
    }

    suspend fun genres(): List<Genre> {
        val cached = cache?.read(GENRES_KEY)?.let { entry ->
            val age = System.currentTimeMillis() - entry.savedAt
            if (age in 0 until TTL_GENRES_MS) parseGenres(entry.body) else null
        }
        if (!cached.isNullOrEmpty()) return cached
        val fetched = runCatching { InnerTubeService.getGenres() }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) {
            runCatching {
                cache?.write(GENRES_KEY, json.encodeToString(ListSerializer(Genre.serializer()), fetched))
            }
        }
        return fetched
    }

    suspend fun genrePlaylists(id: String): List<GenrePlaylist> {
        return runCatching { InnerTubeService.getGenrePlaylists(id, id) }.getOrNull().orEmpty()
    }

    suspend fun spotifyPlaylistDetail(id: String, fetchAll: Boolean = true): Pair<String, List<Track>> {
        val ytRes = runCatching { InnerTubeService.getPlaylistTracks(id) }.getOrNull()?.takeIf { it.second.isNotEmpty() }
        if (ytRes != null) return ytRes
        val scraped = runCatching { scrapeSpotifyEmbed(id, if (fetchAll) 100 else 50) }.getOrNull().orEmpty()
        return "Playlist" to scraped
    }

    suspend fun playlist(id: String): List<Track> = spotifyPlaylistDetail(id).second

    private fun cleanForLyricsSearch(title: String): String {
        var s = title
        val patterns = listOf(
            Regex("""\s*[(\[]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*[(\[]\s*(official\s*)?(music\s*)?(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)\s*[)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s*[(\[]\s*official\s*[)\]]""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { s = it.replace(s, " ") }
        return s.replace(Regex("""\s+"""), " ").trim().trimEnd(',', '-', '–', '—').trim().ifBlank { title.trim() }
    }

    private fun cleanTitleForMatch(s: String): String {
        return s.replace(Regex("""[\(\[].*?[\)\]]"""), "")
            .replace(Regex("""\s*-\s*(live|remaster|remastered|radio edit|bonus|feat|acoustic|version|instrumental).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[^a-zA-Z0-9]"""), "")
            .lowercase()
    }

    private fun isTitleMatch(target: String, candidate: String): Boolean {
        val t1 = cleanTitleForMatch(target)
        val t2 = cleanTitleForMatch(candidate)
        if (t1.isEmpty() || t2.isEmpty()) return false
        return t1 == t2 || t1.contains(t2) || t2.contains(t1)
    }

    private fun isArtistSimilar(target: String, candidate: String): Boolean {
        val a1 = cleanTitleForMatch(target)
        val a2 = cleanTitleForMatch(candidate)
        if (a1.isEmpty() || a2.isEmpty()) return true
        if (a1 == a2 || a1 in a2 || a2 in a1) return true
        val t1 = target.lowercase().split(',', '&', '/', ' ').map { it.trim() }.filter { it.length > 2 }
        val t2 = candidate.lowercase().split(',', '&', '/', ' ').map { it.trim() }.filter { it.length > 2 }
        return t1.any { it in t2 } || t2.any { it in t1 }
    }

    suspend fun lyrics(track: Track): LyricsResult {
        val cacheKey = "lyrics:${track.id.ifBlank { "${track.artist}:${track.title}" }}"
        return try {
            val jsonText = cachedText(cacheKey, TTL_LYRICS_MS) {
                val res = fetchLyricsDirect(track)
                if (res.lyrics.isNotBlank()) {
                    json.encodeToString(LyricsResult.serializer(), res)
                } else {
                    throw ApiException("Lyrics not found")
                }
            }
            json.decodeFromString(LyricsResult.serializer(), jsonText)
        } catch (_: Exception) {
            fetchLyricsDirect(track)
        }
    }

    // ponytail: BitChord-aligned lyrics engine racing syllable-synced providers. Ceiling: 4 parallel jobs.
    private suspend fun fetchLyricsDirect(track: Track): LyricsResult = coroutineScope {
        val rawTitle = track.displayTitle.trim()
        val title = cleanForLyricsSearch(rawTitle)
        val artist = track.artist.removeSuffix(" - Topic").trim()
        val trackSec = ((track.durationMs ?: 0L) / 1000L).takeIf { it > 0 }

        val biniDeferred = async { fetchBiniLyrics(title, artist, trackSec) }
        val lrcRedDeferred = async { fetchLrcRed(title, artist, trackSec) }
        val betterDeferred = async { fetchBetterLyrics(title, artist, trackSec) }
        val lyricsPlusDeferred = async { fetchLyricsPlus(title, artist) }

        val wordSources = listOf(biniDeferred, lrcRedDeferred, betterDeferred, lyricsPlusDeferred)
        var fallbackLineSynced: LyricsResult? = null

        for (job in wordSources) {
            val result = job.await() ?: continue
            if (result.hasWordSync) {
                wordSources.forEach { it.cancel() }
                return@coroutineScope result
            }
            if (fallbackLineSynced == null && result.isSynced) {
                fallbackLineSynced = result
            }
        }

        if (fallbackLineSynced != null) {
            return@coroutineScope fallbackLineSynced
        }

        fetchFallbackLyrics(track, title, artist, trackSec)
    }

    private suspend fun fetchBiniLyrics(title: String, artist: String, trackSec: Long?): LyricsResult? {
        try {
            val res = client.get("https://lyrics-api.binimum.org/") {
                url {
                    parameters.append("track", title)
                    if (artist.isNotBlank()) parameters.append("artist", artist)
                    if (trackSec != null) parameters.append("duration", trackSec.toString())
                }
                header(HttpHeaders.UserAgent, "Blexsy/1.0 (+https://blek.my.id)")
                timeout { requestTimeoutMillis = 4000 }
            }
            if (!res.status.isSuccess()) return null
            val body = res.bodyAsText()
            val obj = json.parseToJsonElement(body).jsonObject
            val results = obj["results"]?.jsonArray ?: return null

            val validHits = results.mapNotNull { it as? JsonObject }.filter { hit ->
                val hitArtist = hit["artist_name"]?.jsonPrimitive?.content.orEmpty()
                isArtistSimilar(artist, hitArtist)
            }.sortedWith(
                compareByDescending<JsonObject> {
                    it["timing_type"]?.jsonPrimitive?.content == "word"
                }.thenBy {
                    val d = it["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                    if (trackSec != null && d > 0) kotlin.math.abs(d - trackSec.toDouble()) else 0.0
                }
            )

            for (hit in validHits.take(2)) {
                val lyricsUrl = hit["lyricsUrl"]?.jsonPrimitive?.content
                if (!lyricsUrl.isNullOrBlank()) {
                    val docRes = client.get(lyricsUrl) { timeout { requestTimeoutMillis = 4000 } }
                    if (docRes.status.isSuccess()) {
                        val text = docRes.bodyAsText().trim()
                        if (text.isNotBlank()) return LyricsResult(lyrics = text, source = "BiniLyrics")
                    }
                }
                val isrc = hit["isrc"]?.jsonPrimitive?.content ?: hit["id"]?.jsonPrimitive?.content
                if (!isrc.isNullOrBlank()) {
                    for (ext in listOf("ttml", "lrc")) {
                        val docRes = client.get("https://lrc.red/s/$isrc.$ext") { timeout { requestTimeoutMillis = 3500 } }
                        if (docRes.status.isSuccess()) {
                            val text = docRes.bodyAsText().trim()
                            if (text.isNotBlank()) return LyricsResult(lyrics = text, source = "BiniLyrics")
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun fetchLrcRed(title: String, artist: String, trackSec: Long?): LyricsResult? {
        try {
            val query = if (artist.isNotBlank()) "$title $artist" else title
            val res = client.get("https://lrc.red/search.json") {
                url { parameters.append("q", query) }
                header(HttpHeaders.UserAgent, "Blexsy/1.0 (+https://blek.my.id)")
                timeout { requestTimeoutMillis = 4000 }
            }
            if (!res.status.isSuccess()) return null
            val body = res.bodyAsText()
            val payload = json.parseToJsonElement(body).jsonObject
            val hits = payload["hits"]?.jsonArray ?: return null

            for (hit in hits.take(5).mapNotNull { it as? JsonObject }) {
                val hitTitle = hit["title"]?.jsonPrimitive?.content ?: hit["name"]?.jsonPrimitive?.content.orEmpty()
                val hitArtist = hit["artist"]?.jsonPrimitive?.content.orEmpty()
                val hitDuration = hit["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                if (trackSec != null && hitDuration > 0 && kotlin.math.abs(hitDuration - trackSec.toDouble()) > 8.0) {
                    continue
                }
                if (!isTitleMatch(title, hitTitle) || !isArtistSimilar(artist, hitArtist)) {
                    continue
                }
                val isrc = hit["isrc"]?.jsonPrimitive?.content
                if (!isrc.isNullOrBlank()) {
                    for (ext in listOf("ttml", "lrc")) {
                        val lrcRes = client.get("https://lrc.red/s/$isrc.$ext") {
                            header(HttpHeaders.UserAgent, "Blexsy/1.0 (+https://blek.my.id)")
                            timeout { requestTimeoutMillis = 3500 }
                        }
                        if (lrcRes.status.isSuccess()) {
                            val text = lrcRes.bodyAsText().trim()
                            if (text.isNotBlank()) return LyricsResult(lyrics = text, source = "lrc.red")
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun fetchBetterLyrics(title: String, artist: String, trackSec: Long?): LyricsResult? {
        for ((endpoint, provName) in listOf(
            "https://lyrics-api.boidu.dev/getLyrics" to "BetterLyrics",
            "https://lyrics-api.boidu.dev/qq/getLyrics" to "BetterLyrics Portato"
        )) {
            try {
                val res = client.get(endpoint) {
                    url {
                        parameters.append("s", title)
                        if (artist.isNotBlank()) parameters.append("a", artist)
                        if (trackSec != null) parameters.append("d", trackSec.toString())
                    }
                    timeout { requestTimeoutMillis = 4000 }
                }
                if (res.status.isSuccess()) {
                    val text = res.bodyAsText().trim()
                    if (text.isNotBlank() && !text.startsWith("<!DOCTYPE") && !text.contains("error", ignoreCase = true)) {
                        val parsedText = try {
                            val jsonElem = json.parseToJsonElement(text)
                            if (jsonElem is JsonObject) {
                                jsonElem["lyrics"]?.jsonPrimitive?.content ?: jsonElem["ttml"]?.jsonPrimitive?.content
                            } else text
                        } catch (_: Exception) { text }
                        if (!parsedText.isNullOrBlank()) {
                            return LyricsResult(lyrics = parsedText, source = provName)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun fetchLyricsPlus(title: String, artist: String): LyricsResult? {
        val mirrors = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app"
        )
        for (mirror in mirrors) {
            try {
                val res = client.get("$mirror/v2/lyrics/get") {
                    url {
                        parameters.append("title", title)
                        if (artist.isNotBlank()) parameters.append("artist", artist)
                    }
                    timeout { requestTimeoutMillis = 3500 }
                }
                if (res.status.isSuccess()) {
                    val body = res.bodyAsText()
                    val obj = json.parseToJsonElement(body).jsonObject
                    val lyricsArr = obj["lyrics"]?.jsonArray
                    if (lyricsArr != null && lyricsArr.isNotEmpty()) {
                        val sb = StringBuilder()
                        for (line in lyricsArr) {
                            val lo = line.jsonObject
                            val time = lo["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                            val textLine = lo["text"]?.jsonPrimitive?.content.orEmpty()
                            val syllabus = lo["syllabus"]?.jsonArray
                            if (syllabus != null && syllabus.isNotEmpty()) {
                                val m = (time / 60000) % 60
                                val s = (time / 1000) % 60
                                val ms = (time % 1000) / 10
                                sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]", m, s, ms))
                                for (sy in syllabus) {
                                    val so = sy.jsonObject
                                    val st = so["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: time
                                    val stx = so["text"]?.jsonPrimitive?.content.orEmpty()
                                    val sm = (st / 60000) % 60
                                    val ss = (st / 1000) % 60
                                    val sms = (st % 1000) / 10
                                    sb.append(String.format(java.util.Locale.US, "<%02d:%02d.%02d>%s", sm, ss, sms, stx))
                                }
                                sb.append('\n')
                            } else if (textLine.isNotBlank()) {
                                val m = (time / 60000) % 60
                                val s = (time / 1000) % 60
                                val ms = (time % 1000) / 10
                                sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d] %s\n", m, s, ms, textLine))
                            }
                        }
                        val converted = sb.toString().trim()
                        if (converted.isNotBlank()) {
                            return LyricsResult(lyrics = converted, source = "LyricsPlus")
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun fetchFallbackLyrics(
        track: Track,
        title: String,
        artist: String,
        trackSec: Long?
    ): LyricsResult {
        // 0. LRCLIB (open-source synced lyrics direct)
        val lrclib = InnerTubeService.getLyrics(title, artist, track.durationMs)
        if (lrclib != null && lrclib.lyrics.isNotBlank()) {
            return lrclib
        }

        // 1. Unison
        try {
            val res = client.get("https://unison.boidu.dev/lyrics") {
                url {
                    parameters.append("song", title)
                    if (artist.isNotBlank()) parameters.append("artist", artist)
                    if (trackSec != null) parameters.append("duration", trackSec.toString())
                }
                timeout { requestTimeoutMillis = 4000 }
            }
            if (res.status.isSuccess()) {
                val body = res.bodyAsText()
                val obj = json.parseToJsonElement(body).jsonObject
                val dataObj = obj["data"]?.jsonObject
                val lyricsText = dataObj?.get("lyrics")?.jsonPrimitive?.content
                if (!lyricsText.isNullOrBlank()) {
                    return LyricsResult(lyrics = lyricsText, source = "Unison")
                }
            }
        } catch (_: Exception) {}

        // 2. SimpMusic (via YouTube videoId)
        val ytid = track.youtubeId
        if (!ytid.isNullOrBlank()) {
            try {
                val res = client.get("https://api-lyrics.simpmusic.org/v1/$ytid") {
                    timeout { requestTimeoutMillis = 4000 }
                }
                if (res.status.isSuccess()) {
                    val body = res.bodyAsText()
                    val obj = json.parseToJsonElement(body).jsonObject
                    val arr = obj["data"]?.jsonArray
                    val bestItem = arr?.mapNotNull { it as? JsonObject }?.minByOrNull {
                        val d = it["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        kotlin.math.abs(d - (trackSec ?: 0L))
                    }
                    val richSync = bestItem?.get("richSyncLyrics")?.jsonPrimitive?.content
                    val synced = bestItem?.get("syncedLyrics")?.jsonPrimitive?.content
                    val result = richSync?.takeIf { it.isNotBlank() } ?: synced?.takeIf { it.isNotBlank() }
                    if (!result.isNullOrBlank()) {
                        return LyricsResult(lyrics = result, source = "SimpMusic")
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. KuGou (Line-synced LRC)
        try {
            val keyword = if (artist.isNotBlank()) "$title - $artist" else title
            val songRes = client.get("https://mobileservice.kugou.com/api/v3/search/song") {
                url {
                    parameters.append("version", "9108")
                    parameters.append("plat", "0")
                    parameters.append("pagesize", "5")
                    parameters.append("showtype", "0")
                    parameters.append("keyword", keyword)
                }
                timeout { requestTimeoutMillis = 4000 }
            }
            if (songRes.status.isSuccess()) {
                val songObj = json.parseToJsonElement(songRes.bodyAsText()).jsonObject
                val infoArr = songObj["data"]?.jsonObject?.get("info")?.jsonArray
                val hashes = infoArr?.mapNotNull { it as? JsonObject }
                    ?.filter {
                        if (trackSec == null) true
                        else {
                            val d = it["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            kotlin.math.abs(d - trackSec.toInt()) <= 8
                        }
                    }
                    ?.sortedBy {
                        val d = it["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        if (trackSec != null) kotlin.math.abs(d - trackSec.toInt()) else 0
                    }
                    ?.mapNotNull { it["hash"]?.jsonPrimitive?.content }
                    .orEmpty()

                for (hash in hashes.take(2)) {
                    val lrcSearchRes = client.get("https://lyrics.kugou.com/search") {
                        url {
                            parameters.append("ver", "1")
                            parameters.append("man", "yes")
                            parameters.append("client", "pc")
                            parameters.append("hash", hash)
                        }
                        timeout { requestTimeoutMillis = 4000 }
                    }
                    if (lrcSearchRes.status.isSuccess()) {
                        val searchObj = json.parseToJsonElement(lrcSearchRes.bodyAsText()).jsonObject
                        val candidate = searchObj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                        val candId = candidate?.get("id")?.jsonPrimitive?.content
                        val accessKey = candidate?.get("accesskey")?.jsonPrimitive?.content
                        if (!candId.isNullOrBlank() && !accessKey.isNullOrBlank()) {
                            val dlRes = client.get("https://lyrics.kugou.com/download") {
                                url {
                                    parameters.append("fmt", "lrc")
                                    parameters.append("charset", "utf8")
                                    parameters.append("client", "pc")
                                    parameters.append("ver", "1")
                                    parameters.append("id", candId)
                                    parameters.append("accesskey", accessKey)
                                }
                                timeout { requestTimeoutMillis = 4000 }
                            }
                            if (dlRes.status.isSuccess()) {
                                val dlObj = json.parseToJsonElement(dlRes.bodyAsText()).jsonObject
                                val b64 = dlObj["content"]?.jsonPrimitive?.content
                                if (!b64.isNullOrBlank()) {
                                    val bytes = try {
                                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                    } catch (_: Throwable) {
                                        java.util.Base64.getDecoder().decode(b64)
                                    }
                                    val decoded = String(bytes, Charsets.UTF_8).trim()
                                    if (decoded.isNotBlank() && !decoded.contains("纯音乐")) {
                                        val cleaned = stripKugouCredits(decoded)
                                        if (cleaned.isNotBlank()) {
                                            return LyricsResult(lyrics = cleaned, source = "KuGou")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 4. LRCLIB (Line-synced fallback)
        try {
            val res = client.get("https://lrclib.net/api/get") {
                url {
                    parameters.append("track_name", title)
                    if (artist.isNotBlank()) parameters.append("artist_name", artist)
                }
                header(HttpHeaders.UserAgent, "Blexsy/1.0 (+https://blek.my.id)")
                timeout { requestTimeoutMillis = 4000 }
            }
            if (res.status.isSuccess()) {
                val body = res.bodyAsText()
                val obj = json.parseToJsonElement(body).jsonObject
                val synced = obj["syncedLyrics"]?.jsonPrimitive?.content
                val plain = obj["plainLyrics"]?.jsonPrimitive?.content
                val lyricsText = synced?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() } ?: ""
                if (lyricsText.isNotBlank()) {
                    return LyricsResult(lyrics = lyricsText, source = "LRCLIB")
                }
            }
            try {
                val sres = client.get("https://lrclib.net/api/search") {
                    url {
                        parameters.append("track_name", title)
                        if (artist.isNotBlank()) parameters.append("artist_name", artist)
                    }
                    header(HttpHeaders.UserAgent, "Blexsy/1.0 (+https://blek.my.id)")
                    timeout { requestTimeoutMillis = 4000 }
                }
                if (sres.status.isSuccess()) {
                    val sbody = sres.bodyAsText()
                    val arr = json.parseToJsonElement(sbody).jsonArray
                    for (item in arr) {
                        val obj2 = item.jsonObject
                        val synced = obj2["syncedLyrics"]?.jsonPrimitive?.content
                        val plain = obj2["plainLyrics"]?.jsonPrimitive?.content
                        val lyricsText = synced?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() } ?: ""
                        if (lyricsText.isNotBlank()) {
                            return LyricsResult(lyrics = lyricsText, source = "LRCLIB")
                        }
                    }
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        // 5. Genius (Plain text fallback)
        try {
            val q = if (artist.isNotBlank()) "$title $artist" else title
            val res = client.get("https://genius.com/api/search/multi") {
                url { parameters.append("q", q) }
                header(HttpHeaders.UserAgent, "Mozilla/5.0")
                timeout { requestTimeoutMillis = 4000 }
            }
            if (res.status.isSuccess()) {
                val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
                val sections = root["response"]?.jsonObject?.get("sections")?.jsonArray
                val songSection = sections?.mapNotNull { it as? JsonObject }?.firstOrNull {
                    it["type"]?.jsonPrimitive?.content == "song"
                }
                val hits = songSection?.get("hits")?.jsonArray
                val songPath = hits?.firstOrNull()?.jsonObject?.get("result")?.jsonObject?.get("path")?.jsonPrimitive?.content
                if (!songPath.isNullOrBlank()) {
                    val htmlRes = client.get("https://genius.com$songPath") {
                        header(HttpHeaders.UserAgent, "Mozilla/5.0")
                        timeout { requestTimeoutMillis = 4000 }
                    }
                    if (htmlRes.status.isSuccess()) {
                        val html = htmlRes.bodyAsText()
                        val containerRegex = Regex("""<div[^>]*data-lyrics-container="true"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                        val containers = containerRegex.findAll(html).map { it.groupValues[1] }.toList()
                        if (containers.isNotEmpty()) {
                            val plainLyrics = containers.joinToString("\n")
                                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                                .replace(Regex("""<[^>]+>"""), "")
                                .replace("&amp;", "&")
                                .replace("&quot;", "\"")
                                .replace("&#39;", "'")
                                .replace("&apos;", "'")
                                .trim()
                            if (plainLyrics.isNotBlank()) {
                                return LyricsResult(lyrics = plainLyrics, source = "Genius")
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return LyricsResult(lyrics = "", source = "Blexsy")
    }

    private fun stripKugouCredits(lrc: String): String {
        val lines = lrc.lines().filter { it.trim().startsWith("[") }
        if (lines.isEmpty()) return lrc
        val creditRegex = Regex(""".+\][^[]+[:：].+""")
        var headCut = 0
        for (i in minOf(30, lines.lastIndex) downTo 0) {
            if (creditRegex.matches(lines[i])) {
                headCut = i + 1
                break
            }
        }
        val body = lines.drop(headCut)
        var tailCut = 0
        for (i in 0..minOf(30, body.lastIndex)) {
            if (creditRegex.matches(body[body.lastIndex - i])) {
                tailCut = i + 1
                break
            }
        }
        return body.dropLast(tailCut).joinToString("\n")
    }

    suspend fun streamUrl(track: Track, refresh: Boolean = false): String {
        return InnerTubeService.resolveTrackStream(null, track) ?: throw ApiException("Audio stream not found")
    }

    private fun parseIsoToMillis(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            val clean = iso.replace(" ", "T")
            val pattern = if (clean.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'"
            val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            sdf.parse(clean)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    override suspend fun getFavorites(): List<Track> {
        if (token == null || token == "guest") return emptyList()
        val res = client.get("$pbUrl/api/collections/favorites/records") {
            applyAuth()
            parameter("perPage", 500)
            parameter("sort", "-created")
        }
        val text = checkResponse(res)
        val obj = json.parseToJsonElement(text).jsonObject
        val items = obj["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            item.jsonObject["track_data"]?.let {
                runCatching { json.decodeFromJsonElement(Track.serializer(), it) }.getOrNull()
            }
        }
    }

    override suspend fun toggleFavorite(track: Track) {
        if (token == null || token == "guest") return
        val escapedId = track.id.replace("'", "\\'")
        val findRes = client.get("$pbUrl/api/collections/favorites/records") {
            applyAuth()
            parameter("filter", "track_id='$escapedId'")
            parameter("perPage", 1)
        }
        val findText = checkResponse(findRes)
        val findObj = json.parseToJsonElement(findText).jsonObject
        val existing = findObj["items"]?.jsonArray?.firstOrNull()?.jsonObject
        if (existing != null) {
            val recordId = existing["id"]?.jsonPrimitive?.content ?: return
            val delRes = client.delete("$pbUrl/api/collections/favorites/records/$recordId") { applyAuth() }
            checkResponse(delRes)
        } else {
            val uid = userId
            val postRes = client.post("$pbUrl/api/collections/favorites/records") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    uid?.let { put("user", it) }
                    put("track_id", track.id)
                    put("track_data", json.encodeToJsonElement(Track.serializer(), track))
                })
            }
            checkResponse(postRes)
        }
    }

    override suspend fun getRecent(limit: Int): List<Track> {
        if (token == null || token == "guest") return emptyList()
        val text = cachedText(recentKey(limit), TTL_RECENT_MS) {
            checkResponse(client.get("$pbUrl/api/collections/recents/records") {
                applyAuth()
                parameter("perPage", limit)
                parameter("sort", "-played_at")
            })
        }
        val obj = json.parseToJsonElement(text).jsonObject
        val items = obj["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val rec = item.jsonObject
            val trackData = rec["track_data"]?.let {
                runCatching { json.decodeFromJsonElement(Track.serializer(), it) }.getOrNull()
            } ?: return@mapNotNull null
            val playedAt = rec["played_at"]?.jsonPrimitive?.contentOrNull ?: trackData.playedAt
            val playCount = rec["play_count"]?.jsonPrimitive?.intOrNull ?: trackData.playCount
            trackData.copy(playedAt = playedAt, playCount = playCount)
        }
    }

    override suspend fun recordRecent(track: Track) {
        if (token == null || token == "guest") return
        val stampedTrack = if (track.playedAt.isNullOrBlank()) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            track.copy(playedAt = sdf.format(java.util.Date()))
        } else track

        val escapedId = stampedTrack.id.replace("'", "\\'")
        val findRes = client.get("$pbUrl/api/collections/recents/records") {
            applyAuth()
            parameter("filter", "track_id='$escapedId'")
            parameter("perPage", 1)
        }
        val findText = checkResponse(findRes)
        val findObj = json.parseToJsonElement(findText).jsonObject
        val existing = findObj["items"]?.jsonArray?.firstOrNull()?.jsonObject

        if (existing != null) {
            val recordId = existing["id"]?.jsonPrimitive?.content ?: return
            val prevCount = existing["play_count"]?.jsonPrimitive?.intOrNull ?: 1
            val patchRes = client.patch("$pbUrl/api/collections/recents/records/$recordId") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("track_data", json.encodeToJsonElement(Track.serializer(), stampedTrack))
                    put("play_count", prevCount + 1)
                    put("played_at", stampedTrack.playedAt ?: "")
                })
            }
            checkResponse(patchRes)
        } else {
            val uid = userId
            val postRes = client.post("$pbUrl/api/collections/recents/records") {
                applyAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    uid?.let { put("user", it) }
                    put("track_id", stampedTrack.id)
                    put("track_data", json.encodeToJsonElement(Track.serializer(), stampedTrack))
                    put("play_count", 1)
                    put("played_at", stampedTrack.playedAt ?: "")
                })
            }
            checkResponse(postRes)
        }

        // ponytail: atomically increment denormalized total_spins counter on users record.
        userId?.let { uid ->
            runCatching {
                client.patch("$pbUrl/api/collections/users/records/$uid") {
                    applyAuth()
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("total_spins+", 1)
                    })
                }
            }
        }

        // Keep the offline snapshots in sync with what was just played, otherwise
        // opening the Profile tab within the 60s TTL would omit the new track.
        updateRecentSnapshots(stampedTrack)
    }

    private suspend fun updateRecentSnapshots(track: Track) {
        val store = cache ?: return
        for (limit in RECENT_SNAPSHOT_LIMITS) {
            val key = recentKey(limit)
            val entry = store.read(key) ?: continue
            val list = runCatching {
                json.decodeFromString<List<Track>>(entry.body)
            }.getOrNull() ?: continue
            val updated = (listOf(track) + list.filterNot { it.id == track.id }).take(limit)
            store.write(key, json.encodeToString(updated))
        }
    }

    override suspend fun getPlaylists(): List<Playlist> {
        if (token == null || token == "guest") return emptyList()
        // ponytail: sort client-side; PocketBase collections imported without autodate fail on sort=-updated.
        val res = client.get("$pbUrl/api/collections/playlists/records") {
            applyAuth()
            parameter("perPage", 200)
        }
        val text = checkResponse(res)
        val obj = json.parseToJsonElement(text).jsonObject
        val items = obj["items"]?.jsonArray ?: return emptyList()
        return items.map { item ->
            val rec = item.jsonObject
            val tracks = rec["tracks"]?.let {
                runCatching { json.decodeFromJsonElement(ListSerializer(Track.serializer()), it) }.getOrNull()
            } ?: emptyList()
            val updatedIso = rec["updated"]?.jsonPrimitive?.contentOrNull
                ?: rec["created"]?.jsonPrimitive?.contentOrNull
                ?: ""
            Playlist(
                id = rec["id"]?.jsonPrimitive?.content ?: "",
                name = rec["name"]?.jsonPrimitive?.content ?: "Playlist",
                tracks = tracks,
                updatedAt = parseIsoToMillis(updatedIso)
            )
        }.sortedByDescending { it.updatedAt }
    }

    override suspend fun createPlaylist(name: String, track: Track?): Playlist =
        createPlaylistWithTracks(name, if (track != null) listOf(track) else emptyList())

    override suspend fun createPlaylistWithTracks(name: String, tracks: List<Track>): Playlist {
        val uid = userId
        val res = client.post("$pbUrl/api/collections/playlists/records") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                uid?.let { put("user", it) }
                put("name", name)
                put("tracks", json.encodeToJsonElement(ListSerializer(Track.serializer()), tracks))
            })
        }
        val text = checkResponse(res)
        val rec = json.parseToJsonElement(text).jsonObject
        val returnedTracks = rec["tracks"]?.let {
            runCatching { json.decodeFromJsonElement(ListSerializer(Track.serializer()), it) }.getOrNull()
        } ?: tracks
        val updatedMillis = parseIsoToMillis(rec["updated"]?.jsonPrimitive?.contentOrNull ?: "")
        return Playlist(
            id = rec["id"]?.jsonPrimitive?.content ?: "",
            name = rec["name"]?.jsonPrimitive?.content ?: name,
            tracks = returnedTracks,
            updatedAt = if (updatedMillis > 0) updatedMillis else System.currentTimeMillis()
        )
    }

    suspend fun createPlaylist(name: String): Playlist = createPlaylist(name, null)

    override suspend fun deletePlaylist(id: String) {
        val res = client.delete("$pbUrl/api/collections/playlists/records/$id") { applyAuth() }
        checkResponse(res)
    }

    override suspend fun updatePlaylistTracks(id: String, tracks: List<Track>) {
        if (token == null || token == "guest") return
        val res = client.patch("$pbUrl/api/collections/playlists/records/$id") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("tracks", json.encodeToJsonElement(ListSerializer(Track.serializer()), tracks))
            })
        }
        checkResponse(res)
    }

    suspend fun credits(track: Track): TrackCredits {
        val cleanTitle = cleanForLyricsSearch(track.displayTitle)
        val cleanArtist = track.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: track.artist
        return fetchCredits(track.id, cleanTitle, cleanArtist)
    }

    suspend fun credits(trackId: String): TrackCredits = fetchCredits(trackId, "", "")

    private suspend fun fetchCredits(trackId: String, title: String, artist: String): TrackCredits {
        val query = "$title $artist".trim().ifBlank { trackId }
        val cacheKey = "credits:${trackId.ifBlank { query }}"
        val raw = runCatching {
            cachedText(cacheKey, TTL_CREDITS_MS) {
                val searchRes = client.get("https://lrc.red/search.json") {
                    parameter("q", query)
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }
                if (!searchRes.status.isSuccess()) return@cachedText ""
                val searchObj = json.parseToJsonElement(searchRes.bodyAsText()).jsonObject
                val isrc = searchObj["hits"]?.jsonArray?.firstOrNull()?.jsonObject?.get("isrc")?.jsonPrimitive?.contentOrNull
                if (isrc.isNullOrBlank()) return@cachedText ""

                val detailRes = client.get("https://lrc.red/s/$isrc.json") {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }
                if (!detailRes.status.isSuccess()) return@cachedText ""
                detailRes.bodyAsText()
            }
        }.getOrDefault("")

        if (raw.isBlank()) {
            return TrackCredits(trackId = trackId, title = title, roles = emptyList(), sources = emptyList())
        }

        return try {
            val d = json.parseToJsonElement(raw).jsonObject
            val roles = mutableListOf<CreditRole>()

            d["songwriters"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }?.let { writers ->
                if (writers.isNotEmpty()) {
                    roles.add(CreditRole(title = "Written by", people = writers.map { CreditPerson(name = it) }))
                }
            }
            d["artist"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { art ->
                roles.add(CreditRole(title = "Performed by", people = listOf(CreditPerson(name = art))))
            }
            d["album"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { alb ->
                roles.add(CreditRole(title = "Album", people = listOf(CreditPerson(name = alb))))
            }
            d["released"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { rel ->
                roles.add(CreditRole(title = "Released", people = listOf(CreditPerson(name = rel))))
            }
            d["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { g -> g.isNotBlank() } }?.let { genres ->
                if (genres.isNotEmpty()) {
                    roles.add(CreditRole(title = "Genre", people = listOf(CreditPerson(name = genres.joinToString(", ")))))
                }
            }
            d["language"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { lang ->
                roles.add(CreditRole(title = "Language", people = listOf(CreditPerson(name = lang.uppercase()))))
            }
            d["sync"]?.jsonPrimitive?.contentOrNull?.let { sync ->
                val timing = when (sync) {
                    "word" -> "Word by word"
                    "line" -> "Line by line"
                    else -> "Not synced"
                }
                roles.add(CreditRole(title = "Timing", people = listOf(CreditPerson(name = timing))))
            }
            d["isrc"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { isrc ->
                roles.add(CreditRole(title = "ISRC", people = listOf(CreditPerson(name = isrc))))
            }

            TrackCredits(
                trackId = trackId,
                title = d["title"]?.jsonPrimitive?.contentOrNull ?: title,
                roles = roles,
                sources = listOf("lrc.red / LRCLIB")
            )
        } catch (_: Exception) {
            TrackCredits(trackId = trackId, title = title, roles = emptyList(), sources = emptyList())
        }
    }

    /**
     * Radio untuk sebuah seed secara client-side via InnerTubeService.
     */
    suspend fun radio(
        track: Track,
        limit: Int = 10,
        excludeIds: Collection<String> = emptyList()
    ): List<Track> {
        val ytid = track.youtubeId?.takeIf { it.isNotBlank() } ?: run {
            InnerTubeService.searchTracks("${track.displayTitle} ${track.artist}", limit = 1).firstOrNull()?.youtubeId
        } ?: return emptyList()
        val (tracks, _) = InnerTubeService.getRadioTracks(ytid)
        val excludeSet = excludeIds.toSet()
        return tracks.filterNot { it.id in excludeSet }.take(limit)
    }
}
