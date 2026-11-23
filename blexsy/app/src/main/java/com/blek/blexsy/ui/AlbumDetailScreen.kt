package com.blek.blexsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.SpotifyAuthManager
import com.blek.blexsy.model.AlbumDetail
import com.blek.blexsy.model.AlbumSummary
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.toHighResArtwork
import com.blek.blexsy.player.BlexsyAudioPlayer
@Composable
fun AlbumDetailScreen(
    albumId: String,
    api: BlexsyApiClient,
    player: BlexsyAudioPlayer,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    favorites: List<Track> = emptyList(),
    onToggleFavorite: ((Track) -> Unit)? = null,
    onTrackOptions: (Track, List<Track>) -> Unit,
    onExpandPlayer: () -> Unit = {},
    bottomNav: (@Composable () -> Unit)? = null
) {
    val savedAlbums by SpotifyAuthManager.savedAlbums.collectAsState()
    var album by remember { mutableStateOf<AlbumDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val activeTrack by player.currentTrack.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val listState = rememberLazyListState()
    val dominantColor = BlexsyColors.Background
    val topBarAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else ((listState.firstVisibleItemScrollOffset - 180f) / 220f).coerceIn(0f, 1f)
        }
    }

    val defaultErrorMessage = stringResource(R.string.album_error_failed)

    LaunchedEffect(albumId) {
        isLoading = true
        error = null
        try {
            album = api.album(albumId)
        } catch (e: Exception) {
            error = e.message ?: defaultErrorMessage
        } finally {
            isLoading = false
        }
    }

    val layout = rememberBlexsyLayoutSpec()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomPadding = if (bottomNav != null) {
        bottomInset + (if (activeTrack != null) 160.dp else 90.dp)
    } else {
        bottomInset + (if (activeTrack != null) 88.dp else 28.dp)
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
            DetailHeroSkeleton(
                modifier = Modifier.fillMaxSize(),
                rows = 7
            )
        } else if (error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = error!!, color = MaterialTheme.colorScheme.error, style = BlexsyTypography.Body)
            }
        } else {
            val a = album ?: return@Box
            val isAlbumPlaying = isPlaying && a.tracks.any { it.id == activeTrack?.id }
            val isAlbumSaved = savedAlbums.any { it.id == a.id }
            val toggleSaveAlbum: () -> Unit = {
                SpotifyAuthManager.toggleSaveAlbum(
                    AlbumSummary(
                        id = a.id,
                        name = a.name,
                        artist = a.artist,
                        image = a.image,
                        year = a.year,
                        type = a.type
                    )
                )
            }

            if (layout.useTwoPaneDetail) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Left Pane: Pinned Album Cover, Metadata & Play CTA
                    Column(
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                            .padding(top = 8.dp, bottom = contentBottomPadding),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = IcArrowLeft,
                                    contentDescription = stringResource(R.string.action_back),
                                    tint = BlexsyColors.TextPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        CoverImage(
                            url = a.image,
                            size = 0,
                            shapeRadius = 12,
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = a.name,
                            style = BlexsyTypography.Display.copy(
                                fontSize = 24.sp,
                                lineHeight = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = a.artist,
                            style = BlexsyTypography.Title.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clickable {
                                a.tracks.firstOrNull()?.primaryArtistRef?.let(onOpenArtist)
                            }
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = stringResource(R.string.album_meta_tracks, a.year ?: "—", a.tracks.size).trimStart(' ', '•'),
                            style = BlexsyTypography.Caption.copy(
                                color = Color.White.copy(alpha = 0.8f)
                            ),
                            textAlign = TextAlign.Center
                        )

                        if (a.tracks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(18.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                BlexsyButton(
                                    text = if (isAlbumSaved) "Saved" else "Save",
                                    onClick = toggleSaveAlbum,
                                    leadingIcon = if (isAlbumSaved) IcCheckCircleFilled else IcPlusCircleOutline,
                                    variant = if (isAlbumSaved) ButtonVariant.Secondary else ButtonVariant.Outline,
                                    size = ButtonSize.Md
                                )
                                BlexsyButton(
                                    text = stringResource(if (isAlbumPlaying) R.string.player_pause else R.string.player_play),
                                    onClick = {
                                        if (isAlbumPlaying) {
                                            player.pause()
                                        } else {
                                            player.play(a.tracks.first(), a.tracks)
                                        }
                                    },
                                    leadingIcon = if (isAlbumPlaying) IcPause else IcPlay,
                                    variant = ButtonVariant.Primary,
                                    size = ButtonSize.Md,
                                    modifier = Modifier.widthIn(min = 120.dp, max = 170.dp)
                                )
                            }
                        }
                    }

                    // Right Pane: Scrollable Track List
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(top = 16.dp, bottom = contentBottomPadding)
                    ) {
                        itemsIndexed(a.tracks) { index, track ->
                            TrackTile(
                                track = track,
                                index = index,
                                isPlayingThis = activeTrack?.id == track.id,
                                isFavorite = favorites.any { it.id == track.id },
                                onToggleFavorite = onToggleFavorite?.let { cb -> { cb(track) } },
                                onClick = { player.play(track, a.tracks) },
                                onOpenArtist = onOpenArtist,
                                onMore = { onTrackOptions(track, a.tracks) }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = contentBottomPadding)
                ) {
                    // Full Width Album Hero extending down to the play button
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(380.dp)
                                .clipToBounds()
                        ) {
                            AsyncImage(
                                model = a.image.toHighResArtwork(),
                                contentDescription = a.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationY = listState.firstVisibleItemScrollOffset * 0.45f
                                    }
                            )
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
                            // Centered album info and elongated play button
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
                                Text(
                                    text = a.name,
                                    style = BlexsyTypography.Display.copy(
                                        fontSize = 28.sp,
                                        lineHeight = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = a.artist,
                                        style = BlexsyTypography.Title.copy(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.clickable {
                                            // Only real YouTube Music channel ids can open an artist page.
                                            a.tracks.firstOrNull()?.primaryArtistRef?.let(onOpenArtist)
                                        }
                                    )
                                    Text(
                                        text = stringResource(R.string.album_meta_tracks, a.year ?: "—", a.tracks.size),
                                        style = BlexsyTypography.Caption.copy(
                                            color = Color.White.copy(alpha = 0.8f)
                                        )
                                    )
                                }
                                if (a.tracks.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(18.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        BlexsyButton(
                                            text = if (isAlbumSaved) "Saved" else "Save",
                                            onClick = toggleSaveAlbum,
                                            leadingIcon = if (isAlbumSaved) IcCheckCircleFilled else IcPlusCircleOutline,
                                            variant = if (isAlbumSaved) ButtonVariant.Secondary else ButtonVariant.Outline,
                                            size = ButtonSize.Md
                                        )
                                        BlexsyButton(
                                            text = stringResource(if (isAlbumPlaying) R.string.player_pause else R.string.player_play),
                                            onClick = {
                                                if (isAlbumPlaying) {
                                                    player.pause()
                                                } else {
                                                    player.play(a.tracks.first(), a.tracks)
                                                }
                                            },
                                            leadingIcon = if (isAlbumPlaying) IcPause else IcPlay,
                                            variant = ButtonVariant.Primary,
                                            size = ButtonSize.Md,
                                            modifier = Modifier.widthIn(min = 136.dp, max = 190.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }

                    itemsIndexed(a.tracks) { index, track ->
                        TrackTile(
                            track = track,
                            index = index,
                            isPlayingThis = activeTrack?.id == track.id,
                            isFavorite = favorites.any { it.id == track.id },
                            onToggleFavorite = onToggleFavorite?.let { cb -> { cb(track) } },
                            onClick = { player.play(track, a.tracks) },
                            onOpenArtist = onOpenArtist,
                            onMore = { onTrackOptions(track, a.tracks) }
                        )
                    }
                }
            }
        }

        if (!layout.useTwoPaneDetail || album == null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = BlexsyColors.Background.copy(alpha = if (album != null) topBarAlpha else 0f),
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
                            imageVector = IcArrowLeft,
                            contentDescription = stringResource(R.string.action_back),
                            tint = BlexsyColors.TextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    if (album != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = album!!.name,
                            style = BlexsyTypography.Title.copy(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = BlexsyColors.TextPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .graphicsLayer { alpha = topBarAlpha }
                        )
                    }
                }
            }
        }

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
