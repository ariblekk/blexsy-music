package com.blek.blexsy.data

import com.blek.blexsy.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class ApiException(message: String, val statusCode: Int? = null) : Exception(message)

private const val TTL_CHART_MS = 24 * 60 * 60_000L // 1 day
/** Spotify refreshes country charts a few times a day and serves them with a 15-minute max-age. */
private const val TTL_SPOTIFY_CHART_MS = 60 * 60_000L // 1 hour
/** Market slug the charts endpoint accepts: `id` for Indonesia, `global` for the world chart. */
private const val CHART_COUNTRY = "id"
private const val TTL_HOME_SECTIONS_MS = 3 * 60 * 60_000L // 3 hours: keep the feed from feeling frozen
private const val HOME_SECTIONS_KEY = "home_sections:active"
private const val TTL_RECENT_MS = 60_000L
private const val TTL_CREDITS_MS = 7 * 24 * 60 * 60_000L
private const val TTL_SUGGEST_MS = 24 * 60 * 60_000L
private const val TTL_LYRICS_MS = 14 * 24 * 60 * 60_000L
private val RECENT_SNAPSHOT_LIMITS = listOf(10, 20, 50)
private const val SEARCH_ARTIST_LIMIT = 1

data class SearchResult(
    val tracks: List<Track>,
    val artists: List<ArtistSummary> = emptyList(),
    val playlists: List<GenrePlaylist> = emptyList(),
    /** Opaque token for the next page; null when the result set is exhausted. */
    val continuation: String? = null,
    /**
     * Set when the page could not be fetched at all, as opposed to fetched-and-empty. Callers
     * need the distinction to offer a retry instead of reporting "no results".
     */
    val failed: Boolean = false
)

/**
 * Artists credited by the returned tracks, in result order. Searching a song title surfaces the
 * performer — not every artist whose name happens to contain the query. Ids that cannot open an
 * artist page are dropped, and [artistResults] only supplies the avatar.
 */
internal fun creditedArtists(
    tracks: List<SpotifyTrack>,
    artistResults: List<SpotifyArtist> = emptyList()
): List<ArtistSummary> {
    val images = artistResults.associate { it.id to it.image }
    return tracks
        .flatMap { track ->
            track.artists.mapIndexed { index, name ->
                val id = track.artistIds.getOrNull(index).orEmpty()
                ArtistSummary(id = id, name = name, image = images[id])
            }
        }
        .filter { it.id.isCatalogArtistId() }
        .distinctBy { it.id }
}

/**
 * Catalog facade. Spotify ([SpotifyMetadata]) is the only metadata source: search, albums,
 * artists and playlists all resolve there, and nothing falls back to YouTube data. YouTube is
 * used for audio alone — [InnerTubeService.resolveStreamUrl] at play time. Lyrics and credits
 * still fan out to their own providers at the bottom of the file.
 */
