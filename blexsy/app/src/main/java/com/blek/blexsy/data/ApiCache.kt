package com.blek.blexsy.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** One cached HTTP body plus the moment it was stored. */
data class CachedBody(val body: String, val savedAt: Long)

/**
 * Tiny disk cache for API responses.
 *
 * Only payloads that are identical for every user may be stored here (charts, genres,
 * albums, artists, playlists, search). User-specific responses either stay in the
 * local-first library storage or are keyed by token.
 *
 * Kept intentionally small instead of installing a global HTTP cache: a generic cache
 * could replay another account's private responses after a logout/login switch.
 */
class ApiCache(context: Context, dirName: String = "blexsy-api-cache") {
    private val dir = File(context.cacheDir, dirName)

    init {
        // Asynchronously clean up stale and legacy Spotify scrape cache on startup
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { purgeStale() }
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(dir, name)
    }

    suspend fun read(key: String): CachedBody? = withContext(Dispatchers.IO) {
        val file = fileFor(key)
        if (!file.exists()) return@withContext null
        runCatching {
            val text = file.readText()
            val newline = text.indexOf('\n')
            if (newline <= 0) return@runCatching null
            CachedBody(
                savedAt = text.substring(0, newline).toLong(),
                body = text.substring(newline + 1)
            )
        }.getOrNull()
    }

    suspend fun write(key: String, body: String) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            fileFor(key).writeText("${System.currentTimeMillis()}\n$body")
            trim()
        }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    /** Purges cache entries older than [maxAgeMs] and any legacy Spotify scrape leftovers. */
    suspend fun purgeStale(maxAgeMs: Long = MAX_AGE_MS) = withContext(Dispatchers.IO) {
        val files = dir.listFiles() ?: return@withContext
        val now = System.currentTimeMillis()
        for (f in files) {
            runCatching {
                val text = f.readText()
                val newline = text.indexOf('\n')
                val savedAt = if (newline > 0) text.substring(0, newline).toLongOrNull() else null
                val isExpired = savedAt == null || (now - savedAt) > maxAgeMs
                val isLegacySpotify = text.contains("__NEXT_DATA__") || text.contains("open.spotify.com/embed")
                if (isExpired || isLegacySpotify) {
                    f.delete()
                }
            }
        }
    }

    /** Keeps the folder bounded so the cache can never grow forever. */
    private fun trim() {
        val files = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        for (f in files) {
            if (now - f.lastModified() > MAX_AGE_MS) {
                f.delete()
            }
        }
        val remaining = dir.listFiles() ?: return
        if (remaining.size <= MAX_FILES) return
        remaining.sortedBy { it.lastModified() }
            .take(remaining.size - MAX_FILES)
            .forEach { it.delete() }
    }

    private companion object {
        const val MAX_FILES = 400
        const val MAX_AGE_MS = 30 * 24 * 60 * 60_000L // 30 days
    }
}
