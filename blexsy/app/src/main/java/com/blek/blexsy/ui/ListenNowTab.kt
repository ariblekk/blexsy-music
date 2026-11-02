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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import com.blek.blexsy.model.AlbumSummary
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

internal fun resolveHeroTracks(
    activeTrack: Track?,
    favorites: List<Track>,
    favoriteArtistTracks: List<Track>,
    chartTracks: List<Track>,
    playCounts: Map<String, Int> = emptyMap(),
    recentTracks: List<Track> = emptyList(),
    limit: Int = 10
): List<Track> {
    // ponytail: weighted scoring (play-count boost + recency dampener) with 1:1 bucket quota
    fun score(track: Track, isFav: Boolean, isArtist: Boolean): Double {
        var s = (if (isFav) 5.0 else 0.0) + (if (isArtist) 3.0 else 0.0)
        val plays = playCounts[track.id] ?: track.playCount ?: 0
        s += minOf(plays * 0.8, 4.0)
        val recentIdx = recentTracks.indexOfFirst { it.id == track.id }
        if (recentIdx in 0..2 && track.id != activeTrack?.id) {
            s -= 2.0
        }
        return s
    }

    val favRanked = favorites
        .filter { it.id != activeTrack?.id }
        .distinctBy { it.id }
        .sortedByDescending { score(it, isFav = true, isArtist = favoriteArtistTracks.any { a -> a.id == it.id }) }

    val artistRanked = favoriteArtistTracks
        .filter { it.id != activeTrack?.id }
        .distinctBy { it.id }
        .sortedByDescending { score(it, isFav = favorites.any { f -> f.id == it.id }, isArtist = true) }

    val pool = buildList {
        if (activeTrack != null) add(activeTrack)
        val maxLen = maxOf(favRanked.size, artistRanked.size)
        for (i in 0 until maxLen) {
            favRanked.getOrNull(i)?.let { add(it) }
            artistRanked.getOrNull(i)?.let { add(it) }
        }
        addAll(chartTracks)
    }
    return pool.distinctBy { it.id }.take(limit)
}

@Composable
internal fun ListenNowTab(
    contentTopPadding: androidx.compose.ui.unit.Dp,
    contentBottomPadding: androidx.compose.ui.unit.Dp,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    tracks: List<Track>,
    favoriteArtistTracks: List<Track> = emptyList(),
    newReleases: List<AlbumSummary> = emptyList(),
    recentTracks: List<Track>,
    favorites: List<Track>,
    playCounts: Map<String, Int> = emptyMap(),
    activeTrack: Track?,
    isPlaying: Boolean = false,
    loadingAlbumId: String? = null,
    isLoading: Boolean,
    onPlayChart: (Track, List<Track>) -> Unit,
    onSeeAllChart: () -> Unit,
    onOpenAlbum: (String) -> Unit = {},
    onPlayAlbum: ((AlbumSummary) -> Unit)? = null,
    onSeeAllNewReleases: (() -> Unit)? = null,
    onPlayRecent: (List<Track>) -> Unit,
    onSeeAllLiked: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onOpenArtist: (String) -> Unit,
    onTrackOptions: (Track, List<Track>) -> Unit,
    onTogglePlayPause: (() -> Unit)? = null
) {
    val heroTracks = remember(activeTrack, favorites, favoriteArtistTracks, tracks, playCounts, recentTracks) {
        resolveHeroTracks(
            activeTrack = activeTrack,
            favorites = favorites,
            favoriteArtistTracks = favoriteArtistTracks,
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

        if (tracks.isNotEmpty()) {
            // Breathing room between the quick access tiles and the featured hero card.
            item { Spacer(modifier = Modifier.height(10.dp)) }
            item {
                FeaturedHero(
                    track = tracks.first(),
                    onClick = { onPlayChart(tracks.first(), tracks) }
                )
            }
        }

        if (tracks.isNotEmpty()) {
            item {
                SectionHeader(
                    kicker = stringResource(R.string.listen_charts_kicker),
                    title = stringResource(R.string.listen_charts_title),
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
                            rank = index + 1,
                            isPlayingThis = activeTrack?.id == track.id,
                            onClick = { onPlayChart(track, tracks) }
                        )
                    }
                }
            }
        }

        if (newReleases.isNotEmpty()) {
            item {
                SectionHeader(
                    kicker = stringResource(R.string.listen_drops_kicker),
                    title = stringResource(R.string.listen_drops_title),
                    action = stringResource(R.string.listen_action_show_all),
                    onAction = onSeeAllNewReleases
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(newReleases.take(10)) { album ->
                        val isThisAlbumPlaying = isPlaying && (activeTrack?.albumId == album.id || activeTrack?.album.equals(album.name, ignoreCase = true))
                        val isThisAlbumLoading = loadingAlbumId == album.id
                        AlbumCard(
                            album = album,
                            onClick = { onOpenAlbum(album.id) },
                            isPlaying = isThisAlbumPlaying,
                            isLoading = isThisAlbumLoading,
                            onPlay = onPlayAlbum?.let { play -> { play(album) } }
                        )
                    }
                }
            }
        }

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

        if (favorites.isNotEmpty()) {
            item {
                SectionHeader(
                    kicker = stringResource(R.string.listen_hearts_kicker),
                    title = stringResource(R.string.listen_hearts_title),
                    action = stringResource(R.string.listen_action_see_all),
                    onAction = onSeeAllLiked
                )
            }
            itemsIndexed(favorites.take(5)) { index, track ->
                TrackTile(
                    track = track,
                    index = index,
                    isPlayingThis = activeTrack?.id == track.id,
                    isFavorite = true,
                    onClick = { onPlayChart(track, favorites) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    onOpenArtist = onOpenArtist,
                    onMore = { onTrackOptions(track, favorites) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
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
            modifier = Modifier.fillMaxSize()
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
                    .then(
                        if (absOffset < 0.5f) Modifier.clickable {
                            if (activeTrack?.id == track.id) {
                                onTogglePlayPause?.invoke() ?: onPlay(track)
                            } else {
                                onPlay(track)
                            }
                        }
                        else Modifier
                    )
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
                                imageVector = Icons.Rounded.PlayArrow,
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
                            fontFamily = DeezerBoldFont,
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
internal fun FeaturedHero(track: Track, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .aspectRatio(1.45f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = track.image.toHighResArtwork(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.35f),
                        1.0f to Color.Black.copy(alpha = 0.9f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 80.dp, bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.listen_charts_title).uppercase(),
                style = BlexsyTypography.Caption.copy(
                    color = BlexsyColors.Primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = track.displayTitle,
                style = BlexsyTypography.Hero.copy(fontSize = 25.sp, lineHeight = 29.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = track.artist,
                style = BlexsyTypography.BodyMuted.copy(
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.8f)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        PlayCircleButton(
            onClick = onClick,
            size = 48,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }
}
