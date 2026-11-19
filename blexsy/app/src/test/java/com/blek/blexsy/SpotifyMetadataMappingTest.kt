package com.blek.blexsy

import com.blek.blexsy.data.AlbumUnionDto
import com.blek.blexsy.data.ArtistUnionDto
import com.blek.blexsy.data.PlaylistV2Dto
import com.blek.blexsy.data.RawWrapper
import com.blek.blexsy.data.RecommendationsWrapperDto
import com.blek.blexsy.data.SpotifyBrowseCategory
import com.blek.blexsy.data.SpotifyGenrePage
import com.blek.blexsy.data.SpotifySuggestDto
import com.blek.blexsy.data.TrackCreditsUnionDto
import com.blek.blexsy.data.TrackDto
import com.blek.blexsy.data.toCredits
import com.blek.blexsy.data.toDetail
import com.blek.blexsy.data.toSuggestions
import com.blek.blexsy.data.toTrack
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the mapping from Spotify's raw pathfinder unions (what the API returns) to
 * the flat models the app consumes. Fixtures are trimmed copies of real payloads.
 */
class SpotifyMetadataMappingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private inline fun <reified T> decode(raw: String): T = json.decodeFromString<T>(raw.trimIndent())

    @Test
    fun trackDetailJoinsArtistsAndPicksLargestCover() {
        val dto = decode<RawWrapper<TrackDto>>(
            """
            {"trackUnion":{"id":"t1","name":"Song","duration":{"totalMilliseconds":213573},
             "contentRating":{"label":"NONE"},"playability":{"playable":true},
             "firstArtist":{"items":[{"uri":"spotify:artist:a1","profile":{"name":"Rick"}}]},
             "otherArtists":{"items":[{"profile":{"name":"Guest"}}]},
             "albumOfTrack":{"name":"Album","uri":"spotify:album:al1",
               "coverArt":{"sources":[{"url":"small","width":64},{"url":"big","width":640}]}}}}
            """
        ).trackUnion!!.toDetail()!!

        assertEquals("t1", dto.track.id)
        assertEquals("Song", dto.track.name)
        assertEquals(listOf("Rick", "Guest"), dto.track.artists)
        assertEquals(213573L, dto.track.durationMs)
        assertEquals("Album", dto.track.album)
        assertEquals("big", dto.track.image)
        assertEquals(listOf("a1"), dto.track.artistIds)
        assertEquals(false, dto.explicit)
    }

    @Test
    fun albumDetailDerivesIdFromUriAndMapsTracks() {
        val dto = decode<RawWrapper<AlbumUnionDto>?>(
            """
            {"albumUnion":{"name":"Alb","uri":"spotify:album:al1","type":"ALBUM","date":{"year":1987},
             "artists":{"items":[{"uri":"spotify:artist:a1","profile":{"name":"Rick"}}]},
             "coverArt":{"sources":[{"url":"cover","width":300}]},
             "tracksV2":{"totalCount":36,"items":[
               {"track":{"name":"T1","uri":"spotify:track:x1","trackNumber":1,
                "duration":{"totalMilliseconds":1000},
                "artists":{"items":[{"uri":"spotify:artist:a1","profile":{"name":"Rick"}}]}}}]}}}
            """
        )!!.albumUnion!!.toDetail()!!

        assertEquals("al1", dto.id)
        assertEquals(1987, dto.releaseYear)
        assertEquals("cover", dto.image)
        assertEquals(36, dto.totalTracks)
        assertEquals(1, dto.tracks.size)
        assertEquals("x1", dto.tracks[0].id)
        assertEquals("Alb", dto.tracks[0].album)
        assertEquals("cover", dto.tracks[0].image)
        assertEquals(listOf("Rick"), dto.tracks[0].artists)
    }

    @Test
    fun artistDetailMapsTopTracksDiscographyAndRelated() {
        val dto = decode<RawWrapper<ArtistUnionDto>?>(
            """
            {"artistUnion":{"id":"a1","uri":"spotify:artist:a1",
             "profile":{"name":"Rick","biography":{"text":"bio"}},
             "visuals":{"avatarImage":{"sources":[{"url":"avatar","width":640}]}},
             "stats":{"monthlyListeners":8563428,"followers":1634108},
             "discography":{
               "topTracks":{"items":[{"track":{"id":"tt","name":"Top","uri":"spotify:track:tt",
                 "duration":{"totalMilliseconds":5},
                 "artists":{"items":[{"profile":{"name":"Rick"}}]}}}]},
               "albums":{"items":[{"releases":{"items":[{"name":"Alb","uri":"spotify:album:al",
                 "date":{"year":2024},"coverArt":{"sources":[{"url":"ac"}]}}]}}]}},
             "relatedContent":{"relatedArtists":{"items":[{"id":"r1","profile":{"name":"Rel"},
               "uri":"spotify:artist:r1","visuals":{"avatarImage":{"sources":[{"url":"rv"}]}}}]}}}}
            """
        )!!.artistUnion!!.toDetail()!!

        assertEquals("Rick", dto.name)
        assertEquals("avatar", dto.image)
        assertEquals(8563428L, dto.monthlyListeners)
        assertEquals("bio", dto.biography)
        assertEquals("tt", dto.topTracks.single().id)
        assertEquals("al", dto.albums.single().id)
        assertEquals(2024, dto.albums.single().year)
        assertEquals("Rel", dto.relatedArtists.single().name)
        assertEquals("rv", dto.relatedArtists.single().image)
    }

    @Test
    fun playlistDetailMapsItemV2AndTrackDuration() {
        val dto = decode<RawWrapper<PlaylistV2Dto>>(
            """
            {"playlistV2":{"name":"PL","uri":"spotify:playlist:p1","description":"desc","followers":5,
             "images":{"items":[{"sources":[{"url":"pl"}]}]},
             "ownerV2":{"data":{"name":"Spotify"}},
             "content":{"totalCount":205,"items":[{"itemV2":{"data":{"name":"golfing",
               "uri":"spotify:track:g1","trackDuration":{"totalMilliseconds":180000},
               "artists":{"items":[{"profile":{"name":"X"}}]},
               "albumOfTrack":{"name":"A","coverArt":{"sources":[{"url":"cov"}]}}}}}]}}}
            """
        ).playlistV2!!.toDetail()!!

        assertEquals("p1", dto.id)
        assertEquals("Spotify", dto.owner)
        assertEquals("pl", dto.image)
        assertEquals(205, dto.totalTracks)
        assertEquals(180000L, dto.tracks.single().durationMs)
        assertEquals("A", dto.tracks.single().album)
        assertEquals("cov", dto.tracks.single().image)
    }

    @Test
    fun genericErrorPayloadsMapToNull() {
        assertNull(decode<RawWrapper<TrackDto>>("""{"trackUnion":{"__typename":"GenericError"}}""").trackUnion!!.toDetail())
        assertNull(decode<RawWrapper<AlbumUnionDto>?>("null")?.albumUnion?.toDetail())
        assertNull(decode<RawWrapper<PlaylistV2Dto>>("""{"playlistV2":{"__typename":"GenericError"}}""").playlistV2!!.toDetail())
    }

    @Test
    fun decodeSpotifyChartsPayload() {
        val raw = """
        {
          "country": "id",
          "color": "#F037A5",
          "charts": [
            {
              "id": "37i9dQZEVXbObFQZ3JLcXt",
              "uri": "spotify:playlist:37i9dQZEVXbObFQZ3JLcXt",
              "name": "Top 50 - Indonesia",
              "description": "Your daily update of the most played tracks right now - Indonesia",
              "image": "https://charts-images.scdn.co/assets/locale_en/regional/daily/region_id_default.jpg",
              "frequency": "daily",
              "updated_at": "2026-11-14T14:01:05Z",
              "rank_type": "plays",
              "entries": [
                {
                  "position": 1,
                  "previous": 3,
                  "peak": null,
                  "plays": 380572470,
                  "track": {
                    "id": "4xoY4lZNoTjEuHsSmhgF1G",
                    "uri": "spotify:track:4xoY4lZNoTjEuHsSmhgF1G",
                    "name": "Sesi Potret",
                    "artists": ["eńau", "Ari Lesmana"],
                    "album": "Sesi Potret",
                    "duration_ms": 243000,
                    "image": "https://i.scdn.co/image/ab67616d00001e02172768978c7f929803ad7e8b"
                  }
                }
              ]
            }
          ],
          "trending": [
            {
              "id": "2YjFqxQicrjoam7PavaViP",
              "uri": "spotify:track:2YjFqxQicrjoam7PavaViP",
              "name": "Birds Call Your Name",
              "artists": ["Nicole Rossi"],
              "album": "Birds Call Your Name",
              "duration_ms": null,
              "image": "https://i.scdn.co/image/ab67616d00001e02ab267b9c6d4c9f91d1cf4d20"
            }
          ],
          "popular_albums": [
            {
              "id": "4FiZgqcHTC0jmfnnG2pDp0",
              "uri": "spotify:album:4FiZgqcHTC0jmfnnG2pDp0",
              "name": "iqro'",
              "artists": ["Raim Laode"],
              "image": "https://i.scdn.co/image/ab67616d00001e02a9d1f9b5d3275b0625e13e79"
            }
          ],
          "popular_artists": [
            {
              "id": "5LcNPa8f8bRmZqELgoRFkN",
              "uri": "spotify:artist:5LcNPa8f8bRmZqELgoRFkN",
              "name": "Raim Laode",
              "image": "https://i.scdn.co/image/ab6761610000e5ebf0835eb9adc7cee5169194d0"
            }
          ]
        }
        """
        val charts = decode<com.blek.blexsy.data.SpotifyCharts>(raw)
        assertEquals("id", charts.country)
        assertEquals(1, charts.charts.size)
        val primary = charts.primary
        assertEquals("Top 50 - Indonesia", primary?.name)
        assertEquals(1, primary?.entries?.size)
        assertEquals("4xoY4lZNoTjEuHsSmhgF1G", primary?.entries?.first()?.track?.id)
        assertEquals(380572470L, primary?.entries?.first()?.plays)
        assertEquals(1, charts.trending.size)
        assertEquals("Birds Call Your Name", charts.trending.first().name)
        assertEquals(1, charts.popularAlbums.size)
        assertEquals("iqro'", charts.popularAlbums.first().name)
        assertEquals(1, charts.popularArtists.size)
        assertEquals("Raim Laode", charts.popularArtists.first().name)
    }

    @Test
    fun trackCreditsMapsContributorsGroupedByRoleAndSources() {
        val raw = """
        {
          "trackUnion": {
            "creditsTrait": {
              "contributors": {
                "items": [
                  {
                    "name": "eńau",
                    "role": "Main Artist",
                    "roleGroup": { "name": "Artist" },
                    "uri": "spotify:artist:a1"
                  },
                  {
                    "name": "Ari Lesmana",
                    "role": "Main Artist",
                    "roleGroup": { "name": "Artist" },
                    "uri": "spotify:artist:a2"
                  },
                  {
                    "name": "eńau",
                    "role": "Composer",
                    "roleGroup": { "name": "Composition & Lyrics" }
                  }
                ]
              },
              "sources": {
                "items": [
                  { "name": "Firefly Records" }
                ]
              }
            }
          }
        }
        """
        val credits = decode<RawWrapper<TrackCreditsUnionDto>>(raw).trackUnion!!.toCredits("4xoY4lZNoTjEuHsSmhgF1G")!!

        assertEquals("4xoY4lZNoTjEuHsSmhgF1G", credits.trackId)
        assertEquals(2, credits.roles.size)

        val mainArtists = credits.roles.first { it.title == "Main Artist" }
        assertEquals(listOf("eńau", "Ari Lesmana"), mainArtists.people.map { it.name })
        assertEquals("a1", mainArtists.people[0].url)
        assertEquals("a2", mainArtists.people[1].url)

        val composers = credits.roles.first { it.title == "Composer" }
        assertEquals(listOf("eńau"), composers.people.map { it.name })

        assertEquals(listOf("Firefly Records"), credits.sources)
    }

    @Test
    fun searchSuggestExtractsAutoCompletesAndDeduplicates() {
        val raw = """
        {
          "searchV2": {
            "query": "sheila",
            "topResultsV2": {
              "itemsV2": [
                {
                  "__typename": "TopResultHit",
                  "item": {
                    "__typename": "SearchAutoCompleteEntity",
                    "data": {
                      "text": "sheila on 7",
                      "uri": "spotify:search:sheila+on+7"
                    }
                  }
                },
                {
                  "__typename": "TopResultHit",
                  "item": {
                    "__typename": "SearchAutoCompleteEntity",
                    "data": {
                      "text": "sheila majid",
                      "uri": "spotify:search:sheila+majid"
                    }
                  }
                },
                {
                  "__typename": "TopResultHit",
                  "item": {
                    "__typename": "TrackResponseWrapper",
                    "data": {
                      "name": "Tunggu Aku Di Jakarta"
                    }
                  }
                }
              ]
            }
          }
        }
        """
        val suggestions = decode<SpotifySuggestDto>(raw).toSuggestions()
        assertEquals(listOf("sheila on 7", "sheila majid", "Tunggu Aku Di Jakarta"), suggestions)
    }

    @Test
    fun decodeRecommendationsPayload() {
        val raw = """
        {
          "seoRecommendedTrack": {
            "items": [
              {
                "data": {
                  "id": "t1",
                  "name": "Song 1",
                  "uri": "spotify:track:t1",
                  "firstArtist": { "items": [{ "profile": { "name": "Artist 1" } }] }
                }
              }
            ]
          }
        }
        """
        val dto = decode<RecommendationsWrapperDto>(raw)
        val tracks = dto.seoRecommendedTrack?.items?.mapNotNull { it.data?.toTrack() }.orEmpty()
        assertEquals(1, tracks.size)
        assertEquals("t1", tracks[0].id)
        assertEquals("Song 1", tracks[0].name)
    }

    @Test
    fun decodeBrowseAndGenrePayloads() {
        val browseRaw = """
        [
          {
            "id": "pop",
            "title": "Pop",
            "color": "#dc148c",
            "image_url": "https://img.jpg",
            "href": "/genre/pop"
          }
        ]
        """
        val categories = decode<List<SpotifyBrowseCategory>>(browseRaw)
        assertEquals(1, categories.size)
        assertEquals("pop", categories[0].id)
        assertEquals("Pop", categories[0].title)

        val genreRaw = """
        {
          "id": "pop",
          "title": "Pop",
          "sections": [
            {
              "title": "All Things Pop",
              "items": [
                {
                  "title": "Lagi Viral",
                  "description": "Viral tracks",
                  "followers": 1500000,
                  "href": "/playlist/p1",
                  "image_url": "https://p1.jpg"
                }
              ]
            }
          ]
        }
        """
        val genre = decode<SpotifyGenrePage>(genreRaw)
        assertEquals("pop", genre.id)
        assertEquals(1, genre.sections.size)
        assertEquals("Lagi Viral", genre.sections[0].items[0].title)
    }
}
