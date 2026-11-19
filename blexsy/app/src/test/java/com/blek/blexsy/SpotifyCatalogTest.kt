package com.blek.blexsy

import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.SpotifyAlbum
import com.blek.blexsy.data.SpotifyAlbumDetail
import com.blek.blexsy.data.SpotifyArtist
import com.blek.blexsy.data.SpotifyArtistDetail
import com.blek.blexsy.data.SpotifyChart
import com.blek.blexsy.data.SpotifyChartEntry
import com.blek.blexsy.data.SpotifyCharts
import com.blek.blexsy.data.SpotifyPlaylist
import com.blek.blexsy.data.SpotifyPlaylistDetail
import com.blek.blexsy.data.SpotifyTrack
import com.blek.blexsy.data.creditedArtists
import com.blek.blexsy.data.toChartFeed
import com.blek.blexsy.model.ChartRank
import com.blek.blexsy.model.withChartRank
import com.blek.blexsy.data.toAlbumDetail
import com.blek.blexsy.data.toAlbumSummary
import com.blek.blexsy.data.toArtistDetail
import com.blek.blexsy.data.toArtistSummary
import com.blek.blexsy.data.toGenrePlaylist
import com.blek.blexsy.data.toPlaylist
import com.blek.blexsy.data.toTrack
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.isCatalogArtistId
import com.blek.blexsy.model.isSpotifyCatalogId
import com.blek.blexsy.ui.suggestQueries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Search sections come from Spotify while playback stays on YouTube. These tests pin the
 * id detection and the mapping into the app's catalog models.
 */
class SpotifyCatalogTest {

    private fun track(id: String = "4cOdK2wGLETKBW3PvgPWqT", name: String = "Never Gonna Give You Up") =
        SpotifyTrack(
            id = id,
            uri = "spotify:track:$id",
            name = name,
            artists = listOf("Rick Astley"),
            album = "Whenever You Need Somebody",
            durationMs = 213_573L,
            image = "https://i.scdn.co/image/cover"
        )

    @Test
    fun spotifyIdsAreRecognizedWithoutSwallowingYouTubeIds() {
        assertTrue("4cOdK2wGLETKBW3PvgPWqT".isSpotifyCatalogId())
        assertTrue("37i9dQZF1DWYoYGBbGKurt".isSpotifyCatalogId())

        assertFalse("UCuAXFkgsw1L7xaCfnd5JJOw".isSpotifyCatalogId())
        assertFalse("FEmusic_artist".isSpotifyCatalogId())
        assertFalse("dQw4w9WgXcQ".isSpotifyCatalogId())
        assertFalse("VLPL123".isSpotifyCatalogId())
        assertFalse("".isSpotifyCatalogId())

        assertTrue("UCuAXFkgsw1L7xaCfnd5JJOw".isCatalogArtistId())
        assertTrue("0gxyHStUsqpMadRV0Di1Qt".isCatalogArtistId())
        assertFalse("dQw4w9WgXcQ".isCatalogArtistId())
    }

    @Test
    fun spotifyArtistIdUnlocksArtistNavigation() {        assertEquals("0gxyHStUsqpMadRV0Di1Qt", ArtistSummary(id = "0gxyHStUsqpMadRV0Di1Qt", name = "Rick").channelId)

        val track = Track(id = "x", title = "Song", artist = "Rick Astley", artistIds = listOf("0gxyHStUsqpMadRV0Di1Qt"))
        assertEquals("0gxyHStUsqpMadRV0Di1Qt", track.primaryArtistRef)
        assertEquals("0gxyHStUsqpMadRV0Di1Qt" to "Rick Astley", track.artistRefs.single())
    }

    @Test
    fun searchTrackKeepsSpotifyPresentationAndNoFakeYouTubeId() {
        val mapped = track().toTrack()

        assertEquals("4cOdK2wGLETKBW3PvgPWqT", mapped.id)
        assertEquals("Never Gonna Give You Up", mapped.title)
        assertEquals("Rick Astley", mapped.artist)
        assertEquals("Whenever You Need Somebody", mapped.album)
        assertEquals(213_573L, mapped.durationMs)
        assertEquals("https://i.scdn.co/image/cover", mapped.image)
        assertNull(mapped.youtubeId)
    }

    @Test
    fun searchSectionsMapToTheModelsTheUiRenders() {
        val artist = SpotifyArtist("a1", "spotify:artist:a1", "Rick", "avatar").toArtistSummary()
        assertEquals("a1", artist.id)
        assertEquals("Rick", artist.name)
        assertEquals("avatar", artist.image)

        val album = SpotifyAlbum("al1", "spotify:album:al1", "Alb", listOf("Rick", "Guest"), "cover", 1987)
            .toAlbumSummary()
        assertEquals("Rick, Guest", album.artist)
        assertEquals("1987", album.year)
        assertEquals("cover", album.image)

        val playlist = SpotifyPlaylist("p1", "spotify:playlist:p1", "Lofi", "Spotify", "pl").toGenrePlaylist()
        assertEquals("p1", playlist.id)
        assertEquals("By Spotify", playlist.description)
        assertEquals("pl", playlist.image)
    }

