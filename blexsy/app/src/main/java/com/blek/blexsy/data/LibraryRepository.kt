package com.blek.blexsy.data

import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Subset of the API used by the local-first library. Keeping it as an interface makes the
 * optimistic/sync behaviour testable without a network stack.
 */
interface LibraryApi {
    val token: String?

    suspend fun getFavorites(): List<Track>
    suspend fun toggleFavorite(track: Track)
    suspend fun getPlaylists(): List<Playlist>
    suspend fun createPlaylist(name: String, track: Track? = null): Playlist
    suspend fun createPlaylistWithTracks(name: String, tracks: List<Track>): Playlist =
        createPlaylist(name, tracks.firstOrNull())
    suspend fun deletePlaylist(id: String)
    suspend fun updatePlaylistTracks(id: String, tracks: List<Track>) {}
    suspend fun getRecent(limit: Int = 50): List<Track> = emptyList()
    suspend fun recordRecent(track: Track) {}
}

/**
 * Local-first view of the user's library.
 *
 * Every user action (like a track, create/delete a playlist) is applied to an in-memory
 * state flow and written to [LibraryStorage] immediately, so the UI reacts in the same
 * frame. The matching server call is queued and pushed on a background coroutine; failures
 * stay in the queue and are retried without the user ever waiting on the network.
 */
