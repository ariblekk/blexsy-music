package com.blek.blexsy.data

import com.blek.blexsy.model.AlbumDetail
import com.blek.blexsy.model.AlbumSummary
import com.blek.blexsy.model.ArtistDetail
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * Fast, keyless metadata provider using Deezer public REST API.
 * Provides high-res cover art, clean track titles, albums, and artist info.
 */
object DeezerService {
    private const val BASE_URL = "https://api.deezer.com"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 8_000
        }
    }

    suspend fun getChartTracks(limit: Int = 50): List<Track> = withContext(Dispatchers.IO) {
        try {
            val res = client.get("$BASE_URL/chart/0/tracks?limit=$limit")
            if (!res.status.isSuccess()) return@withContext emptyList()
            val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val data = root["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapIndexedNotNull { index, el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                val title = obj["title_short"]?.jsonPrimitive?.content
                    ?: obj["title"]?.jsonPrimitive?.content ?: "Track"
                val durationSec = obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                val preview = obj["preview"]?.jsonPrimitive?.content
                val link = obj["link"]?.jsonPrimitive?.content
                val pos = obj["position"]?.jsonPrimitive?.intOrNull ?: (index + 1)

                val artistObj = obj["artist"]?.jsonObject
                val artistName = artistObj?.get("name")?.jsonPrimitive?.content ?: "Unknown artist"
                val artistId = artistObj?.get("id")?.jsonPrimitive?.content

                val albumObj = obj["album"]?.jsonObject
                val albumTitle = albumObj?.get("title")?.jsonPrimitive?.content.orEmpty()
                val albumId = albumObj?.get("id")?.jsonPrimitive?.content
                val cover = albumObj?.get("cover_xl")?.jsonPrimitive?.content
                    ?: albumObj?.get("cover_big")?.jsonPrimitive?.content
                    ?: albumObj?.get("cover_medium")?.jsonPrimitive?.content
                    ?: albumObj?.get("cover")?.jsonPrimitive?.content

                Track(
                    id = "dz_$id",
                    title = title,
                    artist = artistName,
                    album = albumTitle,
                    albumId = albumId?.let { "dz_$it" },
                    artistIds = artistId?.let { listOf("dz_$it") } ?: emptyList(),
                    image = cover,
                    durationMs = durationSec * 1000L,
                    audioUrl = preview,
                    spotifyUrl = link,
                    rank = pos,
                    rankStatus = "same"
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getNewReleases(limit: Int = 30): List<AlbumSummary> = withContext(Dispatchers.IO) {
        val releases = fetchAlbumList("$BASE_URL/editorial/0/releases?limit=$limit")
        if (releases.isNotEmpty()) return@withContext releases.take(limit)
        fetchAlbumList("$BASE_URL/chart/0/albums?limit=$limit").take(limit)
    }

    private suspend fun fetchAlbumList(url: String): List<AlbumSummary> {
        return try {
            val res = client.get(url)
            if (!res.status.isSuccess()) return emptyList()
            val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()

            data.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.content ?: "Album"
                val cover = obj["cover_xl"]?.jsonPrimitive?.content
                    ?: obj["cover_big"]?.jsonPrimitive?.content
                    ?: obj["cover_medium"]?.jsonPrimitive?.content
                    ?: obj["cover"]?.jsonPrimitive?.content
                val recordType = obj["record_type"]?.jsonPrimitive?.content?.uppercase() ?: "ALBUM"
                val artistName = obj["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content

                AlbumSummary(
                    id = "dz_$id",
                    name = title,
                    artist = artistName,
                    image = cover,
                    type = recordType
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getAlbumDetail(albumId: String): AlbumDetail? = withContext(Dispatchers.IO) {
        val cleanId = albumId.removePrefix("dz_")
        if (!cleanId.all { it.isDigit() }) return@withContext null

        try {
            val res = client.get("$BASE_URL/album/$cleanId")
            if (!res.status.isSuccess()) return@withContext null
            val obj = json.parseToJsonElement(res.bodyAsText()).jsonObject
            if (obj["error"] != null) return@withContext null

            val title = obj["title"]?.jsonPrimitive?.content ?: "Album"
            val artistName = obj["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Unknown artist"
            val cover = obj["cover_xl"]?.jsonPrimitive?.content
                ?: obj["cover_big"]?.jsonPrimitive?.content
                ?: obj["cover_medium"]?.jsonPrimitive?.content
                ?: obj["cover"]?.jsonPrimitive?.content
            val releaseDate = obj["release_date"]?.jsonPrimitive?.content
            val recordType = obj["record_type"]?.jsonPrimitive?.content?.uppercase() ?: "ALBUM"
            val tracksArr = obj["tracks"]?.jsonObject?.get("data")?.jsonArray.orEmpty()

            val tracks = tracksArr.mapIndexedNotNull { index, el ->
                val tObj = el.jsonObject
                val tId = tObj["id"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                val tTitle = tObj["title_short"]?.jsonPrimitive?.content
                    ?: tObj["title"]?.jsonPrimitive?.content ?: "Track"
                val durationSec = tObj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                val preview = tObj["preview"]?.jsonPrimitive?.content
                val tArtist = tObj["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: artistName

                Track(
                    id = "dz_$tId",
                    title = tTitle,
                    artist = tArtist,
                    album = title,
                    albumId = "dz_$cleanId",
                    image = cover,
                    durationMs = durationSec * 1000L,
                    audioUrl = preview,
                    rank = index + 1
                )
            }

            AlbumDetail(
                id = "dz_$cleanId",
                name = title,
                artist = artistName,
                image = cover,
                year = releaseDate?.take(4),
                type = recordType,
                tracks = tracks
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun searchTracks(query: String, limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val res = client.get("$BASE_URL/search?q=$encoded&limit=$limit")
            if (!res.status.isSuccess()) return@withContext emptyList()
            val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val data = root["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["title_short"]?.jsonPrimitive?.content
                    ?: obj["title"]?.jsonPrimitive?.content ?: "Track"
                val durationSec = obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                val preview = obj["preview"]?.jsonPrimitive?.content
                val link = obj["link"]?.jsonPrimitive?.content

                val artistObj = obj["artist"]?.jsonObject
                val artistName = artistObj?.get("name")?.jsonPrimitive?.content ?: "Unknown artist"
                val artistId = artistObj?.get("id")?.jsonPrimitive?.content

                val albumObj = obj["album"]?.jsonObject
                val albumTitle = albumObj?.get("title")?.jsonPrimitive?.content.orEmpty()
                val albumId = albumObj?.get("id")?.jsonPrimitive?.content
                val cover = albumObj?.get("cover_xl")?.jsonPrimitive?.content
                    ?: albumObj?.get("cover_big")?.jsonPrimitive?.content
                    ?: albumObj?.get("cover_medium")?.jsonPrimitive?.content

                Track(
                    id = "dz_$id",
                    title = title,
                    artist = artistName,
                    album = albumTitle,
                    albumId = albumId?.let { "dz_$it" },
                    artistIds = artistId?.let { listOf("dz_$it") } ?: emptyList(),
                    image = cover,
                    durationMs = durationSec * 1000L,
                    audioUrl = preview,
                    spotifyUrl = link
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun searchArtists(query: String, limit: Int = 5): List<ArtistSummary> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val res = client.get("$BASE_URL/search/artist?q=$encoded&limit=$limit")
            if (!res.status.isSuccess()) return@withContext emptyList()
            val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val data = root["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val picture = obj["picture_xl"]?.jsonPrimitive?.content
                    ?: obj["picture_big"]?.jsonPrimitive?.content
                    ?: obj["picture_medium"]?.jsonPrimitive?.content
                val fans = obj["nb_fan"]?.jsonPrimitive?.intOrNull

                ArtistSummary(
                    id = "dz_$id",
                    name = name,
                    image = picture,
                    subscribers = fans?.let { "$it fans" }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getArtistDetail(artistId: String): ArtistDetail? = withContext(Dispatchers.IO) {
        val cleanId = artistId.removePrefix("dz_")
        if (!cleanId.all { it.isDigit() }) return@withContext null

        try {
            coroutineScope {
                val artistDeferred = async { client.get("$BASE_URL/artist/$cleanId") }
                val topDeferred = async { client.get("$BASE_URL/artist/$cleanId/top?limit=15") }
                val albumsDeferred = async { client.get("$BASE_URL/artist/$cleanId/albums?limit=20") }

                val aRes = artistDeferred.await()
                if (!aRes.status.isSuccess()) return@coroutineScope null
                val aObj = json.parseToJsonElement(aRes.bodyAsText()).jsonObject
                if (aObj["error"] != null) return@coroutineScope null

                val name = aObj["name"]?.jsonPrimitive?.content ?: "Artist"
                val picture = aObj["picture_xl"]?.jsonPrimitive?.content
                    ?: aObj["picture_big"]?.jsonPrimitive?.content
                    ?: aObj["picture_medium"]?.jsonPrimitive?.content
                val fans = aObj["nb_fan"]?.jsonPrimitive?.intOrNull

                val topTracks = runCatching {
                    val tRes = topDeferred.await()
                    val tData = json.parseToJsonElement(tRes.bodyAsText()).jsonObject["data"]?.jsonArray.orEmpty()
                    tData.mapNotNull { el ->
                        val obj = el.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val title = obj["title_short"]?.jsonPrimitive?.content
                            ?: obj["title"]?.jsonPrimitive?.content ?: "Track"
                        val durationSec = obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                        val preview = obj["preview"]?.jsonPrimitive?.content
                        val albumObj = obj["album"]?.jsonObject
                        val albumTitle = albumObj?.get("title")?.jsonPrimitive?.content.orEmpty()
                        val albumId = albumObj?.get("id")?.jsonPrimitive?.content
                        val cover = albumObj?.get("cover_xl")?.jsonPrimitive?.content
                            ?: albumObj?.get("cover_big")?.jsonPrimitive?.content
                            ?: albumObj?.get("cover_medium")?.jsonPrimitive?.content

                        Track(
                            id = "dz_$id",
                            title = title,
                            artist = name,
                            album = albumTitle,
                            albumId = albumId?.let { "dz_$it" },
                            image = cover,
                            durationMs = durationSec * 1000L,
                            audioUrl = preview
                        )
                    }
                }.getOrDefault(emptyList())

                val albums = runCatching {
                    val alRes = albumsDeferred.await()
                    val alData = json.parseToJsonElement(alRes.bodyAsText()).jsonObject["data"]?.jsonArray.orEmpty()
                    alData.mapNotNull { el ->
                        val obj = el.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val title = obj["title"]?.jsonPrimitive?.content ?: "Album"
                        val cover = obj["cover_xl"]?.jsonPrimitive?.content
                            ?: obj["cover_big"]?.jsonPrimitive?.content
                            ?: obj["cover_medium"]?.jsonPrimitive?.content
                        val recordType = obj["record_type"]?.jsonPrimitive?.content?.uppercase() ?: "ALBUM"

                        AlbumSummary(
                            id = "dz_$id",
                            name = title,
                            artist = name,
                            image = cover,
                            type = recordType
                        )
                    }
                }.getOrDefault(emptyList())

                ArtistDetail(
                    id = "dz_$cleanId",
                    name = name,
                    image = picture,
                    topTracks = topTracks,
                    albums = albums.filter { it.type == "ALBUM" },
                    singlesAndEps = albums.filter { it.type != "ALBUM" },
                    subscribers = fans?.let { "$it fans" },
                    verified = true
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
