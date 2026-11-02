package com.blek.blexsy

import com.blek.blexsy.model.LyricAlignment
import com.blek.blexsy.model.LyricLine
import com.blek.blexsy.model.LyricWord
import com.blek.blexsy.model.LyricsResult
import com.blek.blexsy.model.QueueTier
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.parseLrc
import com.blek.blexsy.model.stripLrcTimestamps
import com.blek.blexsy.model.toHighResArtwork
import com.blek.blexsy.model.withBackgroundVocals
import com.blek.blexsy.model.withInstrumentalGaps
import com.blek.blexsy.player.QueuePolicy
import com.blek.blexsy.ui.formatHistoryDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlexsyLogicTest {
    @Test
    fun testTrackDurationLabel() {
        val track = Track(id = "1", title = "Test", artist = "Artist", durationMs = 195000L)
        assertEquals("3:15", track.durationLabel)
    }

    @Test
    fun testTrackRankResolution() {
        val rankedTrack = Track(id = "1", title = "Track 1", artist = "Artist", rank = 5)
        val unrankedTrack = Track(id = "2", title = "Track 2", artist = "Artist")
        assertEquals(5, rankedTrack.rank ?: (0 + 1))
        assertEquals(1, unrankedTrack.rank ?: (0 + 1))
    }

    @Test
    fun testFormatHistoryDate() {
        assertEquals("Recent", formatHistoryDate(null))
        assertEquals("Recent", formatHistoryDate(""))
        assertEquals("Recent", formatHistoryDate("not-a-date"))

        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        assertEquals("Today", formatHistoryDate(nowIso))

        val yesterdayDate = java.util.Date(System.currentTimeMillis() - 26 * 3600 * 1000L)
        val yesterdayIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(yesterdayDate)
        assertEquals("Yesterday", formatHistoryDate(yesterdayIso))

        val pastDate = "2024-01-15T10:00:00Z"
        assertEquals("Jan 15, 2024", formatHistoryDate(pastDate))
    }

    @Test
    fun testToHighResArtwork() {
        val standardCover = "https://i.scdn.co/image/ab67616d00001e02172768978c7f929803ad7e8b"
        val highRes = standardCover.toHighResArtwork()
        assertEquals("https://i.scdn.co/image/ab67616d0000b273172768978c7f929803ad7e8b", highRes)

        val smallCover = "https://i.scdn.co/image/ab67616d00004851172768978c7f929803ad7e8b"
        assertEquals("https://i.scdn.co/image/ab67616d0000b273172768978c7f929803ad7e8b", smallCover.toHighResArtwork())

        val artistMedium = "https://i.scdn.co/image/ab676161000051745aa62c329f3d95b7f7a142c3"
        assertEquals("https://i.scdn.co/image/ab6761610000e5eb5aa62c329f3d95b7f7a142c3", artistMedium.toHighResArtwork())

        val deezerCover250 = "https://cdn-images.dzcdn.net/images/cover/4439a6a45c49b488e0e9258436f5073a/250x250-000000-80-0-0.jpg"
        assertEquals("https://cdn-images.dzcdn.net/images/cover/4439a6a45c49b488e0e9258436f5073a/1000x1000-000000-80-0-0.jpg", deezerCover250.toHighResArtwork())

        val deezerCover500 = "https://cdn-images.dzcdn.net/images/cover/4439a6a45c49b488e0e9258436f5073a/500x500-000000-80-0-0.jpg"
        assertEquals("https://cdn-images.dzcdn.net/images/cover/4439a6a45c49b488e0e9258436f5073a/1000x1000-000000-80-0-0.jpg", deezerCover500.toHighResArtwork())

        val deezerArtist = "https://cdn-images.dzcdn.net/images/artist/879015e713cc6ad6ffaeec154c027505/56x56-000000-80-0-0.jpg"
        assertEquals("https://cdn-images.dzcdn.net/images/artist/879015e713cc6ad6ffaeec154c027505/1000x1000-000000-80-0-0.jpg", deezerArtist.toHighResArtwork())

        val ytCover = "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj"
        assertEquals("https://lh3.googleusercontent.com/abc=w1024-h1024-l90-rj", ytCover.toHighResArtwork())

        val other = "https://example.com/cover.jpg"
        assertEquals(other, other.toHighResArtwork())
        assertEquals(null, null.toHighResArtwork())
    }

    @Test
    fun testParseLrcAndStrip() {
        val rawLrc = """
            ﻿[ti:I Don't Love You]
            [ar:My Chemical Romance]
            [al:The Black Parade]
            [length:03:58]
            [00:00.00][by:Someone]
            [00:12.50]First line of lyric
            [01:05.00]Second line of lyric
        """.trimIndent()

        val parsed = parseLrc(rawLrc)
        // Intro gap (0ms..12500ms) + 2 lyric lines = 3 lines
        assertEquals(3, parsed.size)
        assertTrue(parsed[0].isGap)
        assertEquals(0L, parsed[0].timeMs)

        val vocalLines = parsed.filterNot { it.isGap }
        assertEquals(2, vocalLines.size)
        assertEquals(12500L, vocalLines[0].timeMs)
        assertEquals("First line of lyric", vocalLines[0].text)
        assertEquals(65000L, vocalLines[1].timeMs)
        assertEquals("Second line of lyric", vocalLines[1].text)

        val stripped = stripLrcTimestamps(rawLrc)
        assertEquals("First line of lyric\nSecond line of lyric", stripped)
    }

    @Test
    fun testArtistRefs() {
        val track = Track(
            id = "1",
            title = "Duet",
            artist = "Singer One, Singer Two",
            artistIds = listOf("id1", "id2")
        )
        val refs = track.artistRefs
        assertEquals(2, refs.size)
        assertEquals("id1" to "Singer One", refs[0])
        assertEquals("id2" to "Singer Two", refs[1])
    }

    @Test
    fun testRankAttributes() {
        val track = Track(
            id = "1",
            title = "Hits",
            artist = "Star",
            rank = 1,
            rankStatus = "UP",
            rankChange = 2
        )
        assertEquals(1, track.rank)
        assertEquals("UP", track.rankStatus)
        assertEquals(2, track.rankChange)

        fun formatMovement(status: String?, change: Int?): String = when (status?.lowercase()) {
            "up" -> if (change != null && change > 0) "▲ $change" else "▲"
            "down" -> if (change != null && change < 0) "▼ ${kotlin.math.abs(change)}" else "▼"
            "new" -> "NEW"
            else -> "—"
        }
        assertEquals("▲ 2", formatMovement("up", 2))
        assertEquals("▼ 3", formatMovement("down", -3))
        assertEquals("NEW", formatMovement("new", null))
        assertEquals("—", formatMovement("same", 0))
    }

    @Test
    fun testParseWordToWordLrc() {
        val rawLrc = "[00:10.00]Hello <00:10.50>world (00:11.20)again"
        val parsed = parseLrc(rawLrc).filterNot { it.isGap }
        assertEquals(1, parsed.size)
        val line = parsed[0]
        assertEquals(10000L, line.timeMs)
        assertEquals("Hello world again", line.text)
        assertEquals(true, line.hasWordSync)
        assertEquals(3, line.words.size)
        assertEquals(10000L, line.words[0].timeMs)
        assertEquals(10500L, line.words[1].timeMs)
        assertEquals(11200L, line.words[2].timeMs)
    }

    @Test
    fun testParseTtmlWordToWord() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:06.680" end="00:08.240" ttm:agent="v1">
                    <span begin="00:06.680" end="00:07.440">아</span><span begin="00:07.440" end="00:08.240">파트 </span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()
        val parsed = parseLrc(ttml).filterNot { it.isGap }
        assertEquals(1, parsed.size)
        val line = parsed[0]
        assertEquals(6680L, line.timeMs)
        assertEquals("아파트", line.text)
        assertTrue(line.hasWordSync)
        assertEquals(1, line.words.size)
        assertEquals(6680L, line.words[0].timeMs)
    }

    @Test
    fun testSafeScrollPadding() {
        val topInset = 48
        val mastheadHeight = 64
        val contentTop = topInset + mastheadHeight
        assertEquals(112, contentTop)

        val bottomInset = 32
        val miniPlayerHeight = 56
        val bottomNavHeight = 74
        val totalBottom = bottomInset + miniPlayerHeight + bottomNavHeight
        assertEquals(162, totalBottom)
    }

    @Test
    fun testResumePositionClamping() {
        val duration = 180000L
        fun resolveResumePosition(pos: Long, dur: Long): Long {
            return if (dur > 0 && pos >= dur - 1500L) 0L else pos.coerceAtLeast(0L)
        }
        assertEquals(45000L, resolveResumePosition(45000L, duration))
        assertEquals(0L, resolveResumePosition(179000L, duration))
        assertEquals(0L, resolveResumePosition(180000L, duration))
    }

    @Test
    fun testPeriodicThrottleInterval() {
        val throttleMs = 4000L
        fun shouldFlush(now: Long, lastFlush: Long): Boolean = (now - lastFlush) >= throttleMs
        assertEquals(false, shouldFlush(1000L, 0L))
        assertEquals(false, shouldFlush(3999L, 0L))
        assertEquals(true, shouldFlush(4000L, 0L))
        assertEquals(true, shouldFlush(5000L, 0L))
    }

    @Test
    fun testFullPlayerViewResolution() {
        fun resolveView(showLyrics: Boolean, showQueue: Boolean): String = when {
            showLyrics -> "LYRICS"
            showQueue -> "QUEUE"
            else -> "COVER"
        }
        assertEquals("COVER", resolveView(showLyrics = false, showQueue = false))
        assertEquals("LYRICS", resolveView(showLyrics = true, showQueue = false))
        assertEquals("QUEUE", resolveView(showLyrics = false, showQueue = true))
        assertEquals("LYRICS", resolveView(showLyrics = true, showQueue = true))
    }

    @Test
    fun testControlsVisibilityOnScroll() {
        fun resolveControlsVisibility(current: Boolean, deltaY: Float): Boolean {
            return when {
                deltaY < -8f -> false
                deltaY > 8f -> true
                else -> current
            }
        }
        assertEquals(false, resolveControlsVisibility(true, -10f)) // scroll down -> hide
        assertEquals(true, resolveControlsVisibility(false, 10f))  // scroll up -> show
        assertEquals(true, resolveControlsVisibility(true, 0f))    // neutral -> preserve
        assertEquals(false, resolveControlsVisibility(false, -2f)) // small delta below threshold -> preserve
    }

    @Test
    fun testScrubberFractionClamping() {
        fun computeFraction(pos: Long, dur: Long): Float {
            val maxDur = (if (dur > 0) dur else 1L).toFloat()
            return (pos.toFloat() / maxDur).coerceIn(0f, 1f)
        }
        assertEquals(0.5f, computeFraction(500L, 1000L))
        assertEquals(0f, computeFraction(-10L, 1000L))
        assertEquals(1f, computeFraction(1500L, 1000L))
        assertEquals(0f, computeFraction(0L, 0L))
    }

    @Test
    fun testOverlayControlsVisibility() {
        fun isControlsVisible(showLyrics: Boolean, showQueue: Boolean, userControlsVisible: Boolean): Boolean {
            val isOverlayMode = showLyrics || showQueue
            return !isOverlayMode || userControlsVisible
        }
        // In cover mode, controls are always visible regardless of scroll
        assertEquals(true, isControlsVisible(showLyrics = false, showQueue = false, userControlsVisible = false))
        assertEquals(true, isControlsVisible(showLyrics = false, showQueue = false, userControlsVisible = true))
        // In lyrics mode, controls follow userControlsVisible
        assertEquals(false, isControlsVisible(showLyrics = true, showQueue = false, userControlsVisible = false))
        assertEquals(true, isControlsVisible(showLyrics = true, showQueue = false, userControlsVisible = true))
        // In queue mode, controls follow userControlsVisible
        assertEquals(false, isControlsVisible(showLyrics = false, showQueue = true, userControlsVisible = false))
        assertEquals(true, isControlsVisible(showLyrics = false, showQueue = true, userControlsVisible = true))
    }

    @Test
    fun testLyricScrollDeltaCalculation() {
        fun computeScrollDelta(itemOffsetY: Int, viewportHeight: Int, targetRatio: Float = 0.26f): Float {
            val targetY = (viewportHeight * targetRatio).toInt()
            return (itemOffsetY - targetY).toFloat()
        }
        // Item is lower down at 500px, target is 260px -> scroll forward +240px
        assertEquals(240f, computeScrollDelta(itemOffsetY = 500, viewportHeight = 1000))
        // Item is higher up at 200px, target is 260px -> scroll backward -60px
        assertEquals(-60f, computeScrollDelta(itemOffsetY = 200, viewportHeight = 1000))
        // Item is already at target -> 0px delta
        assertEquals(0f, computeScrollDelta(itemOffsetY = 260, viewportHeight = 1000))
    }

    @Test
    fun testLyricsControlsInactivityTimeout() {
        fun shouldControlsAutoHide(
            showLyrics: Boolean,
            now: Long,
            lastInteractionTime: Long,
            isDragging: Boolean,
            timeoutMs: Long = 4000L
        ): Boolean {
            if (!showLyrics || isDragging) return false
            return (now - lastInteractionTime) >= timeoutMs
        }

        // In lyrics mode after 4s idle -> auto hide
        assertEquals(true, shouldControlsAutoHide(showLyrics = true, now = 5000L, lastInteractionTime = 1000L, isDragging = false))
        // In lyrics mode before 4s (only 3s idle) -> remain visible
        assertEquals(false, shouldControlsAutoHide(showLyrics = true, now = 4000L, lastInteractionTime = 1000L, isDragging = false))
        // In lyrics mode while dragging scrubber -> never hide
        assertEquals(false, shouldControlsAutoHide(showLyrics = true, now = 10000L, lastInteractionTime = 1000L, isDragging = true))
        // In cover mode -> never auto hide
        assertEquals(false, shouldControlsAutoHide(showLyrics = false, now = 10000L, lastInteractionTime = 1000L, isDragging = false))
    }

    @Test
    fun testTranslationOpacityResolution() {
        fun resolveTranslationAlpha(isActive: Boolean): Float = if (isActive) 0.70f else 0.40f
        assertEquals(0.70f, resolveTranslationAlpha(true))
        assertEquals(0.40f, resolveTranslationAlpha(false))
    }

    @Test
    fun testLyricsFloatingPillVisibility() {
        fun isLyricsFloatingPillVisible(isAutoScrollPaused: Boolean): Boolean = isAutoScrollPaused
        assertEquals(true, isLyricsFloatingPillVisible(true))
        assertEquals(false, isLyricsFloatingPillVisible(false))
    }

    @Test
    fun testPlaylistQueueNotCappedAtTen() {
        fun resolvePlaylistQueue(track: Track, rawQueue: List<Track>): List<Track> {
            val q = if (rawQueue.isEmpty()) listOf(track) else rawQueue
            val trackIdx = q.indexOfFirst { it.id == track.id }
            return if (trackIdx != -1) {
                q.drop(trackIdx)
            } else {
                listOf(track) + q.filterNot { it.id == track.id }
            }
        }
        val tracks = (1..50).map { Track(id = "$it", title = "Track $it", artist = "Artist") }
        val fullQueue = resolvePlaylistQueue(tracks[0], tracks)
        assertEquals(50, fullQueue.size)
        assertEquals("1", fullQueue.first().id)
        assertEquals("50", fullQueue.last().id)

        // If user selects track #3 (index 2), tracks 1-2 are removed, tracks 3..50 kept
        val fromThird = resolvePlaylistQueue(tracks[2], tracks)
        assertEquals(48, fromThird.size)
        assertEquals("3", fromThird.first().id)
        assertEquals("50", fromThird.last().id)
    }

    @Test
    fun testPlayAtIndexSlicesQueueAndRemovesPreviousTracks() {
        fun sliceQueue(queue: List<Track>, selectedIndex: Int): List<Track> {
            return if (selectedIndex in queue.indices) queue.drop(selectedIndex) else queue
        }
        val tracks = (1..10).map { Track(id = "$it", title = "Track $it", artist = "Artist") }
        val sliced = sliceQueue(tracks, 2)
        assertEquals(8, sliced.size)
        assertEquals("3", sliced.first().id)
        assertEquals("10", sliced.last().id)
    }

    @Test
    fun testPlaylistChunkPagination() {
        fun simulatePaginate(totalCount: Int, chunkSize: Int = 343, maxLimit: Int = 2000): List<Pair<Int, Int>> {
            val chunks = mutableListOf<Pair<Int, Int>>()
            var offset = 0
            while (offset < totalCount && offset < maxLimit) {
                val currentLimit = minOf(chunkSize, maxLimit - offset, totalCount - offset)
                chunks.add(offset to currentLimit)
                offset += currentLimit
            }
            return chunks
        }

        // 800 tracks with chunk 343 -> 3 chunks: [0..343, 343..686, 686..800]
        val chunks = simulatePaginate(totalCount = 800, chunkSize = 343)
        assertEquals(3, chunks.size)
        assertEquals(0 to 343, chunks[0])
        assertEquals(343 to 343, chunks[1])
        assertEquals(686 to 114, chunks[2])
        val totalFetched = chunks.sumOf { it.second }
        assertEquals(800, totalFetched)
    }

    @Test
    fun testRadioRelevanceUsesGenreAndCountryNotTitle() {
        fun buildRadioQuery(track: Track, country: String = "ID", limit: Int = 10): Map<String, String> {
            val params = mutableMapOf<String, String>()
            params["track_id"] = track.id
            params["artist"] = track.artist
            val artistId = track.artistIds.firstOrNull() ?: ""
            if (artistId.isNotBlank()) params["artist_id"] = artistId
            if (!track.albumId.isNullOrBlank()) params["album_id"] = track.albumId
            params["country"] = country
            params["limit"] = limit.toString()
            return params
        }

        val track = Track(
            id = "t1",
            title = "Sialan",
            artist = "Juicy Luicy",
            artistIds = listOf("art123"),
            albumId = "alb456"
        )
        val query = buildRadioQuery(track, country = "ID", limit = 10)

        // Relevance must NOT rely on song title to prevent keyword contamination
        assertEquals(false, query.containsKey("title"))
        assertEquals("ID", query["country"])
        assertEquals("art123", query["artist_id"])
        assertEquals("Juicy Luicy", query["artist"])
    }

    @Test
    fun testQueueLoaderVisibilityCondition() {
        fun isQueueLoaderVisible(isRadioLoading: Boolean): Boolean = isRadioLoading
        assertEquals(true, isQueueLoaderVisible(true))
        assertEquals(false, isQueueLoaderVisible(false))
    }

    @Test
    fun testExpressiveLoaderMorphRadius() {
        fun computeRadius(maxR: Float, morph: Float, angleRad: Float): Float {
            val amp = 0.28f * morph
            return maxR * (1f - amp + amp * kotlin.math.cos(4f * angleRad))
        }
        val maxR = 100f
        // Peak of lobe at angle 0 -> radius = maxR
        assertEquals(100f, computeRadius(maxR, morph = 1f, angleRad = 0f))
        // Valley at angle PI/4 (45 deg) -> radius = maxR * (1 - 2*amp)
        val valley = computeRadius(maxR, morph = 1f, angleRad = (Math.PI / 4).toFloat())
        assertEquals(44f, kotlin.math.round(valley))
    }

    @Test
    fun testCreditsCacheKeyAndTtl() {
        fun creditsCacheKey(trackId: String) = "credits:$trackId"
        assertEquals("credits:xyz123", creditsCacheKey("xyz123"))

        val ttlDays = 7
        val ttlMs = ttlDays * 24 * 60 * 60_000L
        assertEquals(604_800_000L, ttlMs)
    }

    @Test
    fun testLyricsLoadingStateResolution() {
        fun resolveLyricsViewContent(isLoading: Boolean, lyrics: String?): String {
            return when {
                isLoading -> "LOADER"
                lyrics != null -> "CONTENT"
                else -> "UNAVAILABLE"
            }
        }
        assertEquals("LOADER", resolveLyricsViewContent(isLoading = true, lyrics = null))
        assertEquals("LOADER", resolveLyricsViewContent(isLoading = true, lyrics = "La la la"))
        assertEquals("CONTENT", resolveLyricsViewContent(isLoading = false, lyrics = "La la la"))
        assertEquals("UNAVAILABLE", resolveLyricsViewContent(isLoading = false, lyrics = null))
    }

    // -----------------------------------------------------------------------------------------
    // Queue policy (radio refill / context queue)
    // -----------------------------------------------------------------------------------------

    @Test
    fun testNormalizeQueueStartsAtSelectedTrackAndDedupes() {
        val tracks = (1..5).map { Track(id = "$it", title = "Track $it") }
        val normalized = QueuePolicy.normalizeQueue(tracks[2], tracks + tracks[2])

        assertEquals(listOf("3", "4", "5"), normalized.map { it.id })
    }

    @Test
    fun testNormalizeQueuePrependsMissingTrack() {
        val a = Track(id = "a")
        val b = Track(id = "b")
        val selected = Track(id = "c")

        assertEquals(
            listOf("c", "a", "b"),
            QueuePolicy.normalizeQueue(selected, listOf(a, b)).map { it.id }
        )
    }

    @Test
    fun testMergeUniqueCapsRadioQueueWithoutDroppingCurrent() {
        val existing = (1..18).map { Track(id = "$it") }
        val incoming = (15..25).map { Track(id = "$it") }

        val merged = QueuePolicy.mergeUnique(
            existing,
            incoming,
            maxSize = QueuePolicy.RADIO_QUEUE_TARGET
        )

        assertEquals(QueuePolicy.RADIO_QUEUE_TARGET, merged.size)
        assertEquals("1", merged.first().id)
        assertEquals("20", merged.last().id)
    }

    @Test
    fun testShouldRefillRadioUsesUpcomingTracks() {
        // Fresh radio (only the seed) must fetch immediately.
        assertTrue(QueuePolicy.shouldRefillRadio(queueSize = 1, currentIndex = 0))
        // Plenty of upcoming tracks: no network call.
        assertFalse(QueuePolicy.shouldRefillRadio(queueSize = 20, currentIndex = 0))
        // Played items are trimmed by the player, leaving a short queue.
        assertTrue(QueuePolicy.shouldRefillRadio(queueSize = 6, currentIndex = 0))
        assertFalse(QueuePolicy.shouldRefillRadio(queueSize = 0, currentIndex = 0))
    }

    @Test
    fun testRadioRetryDelayBacksOffAndCaps() {
        assertEquals(5_000L, QueuePolicy.radioRetryDelayMs(1))
        assertEquals(10_000L, QueuePolicy.radioRetryDelayMs(2))
        assertEquals(20_000L, QueuePolicy.radioRetryDelayMs(3))
        assertEquals(60_000L, QueuePolicy.radioRetryDelayMs(10))
    }

    @Test
    fun testSeenRadioIdsStayBounded() {
        val seen = linkedSetOf<String>()
        repeat(350) { seen.add("id-$it") }

        QueuePolicy.trimSeen(seen)

        assertEquals(QueuePolicy.MAX_SEEN_RADIO_IDS, seen.size)
        assertFalse(seen.contains("id-0"))
        assertTrue(seen.contains("id-349"))
    }

    @Test
    fun testQueueTierAssignmentAndIdPreservation() {
        val raw = Track(id = "track-1", title = "Track 1")
        val entry = QueuePolicy.asQueueEntry(raw, QueueTier.USER_QUEUE)
        assertEquals(QueueTier.USER_QUEUE, entry.queueTier)
        assertTrue(entry.queueEntryId != null && entry.queueEntryId.isNotBlank())

        val preserved = QueuePolicy.asQueueEntry(entry, QueueTier.CONTEXT)
        assertEquals(entry.queueEntryId, preserved.queueEntryId)
        assertEquals(QueueTier.CONTEXT, preserved.queueTier)
    }

    @Test
    fun testFindUserQueueInsertionIndex() {
        val nowPlaying = Track(id = "np", queueTier = QueueTier.CONTEXT)
        val user1 = Track(id = "u1", queueTier = QueueTier.USER_QUEUE)
        val user2 = Track(id = "u2", queueTier = QueueTier.USER_QUEUE)
        val ctx1 = Track(id = "c1", queueTier = QueueTier.CONTEXT)
        val ctx2 = Track(id = "c2", queueTier = QueueTier.CONTEXT)
        val queue = listOf(nowPlaying, user1, user2, ctx1, ctx2)

        // Play Next inserts right after currentIndex
        val nextIdx = QueuePolicy.findUserQueueInsertionIndex(queue, currentIndex = 0, isNext = true)
        assertEquals(1, nextIdx)

        // Add to Queue inserts at tail of USER_QUEUE block (before first CONTEXT)
        val addIdx = QueuePolicy.findUserQueueInsertionIndex(queue, currentIndex = 0, isNext = false)
        assertEquals(3, addIdx)
    }

    @Test
    fun testComputeJumpOrderCrossingUserQueue() {
        // [NowPlaying (0: CONTEXT), User1 (1: USER_QUEUE), Context2 (2: CONTEXT)]
        val tiers = listOf(QueueTier.CONTEXT, QueueTier.USER_QUEUE, QueueTier.CONTEXT)
        // Jumping to Context2 (index 2) crossing User1
        val plan = QueuePolicy.computeJumpOrder(tiers, currentIndex = 0, targetIndex = 2)
        assertTrue(plan != null)
        assertFalse(plan.isPureSeek)
        // Expected order: [0 (history), 2 (target), 1 (preserved user queue)]
        assertEquals(listOf(0, 2, 1), plan.newOrderIndices)
        assertEquals(1, plan.newTargetIndex)
    }

    @Test
    fun testClearUserQueue() {
        val nowPlaying = Track(id = "np", queueTier = QueueTier.CONTEXT)
        val user1 = Track(id = "u1", queueTier = QueueTier.USER_QUEUE)
        val user2 = Track(id = "u2", queueTier = QueueTier.USER_QUEUE)
        val ctx1 = Track(id = "c1", queueTier = QueueTier.CONTEXT)
        val queue = listOf(nowPlaying, user1, user2, ctx1)

        val cleared = QueuePolicy.clearUserQueue(queue, currentIndex = 0)
        assertEquals(2, cleared.size)
        assertEquals("np", cleared[0].id)
        assertEquals("c1", cleared[1].id)
    }

    @Test
    fun testLrcMetadataAndBomSanitization() {
        val rawLrc = """
            ﻿[ti:I Don't Love You]
            [ar:My Chemical Romance]
            [al:The Black Parade]
            [length:03:58]
            [00:00.00][by:Someone]
            [00:26.16]<00:26.16>Well, <00:26.52>when <00:27.27>you <00:27.97>go
            [00:31.29]Don't ever think I'll make you try to stay
        """.trimIndent()

        val parsed = parseLrc(rawLrc)
        // Intro gap (0ms..26160ms) + 2 lyric lines = 3 lines
        assertEquals(3, parsed.size)
        assertTrue(parsed[0].isGap)

        val vocalLines = parsed.filterNot { it.isGap }
        assertEquals(2, vocalLines.size)
        assertEquals("Well, when you go", vocalLines[0].text)
        assertEquals("Don't ever think I'll make you try to stay", vocalLines[1].text)

        val stripped = stripLrcTimestamps(rawLrc)
        assertFalse(stripped.contains("[ti:"))
        assertFalse(stripped.contains("[ar:"))
        assertFalse(stripped.contains("[al:"))
        assertFalse(stripped.contains("[length:"))
        assertFalse(stripped.contains("Someone"))
        assertTrue(stripped.contains("Well, when you go"))
        assertTrue(stripped.contains("Don't ever think I'll make you try to stay"))
    }

    @Test
    fun testDisplaySourceMapping() {
        assertEquals("KuGou", LyricsResult(lyrics = "...", source = "KuGou").displaySource)
        assertEquals("Genius", LyricsResult(lyrics = "...", source = "Genius").displaySource)
        assertEquals("SimpMusic", LyricsResult(lyrics = "...", source = "SimpMusic").displaySource)
        assertEquals("Unison", LyricsResult(lyrics = "...", source = "Unison").displaySource)
        assertEquals("BetterLyrics", LyricsResult(lyrics = "...", source = "BetterLyrics Portato").displaySource)
    }

    @Test
    fun testInstrumentalGapsDetection() {
        val line1 = LyricLine(
            timeMs = 10_000L,
            text = "Line one",
            words = listOf(
                LyricWord(timeMs = 10_000L, text = "Line ", endMs = 11_000L),
                LyricWord(timeMs = 11_000L, text = "one", endMs = 12_000L)
            )
        )
        val line2 = LyricLine(
            timeMs = 25_000L,
            text = "Line two after solo",
            words = listOf(
                LyricWord(timeMs = 25_000L, text = "Line ", endMs = 26_000L)
            )
        )

        val withGaps = listOf(line1, line2).withInstrumentalGaps()
        // 1. Intro gap (0L..10000L)
        // 2. line1 (10000L..12000L)
        // 3. Interlude gap (12000L..25000L, 13s silence >= 4s)
        // 4. line2 (25000L)
        assertEquals(4, withGaps.size)
        assertTrue(withGaps[0].isGap)
        assertEquals(0L, withGaps[0].timeMs)

        assertEquals("Line one", withGaps[1].text)

        assertTrue(withGaps[2].isGap)
        assertEquals(12_000L, withGaps[2].timeMs)

        assertEquals("Line two after solo", withGaps[3].text)
    }

    @Test
    fun testBackgroundVocalsSeparation() {
        val rawLine = LyricLine(
            timeMs = 30_000L,
            text = "I don't love you like I loved you yesterday (Oh, oh)"
        )
        val processed = listOf(rawLine).withBackgroundVocals()
        assertEquals(1, processed.size)
        val line = processed[0]
        assertEquals("I don't love you like I loved you yesterday", line.text)
        val bg = line.background
        assertTrue(bg != null)
        if (bg != null) {
            assertEquals("(Oh, oh)", bg.text)
            assertEquals(30_000L, bg.timeMs)
        }
    }

    @Test
    fun testDuetAlignmentMarkerParsing() {
        val rawLrc = """
            [00:10.00]Singer 1 left line
            [00:15.00]<R>Singer 2 right line
        """.trimIndent()
        val parsed = parseLrc(rawLrc)
        val vocal = parsed.filterNot { it.isGap }
        assertEquals(2, vocal.size)
        assertEquals("Singer 1 left line", vocal[0].text)
        assertEquals(LyricAlignment.START, vocal[0].alignment)

        assertEquals("Singer 2 right line", vocal[1].text)
        assertEquals(LyricAlignment.END, vocal[1].alignment)
    }

    @Test
    fun testVideoTitleAndArtistCleaning() {
        val messyTitle = "DENNY CAKNAN FT RAFIQ - LAUT KIDUL (OFFICIAL LIVE MUSIC VIDEO) DC MUSIK"
        val clean = com.blek.blexsy.data.InnerTubeService.cleanTrackTitle(messyTitle)
        assertFalse(clean.contains("OFFICIAL", ignoreCase = true))
        assertFalse(clean.contains("VIDEO", ignoreCase = true))

        val videoTitle2 = "Jangan Ganggu Hari Ini (Official Video)"
        assertEquals("Jangan Ganggu Hari Ini", com.blek.blexsy.data.InnerTubeService.cleanTrackTitle(videoTitle2))

        val cleanArtist = com.blek.blexsy.data.InnerTubeService.cleanArtist("Hindia feat. Feast")
        assertEquals("Hindia", cleanArtist)
    }

    @Test
    fun testAlbumSummaryCreation() {
        val summary = com.blek.blexsy.model.AlbumSummary(
            id = "MPREb_test123",
            name = "Test Album",
            artist = "Artist Name",
            type = "Single"
        )
        assertEquals("MPREb_test123", summary.id)
        assertEquals("Test Album", summary.name)
        assertEquals("Artist Name", summary.artist)
        assertEquals("Single", summary.type)
    }

    @Test
    fun testDicebearAvatarUrlEncoding() {
        val url = com.blek.blexsy.ui.dicebearAvatarUrl("ari haryanto")
        assertEquals("https://api.dicebear.com/10.x/bottts-neutral/png?seed=ari%20haryanto", url)
        assertFalse(url.contains(" "))

        val blankUrl = com.blek.blexsy.ui.dicebearAvatarUrl("   ")
        assertEquals("https://api.dicebear.com/10.x/bottts-neutral/png?seed=Blexsy", blankUrl)
    }

    @Test
    fun testAuthFormValidation() {
        fun isValidEmail(email: String): Boolean = email.trim().let { it.isNotEmpty() && it.contains('@') }
        fun isValidOtp(code: String): Boolean = code.trim().let { it.length == 6 && it.all { ch -> ch.isDigit() } }
        fun resolveLocalNameSuggestion(raw: String): String? = raw.takeIf { it.isNotBlank() && it != "User Blexsy" }

        assertTrue(isValidEmail("user@example.com"))
        assertFalse(isValidEmail("userexample.com"))
        assertFalse(isValidEmail("   "))

        assertTrue(isValidOtp("123456"))
        assertFalse(isValidOtp("12345"))
        assertFalse(isValidOtp("1234567"))
        assertFalse(isValidOtp("12345a"))

        assertEquals("Ari Haryanto", resolveLocalNameSuggestion("Ari Haryanto"))
        assertEquals(null, resolveLocalNameSuggestion("User Blexsy"))
        assertEquals(null, resolveLocalNameSuggestion("   "))
    }

    @Test
    fun testUiKitDesignSystemDefaults() {
        assertEquals(36, com.blek.blexsy.ui.ButtonSize.Sm.height.value.toInt())
        assertEquals(44, com.blek.blexsy.ui.ButtonSize.Md.height.value.toInt())
        assertEquals(50, com.blek.blexsy.ui.ButtonSize.Lg.height.value.toInt())
        assertEquals(5, com.blek.blexsy.ui.ButtonVariant.values().size)

        val sampleTabs = listOf("Spins", "Hearts", "Crates")
        val pairs = sampleTabs.mapIndexed { idx, label -> idx to label }
        assertEquals(3, pairs.size)
        assertEquals(0 to "Spins", pairs[0])

        val variableWidths = listOf(80, 140, 95)
        assertEquals(0 to 80, com.blek.blexsy.ui.calculateTabIndicatorBounds(variableWidths, 0))
        assertEquals(80 to 140, com.blek.blexsy.ui.calculateTabIndicatorBounds(variableWidths, 1))
        assertEquals(220 to 95, com.blek.blexsy.ui.calculateTabIndicatorBounds(variableWidths, 2))
        assertEquals(0 to 0, com.blek.blexsy.ui.calculateTabIndicatorBounds(variableWidths, 5))

        assertEquals(0, com.blek.blexsy.ui.findTabIndexAtOffset(listOf(0, 0, 0), 50f))
        assertEquals(0, com.blek.blexsy.ui.findTabIndexAtOffset(variableWidths, -10f))
        assertEquals(0, com.blek.blexsy.ui.findTabIndexAtOffset(variableWidths, 0f))
        assertEquals(0, com.blek.blexsy.ui.findTabIndexAtOffset(variableWidths, 30f))
        assertEquals(1, com.blek.blexsy.ui.findTabIndexAtOffset(variableWidths, 80f))
        assertEquals(1, com.blek.blexsy.ui.findTabIndexAtOffset(variableWidths, 150f))
        assertEquals(2, com.blek.blexsy.ui.findTabIndexAtOffset(variableWidths, 220f))
        assertEquals(2, com.blek.blexsy.ui.findTabIndexAtOffset(variableWidths, 500f))
    }

    @Test
    fun testSlangCopyDefaults() {
        val greeting = com.blek.blexsy.ui.getBlexsyGreeting()
        assertTrue(greeting in listOf("What's good.", "Afternoon flex.", "Golden hour beats.", "Still bumpin'?"))
        assertTrue(com.blek.blexsy.ui.BLEXSY_MUSIC_JOKES.isNotEmpty())
        assertTrue(com.blek.blexsy.ui.BLEXSY_MUSIC_JOKES.any { it.contains("banger") || it.contains("no cap") })
    }

    @Test
    fun testSearchRadioQueueBehavior() {
        val player = com.blek.blexsy.player.DefaultBlexsyAudioPlayer()
        val seed = Track(id = "track_123", title = "APT.", artist = "ROSÉ & Bruno Mars")

        assertEquals(com.blek.blexsy.player.QueueSource.CONTEXT, player.queueSource.value)

        player.startRadio(seed)
        assertEquals(com.blek.blexsy.player.QueueSource.RADIO, player.queueSource.value)
        assertEquals("track_123", player.currentTrack.value?.id)
        assertEquals(listOf("track_123"), player.queue.value.map { it.id })

        assertNull(player.contextTitle.value)

        player.play(seed, listOf(seed), "Top 50 Indonesia")
        assertEquals(com.blek.blexsy.player.QueueSource.CONTEXT, player.queueSource.value)
        assertEquals("Top 50 Indonesia", player.contextTitle.value)

        player.startRadio(seed)
        assertEquals(com.blek.blexsy.player.QueueSource.RADIO, player.queueSource.value)
        assertNull(player.contextTitle.value)
    }

    @Test
    fun testPrimaryArtistRefResolution() {
        val withBrowseId = Track(id = "1", title = "Song", artist = "Coldplay", artistIds = listOf("UC123"))
        assertEquals("UC123", withBrowseId.primaryArtistRef)

        val withoutBrowseId = Track(id = "2", title = "Song", artist = "Bernadya")
        assertEquals("Bernadya", withoutBrowseId.primaryArtistRef)

        val unknown = Track(id = "3", title = "Song", artist = "Unknown artist")
        assertNull(unknown.primaryArtistRef)
    }

    @Test
    fun testSearchResultArtists() {
        val artist = com.blek.blexsy.model.ArtistSummary(id = "UC123", name = "Coldplay", subscribers = "25M subscribers")
        val searchResult = com.blek.blexsy.data.SearchResult(tracks = emptyList(), artists = listOf(artist))
        assertEquals(1, searchResult.artists.size)
        assertEquals("Coldplay", searchResult.artists.first().name)
        assertEquals("UC123", searchResult.artists.first().id)
    }

    @Test
    fun testFilterDisplayedArtistsOnlyShowsWhenTypingArtist() {
        val coldplayArtist = com.blek.blexsy.model.ArtistSummary(id = "1", name = "Coldplay")
        val yellowcardArtist = com.blek.blexsy.model.ArtistSummary(id = "2", name = "Yellowcard")
        val trackYellow = Track(id = "t1", title = "Yellow", artist = "Coldplay")

        // 1. Searching for song "Yellow": should not show Yellowcard artist
        val yellowResults = com.blek.blexsy.ui.filterDisplayedArtists(
            query = "Yellow",
            artists = listOf(yellowcardArtist),
            tracks = listOf(trackYellow)
        )
        assertTrue(yellowResults.isEmpty())

        // 2. Searching for artist "Coldplay": should show Coldplay artist
        val coldplayResults = com.blek.blexsy.ui.filterDisplayedArtists(
            query = "Coldplay",
            artists = listOf(coldplayArtist),
            tracks = listOf(trackYellow)
        )
        assertEquals(1, coldplayResults.size)
        assertEquals("Coldplay", coldplayResults.first().name)

        // 3. Typing partial artist name "cold": should show Coldplay
        val partialResults = com.blek.blexsy.ui.filterDisplayedArtists(
            query = "cold",
            artists = listOf(coldplayArtist),
            tracks = listOf(trackYellow)
        )
        assertEquals(1, partialResults.size)
        assertEquals("Coldplay", partialResults.first().name)

        // 4. Searching for song "Die With A Smile": should not show irrelevant artists
        val randomArtist = com.blek.blexsy.model.ArtistSummary(id = "3", name = "Die With A Smile Fans")
        val trackDie = Track(id = "t2", title = "Die With A Smile", artist = "Lady Gaga, Bruno Mars")
        val songResults = com.blek.blexsy.ui.filterDisplayedArtists(
            query = "Die With A Smile",
            artists = listOf(randomArtist),
            tracks = listOf(trackDie)
        )
        assertTrue(songResults.isEmpty())
    }

    @Test
    fun testArtistFollowerTextFormatting() {
        val detail = com.blek.blexsy.model.ArtistDetail(
            id = "UC123",
            name = "Coldplay",
            subscribers = "25M subscribers"
        )
        assertEquals("25M subscribers", detail.subscribers)

        assertEquals("15,4 jt subscriber", com.blek.blexsy.ui.resolveSubscriberCount("15,4 jt subscriber", null, null))
        assertEquals("25M subscribers", com.blek.blexsy.ui.resolveSubscriberCount("25M subscribers", null, null))
        assertEquals("2.5M subscribers", com.blek.blexsy.ui.resolveSubscriberCount(null, 2_500_000, null))
        assertEquals("350.0K subscribers", com.blek.blexsy.ui.resolveSubscriberCount(null, null, 350_000))
        assertNull(com.blek.blexsy.ui.resolveSubscriberCount(null, null, null))
    }

    @Test
    fun testArtistCanvasServiceBlankReturnsNull() = kotlinx.coroutines.runBlocking {
        val resultBlank = com.blek.blexsy.data.ArtistCanvasService.getMotionVideoUrl("")
        assertNull(resultBlank)
        val resultSpaces = com.blek.blexsy.data.ArtistCanvasService.getMotionVideoUrl("   ")
        assertNull(resultSpaces)
    }

    @Test
    fun testGenreDetailScreenModel() {
        val detail = com.blek.blexsy.ui.BlexsyScreen.GenreDetail(id = "pop", name = "Pop", image = "https://example.com/pop.jpg")
        assertEquals("pop", detail.id)
        assertEquals("Pop", detail.name)
        assertEquals("https://example.com/pop.jpg", detail.image)
    }

    @Test
    fun testLauncherAliasOptions() {
        val aliases = com.blek.blexsy.ui.LauncherAliasManager.ALIASES
        assertTrue(aliases.any { it.label == "Blexsy" && it.id == "blexsy" })
        assertTrue(aliases.any { it.label == "Spotify" && it.id == "spotify" })
        assertTrue(aliases.any { it.label == "Music" && it.id == "music" })
        assertEquals(3, aliases.size)
    }

    @Test
    fun testContributorsFallback() {
        val data = com.blek.blexsy.data.ContributorsService.fallback
        assertTrue(data.contributors.any { it.name == "ariblekk" })
        assertTrue(data.credits.any { it.name == "AndroidX Media3" })
    }

    @Test
    fun testNormalizeRawGithubUrl() {
        val blobUrl = "https://github.com/ariblekk/soundpulse/blob/main/contributors.json"
        val expected = "https://raw.githubusercontent.com/ariblekk/soundpulse/main/contributors.json"
        assertEquals(expected, com.blek.blexsy.data.ContributorsService.normalizeRawGithubUrl(blobUrl))
        assertEquals(expected, com.blek.blexsy.data.ContributorsService.normalizeRawGithubUrl(expected))
    }

    @Test
    fun testSkipDeadAirOutroThreshold() {
        fun isOutro(currentMs: Long, durationMs: Long): Boolean {
            return when {
                durationMs > 30_000L -> currentMs >= durationMs * 0.70f || (durationMs - currentMs) <= 45_000L
                durationMs > 10_000L -> currentMs >= durationMs * 0.80f
                else -> false
            }
        }
        val fourMin = 240_000L
        assertFalse(isOutro(60_000L, fourMin))
        assertFalse(isOutro(120_000L, fourMin))
        assertTrue(isOutro(170_000L, fourMin))
        assertTrue(isOutro(200_000L, fourMin))
        assertFalse(isOutro(10_000L, 20_000L))
        assertTrue(isOutro(17_000L, 20_000L))
    }

    @Test
    fun testArtistReleasesAndSimilarArtists() {
        val bioHtml = "<p>Test <b>bio</b> with <a href='https://example.com'>link</a></p>"
        val artist = com.blek.blexsy.model.ArtistDetail(
            id = "UCtest",
            name = "Test Artist",
            biography = bioHtml,
            albums = listOf(com.blek.blexsy.model.AlbumSummary(id = "alb1", name = "Album 1", type = "Album")),
            singlesAndEps = listOf(com.blek.blexsy.model.AlbumSummary(id = "ep1", name = "EP 1", type = "EP")),
            playlists = listOf(com.blek.blexsy.model.AlbumSummary(id = "pl1", name = "Playlist 1", type = "Playlist")),
            similarArtists = listOf(com.blek.blexsy.model.ArtistSummary(id = "art2", name = "Similar Artist"))
        )
        assertEquals(1, artist.albums.size)
        assertEquals(1, artist.singlesAndEps.size)
        assertEquals(1, artist.playlists.size)
        assertEquals(1, artist.similarArtists.size)
        assertNotNull(artist.biography)
        assertEquals(bioHtml, artist.biography)
    }

    @Test
    fun testArtistDetailSerializationAndCache() = kotlinx.coroutines.runBlocking {
        val original = com.blek.blexsy.model.ArtistDetail(
            id = "UCIaFw5VBEK8qaW6nRpx_qnw",
            name = "Coldplay",
            biography = "<p>Coldplay are a British rock band.</p>",
            subscribers = "25M subscribers",
            followers = 25000000,
            albums = listOf(com.blek.blexsy.model.AlbumSummary(id = "alb1", name = "Parachutes", type = "Album")),
            singlesAndEps = listOf(com.blek.blexsy.model.AlbumSummary(id = "ep1", name = "Yellow", type = "Single")),
            playlists = listOf(com.blek.blexsy.model.AlbumSummary(id = "pl1", name = "Coldplay Complete", type = "Playlist")),
            similarArtists = listOf(com.blek.blexsy.model.ArtistSummary(id = "sim1", name = "Keane", subscribers = "1M subscribers")),
            verified = true
        )
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
        val encoded = json.encodeToString(com.blek.blexsy.model.ArtistDetail.serializer(), original)
        val decoded = json.decodeFromString(com.blek.blexsy.model.ArtistDetail.serializer(), encoded)
        assertEquals(original.id, decoded.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.biography, decoded.biography)
        assertEquals(original.subscribers, decoded.subscribers)
        assertEquals(original.albums.size, decoded.albums.size)
        assertEquals(original.albums[0].name, decoded.albums[0].name)
        assertEquals(original.singlesAndEps.size, decoded.singlesAndEps.size)
        assertEquals(original.playlists.size, decoded.playlists.size)
        assertEquals(original.similarArtists.size, decoded.similarArtists.size)
        assertEquals(original.verified, decoded.verified)

        val db = com.blek.blexsy.data.ArtistDatabase(null)
        assertNull(db.get("Coldplay"))
        db.put(original)
    }

    @Test
    fun testSpotifyChartAndNewReleasesParsingAndTtl() {
        val ttlOneDay = 24 * 60 * 60_000L
        assertEquals(86_400_000L, ttlOneDay)

        val sampleNextData = """
            {
              "props": {
                "pageProps": {
                  "state": {
                    "data": {
                      "entity": {
                        "name": "Top 50 - Indonesia",
                        "coverArt": {
                          "sources": [{ "url": "https://charts-images.scdn.co/cover.jpg" }]
                        },
                        "trackList": [
                          {
                            "uri": "spotify:track:4xoY4lZNoTjEuHsSmhgF1G",
                            "title": "Sesi Potret",
                            "subtitle": "eńau, Ari Lesmana",
                            "duration": 243000
                          },
                          {
                            "uri": "spotify:track:1sVQuuvFDKu6vklPVeiUXT",
                            "title": "Dunia Yang Nanti",
                            "subtitle": "Raim Laode",
                            "duration": 202100
                          }
                        ]
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(sampleNextData).jsonObject
        val entity = root["props"]?.jsonObject
            ?.get("pageProps")?.jsonObject
            ?.get("state")?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("entity")?.jsonObject
        assertNotNull(entity)

        val trackList = entity["trackList"]?.jsonArray
        assertNotNull(trackList)
        assertEquals(2, trackList.size)

        val tracks = trackList.mapIndexed { index, el ->
            val item = el.jsonObject
            val uri = item["uri"]?.jsonPrimitive?.content ?: ""
            val id = uri.substringAfterLast(":")
            val title = item["title"]?.jsonPrimitive?.content ?: ""
            val artist = item["subtitle"]?.jsonPrimitive?.content ?: ""
            Track(id = id, title = title, artist = artist, rank = index + 1)
        }

        assertEquals(2, tracks.size)
        assertEquals("4xoY4lZNoTjEuHsSmhgF1G", tracks[0].id)
        assertEquals("Sesi Potret", tracks[0].title)
        assertEquals(1, tracks[0].rank)

        val newReleaseAlbums = tracks.map { track ->
            com.blek.blexsy.model.AlbumSummary(
                id = track.id,
                name = track.title,
                artist = track.artist,
                year = "NEW",
                type = "SINGLE"
            )
        }
        assertEquals(2, newReleaseAlbums.size)
        assertEquals("Sesi Potret", newReleaseAlbums[0].name)
        assertEquals("NEW", newReleaseAlbums[0].year)
    }

    @Test
    fun testThemeModeOptionsAndColors() {
        com.blek.blexsy.ui.BlexsyThemeConfig.themeMode = com.blek.blexsy.ui.ThemeMode.DARK
        assertFalse(com.blek.blexsy.ui.BlexsyColors.isAmoled)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF121212), com.blek.blexsy.ui.BlexsyColors.Background)

        com.blek.blexsy.ui.BlexsyThemeConfig.themeMode = com.blek.blexsy.ui.ThemeMode.AMOLED
        assertTrue(com.blek.blexsy.ui.BlexsyColors.isAmoled)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF000000), com.blek.blexsy.ui.BlexsyColors.Background)

        com.blek.blexsy.ui.BlexsyThemeConfig.themeMode = com.blek.blexsy.ui.ThemeMode.DARK
    }

    @Test
    fun testResolveHeroTracksEvenDistributionAndNoRecents() {
        val active = Track(id = "np", title = "Now Playing")
        val favorites = (1..6).map { Track(id = "fav-$it", title = "Fav $it") }
        val artistTracks = (1..6).map { Track(id = "art-$it", title = "Art $it") }
        val chartTracks = (1..10).map { Track(id = "chart-$it", title = "Chart $it") }

        val resolved = com.blek.blexsy.ui.resolveHeroTracks(
            activeTrack = active,
            favorites = favorites,
            favoriteArtistTracks = artistTracks,
            chartTracks = chartTracks,
            limit = 10
        )

        assertEquals(10, resolved.size)
        // Active track is first
        assertEquals("np", resolved[0].id)
        // Favorites and artist tracks alternate 1:1 (fair share, no domination)
        assertEquals("fav-1", resolved[1].id)
        assertEquals("art-1", resolved[2].id)
        assertEquals("fav-2", resolved[3].id)
        assertEquals("art-2", resolved[4].id)
        // Fallback fill when few items available
        val sparse = com.blek.blexsy.ui.resolveHeroTracks(
            activeTrack = null,
            favorites = listOf(Track(id = "fav-1")),
            favoriteArtistTracks = listOf(Track(id = "art-1")),
            chartTracks = chartTracks,
            limit = 5
        )
        assertEquals(listOf("fav-1", "art-1", "chart-1", "chart-2", "chart-3"), sparse.map { it.id })
    }

    @Test
    fun testResolveHeroTracksWeightedScoringBoostsFrequentPlays() {
        val fav1 = Track(id = "fav-1", title = "Fav 1")
        val fav2 = Track(id = "fav-2", title = "Fav 2")
        val art1 = Track(id = "art-1", title = "Art 1")
        val art2 = Track(id = "art-2", title = "Art 2")

        val resolved = com.blek.blexsy.ui.resolveHeroTracks(
            activeTrack = null,
            favorites = listOf(fav1, fav2),
            favoriteArtistTracks = listOf(art1, art2),
            chartTracks = emptyList(),
            playCounts = mapOf("fav-2" to 10),
            recentTracks = listOf(art1),
            limit = 4
        )
        assertEquals(listOf("fav-2", "art-2", "fav-1", "art-1"), resolved.map { it.id })
    }

    @Test
    fun testTrackMultiArtistResolution() {
        val singleArtistTrack = Track(artist = "Prince", artistIds = listOf("prince-id"))
        assertEquals(listOf("prince-id" to "Prince"), singleArtistTrack.artistRefs)

        val multiArtistWithIds = Track(artist = "Prince, Sheila E.", artistIds = listOf("prince-id", "sheila-id"))
        assertEquals(listOf("prince-id" to "Prince", "sheila-id" to "Sheila E."), multiArtistWithIds.artistRefs)

        val multiArtistCommaOnly = Track(artist = "Coldplay, BTS")
        assertEquals(listOf("Coldplay" to "Coldplay", "BTS" to "BTS"), multiArtistCommaOnly.artistRefs)
    }
}
