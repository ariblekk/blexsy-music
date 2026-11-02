package com.blek.blexsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.model.ArtistDetail
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.toHighResArtwork
import com.blek.blexsy.player.BlexsyAudioPlayer

@Composable
fun ArtistDetailScreen(
    artistId: String,
    api: BlexsyApiClient,
    player: BlexsyAudioPlayer,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit = {},
    onTrackOptions: (Track, List<Track>) -> Unit,
    onExpandPlayer: () -> Unit = {},
    bottomNav: (@Composable () -> Unit)? = null
) {
    var artist by remember(artistId) { mutableStateOf<ArtistDetail?>(null) }
    var motionVideoUrl by remember(artistId) { mutableStateOf<String?>(null) }
    var isLoading by remember(artistId) { mutableStateOf(true) }
    var error by remember(artistId) { mutableStateOf<String?>(null) }
    var showAllTracks by remember(artistId) { mutableStateOf(false) }
    var catalogTabIndex by remember(artistId) { mutableIntStateOf(0) }
    val activeTrack by player.currentTrack.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val listState = remember(artistId) { androidx.compose.foundation.lazy.LazyListState() }
    val dominantColor = rememberDominantColor(artist?.image)
    val topBarAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else ((listState.firstVisibleItemScrollOffset - 180f) / 220f).coerceIn(0f, 1f)
        }
    }

    val defaultErrorMessage = stringResource(R.string.artist_error_failed)

    LaunchedEffect(artistId) {
        val cached = api.cachedArtist(artistId)
        if (cached != null) {
            artist = cached
            isLoading = false
        } else {
            isLoading = true
        }
        error = null
        try {
            artist = api.artist(artistId)
        } catch (e: Exception) {
            if (artist == null) {
                error = e.message ?: defaultErrorMessage
            }
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(artist?.name) {
        val name = artist?.name ?: return@LaunchedEffect
        motionVideoUrl = com.blek.blexsy.data.ArtistCanvasService.getMotionVideoUrl(name)
    }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomPadding = if (bottomNav != null) {
        bottomInset + (if (activeTrack != null) 160.dp else 90.dp)
    } else {
        bottomInset + (if (activeTrack != null) 72.dp else 28.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.00f to dominantColor,
                    0.45f to dominantColor,
                    0.85f to BlexsyColors.Background,
                    1.00f to BlexsyColors.Background
                )
            )
    ) {
        if (isLoading) {
            ArtistDetailSkeleton(
                modifier = Modifier.fillMaxSize(),
                rows = 6
            )
        } else if (error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = error!!, color = MaterialTheme.colorScheme.error, style = BlexsyTypography.Body)
            }
        } else if (artist != null) {
            val art = artist!!
            val displayedTracks = if (showAllTracks) art.topTracks else art.topTracks.take(5)
            val isPlayingArtist = isPlaying && art.topTracks.any { it.id == activeTrack?.id }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentBottomPadding)
            ) {
                // Full Width Artist Hero extending down to the play button
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .clipToBounds()
                    ) {
                        AsyncImage(
                            model = art.image.toHighResArtwork(),
                            contentDescription = art.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationY = listState.firstVisibleItemScrollOffset * 0.45f
                                }
                        )
                        if (motionVideoUrl != null) {
                            ArtistCanvasPlayer(
                                videoUrl = motionVideoUrl!!,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationY = listState.firstVisibleItemScrollOffset * 0.45f
                                    }
                            )
                        }
                        // Top shadow scrim for status bar / back button readability
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.55f),
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
                                        0.45f to Color.Transparent,
                                        0.70f to dominantColor.copy(alpha = 0.50f),
                                        0.88f to dominantColor.copy(alpha = 0.90f),
                                        1.00f to dominantColor
                                    )
                                )
                        )
                        // Centered artist info and elongated play button
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 20.dp)
                                .graphicsLayer {
                                    alpha = (1f - (listState.firstVisibleItemScrollOffset / 400f)).coerceIn(0f, 1f)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = art.name,
                                    style = BlexsyTypography.Display.copy(
                                        fontFamily = DeezerBoldFont,
                                        fontSize = 30.sp,
                                        lineHeight = 36.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.3.sp,
                                        color = Color.White
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (art.verified) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Verified,
                                        contentDescription = stringResource(R.string.a11y_verified),
                                        tint = Color(0xFF3D91F4),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            val subCount = art.followers?.takeIf { it > 0 } ?: art.monthlyListeners?.takeIf { it > 0 }
                            val subText = if (!art.subscribers.isNullOrBlank()) {
                                art.subscribers
                            } else if (subCount != null) {
                                stringResource(R.string.artist_subscribers, formatSubscriberNumber(subCount))
                            } else null
                            if (subText != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = subText,
                                    style = BlexsyTypography.BodyMuted.copy(
                                        color = Color.White.copy(alpha = 0.75f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    textAlign = TextAlign.Center
                                )
                            }

                            if (art.topTracks.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(18.dp))
                                BlexsyButton(
                                    text = stringResource(if (isPlayingArtist) R.string.player_pause else R.string.player_play),
                                    onClick = {
                                        if (isPlayingArtist) {
                                            player.pause()
                                        } else {
                                            player.play(art.topTracks.first(), art.topTracks)
                                        }
                                    },
                                    leadingIcon = if (isPlayingArtist) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    variant = ButtonVariant.Primary,
                                    size = ButtonSize.Md,
                                    modifier = Modifier.widthIn(min = 160.dp, max = 220.dp)
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }

                // Lagu Populer
                if (art.topTracks.isNotEmpty()) {
                    item {
                        SectionHeader(
                            kicker = stringResource(R.string.artist_kicker_favorites),
                            title = stringResource(R.string.artist_title_top_tracks),
                            action = if (art.topTracks.size > 5) (if (showAllTracks) stringResource(R.string.action_less) else stringResource(R.string.action_see_all)) else null,
                            onAction = { showAllTracks = !showAllTracks }
                        )
                    }

                    itemsIndexed(displayedTracks) { index, track ->
                        TrackTile(
                            track = track,
                            index = index,
                            isPlayingThis = activeTrack?.id == track.id,
                            onClick = { player.play(track, art.topTracks) },
                            onMore = { onTrackOptions(track, art.topTracks) }
                        )
                    }
                }

                // Diskografi / Releases (Separated by tabs)
                val hasAlbums = art.albums.isNotEmpty()
                val hasSingles = art.singlesAndEps.isNotEmpty()

                if (hasAlbums || hasSingles) {
                    item {
                        Spacer(modifier = Modifier.height(14.dp))
                        SectionHeader(
                            kicker = stringResource(R.string.artist_kicker_catalog),
                            title = if (hasAlbums && hasSingles) stringResource(R.string.artist_title_releases) else if (hasAlbums) stringResource(R.string.artist_title_albums) else stringResource(R.string.artist_title_singles)
                        )
                    }

                    if (hasAlbums && hasSingles) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BlexsyTabs(
                                    tabs = listOf(stringResource(R.string.artist_tab_albums), stringResource(R.string.artist_tab_singles)),
                                    selectedIndex = catalogTabIndex.coerceIn(0, 1),
                                    onSelect = { catalogTabIndex = it },
                                    fillMaxWidth = false
                                )
                            }
                        }
                    }

                    val currentReleases = when {
                        hasAlbums && hasSingles -> if (catalogTabIndex == 0) art.albums else art.singlesAndEps
                        hasAlbums -> art.albums
                        else -> art.singlesAndEps
                    }

                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(currentReleases) { alb ->
                                Column(
                                    modifier = Modifier
                                        .width(140.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onOpenAlbum(alb.id) }
                                ) {
                                    CoverImage(url = alb.image, size = 140, shapeRadius = 4)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = alb.name,
                                        style = BlexsyTypography.Title.copy(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${alb.year ?: "—"} • ${alb.type}",
                                        style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                // Playlists dari Artis
                if (art.playlists.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(18.dp))
                        SectionHeader(
                            kicker = stringResource(R.string.artist_kicker_curated),
                            title = stringResource(R.string.artist_title_playlists)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(art.playlists) { plist ->
                                Column(
                                    modifier = Modifier
                                        .width(140.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onOpenAlbum(plist.id) }
                                ) {
                                    CoverImage(url = plist.image, size = 140, shapeRadius = 4)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = plist.name,
                                        style = BlexsyTypography.Title.copy(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = plist.year ?: stringResource(R.string.type_playlist),
                                        style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                // Similar Artists (Mungkin Anda juga berminat / Fans Also Dig)
                if (art.similarArtists.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(18.dp))
                        SectionHeader(
                            kicker = stringResource(R.string.artist_kicker_similar),
                            title = stringResource(R.string.artist_title_similar)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(art.similarArtists) { simArtist ->
                                Column(
                                    modifier = Modifier
                                        .width(110.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onOpenArtist(simArtist.id) },
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CoverImage(
                                        url = simArtist.image,
                                        size = 110,
                                        shapeRadius = 55
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = simArtist.name,
                                        style = BlexsyTypography.Title.copy(
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = simArtist.subscribers ?: stringResource(R.string.search_artist),
                                        style = BlexsyTypography.Subtitle.copy(fontSize = 11.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                // Biografi
                art.biography?.let { bio ->
                    if (bio.isNotBlank()) {
                        item {
                            Spacer(modifier = Modifier.height(20.dp))
                            SectionHeader(
                                kicker = stringResource(R.string.artist_kicker_backstory),
                                title = stringResource(R.string.artist_title_meet)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BlexsyColors.SurfaceContainerHigh)
                                    .padding(18.dp)
                            ) {
                                val annotatedBio = remember(bio) {
                                    val formattedHtml = if (!bio.contains("<br", ignoreCase = true) && !bio.contains("<p", ignoreCase = true)) {
                                        bio.replace("\n", "<br/>")
                                    } else {
                                        bio
                                    }
                                    runCatching {
                                        AnnotatedString.fromHtml(
                                            htmlString = formattedHtml,
                                            linkStyles = TextLinkStyles(
                                                style = SpanStyle(
                                                    color = BlexsyColors.Primary,
                                                    textDecoration = TextDecoration.Underline
                                                )
                                            )
                                        )
                                    }.getOrElse {
                                        AnnotatedString(stripHtml(bio))
                                    }
                                }
                                Text(
                                    text = annotatedBio,
                                    style = BlexsyTypography.BodyMuted,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = BlexsyColors.Background.copy(alpha = if (artist != null) topBarAlpha else 0f),
            shadowElevation = if (topBarAlpha > 0.8f) 4.dp else 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = BlexsyColors.TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                if (artist != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer { alpha = topBarAlpha },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = artist!!.name,
                            style = BlexsyTypography.Title.copy(
                                fontFamily = DeezerBoldFont,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.2.sp,
                                color = BlexsyColors.TextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (artist!!.verified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.Verified,
                                contentDescription = stringResource(R.string.a11y_verified),
                                tint = Color(0xFF3D91F4),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Mini player + floating transparent gradient navbar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            MiniPlayer(
                player = player,
                onExpand = onExpandPlayer,
                includeNavigationBars = bottomNav == null
            )
            bottomNav?.invoke()
        }
    }
}

internal fun formatSubscriberNumber(count: Int): String = when {
    count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000.0)
    else -> count.toString()
}
