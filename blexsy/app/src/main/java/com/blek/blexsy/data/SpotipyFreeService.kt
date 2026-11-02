package com.blek.blexsy.data

import com.blek.blexsy.model.AlbumDetail
import com.blek.blexsy.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * Service to connect to SpotipyFree FastAPI backend deployed at https://api.blek.my.id/v1.
 * Provides high-speed Spotify metadata without fragile HTML scraping.
 */
object SpotipyFreeService {
    private const val BASE_URL = "https://api.blek.my.id/v1"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "Blexsy/1.0 (Android)")
        }
    }

    suspend fun getAlbum(albumId: String): AlbumDetail? = withContext(Dispatchers.IO) {
        val cleanId = albumId.removePrefix("spotify:album:").trim()
        if (cleanId.isBlank()) return@withContext null
        try {
            val res = client.get("$BASE_URL/albums/$cleanId")
            if (!res.status.isSuccess()) return@withContext null
            val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val name = root["name"]?.jsonPrimitive?.content ?: "Album"
            val artistName = root["artists"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Unknown artist"
            val cover = root["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: root["coverArt"]?.jsonObject?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
            val releaseDate = root["release_date"]?.jsonPrimitive?.content ?: root["date"]?.jsonObject?.get("year")?.jsonPrimitive?.content
            val recordType = root["album_type"]?.jsonPrimitive?.content?.uppercase() ?: root["type"]?.jsonPrimitive?.content?.uppercase() ?: "ALBUM"

            val tracksArr = root["tracks"]?.jsonObject?.get("items")?.jsonArray
                ?: root["tracksV2"]?.jsonObject?.get("items")?.jsonArray
                ?: emptyList()

            val tracks = tracksArr.mapIndexedNotNull { index, el ->
                val tObj = if (el.jsonObject.containsKey("track")) el.jsonObject["track"]?.jsonObject ?: el.jsonObject else el.jsonObject
                val id = tObj["id"]?.jsonPrimitive?.content ?: tObj["track_id"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                val title = tObj["name"]?.jsonPrimitive?.content ?: "Track"
                val durationMs = tObj["duration_ms"]?.jsonPrimitive?.longOrNull
                    ?: tObj["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.longOrNull
                val tArtist = tObj["artists"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }?.joinToString(", ")?.ifBlank { artistName } ?: artistName

                Track(
                    id = id,
                    title = title,
                    artist = tArtist,
                    album = name,
                    albumId = cleanId,
                    image = cover,
                    durationMs = durationMs,
                    spotifyUrl = "https://open.spotify.com/track/$id",
                    rank = index + 1
                )
            }

            AlbumDetail(
                id = cleanId,
                name = name,
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

    suspend fun getPlaylistTracks(playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val cleanId = playlistId.removePrefix("spotify:playlist:").trim()
        if (cleanId.isBlank()) return@withContext emptyList()
        try {
            val res = client.get("$BASE_URL/playlists/$cleanId")
            if (!res.status.isSuccess()) return@withContext emptyList()
            val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val items = root["items"]?.jsonArray ?: return@withContext emptyList()
            items.mapIndexedNotNull { index, el ->
                val tObj = el.jsonObject["track"]?.jsonObject ?: el.jsonObject
                val id = tObj["id"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                val title = tObj["name"]?.jsonPrimitive?.content ?: "Track"
                val durationMs = tObj["duration_ms"]?.jsonPrimitive?.longOrNull
                val artists = tObj["artists"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }?.joinToString(", ") ?: "Unknown artist"
                val albumObj = tObj["album"]?.jsonObject
                val albumName = albumObj?.get("name")?.jsonPrimitive?.content.orEmpty()
                val cover = albumObj?.get("images")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content

                Track(
                    id = id,
                    title = title,
                    artist = artists,
                    album = albumName,
                    image = cover,
                    durationMs = durationMs,
                    spotifyUrl = "https://open.spotify.com/track/$id",
                    rank = index + 1
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun searchTracks(query: String, limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val res = client.get("$BASE_URL/search?q=$encoded&limit=$limit")
            if (!res.status.isSuccess()) return@withContext emptyList()
            val root = json.parseToJsonElement(res.bodyAsText()).jsonObject
            val items = root["tracks"]?.jsonObject?.get("items")?.jsonArray ?: return@withContext emptyList()
            items.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: obj["track_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["name"]?.jsonPrimitive?.content ?: "Track"
                val durationMs = obj["duration_ms"]?.jsonPrimitive?.longOrNull
                val artists = obj["artists"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }?.joinToString(", ") ?: "Unknown artist"
                val albumObj = obj["album"]?.jsonObject
                val albumName = albumObj?.get("name")?.jsonPrimitive?.content.orEmpty()
                val albumId = albumObj?.get("id")?.jsonPrimitive?.content
                val cover = albumObj?.get("images")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                    ?: albumObj?.get("coverArt")?.jsonObject?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content

                Track(
                    id = id,
                    title = title,
                    artist = artists,
                    album = albumName,
                    albumId = albumId,
                    image = cover,
                    durationMs = durationMs,
                    spotifyUrl = "https://open.spotify.com/track/$id"
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
