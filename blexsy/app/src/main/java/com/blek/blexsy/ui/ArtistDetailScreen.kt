package com.blek.blexsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalUriHandler
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.SpotifyAuthManager
import com.blek.blexsy.model.AlbumSummary
import com.blek.blexsy.model.ArtistDetail
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.isCatalogArtistId
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
    favorites: List<Track> = emptyList(),
    onToggleFavorite: ((Track) -> Unit)? = null,
    onTrackOptions: (Track, List<Track>) -> Unit,
    onExpandPlayer: () -> Unit = {},
    bottomNav: (@Composable () -> Unit)? = null
) {
    val followedArtists by SpotifyAuthManager.followedArtists.collectAsState()
    var artist by remember(artistId) { mutableStateOf<ArtistDetail?>(null) }
    var motionVideoUrl by remember(artistId) { mutableStateOf<String?>(null) }
    var isLoading by remember(artistId) { mutableStateOf(true) }
    var error by remember(artistId) { mutableStateOf<String?>(null) }
    var showAllTracks by remember(artistId) { mutableStateOf(false) }
    var showAboutDialog by remember(artistId) { mutableStateOf(false) }
    var catalogTabIndex by remember(artistId) { mutableIntStateOf(0) }
    val activeTrack by player.currentTrack.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val listState = remember(artistId) { androidx.compose.foundation.lazy.LazyListState() }
    val dominantColor = BlexsyColors.Background
    val topBarAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else ((listState.firstVisibleItemScrollOffset - 180f) / 220f).coerceIn(0f, 1f)
        }
    }

    val defaultErrorMessage = stringResource(R.string.artist_error_failed)

    LaunchedEffect(artistId) {
        isLoading = true
        error = null
        try {
            // Opened immediately from any artist reference: a catalog id goes straight through,
            // a display name is resolved here while the skeleton is already on screen.
            val resolvedId = if (artistId.isCatalogArtistId()) artistId else api.resolveArtistId(artistId)
            if (resolvedId == null) {
                error = defaultErrorMessage
            } else {
                artist = api.artist(resolvedId)
            }
        } catch (e: Exception) {
            if (artist == null) {
                error = e.message ?: defaultErrorMessage
            }
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(artist?.id) {
        val art = artist ?: return@LaunchedEffect
        SpotifyAuthManager.verifyArtistFollowedLive(
            ArtistSummary(
                id = art.id,
                name = art.name,
                image = art.image,
                subscribers = art.subscribers
            )
        )
    }

    LaunchedEffect(artist?.name) {
        val name = artist?.name ?: return@LaunchedEffect
        motionVideoUrl = com.blek.blexsy.data.ArtistCanvasService.getMotionVideoUrl(name)
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
            val isFollowed = followedArtists.any {
                it.id.equals(art.id, ignoreCase = true) ||
                    it.name.trim().equals(art.name.trim(), ignoreCase = true)
            }
            val toggleFollow: () -> Unit = {
                SpotifyAuthManager.toggleFollowArtist(
                    ArtistSummary(
                        id = art.id,
                        name = art.name,
                        image = art.image,
                        subscribers = art.subscribers
                    )
                )
            }
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
                        // Fallback hierarchy: 1. Canvas Apple (motionVideoUrl) -> 2. Banner -> 3. Avatar
                        val heroStaticBackdrop = (art.banner ?: art.image).toHighResArtwork()
                        AsyncImage(
                            model = heroStaticBackdrop,
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
                        // Artist info and play button (Left-aligned row on tablet, centered column on phone)
                        if (layout.isTablet) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 20.dp)
                                    .graphicsLayer {
                                        alpha = (1f - (listState.firstVisibleItemScrollOffset / 400f)).coerceIn(0f, 1f)
                                    },
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CoverImage(
                                        url = art.image,
                                        size = 88,
                                        shapeRadius = 44,
                                        modifier = Modifier.padding(end = 16.dp)
                                    )
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = art.name,
                                                style = BlexsyTypography.Display.copy(
                                                    fontFamily = HeadingFont,
                                                    fontSize = 32.sp,
                                                    lineHeight = 38.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 0.3.sp,
                                                    color = Color.White
                                                ),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (art.verified) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = IcSealCheck,
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
                                                    fontFamily = MonoFont,
                                                    color = Color.White.copy(alpha = 0.75f),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            )
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    BlexsyButton(
                                        text = if (isFollowed) "Following" else "Follow",
                                        onClick = toggleFollow,
                                        leadingIcon = if (isFollowed) IcCheck else IcPlus,
                                        variant = if (isFollowed) ButtonVariant.Secondary else ButtonVariant.Outline,
                                        size = ButtonSize.Md
                                    )
                                    if (art.topTracks.isNotEmpty()) {
                                        BlexsyButton(
                                            text = stringResource(if (isPlayingArtist) R.string.player_pause else R.string.player_play),
                                            onClick = {
                                                if (isPlayingArtist) {
                                                    player.pause()
                                                } else {
                                                    player.play(art.topTracks.first(), art.topTracks)
                                                }
                                            },
                                            leadingIcon = if (isPlayingArtist) IcPause else IcPlay,
                                            variant = ButtonVariant.Primary,
                                            size = ButtonSize.Md,
                                            modifier = Modifier.widthIn(min = 128.dp, max = 180.dp)
                                        )
                                    }
                                }
                            }
                        } else {
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
                                            fontFamily = HeadingFont,
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
                                            imageVector = IcSealCheck,
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
                                            fontFamily = MonoFont,
                                            color = Color.White.copy(alpha = 0.75f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Spacer(modifier = Modifier.height(18.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    BlexsyButton(
                                        text = if (isFollowed) "Following" else "Follow",
                                        onClick = toggleFollow,
                                        leadingIcon = if (isFollowed) IcCheck else IcPlus,
                                        variant = if (isFollowed) ButtonVariant.Secondary else ButtonVariant.Outline,
                                        size = ButtonSize.Md
                                    )
                                    if (art.topTracks.isNotEmpty()) {
                                        BlexsyButton(
                                            text = stringResource(if (isPlayingArtist) R.string.player_pause else R.string.player_play),
                                            onClick = {
                                                if (isPlayingArtist) {
                                                    player.pause()
                                                } else {
                                                    player.play(art.topTracks.first(), art.topTracks)
                                                }
                                            },
                                            leadingIcon = if (isPlayingArtist) IcPause else IcPlay,
                                            variant = ButtonVariant.Primary,
                                            size = ButtonSize.Md,
                                            modifier = Modifier.widthIn(min = 136.dp, max = 190.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }

                val hasAlbums = art.albums.isNotEmpty()
                val hasSingles = art.singlesAndEps.isNotEmpty()

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

                    if (layout.useTwoPaneDetail && displayedTracks.size >= 4) {
                        val indexedPairs = displayedTracks.mapIndexed { idx, trk -> idx to trk }.chunked(2)
                        items(indexedPairs) { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for ((index, track) in pair) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        TrackTile(
                                            track = track,
                                            index = index,
                                            isPlayingThis = activeTrack?.id == track.id,
                                            isFavorite = favorites.any { it.id == track.id },
                                            onToggleFavorite = onToggleFavorite?.let { cb -> { cb(track) } },
                                            onClick = { player.play(track, art.topTracks) },
                                            onMore = { onTrackOptions(track, art.topTracks) }
                                        )
                                    }
                                }
                                if (pair.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        itemsIndexed(displayedTracks) { index, track ->
                            TrackTile(
                                track = track,
                                index = index,
                                isPlayingThis = activeTrack?.id == track.id,
                                isFavorite = favorites.any { it.id == track.id },
                                onToggleFavorite = onToggleFavorite?.let { cb -> { cb(track) } },
                                onClick = { player.play(track, art.topTracks) },
                                onMore = { onTrackOptions(track, art.topTracks) }
                            )
                        }
                    }
                }

                // Diskografi / Releases (Separated by tabs: All / Albums / Singles & EPs)
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
                                    tabs = listOf(
                                        stringResource(R.string.artist_tab_all),
                                        stringResource(R.string.artist_tab_albums),
                                        stringResource(R.string.artist_tab_singles)
                                    ),
                                    selectedIndex = catalogTabIndex.coerceIn(0, 2),
                                    onSelect = { catalogTabIndex = it },
                                    fillMaxWidth = false
                                )
                            }
                        }
                    }

                    val combinedReleases = (art.albums + art.singlesAndEps)
                        .distinctBy { it.id.ifBlank { "${it.name}:${it.year}:${it.type}" } }
                        .sortedByDescending { it.year?.toIntOrNull() ?: 0 }

                    val currentReleases = when {
                        hasAlbums && hasSingles -> when (catalogTabIndex.coerceIn(0, 2)) {
                            1 -> art.albums
                            2 -> art.singlesAndEps
                            else -> combinedReleases
                        }
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

                // Tentang Artis / Biografi Card (Avatar + Listening + Potongan Bio -> Klik buka Dialog lengkap)
                val hasAboutInfo = !art.biography.isNullOrBlank() ||
                    (art.monthlyListeners ?: 0) > 0 ||
                    (art.followers ?: 0) > 0 ||
                    art.externalLinks.isNotEmpty() ||
                    art.topCities.isNotEmpty() ||
                    art.gallery.isNotEmpty() ||
                    art.pinnedPick != null
                if (hasAboutInfo) {
                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                        SectionHeader(
                            kicker = stringResource(R.string.artist_kicker_backstory),
                            title = stringResource(R.string.artist_title_meet)
                        )
                        ArtistAboutCard(
                            artist = art,
                            onClick = { showAboutDialog = true }
                        )
                    }
                }
            }

            if (showAboutDialog) {
                ArtistAboutDialog(
                    artist = art,
                    isTablet = layout.isTablet,
                    onOpenAlbum = { albumId ->
                        showAboutDialog = false
                        onOpenAlbum(albumId)
                    },
                    onDismiss = { showAboutDialog = false }
                )
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
                        imageVector = IcArrowLeft,
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
                                fontFamily = HeadingFont,
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
                                imageVector = IcSealCheck,
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

private fun formatExactCount(count: Int): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(count)

private fun socialPlatformDisplayName(rawName: String, url: String): String {
    val upper = rawName.trim().uppercase()
    return when {
        upper.contains("INSTAGRAM") || url.contains("instagram.com", true) -> "Instagram"
        upper.contains("TWITTER") || upper == "X" || url.contains("twitter.com", true) || url.contains("x.com", true) -> "X / Twitter"
        upper.contains("FACEBOOK") || url.contains("facebook.com", true) -> "Facebook"
        upper.contains("WIKIPEDIA") || url.contains("wikipedia.org", true) -> "Wikipedia"
        upper.contains("YOUTUBE") || url.contains("youtube.com", true) -> "YouTube"
        upper.contains("TIKTOK") || url.contains("tiktok.com", true) -> "TikTok"
        upper.contains("SOUNDCLOUD") || url.contains("soundcloud.com", true) -> "SoundCloud"
        upper.isNotBlank() && upper != "LINK" -> rawName.lowercase().replaceFirstChar { it.uppercase() }
        else -> "Website Resmi"
    }
}

@Composable
private fun rememberFormattedBio(bio: String): AnnotatedString = remember(bio) {
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

@Composable
private fun ArtistAboutCard(
    artist: ArtistDetail,
    onClick: () -> Unit
) {
    val plainBioSnippet = remember(artist.biography) {
        artist.biography
            ?.takeIf { it.isNotBlank() }
            ?.let { stripHtml(it).replace(Regex("\\s+"), " ").trim() }
    }
    val monthlyListeners = artist.monthlyListeners?.takeIf { it > 0 }
    val followers = artist.followers?.takeIf { it > 0 }
    val listeningHeadline = when {
        monthlyListeners != null -> "${formatExactCount(monthlyListeners)} monthly listeners"
        followers != null -> "${formatExactCount(followers)} followers"
        !artist.subscribers.isNullOrBlank() -> artist.subscribers
        else -> null
    }
    val secondaryStat = when {
        monthlyListeners != null && followers != null -> "${formatExactCount(followers)} followers"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(BlexsyColors.SurfaceContainerHigh)
            .border(1.dp, BlexsyColors.Border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(
                url = artist.image ?: artist.banner,
                size = 64,
                shapeRadius = 32
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = artist.name,
                        style = BlexsyTypography.Title.copy(
                            fontFamily = HeadingFont,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = BlexsyColors.TextPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (artist.verified) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Icon(
                            imageVector = IcSealCheck,
                            contentDescription = stringResource(R.string.a11y_verified),
                            tint = Color(0xFF3D91F4),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (artist.worldRank != null && artist.worldRank > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(BlexsyColors.Primary.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "#${artist.worldRank} Global",
                                style = BlexsyTypography.Caption.copy(
                                    fontFamily = MonoFont,
                                    color = BlexsyColors.Primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
                if (listeningHeadline != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = listeningHeadline,
                        style = BlexsyTypography.Title.copy(
                            fontFamily = MonoFont,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BlexsyColors.Primary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (secondaryStat != null) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = secondaryStat,
                        style = BlexsyTypography.Caption.copy(
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                            color = BlexsyColors.TextSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = IcCaretRight,
                contentDescription = null,
                tint = BlexsyColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = plainBioSnippet
                ?: "Ketuk untuk melihat profil lengkap, statistik pendengar, kota pendengar teratas, dan tautan media sosial resmi ${artist.name}.",
            style = BlexsyTypography.BodyMuted.copy(
                color = BlexsyColors.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        val previewChips = buildList {
            artist.externalLinks.take(3).forEach { link ->
                add(socialPlatformDisplayName(link.name, link.url))
            }
            artist.topCities.firstOrNull()?.let { topCity ->
                add("Top City: ${topCity.city}")
            }
            if (artist.gallery.size > 1) {
                add("${artist.gallery.size} Foto")
            }
        }
        if (previewChips.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                previewChips.take(4).forEach { chip ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(BlexsyColors.SurfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = chip,
                            style = BlexsyTypography.Caption.copy(
                                fontSize = 11.sp,
                                color = BlexsyColors.TextSecondary,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistAboutDialog(
    artist: ArtistDetail,
    isTablet: Boolean,
    onOpenAlbum: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val headerArtwork = (artist.banner ?: artist.gallery.firstOrNull() ?: artist.image).toHighResArtwork()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isTablet) 0.72f else 0.93f)
                .widthIn(max = 640.dp)
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(20.dp),
            color = BlexsyColors.SurfaceContainer,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Header Banner + Avatar + Close button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clipToBounds()
                ) {
                    AsyncImage(
                        model = headerArtwork,
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Black.copy(alpha = 0.35f),
                                    0.5f to Color.Black.copy(alpha = 0.50f),
                                    1.0f to BlexsyColors.SurfaceContainer
                                )
                            )
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                    ) {
                        Icon(
                            imageVector = IcClose,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverImage(
                            url = artist.image ?: artist.banner,
                            size = 68,
                            shapeRadius = 34
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = artist.name,
                                    style = BlexsyTypography.Display.copy(
                                        fontFamily = HeadingFont,
                                        fontSize = 22.sp,
                                        lineHeight = 26.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (artist.verified) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = IcSealCheck,
                                        contentDescription = stringResource(R.string.a11y_verified),
                                        tint = Color(0xFF3D91F4),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (artist.worldRank != null && artist.worldRank > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "#${artist.worldRank} in the World",
                                    style = BlexsyTypography.Caption.copy(
                                        fontFamily = MonoFont,
                                        color = BlexsyColors.Primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                )
                            } else if (artist.id.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "ID: ${artist.id}",
                                    style = BlexsyTypography.Caption.copy(
                                        fontFamily = MonoFont,
                                        color = Color.White.copy(alpha = 0.65f),
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 1. Statistik Pendengar & Katalog
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val monthly = artist.monthlyListeners?.takeIf { it > 0 }
                        val followers = artist.followers?.takeIf { it > 0 }
                        if (monthly != null) {
                            ArtistStatTile(
                                label = "MONTHLY LISTENERS",
                                primaryValue = formatExactCount(monthly),
                                subValue = formatSubscriberNumber(monthly),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (followers != null) {
                            ArtistStatTile(
                                label = "FOLLOWERS",
                                primaryValue = formatExactCount(followers),
                                subValue = formatSubscriberNumber(followers),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (artist.worldRank != null && artist.worldRank > 0) {
                            ArtistStatTile(
                                label = "WORLD RANK",
                                primaryValue = "#${artist.worldRank}",
                                subValue = "Top Global Artist",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        ArtistStatTile(
                            label = "CATALOG",
                            primaryValue = "${artist.albums.size} Album • ${artist.singlesAndEps.size} Single",
                            subValue = "${artist.topTracks.size} Lagu Populer • ${artist.similarArtists.size} Artis Terkait",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // 2. Pilihan Artis / Pinned Pick (jika dikirim oleh BE)
                    artist.pinnedPick?.let { pin ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "PILIHAN ARTIS",
                                style = BlexsyTypography.Eyebrow
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BlexsyColors.SurfaceContainerHigh)
                                    .clickable(enabled = !pin.id.isNullOrBlank() && pin.type.equals("ALBUM", true)) {
                                        pin.id?.let(onOpenAlbum)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverImage(
                                    url = pin.image ?: artist.image,
                                    size = 56,
                                    shapeRadius = 8
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    if (!pin.type.isNullOrBlank()) {
                                        Text(
                                            text = pin.type.uppercase(),
                                            style = BlexsyTypography.Caption.copy(
                                                fontFamily = MonoFont,
                                                color = BlexsyColors.Primary,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                    Text(
                                        text = pin.title,
                                        style = BlexsyTypography.Title.copy(
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sub = pin.comment ?: pin.subtitle
                                    if (!sub.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = sub,
                                            style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Sosial Media & Tautan Resmi (externalLinks)
                    if (artist.externalLinks.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "SOSIAL MEDIA & TAUTAN RESMI",
                                style = BlexsyTypography.Eyebrow
                            )
                            artist.externalLinks.forEach { link ->
                                val platform = socialPlatformDisplayName(link.name, link.url)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BlexsyColors.SurfaceContainerHigh)
                                        .clickable {
                                            runCatching { uriHandler.openUri(link.url) }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = IcGlobe,
                                        contentDescription = platform,
                                        tint = BlexsyColors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = platform,
                                            style = BlexsyTypography.Title.copy(
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                        Text(
                                            text = link.url,
                                            style = BlexsyTypography.Caption.copy(
                                                fontFamily = MonoFont,
                                                fontSize = 11.sp,
                                                color = BlexsyColors.TextSecondary
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = IcCaretRight,
                                        contentDescription = null,
                                        tint = BlexsyColors.TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 4. Kota Pendengar Teratas (stats.topCities)
                    if (artist.topCities.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "KOTA PENDENGAR TERATAS",
                                style = BlexsyTypography.Eyebrow
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BlexsyColors.SurfaceContainerHigh)
                                    .padding(vertical = 6.dp)
                            ) {
                                artist.topCities.forEachIndexed { idx, city ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "#${idx + 1}",
                                            style = BlexsyTypography.Title.copy(
                                                fontFamily = MonoFont,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = BlexsyColors.Primary
                                            ),
                                            modifier = Modifier.width(30.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            val locationSuffix = listOfNotNull(
                                                city.region?.takeIf { it.isNotBlank() },
                                                city.country.takeIf { it.isNotBlank() }
                                            ).joinToString(", ")
                                            Text(
                                                text = if (locationSuffix.isNotEmpty()) "${city.city}, $locationSuffix" else city.city,
                                                style = BlexsyTypography.Title.copy(
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            )
                                        }
                                        if (city.listeners > 0) {
                                            Text(
                                                text = "${formatExactCount(city.listeners)} listeners",
                                                style = BlexsyTypography.Caption.copy(
                                                    fontFamily = MonoFont,
                                                    fontSize = 12.sp,
                                                    color = BlexsyColors.TextSecondary
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. Galeri Foto (visuals.gallery)
                    if (artist.gallery.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "GALERI FOTO (${artist.gallery.size})",
                                style = BlexsyTypography.Eyebrow
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(artist.gallery) { photoUrl ->
                                    AsyncImage(
                                        model = photoUrl.toHighResArtwork(),
                                        contentDescription = artist.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 180.dp, height = 125.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(BlexsyColors.SurfaceContainerHigh)
                                    )
                                }
                            }
                        }
                    }

                    // 6. Biografi Lengkap
                    artist.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "BIOGRAFI",
                                style = BlexsyTypography.Eyebrow
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BlexsyColors.SurfaceContainerHigh)
                                    .padding(16.dp)
                            ) {
                                val annotatedBio = rememberFormattedBio(bio)
                                Text(
                                    text = annotatedBio,
                                    style = BlexsyTypography.BodyMuted,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun ArtistStatTile(
    label: String,
    primaryValue: String,
    subValue: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BlexsyColors.SurfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = BlexsyTypography.Caption.copy(
                fontFamily = MonoFont,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = BlexsyColors.TextSecondary,
                letterSpacing = 0.8.sp
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = primaryValue,
            style = BlexsyTypography.Title.copy(
                fontFamily = MonoFont,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = BlexsyColors.TextPrimary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subValue.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subValue,
                style = BlexsyTypography.Caption.copy(
                    fontSize = 11.sp,
                    color = BlexsyColors.Primary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

