package com.blek.blexsy.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.blek.blexsy.model.ArtistDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * SQLite cache for artist detail payloads to enable instant loading and offline browsing.
 */
class ArtistDatabase(
    context: Context?,
    name: String = "blexsy_artists.db"
) : SQLiteOpenHelper(context, name, null, 1) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS artist_cache (
                id TEXT PRIMARY KEY,
                name TEXT,
                payload TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_artist_name ON artist_cache(name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS artist_cache")
        onCreate(db)
    }

    suspend fun get(idOrName: String): ArtistDetail? = withContext(Dispatchers.IO) {
        val clean = idOrName.trim()
        if (clean.isBlank()) return@withContext null
        runCatching {
            val db = readableDatabase ?: return@withContext null
            val query = "SELECT payload FROM artist_cache WHERE id = ? OR LOWER(id) = ? OR LOWER(name) = ? LIMIT 1"
            db.rawQuery(query, arrayOf(clean, clean.lowercase(), clean.lowercase())).use { cursor ->
                if (cursor.moveToFirst()) {
                    val raw = cursor.getString(0)
                    json.decodeFromString(ArtistDetail.serializer(), raw)
                } else null
            }
        }.getOrNull()
    }

    suspend fun put(artist: ArtistDetail) = withContext(Dispatchers.IO) {
        if (artist.id.isBlank() && artist.name.isBlank()) return@withContext
        runCatching {
            val db = writableDatabase ?: return@withContext
            val values = ContentValues().apply {
                put("id", artist.id.ifBlank { artist.name })
                put("name", artist.name)
                put("payload", json.encodeToString(ArtistDetail.serializer(), artist))
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("artist_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            trim(MAX_ENTRIES)
        }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching {
            writableDatabase?.delete("artist_cache", null, null)
        }
        Unit
    }

    private fun trim(maxEntries: Int) {
        runCatching {
            writableDatabase?.execSQL(
                "DELETE FROM artist_cache WHERE id NOT IN (SELECT id FROM artist_cache ORDER BY updated_at DESC LIMIT ?)",
                arrayOf(maxEntries)
            )
        }
    }

    private companion object {
        // ponytail: bounded size prevents SQLite db from growing unbounded; raise when users request permanent offline artist library
        const val MAX_ENTRIES = 200
    }
}