class LibraryRepository(
    private val api: LibraryApi,
    private val storage: LibraryStorage = InMemoryLibraryStorage(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _favorites = MutableStateFlow(storage.readFavorites())
    val favorites: StateFlow<List<Track>> = _favorites.asStateFlow()

    private val _playlists = MutableStateFlow(storage.readPlaylists())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _recentTracks = MutableStateFlow(storage.readRecent())
    val recentTracks: StateFlow<List<Track>> = _recentTracks.asStateFlow()

    private val _playCounts = MutableStateFlow(storage.readPlayCounts())
    val playCounts: StateFlow<Map<String, Int>> = _playCounts.asStateFlow()

    private val _pendingOps = MutableStateFlow(storage.readOps().size)

    /** Changes saved locally but not confirmed by the server yet. */
    val pendingOps: StateFlow<Int> = _pendingOps.asStateFlow()

    private val opLock = Any()
    private val syncLock = Mutex()

    /** localId -> serverId for playlists that were created optimistically. */
    private val localIdAliases = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MS)
                if (_pendingOps.value > 0) flush()
            }
        }
    }

    fun isFavorite(trackId: String): Boolean = _favorites.value.any { it.id == trackId }

    /** Flip the heart instantly, then push the change in the background. */
    fun toggleFavorite(track: Track) {
        val liked = _favorites.value.none { it.id == track.id }
        val next = if (liked) {
            listOf(track) + _favorites.value.filterNot { it.id == track.id }
        } else {
            _favorites.value.filterNot { it.id == track.id }
        }
        writeFavorites(next)
        enqueue(LibraryOp(kind = LibraryOpKind.FAVORITE, track = track, liked = liked))
    }

    /** Record a spin locally right away, and push to server when authenticated. */
    fun recordRecent(track: Track) {
        val stampedTrack = if (track.playedAt.isNullOrBlank()) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            track.copy(playedAt = sdf.format(java.util.Date()))
        } else track

        val currentCounts = _playCounts.value.toMutableMap()
        val newCount = (currentCounts[stampedTrack.id] ?: (stampedTrack.playCount ?: 0)) + 1
        currentCounts[stampedTrack.id] = newCount
        _playCounts.value = currentCounts
        storage.writePlayCounts(currentCounts)

        val trackWithCount = stampedTrack.copy(playCount = newCount)
        val current = _recentTracks.value
        val updated = (listOf(trackWithCount) + current.filterNot { it.id == trackWithCount.id }).take(MAX_RECENT_TRACKS)
        writeRecent(updated)

        enqueue(LibraryOp(kind = LibraryOpKind.RECORD_RECENT, track = trackWithCount))
    }

    /** Creates the playlist locally right away and syncs it when the network allows. */
    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Playlist {
        val playlist = Playlist(
            id = LOCAL_PLAYLIST_PREFIX + UUID.randomUUID(),
            name = name,
            tracks = tracks,
            updatedAt = System.currentTimeMillis()
        )
        writePlaylists(_playlists.value + playlist)
        enqueue(
            LibraryOp(
                kind = LibraryOpKind.CREATE_PLAYLIST,
                localPlaylistId = playlist.id,
                playlistName = playlist.name,
                tracks = tracks
            )
        )
        return playlist
    }

    /** Appends a track to the playlist locally and queues a server update. */
    fun addTrackToPlaylist(playlist: Playlist, track: Track) {
        val remoteId = if (playlist.id.startsWith(LOCAL_PLAYLIST_PREFIX)) {
            localIdAliases[playlist.id]
        } else {
            playlist.id
        }
        val current = _playlists.value
        val target = current.firstOrNull { it.id == playlist.id || (remoteId != null && it.id == remoteId) } ?: playlist
        if (target.tracks.any { it.id == track.id }) return
        val updatedTracks = target.tracks + track
        val updated = target.copy(tracks = updatedTracks, updatedAt = System.currentTimeMillis())
        writePlaylists(current.map { if (it.id == target.id) updated else it })

        if (remoteId != null) {
            enqueue(
                LibraryOp(
                    kind = LibraryOpKind.UPDATE_PLAYLIST,
                    serverPlaylistId = remoteId,
                    tracks = updatedTracks
                )
            )
        } else {
            updateOps { ops ->
                ops.map { op ->
                    if (op.kind == LibraryOpKind.CREATE_PLAYLIST && op.localPlaylistId == playlist.id) {
                        op.copy(tracks = updatedTracks)
                    } else op
                }
            }
        }
    }

    /** Removes the playlist from the list immediately; the server delete follows. */
    fun deletePlaylist(playlist: Playlist) {
        // A playlist created seconds ago may already have been synced (its list id became
        // the server id while this screen still holds the optimistic one).
        val remoteId = if (playlist.id.startsWith(LOCAL_PLAYLIST_PREFIX)) {
            localIdAliases[playlist.id]
        } else {
            playlist.id
        }
        writePlaylists(
            _playlists.value.filterNot {
                it.id == playlist.id || (remoteId != null && it.id == remoteId)
            }
        )
        if (remoteId == null) {
            // It never reached the server, so drop the pending create instead of queueing a delete.
            updateOps { ops ->
                ops.filterNot {
                    it.kind == LibraryOpKind.CREATE_PLAYLIST && it.localPlaylistId == playlist.id
                }
            }
            return
        }
        enqueue(LibraryOp(kind = LibraryOpKind.DELETE_PLAYLIST, serverPlaylistId = remoteId))
    }

    /** Push what is queued, then reconcile with the server. Safe to call any time. */
    fun refresh() {
        scope.launch {
            flush()
            pull()
        }
    }

    /** Drops the local mirror, e.g. on sign-out. */
    fun signOut() {
        writeFavorites(emptyList())
        writePlaylists(emptyList())
        writeRecent(emptyList())
        persistOps(emptyList())
    }

    fun shutdown() {
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------------
    // Background sync
    // ---------------------------------------------------------------------------------------

    private suspend fun flush() {
        if (_pendingOps.value == 0) return
        syncLock.withLock {
            val queued = readOps()
            if (queued.isEmpty() || !hasAccount()) return@withLock

            var serverFavorites: List<Track>? = null

            for (op in queued) {
                val applied = try {
                    when (op.kind) {
                        LibraryOpKind.CREATE_PLAYLIST -> {
                            val created = if (op.tracks.isNotEmpty()) {
                                api.createPlaylistWithTracks(op.playlistName.orEmpty(), op.tracks)
                            } else {
                                api.createPlaylist(op.playlistName.orEmpty())
                            }
                            applyRemotePlaylist(op.localPlaylistId, created)
                            true
                        }

                        LibraryOpKind.DELETE_PLAYLIST -> {
                            op.serverPlaylistId?.let { api.deletePlaylist(it) }
                            true
                        }

                        LibraryOpKind.UPDATE_PLAYLIST -> {
                            op.serverPlaylistId?.let { api.updatePlaylistTracks(it, op.tracks) }
                            true
                        }

                        LibraryOpKind.FAVORITE -> {
                            val track = op.track
                            if (track == null) {
                                true
                            } else {
                                if (serverFavorites == null) {
                                    // Read once so a toggle is only sent when the server state
                                    // actually differs from what the user asked for.
                                    serverFavorites = runCatching { api.getFavorites() }
                                        .getOrElse { emptyList() }
                                }
                                val current = serverFavorites.orEmpty()
                                if (current.any { it.id == track.id } != op.liked) {
                                    api.toggleFavorite(track)
                                }
                                serverFavorites = if (op.liked) {
                                    listOf(track) + current.filterNot { it.id == track.id }
                                } else {
                                    current.filterNot { it.id == track.id }
                                }
                                true
                            }
                        }

                        LibraryOpKind.RECORD_RECENT -> {
                            val track = op.track
                            if (track != null) {
                                api.recordRecent(track)
                            }
                            true
                        }
                    }
                } catch (e: ApiException) {
                    // 400/401/403/422 can never succeed with the current payload/token: drop instead of
                    // retrying this operation forever.
                    e.statusCode in listOf(400, 401, 403, 422)
                } catch (_: Exception) {
                    false
                }

                if (!applied) break // keep this op and the rest of the queue for the next try
                removeOp(op)
            }
        }
    }

    private suspend fun pull() {
        if (!hasAccount()) return
        val serverFavorites = runCatching { api.getFavorites() }.getOrNull()
        val serverPlaylists = runCatching { api.getPlaylists() }.getOrNull()
        val serverRecent = runCatching { api.getRecent(MAX_RECENT_TRACKS) }.getOrNull()
        if (serverFavorites != null) applyServerFavorites(serverFavorites)
        if (serverPlaylists != null) applyServerPlaylists(serverPlaylists)
        if (serverRecent != null) applyServerRecent(serverRecent)
    }

    /**
     * Takes the server list but replays still-pending local intents on top, so a pull that
     * finishes before a push cannot visually roll back an optimistic like.
     */
    private fun applyServerFavorites(server: List<Track>) {
        var merged = server
        for (op in readOps()) {
            if (op.kind != LibraryOpKind.FAVORITE) continue
            val track = op.track ?: continue
            merged = if (op.liked) {
                listOf(track) + merged.filterNot { it.id == track.id }
            } else {
                merged.filterNot { it.id == track.id }
            }
        }
        writeFavorites(merged)
    }

    private fun applyServerPlaylists(server: List<Playlist>) {
        val ops = readOps()
        val pendingDeletes = ops
            .filter { it.kind == LibraryOpKind.DELETE_PLAYLIST }
            .mapNotNull { it.serverPlaylistId }
            .toSet()
        val pendingCreates = ops
            .filter { it.kind == LibraryOpKind.CREATE_PLAYLIST }
            .mapNotNull { it.localPlaylistId }
            .toSet()
        val pendingUpdates = ops
            .filter { it.kind == LibraryOpKind.UPDATE_PLAYLIST }
            .associateBy { it.serverPlaylistId }

        var merged = server
            .filterNot { it.id in pendingDeletes }
            .map { srv ->
                pendingUpdates[srv.id]?.let { op -> srv.copy(tracks = op.tracks) } ?: srv
            }

        // Keep playlists whose create is still queued so the list does not flicker.
        val optimistic = _playlists.value.filter {
            it.id.startsWith(LOCAL_PLAYLIST_PREFIX) && it.id in pendingCreates
        }
        merged = merged + optimistic.filter { local -> merged.none { it.id == local.id } }
        writePlaylists(merged)
    }

    private fun applyServerRecent(server: List<Track>) {
        val pendingRecent = readOps()
            .filter { it.kind == LibraryOpKind.RECORD_RECENT }
            .mapNotNull { it.track }

        val merged = (pendingRecent + server + _recentTracks.value)
            .distinctBy { it.id }
            .take(MAX_RECENT_TRACKS)
        writeRecent(merged)
    }

    /** Replaces the optimistic ("local-…") playlist with the one the server created. */
    private fun applyRemotePlaylist(localId: String?, created: Playlist) {
        if (localId == null) return
        localIdAliases[localId] = created.id
        val current = _playlists.value
        val existing = current.firstOrNull { it.id == localId }
        if (existing == null) {
            // Deleted while the create request was in flight: undo it on the server.
            enqueue(LibraryOp(kind = LibraryOpKind.DELETE_PLAYLIST, serverPlaylistId = created.id))
            return
        }
        writePlaylists(
            current.map { if (it.id == localId) created.copy(tracks = it.tracks) else it }
        )
    }

    // ---------------------------------------------------------------------------------------
    // Storage helpers
    // ---------------------------------------------------------------------------------------

    private fun hasAccount(): Boolean {
        val token = api.token
        return !token.isNullOrBlank() && token != GUEST_TOKEN
    }

    private fun writeFavorites(tracks: List<Track>) {
        _favorites.value = tracks
        storage.writeFavorites(tracks)
    }

    private fun writePlaylists(playlists: List<Playlist>) {
        _playlists.value = playlists
        storage.writePlaylists(playlists)
    }

    private fun writeRecent(tracks: List<Track>) {
        _recentTracks.value = tracks
        storage.writeRecent(tracks)
    }

    private fun readOps(): List<LibraryOp> = synchronized(opLock) { storage.readOps() }

    private fun persistOps(ops: List<LibraryOp>) {
        synchronized(opLock) { storage.writeOps(ops) }
        _pendingOps.value = ops.size
    }

    private fun updateOps(block: (List<LibraryOp>) -> List<LibraryOp>) {
        val next = synchronized(opLock) {
            val ops = block(storage.readOps())
            storage.writeOps(ops)
            ops
        }
        _pendingOps.value = next.size
    }

    private fun enqueue(op: LibraryOp) {
        updateOps { ops ->
            val merged = when (op.kind) {
                LibraryOpKind.FAVORITE -> {
                    // Coalesce: only the latest intent for a track matters.
                    ops.filterNot {
                        it.kind == LibraryOpKind.FAVORITE && it.track?.id == op.track?.id
                    } + op
                }
                LibraryOpKind.RECORD_RECENT -> {
                    // Only keep latest record intent per track.
                    ops.filterNot {
                        it.kind == LibraryOpKind.RECORD_RECENT && it.track?.id == op.track?.id
                    } + op
                }
                else -> ops + op
            }
            if (merged.size > MAX_PENDING_OPS) merged.takeLast(MAX_PENDING_OPS) else merged
        }
        scope.launch { flush() }
    }

    private fun removeOp(op: LibraryOp) {
        updateOps { ops -> ops.filterNot { it == op } }
    }

    companion object {
        const val LOCAL_PLAYLIST_PREFIX = "local-"

        private const val GUEST_TOKEN = "guest"
        private const val RETRY_INTERVAL_MS = 15_000L
        private const val MAX_PENDING_OPS = 200
        private const val MAX_RECENT_TRACKS = 50
    }
}