    @Test
    fun albumDetailTracksCarryArtistIdsSoTheHeaderStaysClickable() {
        val detail = SpotifyAlbumDetail(
            id = "al1",
            name = "Whenever You Need Somebody",
            type = "ALBUM",
            releaseYear = 1987,
            artists = listOf(SpotifyArtist("0gxyHStUsqpMadRV0Di1Qt", "spotify:artist:0gxyHStUsqpMadRV0Di1Qt", "Rick Astley")),
            image = "cover",
            totalTracks = 2,
            tracks = listOf(track(), track(id = "t2", name = "Together Forever").copy(album = null, image = null))
        ).toAlbumDetail()

        assertEquals("Whenever You Need Somebody", detail.name)
        assertEquals("Rick Astley", detail.artist)
        assertEquals("1987", detail.year)
        assertEquals("cover", detail.image)
        assertEquals(2, detail.tracks.size)
        // Track-level album/cover fall back to the album header.
        assertEquals("Whenever You Need Somebody", detail.tracks[1].album)
        assertEquals("cover", detail.tracks[1].image)
        assertEquals("0gxyHStUsqpMadRV0Di1Qt", detail.tracks[1].primaryArtistRef)
    }

    @Test
    fun artistDetailSaturatesCountsAndMapsShelves() {
        val artistId = "0gxyHStUsqpMadRV0Di1Qt"
        val detail = SpotifyArtistDetail(
            id = artistId,
            name = "Rick",
            image = "avatar",
            monthlyListeners = 8_563_428,
            followers = Long.MAX_VALUE,
            biography = "bio",
            topTracks = listOf(track()),
            albums = listOf(SpotifyAlbum("al1", "spotify:album:al1", "Alb", listOf("Rick"), "cover", 2024)),
            relatedArtists = listOf(SpotifyArtist("r1", "spotify:artist:r1", "Rel", "rel"))
        ).toArtistDetail()

        assertEquals(8_563_428, detail.monthlyListeners)
        assertEquals(Int.MAX_VALUE, detail.followers)
        assertEquals("bio", detail.biography)
        assertEquals(artistId, detail.topTracks.single().primaryArtistRef)
        assertEquals("al1", detail.albums.single().id)
        assertEquals("Rel", detail.similarArtists.single().name)
    }

    @Test
    fun playlistDetailMapsTracksForPlaybackFallback() {
        val detail = SpotifyPlaylistDetail(
            id = "p1",
            name = "Lofi",
            image = "pl",
            totalTracks = 205,
            tracks = listOf(track().copy(image = null))
        ).toPlaylist()

        assertEquals("p1", detail.id)
        assertEquals("Lofi", detail.name)
        assertEquals("Rick Astley", detail.tracks.single().artist)
        // Playlist items carry no own artwork, so the playlist cover is the fallback.
        assertEquals("pl", detail.tracks.single().image)
        assertNull(detail.tracks.single().youtubeId)
    }

    @Test
    fun multipleArtistsKeepTheirOwnIdsAndNames() {
        val first = "0gxyHStUsqpMadRV0Di1Qt"
        val second = "3MMDw11m6HJrXlGOE3honc"
        val track = Track(
            id = "x",
            title = "Aku Harus Pergi",
            artist = "Whisnu Santika, Ari Lesmana",
            artistIds = listOf(first, second)
        )

        assertEquals(listOf(first to "Whisnu Santika", second to "Ari Lesmana"), track.artistRefs)
        assertEquals(first, track.primaryArtistRef)
    }

    @Test
    fun artistWithoutAnIdKeepsItsNameAsPlaceholder() {
        val known = "0gxyHStUsqpMadRV0Di1Qt"
        val track = Track(
            id = "x",
            title = "Song",
            artist = "Rick Astley, Guest",
            artistIds = listOf(known)
        )

        assertEquals(listOf(known to "Rick Astley", "Guest" to "Guest"), track.artistRefs)
    }

    @Test
    fun sectionNamesMustMatchTheQuery() {
        val api = BlexsyApiClient()

        assertTrue(api.queryMatchesName("ari haryanto", "Ari Haryanto"))
        assertTrue(api.queryMatchesName("bahagiamu", "Bahagiamu"))
        assertTrue(api.queryMatchesName("ari haryanto lagu", "Ari Haryanto"))
        assertFalse(api.queryMatchesName("ari haryanto", "Ari Lesmana"))
        assertFalse(api.queryMatchesName("ari haryanto", "Menua Bersamamu"))
    }

    @Test
    fun artistsComeFromTheTracksThatMatched() {
        val eminem = "0gxyHStUsqpMadRV0Di1Qt"
        val performer = SpotifyArtist(id = eminem, name = "Eminem", image = "https://img/eminem.jpg")
        val tracks = listOf(
            SpotifyTrack(
                id = "t1",
                name = "Lose Yourself",
                artists = listOf("Eminem"),
                artistIds = listOf(eminem)
            )
        )

        // The avatar comes from the artist object, the id from the track that credited it.
        assertEquals(
            listOf(ArtistSummary(id = eminem, name = "Eminem", image = "https://img/eminem.jpg")),
            creditedArtists(tracks, listOf(performer))
        )
        // A name with no openable artist id is not a page we can navigate to.
        assertTrue(
            creditedArtists(listOf(SpotifyTrack(id = "t2", name = "Song", artists = listOf("Guest")))).isEmpty()
        )
    }

