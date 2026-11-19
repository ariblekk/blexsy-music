package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.BuildConfig
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.ChartFeed
import com.blek.blexsy.model.FeaturedItem
import com.blek.blexsy.model.HomeSectionItem
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.toHighResArtwork
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Header line under a chart title: how many tracks are ranked and when Spotify last refreshed it.
 * Null for the YouTube fallback chart, which carries neither, so the header stays a single line.
 */
internal fun ChartFeed.chartSubtitle(): String? {
    if (tracks.isEmpty()) return null
    val count = tracks.size
    // `updated_at` is an ISO instant; the day is all a chart header needs.
    val day = updatedAt?.take(10)?.takeIf { it.length == 10 }
    return if (day != null) "$count lagu • diperbarui $day" else "$count lagu"
}

internal fun resolveHeroTracks(
    favorites: List<Track>,
    chartTracks: List<Track>,
    playCounts: Map<String, Int> = emptyMap(),
    recentTracks: List<Track> = emptyList(),
    limit: Int = 10
): List<Track> {
    // ponytail: weighted scoring (favourite boost + play-count boost + recency dampener).
    // The pool is deliberately independent of the playing track: promoting / excluding it re-ranks
    // the list on every play, which swaps the carousel slides under the pager's index.
    fun score(track: Track): Double {
        var s = 5.0
        val plays = playCounts[track.id] ?: track.playCount ?: 0
        s += minOf(plays * 0.8, 4.0)
        val recentIdx = recentTracks.indexOfFirst { it.id == track.id }
        if (recentIdx in 0..2) {
            s -= 2.0
        }
        return s
    }

    val favRanked = favorites
        .distinctBy { it.id }
        .sortedByDescending { score(it) }

    return (favRanked + chartTracks).distinctBy { it.id }.take(limit)
}

