package com.blek.blexsy.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches Apple Music Motion Artist Canvas loop video (HLS .m3u8).
 * ponytail: uses baked developer token with memory cache; upgrade path: dynamic web-player token refresh on 401.
 */
object ArtistCanvasService {
    private const val BASE_URL = "https://amp-api.music.apple.com"
    private const val TOKEN =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ" +
        ".eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzkwODk0NTM2LCJleHAiOjE3OTY5NDI1MzYsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ" +
        ".VPhwMsGxew0uPgpC1HvhCyQmaWNI5AmJsCxSRT3NFlbF79Gtfdg2O1r-QZv6Gbj7lzYtCSxsmcVDbWA9ujHsHA"

    private val cache = ConcurrentHashMap<String, String>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }
    }

    suspend fun getMotionVideoUrl(artistName: String): String? = withContext(Dispatchers.IO) {
        if (artistName.isBlank()) return@withContext null
        val key = artistName.trim().lowercase()
        if (cache.containsKey(key)) return@withContext cache[key]?.takeIf { it.isNotEmpty() }

        val url = runCatching {
            // 1. Search artist to find Apple Music artist ID
            val searchRes = client.get("$BASE_URL/v1/catalog/us/search") {
                header("Authorization", "Bearer $TOKEN")
                header("Origin", "https://music.apple.com")
                header("Referer", "https://music.apple.com/")
                header("User-Agent", "Mozilla/5.0")
                parameter("term", artistName)
                parameter("types", "artists")
                parameter("limit", "1")
            }
            if (searchRes.status != HttpStatusCode.OK) return@runCatching null
            val searchJson = searchRes.body<JsonObject>()
            val artistObj = searchJson["results"]?.jsonObject?.get("artists")?.jsonObject?.get("data")
                ?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
            val artistId = artistObj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null

            // 2. Fetch editorialVideo motion artwork
            val detailRes = client.get("$BASE_URL/v1/catalog/us/artists/$artistId") {
                header("Authorization", "Bearer $TOKEN")
                header("Origin", "https://music.apple.com")
                header("Referer", "https://music.apple.com/")
                header("User-Agent", "Mozilla/5.0")
                parameter("extend", "editorialVideo")
            }
            if (detailRes.status != HttpStatusCode.OK) return@runCatching null
            val detailJson = detailRes.body<JsonObject>()
            val artistDetail = detailJson["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
            val videoObj = artistDetail["attributes"]?.jsonObject?.get("editorialVideo")?.jsonObject

            fun pick(kind: String): String? =
                videoObj?.get(kind)?.jsonObject?.get("video")?.jsonPrimitive?.contentOrNull

            // Prefer square loop (taller aspect ratio for mobile hero), fallback to fullscreen / wide
            pick("motionArtistSquare1x1")
                ?: pick("motionArtistFullscreen16x9")
                ?: pick("motionArtistWide16x9")
        }.getOrNull()

        if (url != null) {
            cache[key] = url
        }
        url
    }
}
