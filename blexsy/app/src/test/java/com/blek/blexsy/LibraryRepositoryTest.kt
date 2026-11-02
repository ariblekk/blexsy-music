package com.blek.blexsy

import com.blek.blexsy.data.InMemoryLibraryStorage
import com.blek.blexsy.data.LibraryApi
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeLibraryApi(
    var online: Boolean = true,
    var failToggles: Boolean = false
) : LibraryApi {
    override var token: String? = "test-token"

    val favorites = mutableListOf<Track>()
    val playlists = mutableListOf<Playlist>()

    var toggleCalls = 0
    var createCalls = 0
    var deleteCalls = 0
    var updateCalls = 0

    private fun requireOnline() {
        if (!online) throw IllegalStateException("offline")
    }

    override suspend fun getFavorites(): List<Track> {
        requireOnline()
        return favorites.toList()
    }

    override suspend fun toggleFavorite(track: Track) {
        requireOnline()
        toggleCalls++
        if (failToggles) throw IllegalStateException("toggle failed")
        if (favorites.any { it.id == track.id }) {
            favorites.removeAll { it.id == track.id }
        } else {
            favorites.add(0, track)
        }
    }

    override suspend fun getPlaylists(): List<Playlist> {
        requireOnline()
        return playlists.toList()
    }

    override suspend fun createPlaylist(name: String, track: Track?): Playlist {
        requireOnline()
        createCalls++
        val created = Playlist(
            id = "srv-${playlists.size + 1}",
            name = name,
            tracks = listOfNotNull(track)
        )
        playlists.add(created)
        return created
    }

    override suspend fun deletePlaylist(id: String) {
        requireOnline()
        deleteCalls++
        playlists.removeAll { it.id == id }
    }

    override suspend fun updatePlaylistTracks(id: String, tracks: List<Track>) {
        requireOnline()
        updateCalls++
        val idx = playlists.indexOfFirst { it.id == id }
        if (idx >= 0) {
            playlists[idx] = playlists[idx].copy(tracks = tracks)
        }
    }

    val recent = mutableListOf<Track>()
    var recordRecentCalls = 0

    override suspend fun getRecent(limit: Int): List<Track> {
        requireOnline()
        return recent.take(limit)
    }

    override suspend fun recordRecent(track: Track) {
        requireOnline()
        recordRecentCalls++
        recent.removeAll { it.id == track.id }
        recent.add(0, track)
    }
}

class LibraryRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun repository(
        api: LibraryApi,
        storage: InMemoryLibraryStorage = InMemoryLibraryStorage()
    ) = LibraryRepository(api, storage, scope)

    private val track = Track(id = "t1", title = "Lagu", artist = "Artis")

    @Test
    fun likeShowsInstantlyAndStillQueuesWhenOffline() {
        val api = FakeLibraryApi(online = false)
        val storage = InMemoryLibraryStorage()
        val repo = repository(api, storage)

        repo.toggleFavorite(track)

        assertTrue(repo.isFavorite("t1"))
        assertEquals(1, repo.pendingOps.value)
        assertEquals(listOf("t1"), storage.readFavorites().map { it.id })
        repo.shutdown()
    }

    @Test
    fun likeIsPushedInBackgroundWhenOnline() {
        val api = FakeLibraryApi()
        val repo = repository(api)

        repo.toggleFavorite(track)

        assertTrue(repo.isFavorite("t1"))
        assertEquals(1, api.toggleCalls)
        assertEquals(0, repo.pendingOps.value)
        repo.shutdown()
    }

    @Test
    fun unlikeIsInstantToo() {
        val api = FakeLibraryApi()
        val repo = repository(api)

        repo.toggleFavorite(track)
        repo.toggleFavorite(track)

        assertTrue(!repo.isFavorite("t1"))
        assertEquals(0, repo.favorites.value.size)
        assertEquals(2, api.toggleCalls)
        assertTrue(api.favorites.isEmpty())
        repo.shutdown()
    }

    @Test
    fun createPlaylistAppearsBeforeServerResponds() {
        val api = FakeLibraryApi()
        val repo = repository(api)

        val created = repo.createPlaylist("Chill")

        assertTrue(created.id.startsWith(LibraryRepository.LOCAL_PLAYLIST_PREFIX))
        assertEquals(listOf("Chill"), repo.playlists.value.map { it.name })
        repo.shutdown()
    }

    @Test
    fun syncedPlaylistSwapsLocalIdForServerId() {
        val api = FakeLibraryApi()
        val repo = repository(api)

        repo.createPlaylist("Chill")
        repo.refresh()

        assertEquals(1, api.createCalls)
        assertEquals(0, repo.pendingOps.value)
        assertEquals(listOf("srv-1"), repo.playlists.value.map { it.id })
        repo.shutdown()
    }

    @Test
    fun offlinePlaylistSyncsOnNextRefresh() {
        val api = FakeLibraryApi(online = false)
        val repo = repository(api)

        repo.createPlaylist("Offline mix")
        assertEquals(1, repo.pendingOps.value)

        api.online = true
        repo.refresh()

        assertEquals(0, repo.pendingOps.value)
        assertEquals(1, api.createCalls)
        assertEquals(listOf("srv-1"), repo.playlists.value.map { it.id })
        repo.shutdown()
    }

    @Test
    fun pendingLikeIsNotRolledBackByServerPull() {
        val api = FakeLibraryApi(failToggles = true)
        val repo = repository(api)

        repo.toggleFavorite(track)
        repo.refresh()

        assertTrue(repo.isFavorite("t1"))
        assertEquals(1, repo.pendingOps.value)
        repo.shutdown()
    }

    @Test
    fun deletingUnsyncedPlaylistCancelsTheCreate() {
        val api = FakeLibraryApi(online = false)
        val repo = repository(api)

        val created = repo.createPlaylist("Sementara")
        repo.deletePlaylist(created)

        assertTrue(repo.playlists.value.isEmpty())
        assertEquals(0, repo.pendingOps.value)

        api.online = true
        repo.refresh()

        assertEquals(0, api.createCalls)
        assertEquals(0, api.deleteCalls)
        repo.shutdown()
    }

    @Test
    fun deletingSyncedPlaylistIsOptimisticAndHitsServer() {
        val api = FakeLibraryApi()
        val repo = repository(api)

        repo.createPlaylist("Hapus aku")
        val synced = repo.playlists.value.single()
        repo.deletePlaylist(synced)

        assertTrue(repo.playlists.value.isEmpty())
        assertEquals(1, api.deleteCalls)
        assertEquals(0, repo.pendingOps.value)
        repo.shutdown()
    }

    @Test
    fun deletingPlaylistFromAStaleOptimisticScreenStillHitsServer() {
        val api = FakeLibraryApi()
        val repo = repository(api)

        // The returned object still carries the optimistic id, like a detail screen that
        // was opened before the background sync finished.
        val optimistic = repo.createPlaylist("Stale")
        repo.deletePlaylist(optimistic)

        assertTrue(repo.playlists.value.isEmpty())
        assertEquals(1, api.deleteCalls)
        repo.shutdown()
    }

    @Test
    fun refreshPullsServerFavoritesWhenQueueIsEmpty() = runBlocking {
        val api = FakeLibraryApi()
        api.favorites.add(Track(id = "srv-track", title = "Dari server", artist = "A"))
        val repo = repository(api)

        repo.refresh()

        assertEquals(listOf("srv-track"), repo.favorites.value.map { it.id })
        repo.shutdown()
    }

    @Test
    fun recordRecentShowsInstantlyAndStoresLocallyForGuest() {
        val api = FakeLibraryApi(online = true).apply { token = "guest" }
        val storage = InMemoryLibraryStorage()
        val repo = repository(api, storage)

        repo.recordRecent(track)

        assertEquals(listOf("t1"), repo.recentTracks.value.map { it.id })
        assertEquals(listOf("t1"), storage.readRecent().map { it.id })
        assertEquals(1, repo.pendingOps.value)
        assertEquals(0, api.recordRecentCalls)
        repo.shutdown()
    }

    @Test
    fun guestRecentsAreFlushedToServerWhenAuthenticated() = runBlocking {
        val api = FakeLibraryApi(online = true).apply { token = "guest" }
        val storage = InMemoryLibraryStorage()
        val repo = repository(api, storage)

        val track2 = Track(id = "t2", title = "Lagu 2", artist = "Artis 2")
        repo.recordRecent(track)
        repo.recordRecent(track2)

        assertEquals(2, repo.pendingOps.value)
        assertEquals(0, api.recordRecentCalls)

        // User authenticates with real token
        api.token = "real-account-token"
        repo.refresh()

        assertEquals(2, api.recordRecentCalls)
        assertEquals(0, repo.pendingOps.value)
        assertTrue(api.recent.any { it.id == "t1" })
        assertTrue(api.recent.any { it.id == "t2" })
        repo.shutdown()
    }

    @Test
    fun signOutClearsTheLocalMirror() {
        val api = FakeLibraryApi(online = false)
        val storage = InMemoryLibraryStorage()
        val repo = repository(api, storage)

        repo.toggleFavorite(track)
        repo.createPlaylist("Chill")
        repo.recordRecent(track)
        repo.signOut()

        assertTrue(repo.favorites.value.isEmpty())
        assertTrue(repo.playlists.value.isEmpty())
        assertTrue(repo.recentTracks.value.isEmpty())
        assertEquals(0, repo.pendingOps.value)
        assertTrue(storage.readFavorites().isEmpty())
        assertTrue(storage.readRecent().isEmpty())
        assertTrue(storage.readOps().isEmpty())
        repo.shutdown()
    }

    @Test
    fun addTrackToPlaylistUpdatesLocallyAndSyncsToServer() {
        val api = FakeLibraryApi()
        val repo = repository(api)

        val created = repo.createPlaylist("Chill")
        repo.refresh()
        assertEquals(1, api.createCalls)

        val newTrack = Track(id = "new-1", title = "Banger")
        repo.addTrackToPlaylist(created, newTrack)

        val local = repo.playlists.value.first()
        assertEquals(listOf("new-1"), local.tracks.map { it.id })

        repo.refresh()
        assertEquals(1, api.updateCalls)
        assertEquals(listOf("new-1"), api.playlists.first().tracks.map { it.id })
        repo.shutdown()
    }
}