@Composable
internal fun ListenNowTab(
    contentTopPadding: androidx.compose.ui.unit.Dp,
    contentBottomPadding: androidx.compose.ui.unit.Dp,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    chartTitle: String? = null,
    chartFeed: ChartFeed? = null,
    tracks: List<Track>,
    recentTracks: List<Track>,
    favorites: List<Track>,
    favoriteArtists: List<ArtistSummary> = emptyList(),
    collectionPlaylists: List<Playlist> = emptyList(),
    dynamicSections: List<HomeSectionItem> = emptyList(),
    playCounts: Map<String, Int> = emptyMap(),
    activeTrack: Track?,
    isPlaying: Boolean = false,
    loadingAlbumId: String? = null,
    isLoading: Boolean,
    onPlayChart: (Track, List<Track>) -> Unit,
    onSeeAllChart: () -> Unit,
    onPlayRecent: (List<Track>) -> Unit,
    onSeeAllLiked: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit = {},
    onSeeAllPlaylists: (() -> Unit)? = null,
    onTrackOptions: (Track, List<Track>) -> Unit,
    onTogglePlayPause: (() -> Unit)? = null
) {
    // Keyed on library sources only. `activeTrack`, `recentTracks` and `playCounts` all change on
    // every play; keying the list on them rebuilt the carousel mid-song, which read as a slide.
    // They are still fed into the ranking, just sampled when the library itself changes.
    val heroTracks = remember(favorites, tracks) {
        resolveHeroTracks(
            favorites = favorites,
            chartTracks = tracks,
            playCounts = playCounts,
            recentTracks = recentTracks
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentBottomPadding)
    ) {
        // 1. Hero Slider
        if (heroTracks.isNotEmpty()) {
            item {
                ArtistStyleHeroCarousel(
                    tracks = heroTracks,
                    activeTrack = activeTrack,
                    isPlaying = isPlaying,
                    topInset = topInset,
                    onPlay = { track -> onPlayChart(track, heroTracks) },
                    onTogglePlayPause = onTogglePlayPause
                )
            }
        } else if (isLoading) {
            item {
                Spacer(modifier = Modifier.height(contentTopPadding))
                HomeFeedSkeleton()
            }
        } else {
            item { Spacer(modifier = Modifier.height(contentTopPadding)) }
        }

        if (isLoading && heroTracks.isNotEmpty() && tracks.isEmpty()) {
            item { HomeFeedSkeleton() }
        }

        // Server-driven recommendation shelves (e.g. YouTube "Pilihan cepat").
        for (sec in dynamicSections) {
            val secTracks = sec.tracks
            if (secTracks.isNotEmpty()) {
                item {
                    SectionHeader(
                        kicker = stringResource(R.string.listen_popular_artists_kicker),
                        title = sec.title
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(secTracks.take(10)) { track ->
                            TrackCard(
                                track = track,
                                showRank = false,
                                isPlayingThis = activeTrack?.id == track.id,
                                onClick = { onPlayChart(track, secTracks) }
                            )
                        }
                    }
                }
            }
        }

        // Baru Saja Di Dengar — rendered once, whether or not the server sent shelves.
        if (recentTracks.isNotEmpty()) {
            item {
                SectionHeader(
                    kicker = stringResource(R.string.listen_repeat_kicker),
                    title = stringResource(R.string.listen_repeat_title),
                    action = stringResource(R.string.listen_action_run_it_back),
                    onAction = { onPlayRecent(recentTracks) }
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(recentTracks) { track ->
                        TrackCard(
                            track = track,
                            isPlayingThis = activeTrack?.id == track.id,
                            onClick = { onPlayChart(track, recentTracks) }
                        )
                    }
                }
            }
        }

        // Chart. The rows come from Spotify and do not depend on the YouTube home feed, so this
        // shelf is rendered whenever a chart exists — it used to live in the `else` branch above
        // and vanished entirely when the home feed came back empty.
        if (tracks.isNotEmpty()) {
            item {
                SectionHeader(
                    kicker = stringResource(R.string.listen_charts_kicker),
                    title = chartTitle?.ifBlank { null } ?: stringResource(R.string.listen_charts_title),
                    subtitle = chartFeed?.chartSubtitle(),
                    action = stringResource(R.string.listen_action_see_all),
                    onAction = onSeeAllChart
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(tracks.take(10)) { index, track ->
                        TrackCard(
                            track = track,
                            // Spotify rows already carry their real position; the index is
                            // only a fallback for a YouTube chart that has none.
                            rank = track.rank ?: (index + 1),
                            isPlayingThis = activeTrack?.id == track.id,
                            onClick = { onPlayChart(track, tracks) }
                        )
                    }
                }
            }
        }

        // 7. Trending songs: charting but not yet ranked — no position badge.
        val trendingSongs = chartFeed?.trending.orEmpty().take(10)
        if (trendingSongs.isNotEmpty()) {
            item {
                SectionHeader(
                    kicker = stringResource(R.string.listen_trending_kicker),
                    title = stringResource(R.string.listen_trending_title)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(trendingSongs) { track ->
                        TrackCard(
                            track = track,
                            showRank = false,
                            isPlayingThis = activeTrack?.id == track.id,
                            onClick = { onPlayChart(track, trendingSongs) }
                        )
                    }
                }
            }
        }

        // 8. Trending artists in the market.
        val trendingArtists = chartFeed?.popularArtists.orEmpty().take(10)
        if (trendingArtists.isNotEmpty()) {
            item {
                SectionHeader(
                    kicker = stringResource(R.string.listen_trending_artists_kicker),
                    title = stringResource(R.string.listen_trending_artists_title)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(trendingArtists, key = { "trending-artist:${it.id}" }) { artist ->
                        ArtistCircleCard(
                            artist = artist,
                            onClick = { artist.channelId?.let(onOpenArtist) }
                        )
                    }
                }
            }
        }

        // 5. Artist Favorite
        if (favoriteArtists.isNotEmpty()) {            item {
                SectionHeader(
                    kicker = stringResource(R.string.listen_favorite_artists_kicker),
                    title = stringResource(R.string.listen_favorite_artists_title)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(favoriteArtists) { artist ->
                        ArtistCircleCard(
                            artist = artist,
                            onClick = { artist.channelId?.let(onOpenArtist) }
                        )
                    }
                }
            }
        }

        // 7. Daftar Playlist Koleksi
        if (collectionPlaylists.isNotEmpty()) {
            item {
                SectionHeader(
                    kicker = stringResource(R.string.listen_playlist_collection_kicker),
                    title = stringResource(R.string.listen_playlist_collection_title),
                    action = if (onSeeAllPlaylists != null) stringResource(R.string.listen_action_see_all) else null,
                    onAction = onSeeAllPlaylists
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(collectionPlaylists) { playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            onClick = { onOpenPlaylist(playlist) }
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
internal fun ArtistStyleHeroCarousel(
    tracks: List<Track>,
    activeTrack: Track?,
    isPlaying: Boolean,
    topInset: androidx.compose.ui.unit.Dp,
    onPlay: (Track) -> Unit,
    onTogglePlayPause: (() -> Unit)? = null
) {
    val pagerState = rememberPagerState(pageCount = { tracks.size })

    // Defensive: keep the pager inside bounds when the ranked list shrinks.
    LaunchedEffect(tracks.size) {
        if (tracks.isNotEmpty() && pagerState.currentPage > tracks.lastIndex) {
            pagerState.scrollToPage(tracks.lastIndex)
        }
    }

    LaunchedEffect(tracks.size) {
        if (tracks.size <= 1) return@LaunchedEffect
        while (true) {
            delay(6500L) // Jeda 6.5 detik per slide agar tidak terburu-buru
            if (!pagerState.isScrollInProgress) {
                val next = (pagerState.currentPage + 1) % tracks.size
                pagerState.animateScrollToPage(
                    page = next,
                    animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .clipToBounds()
    ) {
        // Fader blur image carousel: gambar tidak bergeser melainkan crossfade + blur lembut
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Keyed by track, not by index, so Compose reuses the right slide and image when the
            // ranked list changes instead of repainting new artwork in an old slot.
            key = { index -> tracks[index].id }
        ) { page ->
            val track = tracks[page]
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val absOffset = pageOffset.absoluteValue.coerceIn(0f, 1f)

            AsyncImage(
                model = track.image.toHighResArtwork(),
                contentDescription = track.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = pageOffset * size.width
                        alpha = (1f - absOffset).coerceIn(0f, 1f)
                    }
                    .blur(radius = (22.dp * absOffset).coerceIn(0.dp, 22.dp))
            )
        }

        // Top shadow scrim for status bar readability
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topInset + 64.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.65f),
                        1f to Color.Transparent
                    )
                )
        )

        // Bottom shadow gradient blending smoothly into the screen background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.38f to Color.Transparent,
                        0.68f to Color.Black.copy(alpha = 0.50f),
                        0.88f to Color.Black.copy(alpha = 0.90f),
                        1.00f to BlexsyColors.Background
                    )
                )
        )

        // Animasi teks dan button bertransisi fade lembut
        AnimatedContent(
            targetState = pagerState.currentPage.coerceIn(0, tracks.lastIndex),
            transitionSpec = {
                fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(350))
            },
            label = "hero_fade_transition",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) { pageIndex ->
            val track = tracks.getOrNull(pageIndex) ?: return@AnimatedContent
            val isCurrent = activeTrack?.id == track.id
            val isPlayingThis = isCurrent && isPlaying
            val isPausedThis = isCurrent && !isPlaying

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isPlayingThis) {
                            Icon(
                                imageVector = IcPlay,
                                contentDescription = stringResource(R.string.a11y_playing),
                                tint = BlexsyColors.Primary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Text(
                            text = when {
                                isPausedThis -> stringResource(R.string.listen_pick_resume)
                                isPlayingThis -> stringResource(R.string.listen_pick_now_spinning)
                                else -> stringResource(R.string.listen_pick_artist)
                            },
                            style = BlexsyTypography.Caption.copy(
                                color = BlexsyColors.Primary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                fontSize = 11.sp
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = track.displayTitle,
                        style = BlexsyTypography.Display.copy(
                            fontFamily = HeadingFont,
                            fontSize = 24.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = track.artist,
                        style = BlexsyTypography.BodyMuted.copy(
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                PlayCircleButton(
                    onClick = {
                        if (isCurrent) {
                            onTogglePlayPause?.invoke() ?: onPlay(track)
                        } else {
                            onPlay(track)
                        }
                    },
                    isPlaying = isPlayingThis,
                    size = 52
                )
            }
        }
    }
}

@Composable
internal fun FeaturedCustomHero(item: FeaturedItem, onClick: () -> Unit) {
    // ponytail: 2 variants for featured card: banner (external/link/promo) or track (music/playable)
    when (item.layout.lowercase()) {
        "banner" -> FeaturedBannerCard(item = item, onClick = onClick)
        else -> FeaturedTrackCard(item = item, onClick = onClick)
    }
}

@Composable
internal fun FeaturedBannerCard(item: FeaturedItem, onClick: () -> Unit) {
    FeaturedCardLayout(
        item = item,
        onClick = onClick,
        buttonText = stringResource(R.string.featured_action_open)
    )
}

@Composable
internal fun FeaturedTrackCard(item: FeaturedItem, onClick: () -> Unit) {
    FeaturedCardLayout(
        item = item,
        onClick = onClick,
        buttonText = stringResource(R.string.featured_action_listen_now)
    )
}

@Composable
private fun FeaturedCardLayout(
    item: FeaturedItem,
    onClick: () -> Unit,
    buttonText: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BlexsyColors.SurfaceContainerHigh)
            .padding(12.dp)
    ) {
        Column {
            // 1. Artwork 16:9 with kicker badge & options
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
            ) {
                if (!item.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.imageUrl.toHighResArtwork(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BlexsyColors.SurfaceContainer)
                    )
                }

                // Kicker badge top-left
                val kickerText = item.kicker.trim().ifBlank { "FEATURED" }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = kickerText,
                        style = BlexsyTypography.Caption.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.4.sp
                        )
                    )
                }

                // 3-dots top-right
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = IcDotsThreeVertical,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Bottom Row: Title + Subtitle and Pill Action Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text(
                        text = item.title,
                        style = BlexsyTypography.Title.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.subtitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = item.subtitle,
                            style = BlexsyTypography.BodyMuted.copy(
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        )
                    }
                }

                // Pill Button
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onClick)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = buttonText,
                        style = BlexsyTypography.Caption.copy(
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun ArtistCircleCard(
    artist: ArtistSummary,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(BlexsyColors.SurfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = artist.name.take(1).uppercase(),
                style = BlexsyTypography.Title.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.35f)
                )
            )
            if (!artist.image.isNullOrBlank()) {
                AsyncImage(
                    model = artist.image,
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = BlexsyTypography.Title.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = BlexsyColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.search_artist),
            style = BlexsyTypography.Subtitle.copy(fontSize = 11.sp),
            color = BlexsyColors.TextTertiary,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(152.dp),
    image: String? = null
) {
    val isLiked = playlist.id == "liked"
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BlexsyColors.Surface)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            if (isLiked && playlist.tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF6A4BA8), Color(0xFF3B2A5C))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = IcHeartFilled,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            } else {
                CoverImage(
                    url = image ?: playlist.tracks.firstOrNull()?.image,
                    size = 0,
                    shapeRadius = 0,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Text(
                text = playlist.name,
                style = BlexsyTypography.Title.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = BlexsyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Home-feed cards carry one blank placeholder track, kept only for the cover image.
            // Counting it printed "1 track" for every playlist, so blank entries are ignored.
            val trackCount = playlist.tracks.count { it.id.isNotBlank() }
            if (trackCount > 0) {
                Text(
                    text = stringResource(R.string.playlist_tracks_count, trackCount),
                    style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