class BlexsyApiClient(
    private val cache: ApiCache? = null
) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(ContentEncoding) {
            gzip()
            deflate()
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "Blexsy/1.0 (Android)")
        }
    }

    // ---------------------------------------------------------------------
    // Disk cache: fresh entries are returned without touching the network, and
    // stale entries are used as a fallback when the server is unreachable. This is
    // what makes a cold start / second visit to an album feel instant.
    // ---------------------------------------------------------------------

    private suspend fun cachedText(
        key: String,
        ttlMs: Long,
        refresh: Boolean = false,
        fetch: suspend () -> String
    ): String {
        val cached = cache?.read(key)
        val age = cached?.let { System.currentTimeMillis() - it.savedAt }
        if (!refresh && cached != null && age != null && age in 0 until ttlMs) {
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

    suspend fun clearHomeFeedCache() {
        cache?.let { c ->
            c.evict(HOME_SECTIONS_KEY)
            c.evict(chartKey(50))
            c.evict(spotifyChartKey(CHART_COUNTRY, 50))
        }
    }

    /** Snapshot helpers used by the home screen to paint before the network answers. */
    suspend fun homeSectionsSnapshot(): List<HomeSection>? =
        cache?.read(HOME_SECTIONS_KEY)?.let { entry ->
            runCatching { json.decodeFromString<List<HomeSection>>(entry.body) }.getOrNull()
        }

    /** Last chart written to disk — Spotify first so a cold start paints the ranked one. */
    suspend fun chartSnapshot(limit: Int = 50, country: String = CHART_COUNTRY): ChartFeed? {
        val keys = listOf(spotifyChartKey(country, limit), chartKey(limit))
        return keys.firstNotNullOfOrNull { key ->
            cache?.read(key)?.let { entry ->
                runCatching { json.decodeFromString<ChartFeed>(entry.body) }.getOrNull()
                    ?.takeIf { it.tracks.isNotEmpty() }
            }
        }
    }

    private fun chartKey(limit: Int) = "chart_yt:$limit"

    private fun spotifyChartKey(country: String, limit: Int) = "chart_sp:$country:$limit"

    /** Reads a cache entry only while it is younger than [ttlMs]; stale entries are ignored. */
    private suspend inline fun <reified T> freshCache(key: String, ttlMs: Long, refresh: Boolean): T? {
        if (refresh) {
            cache?.evict(key)
            return null
        }
        val entry = cache?.read(key) ?: return null
        if (System.currentTimeMillis() - entry.savedAt > ttlMs) return null
        return runCatching { json.decodeFromString<T>(entry.body) }.getOrNull()
    }

    /**
     * Market chart: Spotify ranks first (real positions, play counts and week-on-week movement),
     * YouTube Music charts are the fallback. The endpoint is unofficial, so a failure here is
     * expected rather than exceptional — either way the home feed must still get a chart.
     */
    suspend fun chart(
        limit: Int = 50,
        refresh: Boolean = false,
        country: String = CHART_COUNTRY
    ): ChartFeed {
        spotifyChart(limit, refresh, country)?.let { if (it.tracks.isNotEmpty()) return it }
        val key = chartKey(limit)
        freshCache<ChartFeed>(key, TTL_CHART_MS, refresh)?.let { if (it.tracks.isNotEmpty()) return it }
        val feeds = runCatching { InnerTubeService.getCharts(limit) }.getOrDefault(emptyList())
        val feed = feeds.firstOrNull() ?: ChartFeed(title = "Top Indonesia", tracks = emptyList())
        if (feed.tracks.isNotEmpty()) cache?.write(key, json.encodeToString(feed))
        return feed
    }

    /** The market chart without the YouTube fallback; null when Spotify had nothing to rank. */
    suspend fun spotifyChartFeed(
        limit: Int = 50,
        refresh: Boolean = false,
        country: String = CHART_COUNTRY
    ): ChartFeed? = spotifyChart(limit, refresh, country)?.takeIf { it.tracks.isNotEmpty() }

    /** Cached Spotify chart, or null when the endpoint failed to return a ranked chart. */
    private suspend fun spotifyChart(limit: Int, refresh: Boolean, country: String): ChartFeed? {
        val key = spotifyChartKey(country, limit)
        freshCache<ChartFeed>(key, TTL_SPOTIFY_CHART_MS, refresh)?.let { return it }
        val feed = runCatching { SpotifyMetadata.getCharts(country, top = "daily") }
            .getOrNull()
            ?.toChartFeed()
            ?: return null
        // An empty chart is not worth remembering.
        if (feed.tracks.isNotEmpty()) cache?.write(key, json.encodeToString(feed))
        return feed
    }

    /**
     * Spotify search only. YouTube supplies audio at play time (via the title/artist fallback in
     * [InnerTubeService.resolveTrackStream]), never metadata, so nothing here is merged.
     * [continuation] is the next Spotify offset.
     */
    suspend fun search(query: String, continuation: String? = null, limit: Int = 20): SearchResult {
        val pageSize = limit.coerceIn(1, 50)
        val offset = continuation?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = try {
            SpotifyMetadata.search(query, limit = pageSize, offset = offset)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SearchResult(emptyList(), failed = true)
        }
        val tracks = page.tracks.map { it.toTrack() }

        // Performers first: a song-title query must show who plays the song, not whoever's name
        // contains the words. Name-matched Spotify artists are only the fallback for a query that
        // returned no tracks at all.
        val performers = creditedArtists(page.tracks, page.artists)
        val matchedByName = page.artists
            .filter { queryMatchesName(query, it.name) }
            .map { it.toArtistSummary() }

        return SearchResult(
            tracks = tracks,
            artists = (performers.ifEmpty { matchedByName }).take(SEARCH_ARTIST_LIMIT),
            // Album and playlist shelves are left out: search shows artists and songs only.
            playlists = emptyList(),
            continuation = if (tracks.isEmpty()) null else (offset + pageSize).toString()
        )
    }

    /** Spotify only — a non-Spotify id has no detail page to show. */
    suspend fun album(id: String): AlbumDetail =
        SpotifyMetadata.getAlbum(id).toAlbumDetail()

    /** YouTube home feed, narrowed to playable songs: album/artist/playlist cards would need a YouTube detail page. */
    suspend fun homeSections(refresh: Boolean = false): List<HomeSection> {
        val key = HOME_SECTIONS_KEY
        freshCache<List<HomeSection>>(key, TTL_HOME_SECTIONS_MS, refresh)?.let { return it }
        val sections = runCatching { InnerTubeService.getHome() }
            .getOrDefault(emptyList())
            .filterNot { sec ->
                val title = sec.title.lowercase()
                title.contains("trending") || title.contains("tangga") || title.contains("chart")
            }
            .mapNotNull { section ->
                val songs = section.items.mapNotNull { it.track }
                if (songs.isEmpty()) {
                    null
                } else {
                    section.copy(
                        items = songs.map { track ->
                            HomeSectionItem(
                                type = "song",
                                id = track.id,
                                title = track.displayTitle,
                                track = track
                            )
                        }
                    )
                }
            }
        if (sections.isNotEmpty()) cache?.write(key, json.encodeToString(sections))
        return sections
    }

    /** Spotify only: a display name is resolved through Spotify search, never a YouTube channel. */
    suspend fun resolveArtistId(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            SpotifyMetadata.search(trimmed, limit = 5).artists
                .firstOrNull { queryMatchesName(trimmed, it.name) }
                ?.id
        }.getOrNull()
    }

    /** Spotify only — any other id has no artist page here. */
    suspend fun artist(id: String): ArtistDetail =
        SpotifyMetadata.getArtist(id).toArtistDetail()

    /** Browse category page: curated sections of playlists, albums, and sub-genres. */
    suspend fun genrePage(id: String): SpotifyGenrePage = SpotifyMetadata.getGenre(id)

    suspend fun playlistDetail(id: String, fetchAll: Boolean = true): Pair<String, List<Track>> {
        val detail = SpotifyMetadata.getPlaylist(id).toPlaylist()
        return detail.name to detail.tracks
    }

    suspend fun playlist(id: String): List<Track> = playlistDetail(id).second

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

    /** Loose "does this catalog name answer the query" test: normalized equality or containment. */
    internal fun queryMatchesName(query: String, name: String): Boolean {
        val q = cleanTitleForMatch(query)
        val n = cleanTitleForMatch(name)
        if (q.isEmpty() || n.isEmpty()) return false
        return n == q || n.contains(q) || q.contains(n)
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

    suspend fun searchSuggest(query: String): List<String> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val cacheKey = "suggest:${q.lowercase()}"
        return runCatching {
            val jsonText = cachedText(cacheKey, TTL_SUGGEST_MS) {
                val list = SpotifyMetadata.searchSuggest(q)
                if (list.isNotEmpty()) {
                    json.encodeToString(list)
                } else {
                    throw ApiException("No suggestions")
                }
            }
            json.decodeFromString<List<String>>(jsonText)
        }.getOrElse {
            runCatching { SpotifyMetadata.searchSuggest(q) }.getOrDefault(emptyList())
        }
    }

    suspend fun credits(track: Track): TrackCredits {
        val cacheKey = "credits:${track.id.ifBlank { "${track.artist}:${track.title}" }}"
        return runCatching {
            val jsonText = cachedText(cacheKey, TTL_CREDITS_MS) {
                val res = resolveCredits(track)
                if (res.roles.isNotEmpty()) {
                    json.encodeToString(TrackCredits.serializer(), res)
                } else {
                    throw ApiException("No credits found")
                }
            }
            json.decodeFromString(TrackCredits.serializer(), jsonText)
        }.getOrElse { resolveCredits(track) }
    }

    suspend fun credits(trackId: String): TrackCredits {
        if (trackId.isSpotifyCatalogId()) {
            runCatching {
                SpotifyMetadata.getCredits(trackId)
            }.getOrNull()?.takeIf { it.roles.isNotEmpty() }?.let { return it }
        }
        return fetchCredits(trackId, "", "")
    }

    private suspend fun resolveCredits(track: Track): TrackCredits {
        val cleanTitle = cleanForLyricsSearch(track.displayTitle)
        val cleanArtist = track.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: track.artist

        // 1. Direct Spotify track credits if Spotify catalog id
        if (track.id.isSpotifyCatalogId()) {
            val spCredits = runCatching { SpotifyMetadata.getCredits(track.id) }.getOrNull()
            if (spCredits != null && spCredits.roles.isNotEmpty()) {
                return spCredits.copy(title = track.displayTitle.ifBlank { spCredits.title })
            }
        }

        // 2. Resolve via Spotify search if YouTube id
        val spSearchResults = runCatching {
            SpotifyMetadata.search("$cleanTitle $cleanArtist", limit = 1)
        }.getOrNull()
        val spTrackId = spSearchResults?.tracks?.firstOrNull()?.id
        if (!spTrackId.isNullOrBlank()) {
            val spCredits = runCatching { SpotifyMetadata.getCredits(spTrackId) }.getOrNull()
            if (spCredits != null && spCredits.roles.isNotEmpty()) {
                return spCredits.copy(trackId = track.id, title = track.displayTitle.ifBlank { spCredits.title })
            }
        }

        // 3. Fallback to third-party lrc.red / LRCLIB
        return fetchCredits(track.id, cleanTitle, cleanArtist)
    }

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

}
