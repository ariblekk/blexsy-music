package com.blek.blexsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.R
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.SpotifyGenreItem
import com.blek.blexsy.data.SpotifyGenrePage
import com.blek.blexsy.data.SpotifyGenreSection
import com.blek.blexsy.model.toHighResArtwork
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlinx.coroutines.launch

/**
 * Browse category ("genre") page built from `/v1/genre/{id}`: a playlist-style hero followed by
 * the server's curated sections (playlists, albums, sub-categories), each a horizontal card row.
 */
@Composable
fun GenreDetailScreen(
    genreId: String,
    genreTitle: String,
    genreImage: String? = null,
    api: BlexsyApiClient,
    player: BlexsyAudioPlayer,
    onBack: () -> Unit,
    onOpenPlaylist: (id: String, title: String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenGenre: (id: String, title: String, imageUrl: String?) -> Unit,
    onExpandPlayer: () -> Unit = {},
    bottomNav: (@Composable () -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<SpotifyGenrePage?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var playAttempt by remember { mutableStateOf(0) }
    var isStartingPlay by remember { mutableStateOf(false) }
    val activeTrack by player.currentTrack.collectAsState()
    val listState = rememberLazyListState()
    val defaultErrorMessage = stringResource(R.string.genre_error_failed)

    val sections = remember(page) {
        page?.sections.orEmpty().filter { it.items.isNotEmpty() }
    }
    val heroImage = genreImage
        ?: sections.firstNotNullOfOrNull { section -> section.items.firstNotNullOfOrNull { it.imageUrl } }
    val playlistCount = remember(sections) { sections.sumOf { section -> section.playlistItems().size } }
    val firstPlaylist = remember(sections) {
        sections.firstNotNullOfOrNull { section ->
            section.playlistItems().firstNotNullOfOrNull { item ->
                parseHref(item.href)?.let { (_, id) -> id to item }
            }
        }
    }

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

    LaunchedEffect(genreId, playAttempt) {
        isLoading = true
        error = null
        try {
            page = api.genrePage(genreId)
        } catch (e: Exception) {
            error = e.message ?: defaultErrorMessage
        } finally {
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BlexsyColors.Background)
    ) {
        when {
            isLoading -> DetailHeroSkeleton(rows = 5)
            error != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = BlexsyTypography.Body,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    BlexsyButton(
                        text = stringResource(R.string.search_failed_retry),
                        onClick = { playAttempt++ },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Sm
                    )
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentBottomPadding)
            ) {
                item {
                    GenreHero(
                        title = page?.title?.takeIf { it.isNotBlank() } ?: genreTitle,
                        subtitle = page?.subtitle,
                        imageUrl = heroImage,
                        count = playlistCount,
                        showPlay = firstPlaylist != null,
                        isStartingPlay = isStartingPlay,
                        scrollOffset = { listState.firstVisibleItemScrollOffset },
                        onPlay = {
                            val item = firstPlaylist
                            if (item != null && !isStartingPlay) {
                                isStartingPlay = true
                                scope.launch {
                                    try {
                                        val tracks = api.playlist(item.first)
                                        if (tracks.isNotEmpty()) {
                                            player.play(tracks.first(), tracks, item.second.title)
                                        }
                                    } catch (_: Exception) {
                                    } finally {
                                        isStartingPlay = false
                                    }
                                }
                            }
                        }
                    )
                }

                sections.forEachIndexed { index, section ->
                    item(key = "header:$index") {
                        Text(
                            text = section.title,
                            style = BlexsyTypography.SectionTitle.copy(fontSize = 19.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp)
                        )
                    }
                    item(key = "row:$index") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(items = section.items) { item ->
                                GenreItemCard(
                                    item = item,
                                    onClick = {
                                        val (kind, id) = parseHref(item.href) ?: return@GenreItemCard
                                        when (kind) {
                                            "playlist" -> onOpenPlaylist(id, item.title)
                                            "album" -> onOpenAlbum(id)
                                            "genre" -> onOpenGenre(id, item.title, item.imageUrl)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }

        GenreTopBar(
            title = genreTitle,
            alpha = if (isLoading) 0f else topBarAlpha,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter)
        )

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

@Composable
private fun GenreHero(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    count: Int,
    showPlay: Boolean,
    isStartingPlay: Boolean,
    scrollOffset: () -> Int,
    onPlay: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .clipToBounds()
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl.toHighResArtwork(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = scrollOffset() * 0.45f
                    }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BlexsyColors.SurfaceContainerHigh)
            )
        }

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.45f to Color.Transparent,
                        0.70f to BlexsyColors.Background.copy(alpha = 0.50f),
                        0.88f to BlexsyColors.Background.copy(alpha = 0.90f),
                        1.00f to BlexsyColors.Background
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .graphicsLayer {
                    alpha = (1f - (scrollOffset() / 400f)).coerceIn(0f, 1f)
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
            Text(
                text = subtitle?.let(::stripHtml)?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.genre_playlists_count, count),
                style = BlexsyTypography.Subtitle.copy(
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.85f)
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (showPlay) {
                Spacer(modifier = Modifier.height(18.dp))
                BlexsyButton(
                    text = stringResource(R.string.player_play),
                    onClick = onPlay,
                    isLoading = isStartingPlay,
                    leadingIcon = IcPlay,
                    variant = ButtonVariant.Primary,
                    size = ButtonSize.Md,
                    modifier = Modifier.widthIn(min = 160.dp, max = 220.dp)
                )
            }
        }
    }
}

@Composable
private fun GenreItemCard(
    item: SpotifyGenreItem,
    onClick: () -> Unit
) {
    val subtitle = item.artists.joinToString(", ").ifBlank {
        item.description?.let(::stripHtml).orEmpty()
    }

    Column(
        modifier = Modifier
            .width(148.dp)
            .clickable(enabled = parseHref(item.href) != null, onClick = onClick)
    ) {
        CoverImage(
            url = item.imageUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            size = 0,
            shapeRadius = 6
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            style = BlexsyTypography.Title.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GenreTopBar(
    title: String,
    alpha: Float,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = BlexsyColors.Background.copy(alpha = alpha),
        shadowElevation = if (alpha > 0.8f) 4.dp else 0.dp
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
                    .graphicsLayer { this.alpha = alpha }
            )
        }
    }
}

private fun SpotifyGenreSection.playlistItems() = items.filter { parseHref(it.href)?.first == "playlist" }

/** `/playlist/{id}`, `/album/{id}`, `/genre/{id}` → (kind, id). */
private fun parseHref(href: String?): Pair<String, String>? {
    val parts = href?.trim()?.trimStart('/')?.split('/') ?: return null
    if (parts.size < 2) return null
    return parts[0] to parts[1].substringBefore('?')
}