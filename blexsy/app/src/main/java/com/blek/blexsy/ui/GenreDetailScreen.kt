package com.blek.blexsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
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
import com.blek.blexsy.model.GenrePlaylist
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.toHighResArtwork
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlinx.coroutines.launch

@Composable
fun GenrePlaylistsScreen(
    genreId: String,
    genreName: String,
    genreImage: String? = null,
    api: BlexsyApiClient,
    player: BlexsyAudioPlayer,
    onBack: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onExpandPlayer: () -> Unit = {},
    bottomNav: (@Composable () -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<GenrePlaylist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val activeTrack by player.currentTrack.collectAsState()
    val listState = rememberLazyListState()
    val heroImage = genreImage ?: playlists.firstOrNull()?.image
    val dominantColor = rememberDominantColor(heroImage)
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

    val defaultErrorMessage = stringResource(R.string.genre_error_failed)

    LaunchedEffect(genreId) {
        isLoading = true
        error = null
        try {
            playlists = api.genrePlaylists(genreId)
        } catch (e: Exception) {
            error = e.message ?: defaultErrorMessage
        } finally {
            isLoading = false
        }
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
            GenreDetailSkeleton(modifier = Modifier.fillMaxSize())
        } else if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = BlexsyTypography.Body,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentBottomPadding)
            ) {
                // Full Width Genre Hero extending down to the play button
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .clipToBounds()
                    ) {
                        if (!heroImage.isNullOrBlank()) {
                            AsyncImage(
                                model = heroImage.toHighResArtwork(),
                                contentDescription = genreName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationY = listState.firstVisibleItemScrollOffset * 0.45f
                                    }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(dominantColor)
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

                        // Centered category info and elongated play button
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
                                text = stringResource(R.string.genre_kicker),
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
                                text = genreName,
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
                            Text(
                                text = stringResource(R.string.genre_playlists_count, playlists.size),
                                style = BlexsyTypography.Caption.copy(
                                    color = Color.White.copy(alpha = 0.8f)
                                ),
                                textAlign = TextAlign.Center
                            )
                            if (playlists.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(18.dp))
                                var isStartingPlay by remember { mutableStateOf(false) }
                                BlexsyButton(
                                    text = stringResource(if (isStartingPlay) R.string.action_loading else R.string.player_play),
                                    onClick = {
                                        if (!isStartingPlay) {
                                            isStartingPlay = true
                                            scope.launch {
                                                try {
                                                    val first = playlists.first()
                                                    val tracks = api.playlist(first.id)
                                                    if (tracks.isNotEmpty()) {
                                                        player.play(tracks.first(), tracks, first.name)
                                                    }
                                                } catch (_: Exception) {
                                                } finally {
                                                    isStartingPlay = false
                                                }
                                            }
                                        }
                                    },
                                    leadingIcon = Icons.Rounded.PlayArrow,
                                    variant = ButtonVariant.Primary,
                                    size = ButtonSize.Md,
                                    modifier = Modifier.widthIn(min = 160.dp, max = 220.dp)
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }

                if (playlists.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.genre_empty),
                                style = BlexsyTypography.BodyMuted
                            )
                        }
                    }
                }

                items(playlists) { item ->
                    var isOpening by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isOpening) {
                                isOpening = true
                                scope.launch {
                                    try {
                                        val tracks = api.playlist(item.id)
                                        onOpenPlaylist(
                                            Playlist(
                                                id = item.id,
                                                name = item.name,
                                                tracks = tracks
                                            )
                                        )
                                    } catch (_: Exception) {
                                    } finally {
                                        isOpening = false
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverImage(url = item.image, size = 56, shapeRadius = 6)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = BlexsyTypography.Title.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stripHtml(item.description),
                                    style = BlexsyTypography.Subtitle.copy(fontSize = 13.sp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top App Bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = BlexsyColors.Background.copy(alpha = if (isLoading) 0f else topBarAlpha),
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
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = genreName,
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

        // Bottom Bar (MiniPlayer + BottomNav)
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
