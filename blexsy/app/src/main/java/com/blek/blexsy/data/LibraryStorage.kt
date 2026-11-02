package com.blek.blexsy.data

import android.content.Context
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A library change that still has to be pushed to the server. */
@Serializable
enum class LibraryOpKind {
    FAVORITE,
    CREATE_PLAYLIST,
    DELETE_PLAYLIST,
    RECORD_RECENT,
    UPDATE_PLAYLIST
}

/**
 * One queued, offline-first library mutation.
 *
 * [liked] is the *desired* state for [track] instead of a blind "toggle", so replaying an
 * operation after a flaky network call cannot flip the server twice.
 */
@Serializable
data class LibraryOp(
    val kind: LibraryOpKind,
    val track: Track? = null,
    val liked: Boolean = false,
    val localPlaylistId: String? = null,
    val playlistName: String? = null,
    val serverPlaylistId: String? = null,
    val tracks: List<Track> = emptyList()
)

/**
 * Persistence for the local library mirror. The UI reads from this first so taps feel
 * instant; the network catches up in the background.
 */
interface LibraryStorage {
    fun readFavorites(): List<Track>
    fun writeFavorites(tracks: List<Track>)
    fun readPlaylists(): List<Playlist>
    fun writePlaylists(playlists: List<Playlist>)
    fun readRecent(): List<Track>
    fun writeRecent(tracks: List<Track>)
    fun readPlayCounts(): Map<String, Int> = emptyMap()
    fun writePlayCounts(counts: Map<String, Int>) {}
    fun readOps(): List<LibraryOp>
    fun writeOps(ops: List<LibraryOp>)
    fun clear()
}

/** SharedPreferences-backed store. Small, synchronous and good enough for a few hundred rows. */
class PrefsLibraryStorage(context: Context) : LibraryStorage {
    private val prefs = context.applicationContext
        .getSharedPreferences("blexsy_library", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val tracksSerializer = ListSerializer(Track.serializer())
    private val playlistsSerializer = ListSerializer(Playlist.serializer())
    private val opsSerializer = ListSerializer(LibraryOp.serializer())
    private val playCountsSerializer = MapSerializer(String.serializer(), Int.serializer())

    override fun readFavorites(): List<Track> = read(KEY_FAVORITES, tracksSerializer)

    override fun writeFavorites(tracks: List<Track>) = write(KEY_FAVORITES, tracksSerializer, tracks)

    override fun readPlaylists(): List<Playlist> = read(KEY_PLAYLISTS, playlistsSerializer)

    override fun writePlaylists(playlists: List<Playlist>) =
        write(KEY_PLAYLISTS, playlistsSerializer, playlists)

    override fun readRecent(): List<Track> = read(KEY_RECENT, tracksSerializer)

    override fun writeRecent(tracks: List<Track>) = write(KEY_RECENT, tracksSerializer, tracks)

    override fun readPlayCounts(): Map<String, Int> {
        val raw = prefs.getString(KEY_PLAY_COUNTS, null) ?: return emptyMap()
        return runCatching { json.decodeFromString(playCountsSerializer, raw) }.getOrDefault(emptyMap())
    }

    override fun writePlayCounts(counts: Map<String, Int>) {
        prefs.edit().putString(KEY_PLAY_COUNTS, json.encodeToString(playCountsSerializer, counts)).apply()
    }

    override fun readOps(): List<LibraryOp> = read(KEY_OPS, opsSerializer)

    override fun writeOps(ops: List<LibraryOp>) = write(KEY_OPS, opsSerializer, ops)

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun <T> read(key: String, serializer: KSerializer<List<T>>): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun <T> write(key: String, serializer: KSerializer<List<T>>, value: List<T>) {
        prefs.edit().putString(key, json.encodeToString(serializer, value)).apply()
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_OPS = "pending_ops"
        const val KEY_RECENT = "recent_spins"
        const val KEY_PLAY_COUNTS = "play_counts"
    }
}

/** Non-persistent fallback used by previews and unit tests. */
class InMemoryLibraryStorage : LibraryStorage {
    private var favorites: List<Track> = emptyList()
    private var playlists: List<Playlist> = emptyList()
    private var recent: List<Track> = emptyList()
    private var playCounts: Map<String, Int> = emptyMap()
    private var ops: List<LibraryOp> = emptyList()

    override fun readFavorites(): List<Track> = favorites
    override fun writeFavorites(tracks: List<Track>) { favorites = tracks }
    override fun readPlaylists(): List<Playlist> = playlists
    override fun writePlaylists(playlists: List<Playlist>) { this.playlists = playlists }
    override fun readRecent(): List<Track> = recent
    override fun writeRecent(tracks: List<Track>) { this.recent = tracks }
    override fun readPlayCounts(): Map<String, Int> = playCounts
    override fun writePlayCounts(counts: Map<String, Int>) { this.playCounts = counts }
    override fun readOps(): List<LibraryOp> = ops
    override fun writeOps(ops: List<LibraryOp>) { this.ops = ops }
    override fun clear() {
        favorites = emptyList()
        playlists = emptyList()
        recent = emptyList()
        playCounts = emptyMap()
        ops = emptyList()
    }
}