    @Test
    fun suggestionsAreLocalPrefixMatchesOnly() {
        val known = listOf("Eminem", "Eminem", "Emotional", "Taylor Swift", "em")

        // Prefix match, case-insensitive, deduped; a one-character query never suggests.
        assertEquals(listOf("Eminem", "Emotional"), suggestQueries("em", known))
        assertEquals(listOf("Eminem"), suggestQueries("EMIN", known))
        assertTrue(suggestQueries("e", known).isEmpty())
        // An exact hit is not a suggestion: tapping it would just re-run the same query.
        assertTrue(suggestQueries("taylor swift", known).isEmpty())
    }

    @Test
    fun countryChartKeepsItsRanksAndOrder() {
        val charts = SpotifyCharts(
            country = "ID",
            color = "#F037A5",
            charts = listOf(
                // The server leaves every chart it did not expand empty.
                SpotifyChart(id = "weekly", name = "Top Songs - Indonesia", frequency = "weekly"),
                SpotifyChart(
                    id = "37i9dQZEVXbObFQZ3JLcXt",
                    name = "Top 50 - Indonesia",
                    image = "chart-cover",
                    frequency = "daily",
                    updatedAt = "2026-11-14T14:01:05Z",
                    entries = listOf(
                        SpotifyChartEntry(position = 2, previous = 1, plays = 900L, track = track(id = "t2", name = "Second")),
                        SpotifyChartEntry(
                            position = 1,
                            previous = null,
                            plays = 1_000L,
                            track = track(id = "t1", name = "First").copy(image = null)
                        )
                    )
                )
            )
        )

        val feed = charts.toChartFeed()

        assertEquals("Top 50 - Indonesia", feed.title)
        assertEquals("daily", feed.frequency)
        assertEquals("ID", feed.country)
        assertEquals("2026-11-14T14:01:05Z", feed.updatedAt)
        // Entries come back sorted by position, not in payload order.
        assertEquals(listOf("t1", "t2"), feed.tracks.map { it.id })
        // Chart cover is the fallback for a track with no art of its own.
        assertEquals("chart-cover", feed.tracks.first().image)

        assertEquals(1, feed.rankOf("t1")?.position)
        assertTrue(feed.rankOf("t1")!!.isNew)
        assertNull(feed.rankOf("t1")?.delta)
        // Falling one place is a negative delta; climbing is positive.
        assertEquals(-1, feed.rankOf("t2")?.delta)
        assertEquals(900L, feed.rankOf("t2")?.plays)

        // Each track carries its own rank so any list renders the real position and marker.
        val (first, second) = feed.tracks
        assertEquals(1, first.rank)
        assertEquals("new", first.rankStatus)
        assertEquals(2, second.rank)
        assertEquals("down", second.rankStatus)
    }

    @Test
    fun rankMarkersFollowTheMovement() {
        fun mapped(id: String) = track(id = id).toTrack()

        val climbing = mapped("a").withChartRank(ChartRank(position = 3, previous = 7))
        assertEquals("up", climbing.rankStatus)
        assertEquals(3, climbing.rank)

        val flat = mapped("b").withChartRank(ChartRank(position = 3, previous = 3))
        assertEquals("same", flat.rankStatus)

        val fresh = mapped("c").withChartRank(ChartRank(position = 3))
        assertEquals("new", fresh.rankStatus)
    }

    @Test
    fun aMarketChartCarriesTrendingAndPopularArtists() {
        val charts = SpotifyCharts(
            country = "id",
            charts = listOf(
                SpotifyChart(
                    id = "37i9dQZEVXbObFQZ3JLcXt",
                    name = "Top 50 - Indonesia",
                    frequency = "daily",
                    entries = listOf(SpotifyChartEntry(position = 1, plays = 10L, track = track()))
                )
            ),
            trending = listOf(track(id = "tr1", name = "Rising")),
            popularArtists = listOf(SpotifyArtist("a1", "spotify:artist:a1", "Raim Laode", "avatar"))
        )

        val feed = charts.toChartFeed()

        // Both shelves ride along with the same request, so the home feed needs no extra call.
        assertEquals(listOf("tr1"), feed.trending.map { it.id })
        assertEquals(listOf("Raim Laode"), feed.popularArtists.map { it.name })
        assertEquals("avatar", feed.popularArtists.single().image)
    }

    @Test
    fun aCountryWithoutChartsYieldsAnEmptyFeed() {
        // Spotify has no hub for some countries: 200 with everything empty, not an error.
        val feed = SpotifyCharts(country = "RU").toChartFeed()

        assertTrue(feed.tracks.isEmpty())
        assertTrue(feed.ranks.isEmpty())
        assertEquals("RU", feed.country)
        assertNull(feed.frequency)
    }
}
