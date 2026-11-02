package com.blek.blexsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
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
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.toHighResArtwork
import com.blek.blexsy.player.BlexsyAudioPlayer

@Composable
fun PlaylistDetailScreen(
    title: String,
    imageUrl: String?,
    description: String? = null,
    tracks: List<Track>,
    player: BlexsyAudioPlayer,
    onBack: () -> Unit,
    onDeletePlaylist: (() -> Unit)? = null,
    onTrackOptions: (Track, List<Track>) -> Unit,
    onExpandPlayer: () -> Unit = {},
    bottomNav: (@Composable () -> Unit)? = null
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val activeTrack by player.currentTrack.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val listState = rememberLazyListState()
    val dominantColor = rememberDominantColor(imageUrl)
    val topBarAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else ((listState.firstVisibleItemScrollOffset - 180f) / 220f).coerceIn(0f, 1f)
        }
    }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomPadding = if (bottomNav != null) {
        bottomInset + (if (activeTrack != null) 160.dp else 90.dp)
    } else {
        bottomInset + (if (activeTrack != null) 72.dp else 28.dp)
    }

    val isPlaylistPlaying = isPlaying && tracks.any { it.id == activeTrack?.id }

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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentBottomPadding)
        ) {
            // Full Width Playlist Hero extending down to the play button
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .clipToBounds()
                ) {
                    AsyncImage(
                        model = imageUrl.toHighResArtwork(),
                        contentDescription = title,
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
                    // Centered playlist info and elongated play button
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
                            text = stringResource(R.string.playlist_kicker),
                            style = BlexsyTypography.Caption.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                color = Color.White.copy(alpha = 0.85f)
                            ),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = title,
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
                        description?.let { desc ->
                            Text(
                                text = desc,
                                style = BlexsyTypography.Subtitle.copy(
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.85f)
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = stringResource(R.string.playlist_tracks_bangers, tracks.size),
                            style = BlexsyTypography.Caption.copy(
                                color = Color.White.copy(alpha = 0.8f)
                            ),
                            textAlign = TextAlign.Center
                        )
                        if (tracks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(18.dp))
                            BlexsyButton(
                                text = stringResource(if (isPlaylistPlaying) R.string.player_pause else R.string.player_play),
                                onClick = {
                                    if (isPlaylistPlaying) {
                                        player.pause()
                                    } else {
                                        player.play(tracks.first(), tracks, title)
                                    }
                                },
                                leadingIcon = if (isPlaylistPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                variant = ButtonVariant.Primary,
                                size = ButtonSize.Md,
                                modifier = Modifier.widthIn(min = 160.dp, max = 220.dp)
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            itemsIndexed(tracks) { index, track ->
                TrackTile(
                    track = track,
                    index = index,
                    isPlayingThis = activeTrack?.id == track.id,
                    onClick = { player.play(track, tracks, title) },
                    onMore = { onTrackOptions(track, tracks) }
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = BlexsyColors.Background.copy(alpha = topBarAlpha),
            shadowElevation = if (topBarAlpha > 0.8f) 4.dp else 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
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
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = title,
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
                if (onDeletePlaylist != null) {
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.playlist_delete_content_desc),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
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

    if (showDeleteConfirm && onDeletePlaylist != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.playlist_delete_dialog_title), style = BlexsyTypography.SectionTitle) },
            text = { Text(stringResource(R.string.playlist_delete_dialog_desc, title), style = BlexsyTypography.BodyMuted) },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeletePlaylist()
                        onBack()
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(R.string.playlist_delete_dialog_confirm), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.playlist_delete_dialog_dismiss), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp)
        )
    }
}
