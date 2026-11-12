package com.blek.blexsy.data

import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Local-only view of the user's library.
 *
 * Every action writes the in-memory [StateFlow] and the [LibraryStorage] in the same frame,
 * so the UI reacts instantly. There is no server to sync with: the device is the source of
 * truth.
 */
class LibraryRepository(
    private val storage: LibraryStorage = InMemoryLibraryStorage()
) {
    private val _favorites = MutableStateFlow(storage.readFavorites())
    val favorites: StateFlow<List<Track>> = _favorites.asStateFlow()

    private val _playlists = MutableStateFlow(storage.readPlaylists())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _recentTracks = MutableStateFlow(storage.readRecent())
    val recentTracks: StateFlow<List<Track>> = _recentTracks.asStateFlow()

    private val _playCounts = MutableStateFlow(storage.readPlayCounts())
    val playCounts: StateFlow<Map<String, Int>> = _playCounts.asStateFlow()

    fun isFavorite(trackId: String): Boolean = _favorites.value.any { it.id == trackId }

    /** Flip the heart instantly. */
    fun toggleFavorite(track: Track) {
        val liked = _favorites.value.none { it.id == track.id }
        val next = if (liked) {
            listOf(track) + _favorites.value.filterNot { it.id == track.id }
        } else {
            _favorites.value.filterNot { it.id == track.id }
        }
        writeFavorites(next)
    }

    /** Record a spin: bump the play count and move the track to the top of the history. */
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
        val updated = (listOf(trackWithCount) + _recentTracks.value.filterNot { it.id == trackWithCount.id })
            .take(MAX_RECENT_TRACKS)
        writeRecent(updated)
    }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Playlist {
        val playlist = Playlist(
            id = "pl-" + UUID.randomUUID(),
            name = name,
            tracks = tracks,
            updatedAt = System.currentTimeMillis()
        )
        writePlaylists(_playlists.value + playlist)
        return playlist
    }

    fun addTrackToPlaylist(playlist: Playlist, track: Track) {
        val current = _playlists.value
        val target = current.firstOrNull { it.id == playlist.id } ?: playlist
        if (target.tracks.any { it.id == track.id }) return
        val updated = target.copy(
            tracks = target.tracks + track,
            updatedAt = System.currentTimeMillis()
        )
        writePlaylists(current.map { if (it.id == target.id) updated else it })
    }

    fun deletePlaylist(playlist: Playlist) {
        writePlaylists(_playlists.value.filterNot { it.id == playlist.id })
    }

    /** Re-reads the persisted mirror. Kept for callers that used to trigger a server pull. */
    fun refresh() {
        _favorites.value = storage.readFavorites()
        _playlists.value = storage.readPlaylists()
        _recentTracks.value = storage.readRecent()
        _playCounts.value = storage.readPlayCounts()
    }

    /** Drops the local mirror, e.g. on a manual reset. */
    fun signOut() {
        writeFavorites(emptyList())
        writePlaylists(emptyList())
        writeRecent(emptyList())
        _playCounts.value = emptyMap()
        storage.writePlayCounts(emptyMap())
    }

    fun shutdown() {
        // Nothing to tear down: there is no background sync loop anymore.
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

    companion object {
        private const val MAX_RECENT_TRACKS = 50
    }
}
