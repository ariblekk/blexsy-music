package com.blek.blexsy.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.res.stringResource
import com.blek.blexsy.R
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.model.LyricsResult
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.TrackCredits
import com.blek.blexsy.model.isCatalogArtistId
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Spotify-style full player:
 * - cover / lyrics / queue switcher
 * - large white play button, shuffle-free control row
 * - thin scrubber with a small round thumb
 *
 * This composable only owns the sheet shell and the shared state; the panels live in
 * [PlayerCoverView], [PlayerLyricsView] and [PlayerQueueView], the transport row in
 * [PlayerControls] and the bottom sheets in their own files.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun FullPlayerModal(
    player: BlexsyAudioPlayer,
    api: BlexsyApiClient? = null,
    library: LibraryRepository? = null,
    onDismiss: () -> Unit,
    onOpenArtist: ((String) -> Unit)? = null,
    onOpenAlbum: ((String) -> Unit)? = null,
    onOpenEqualizer: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val trackState by player.currentTrack.collectAsState()
    val track = trackState ?: return
    val dominantColor = rememberDominantColor(track.image)
    val isPlaying by player.isPlaying.collectAsState()
    val isBuffering by player.isBuffering.collectAsState()
    val pos by player.positionMs.collectAsState()
    val dur by player.durationMs.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()
    val queue by player.queue.collectAsState()
    val queueSource by player.queueSource.collectAsState()
    val radioSeedTrack = if (player is com.blek.blexsy.AndroidBlexsyAudioPlayer) player.currentRadioSeed else null
    val isRadioLoading by (if (player is com.blek.blexsy.AndroidBlexsyAudioPlayer) player.isRadioLoading.collectAsState() else remember { mutableStateOf(false) }).let { it }
    val radioError by (if (player is com.blek.blexsy.AndroidBlexsyAudioPlayer) player.radioError.collectAsState() else remember { mutableStateOf<String?>(null) }).let { it }

    // Local-first: the save/checkmark badge reflects whether the track is in Liked Songs or any user playlist.
    val favorites by (library?.favorites ?: emptyFlow()).collectAsState(emptyList())
    val playlists by (library?.playlists ?: emptyFlow()).collectAsState(emptyList())
    val isLikedTrack = favorites.any { it.id == track.id }
    val isFavorite = isLikedTrack || playlists.any { pl -> pl.tracks.any { it.id == track.id } }

    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var isScrubberDragging by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }

    val showControlsWithTimeout = {
        areControlsVisible = true
        lastInteractionTime = android.os.SystemClock.elapsedRealtime()
    }

    LaunchedEffect(track.id) {
        isScrubberDragging = false
    }

    LaunchedEffect(showLyrics, showQueue, track.id) {
        if (!showLyrics && !showQueue) {
            areControlsVisible = true
        } else if (showLyrics) {
            showControlsWithTimeout()
        }
    }

    // Auto-hide playback controls in lyrics mode after 4s of idle inactivity
    LaunchedEffect(showLyrics, areControlsVisible, lastInteractionTime, isScrubberDragging) {
        if (showLyrics && areControlsVisible && !isScrubberDragging) {
            delay(4000L)
            areControlsVisible = false
        }
    }

    val contentScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    lastInteractionTime = android.os.SystemClock.elapsedRealtime()
                    if (available.y < -8f) {
                        areControlsVisible = false
                    } else if (available.y > 8f) {
                        showControlsWithTimeout()
                    }
                }
                return Offset.Zero
            }
        }
    }

    val currentView = when {
        showLyrics -> FullPlayerView.LYRICS
        showQueue -> FullPlayerView.QUEUE
        else -> FullPlayerView.COVER
    }

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var lyricsResult by remember(track.id) { mutableStateOf<LyricsResult?>(null) }
    var isLoadingLyrics by remember(track.id) { mutableStateOf(api != null) }
    var showCreditsSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showSelectArtistSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    // Every artist gets an entry; entries without a catalog id are resolved by name on tap.
    val artists = track.artistRefs
    var creditsData by remember(track.id) { mutableStateOf<TrackCredits?>(null) }
    var isLoadingCredits by remember(track.id) { mutableStateOf(false) }

    // Prefetch credits in background as soon as the more-options menu opens
    LaunchedEffect(showMoreSheet, track.id) {
        if (showMoreSheet && creditsData == null) {
            runCatching {
                creditsData = api?.credits(track)
            }
        }
    }

    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE) }

    var isKeepAwake by remember {
        mutableStateOf(prefs.getBoolean("pref_keep_screen_awake", false))
    }
    var showTranslation by remember {
        mutableStateOf(prefs.getBoolean("pref_translate_lyrics", false))
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "pref_keep_screen_awake") {
                isKeepAwake = prefs.getBoolean("pref_keep_screen_awake", false)
            } else if (key == "pref_translate_lyrics") {
                showTranslation = prefs.getBoolean("pref_translate_lyrics", false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    var translatedLyrics by remember(track.id) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var isOriginalIndonesian by remember(track.id) { mutableStateOf(false) }
    var isTranslating by remember { mutableStateOf(false) }

    val setKeepAwake: (Boolean) -> Unit = { enabled ->
        isKeepAwake = enabled
        prefs.edit().putBoolean("pref_keep_screen_awake", enabled).apply()
    }

    val setTranslation: (Boolean) -> Unit = { enabled ->
        showTranslation = enabled
        prefs.edit().putBoolean("pref_translate_lyrics", enabled).apply()
        if (enabled && translatedLyrics.isEmpty() && !isOriginalIndonesian) {
            val lines = lyricsResult?.parsedLines.orEmpty()
            if (lines.isNotEmpty()) {
                isTranslating = true
                scope.launch {
                    try {
                        val result = com.blek.blexsy.model.translateLyrics(lines, targetLang = "id")
                        translatedLyrics = result.lines
                        isOriginalIndonesian = result.isOriginalTarget
                    } catch (_: Exception) {
                    } finally {
                        isTranslating = false
                    }
                }
            }
        }
    }

    LaunchedEffect(lyricsResult, showTranslation) {
        if (showTranslation && translatedLyrics.isEmpty() && !isOriginalIndonesian) {
            val lines = lyricsResult?.parsedLines.orEmpty()
            if (lines.isNotEmpty()) {
                isTranslating = true
                try {
                    val result = com.blek.blexsy.model.translateLyrics(lines, targetLang = "id")
                    translatedLyrics = result.lines
                    isOriginalIndonesian = result.isOriginalTarget
                } catch (_: Exception) {
                } finally {
                    isTranslating = false
                }
            }
        }
    }

    LaunchedEffect(track.id) {
        if (api != null) {
            isLoadingLyrics = true
            try {
                lyricsResult = api.lyrics(track)
            } catch (_: Exception) {
                lyricsResult = null
            } finally {
                isLoadingLyrics = false
            }
        } else {
            isLoadingLyrics = false
        }
    }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val dismissThresholdPx = with(density) { 140.dp.toPx() }

    val dismissOffsetY = remember { Animatable(0f) }
    val dismissAlpha = remember { Animatable(1f) }
    var isDismissing by remember { mutableStateOf(false) }

    val hideSheet: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            scope.launch {
                launch {
                    dismissOffsetY.animateTo(
                        targetValue = screenHeightPx,
                        animationSpec = tween(durationMillis = 360, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                    )
                }
                launch {
                    dismissAlpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing)
                    )
                }
            }.invokeOnCompletion {
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }

    val dismissConnection = remember(sheetState, dismissThresholdPx, showLyrics, showQueue) {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                // Di mode lirik atau antrean, biarkan LazyColumn mengonsumsi scroll sepenuhnya tanpa diintersepsi
                if (showLyrics || showQueue) return Velocity.Zero

                val currentOffset = runCatching { sheetState.requireOffset() }.getOrDefault(0f)
                if (currentOffset > dismissThresholdPx && available.y > 1000f) {
                    hideSheet()
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val currentOffset = runCatching { sheetState.requireOffset() }.getOrDefault(0f)
                val threshold = if (showLyrics || showQueue) dismissThresholdPx * 1.8f else dismissThresholdPx
                val minVelocity = if (showLyrics || showQueue) 3000f else 1200f
                if (currentOffset > threshold && available.y > minVelocity) {
                    hideSheet()
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val handleBack: () -> Unit = {
        if (!isDismissing) {
            if (showLyrics) {
                showLyrics = false
            } else if (showQueue) {
                showQueue = false
            } else {
                hideSheet()
            }
        }
    }

    // Shared by every panel: close the player, then open the artist page. A display name is fine
    // too — the artist page resolves it itself while its skeleton shows.
    val navigateToArtist: (String) -> Unit = { rawArtistsId ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            onOpenArtist?.invoke(rawArtistsId.trim())
        }
    }
    val artistClick: (() -> Unit)? = if (onOpenArtist != null && artists.isNotEmpty()) {
        {
            // One artist: open it. Several: let the user pick, in credited order.
            if (artists.size > 1) {
                showSelectArtistSheet = true
            } else {
                navigateToArtist(artists.first().first)
            }
        }
    } else null
    val openMore: () -> Unit = { showMoreSheet = true }

    ModalBottomSheet(
        onDismissRequest = handleBack,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        sheetMaxWidth = androidx.compose.ui.unit.Dp.Unspecified,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.72f * dismissAlpha.value),
        shape = RectangleShape,
        dragHandle = null,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false)
    ) {
        PredictiveBackHandler(enabled = true) { progress ->
            try {
                progress.collect {}
                handleBack()
            } catch (_: CancellationException) {}
        }

        val view = LocalView.current
        SideEffect {
            var parent = view.parent
            var dialogWindow: android.view.Window? = null
            while (parent != null) {
                if (parent is androidx.compose.ui.window.DialogWindowProvider) {
                    dialogWindow = parent.window
                    break
                }
                parent = parent.parent
            }
            @Suppress("DEPRECATION")
            (dialogWindow ?: (view.context as? android.app.Activity)?.window)?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }

        DisposableEffect(isKeepAwake) {
            view.keepScreenOn = isKeepAwake
            onDispose {
                view.keepScreenOn = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dismissOffsetY.value
                    alpha = dismissAlpha.value
                }
                .nestedScroll(dismissConnection)
        ) {
            // 1. Backdrop: the track's own artwork, blurred and darkened. Nothing else is fetched —
            //    no canvas video resolve, no network round-trip while the player opens. Alpha stays
            //    low so it reads as texture behind the title instead of a picture.
            //    The flat fill underneath is not decoration: the sheet's containerColor is
            //    Color.Transparent, so without it the home screen would show through.
            // ponytail: Modifier.blur needs API 31+; older devices just get the darkened artwork.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BlexsyColors.Background)
            )
            val backdropUrl = track.highResImage ?: track.image
            if (!backdropUrl.isNullOrBlank()) {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Overscale so the blurred edges never show as a vignette.
                            scaleX = 1.15f
                            scaleY = 1.15f
                            alpha = 0.5f
                        }
                        .blur(radius = 48.dp)
                )
            }

            // 2. Grain: same noise as the navbar / mini player, so the blurred artwork never
            //    reads as a flat gradient. One layer only, otherwise it looks dirty.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(brush = FrostGrainBrush, alpha = FrostGrainAlpha)
                    }
            )

            val layout = rememberBlexsyLayoutSpec()

            if (layout.useTwoPanePlayer) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 32.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Pane: Cover Artwork + Track Info + Transport Controls
                    Box(
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 440.dp)
                                .fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                                    AnimatedContent(
                                        targetState = FullPlayerView.COVER,
                                        modifier = Modifier.fillMaxSize(),
                                        label = "player_left_cover"
                                    ) { _ ->
                                        PlayerCoverView(
                                            track = track,
                                            onCoverClick = { showQueue = !showQueue },
                                            onMoreClick = openMore,
                                            onArtistClick = artistClick,
                                            sharedScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            isFavorite = isFavorite,
                                            onToggleFavorite = if (library != null) ({
                                                if (!isFavorite) {
                                                    library.toggleFavorite(track)
                                                } else {
                                                    showAddToPlaylistSheet = true
                                                }
                                            }) else null
                                        )
                                    }
                                }
                            }

                            PlayerControls(
                                player = player,
                                positionMs = pos,
                                durationMs = dur,
                                isPlaying = isPlaying,
                                isBuffering = isBuffering,
                                repeatMode = repeatMode,
                                isOverlayMode = false,
                                controlsVisible = true,
                                isScrubbing = isScrubberDragging,
                                onScrubbingChange = { isScrubberDragging = it },
                                onInteraction = { lastInteractionTime = android.os.SystemClock.elapsedRealtime() },
                                onShowControls = showControlsWithTimeout,
                                onOpenSleepTimer = { showSleepTimerDialog = true },
                                showLyrics = !showQueue,
                                showQueue = showQueue,
                                onShowLyricsChange = { if (it) showQueue = false },
                                onShowQueueChange = { showQueue = it }
                            )
                        }
                    }

                    // Right Pane: Live Lyrics or Queue Panel
                    Box(
                        modifier = Modifier
                            .weight(0.54f)
                            .fillMaxHeight()
                    ) {
                        val rightPaneView = if (showQueue) FullPlayerView.QUEUE else FullPlayerView.LYRICS
                        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                            AnimatedContent(
                                targetState = rightPaneView,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(280)) togetherWith fadeOut(animationSpec = tween(280))
                                },
                                label = "player_right_pane"
                            ) { panel ->
                                when (panel) {
                                    FullPlayerView.QUEUE -> PlayerQueueView(
                                        player = player,
                                        track = track,
                                        queue = queue,
                                        queueSource = queueSource,
                                        radioSeedTrack = radioSeedTrack,
                                        radioError = radioError,
                                        isRadioLoading = isRadioLoading,
                                        dominantColor = dominantColor,
                                        contentScrollConnection = contentScrollConnection,
                                        onShowControls = { areControlsVisible = true },
                                        onCoverClick = { showQueue = false },
                                        onMoreClick = openMore,
                                        onArtistClick = artistClick,
                                        sharedScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = this,
                                        showFloatingTrackCard = false
                                    )

                                    else -> PlayerLyricsView(
                                        track = track,
                                        player = player,
                                        lyrics = lyricsResult,
                                        isLoading = isLoadingLyrics,
                                        positionMs = pos,
                                        isPlaying = isPlaying,
                                        showTranslation = showTranslation,
                                        translatedLyrics = translatedLyrics,
                                        isTranslating = isTranslating,
                                        isKeepAwake = isKeepAwake,
                                        controlsVisible = true,
                                        onShowControls = showControlsWithTimeout,
                                        onHideControls = {},
                                        onToggleKeepAwake = { setKeepAwake(!isKeepAwake) },
                                        onToggleTranslation = { setTranslation(!showTranslation) },
                                        onCoverClick = {},
                                        onMoreClick = openMore,
                                        onArtistClick = artistClick,
                                        dominantColor = dominantColor,
                                        contentScrollConnection = contentScrollConnection,
                                        sharedScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = this,
                                        showFloatingTrackCard = false
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 540.dp)
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            SharedTransitionLayout(
                                modifier = Modifier
                                    .fillMaxSize()
                            ) {
                                AnimatedContent(
                                    targetState = currentView,
                                    modifier = Modifier.fillMaxSize(),
                                    transitionSpec = {
                                        fadeIn(animationSpec = tween(320)) togetherWith fadeOut(animationSpec = tween(320))
                                    },
                                    label = "player_content_morph"
                                ) { panel ->
                                    when (panel) {
                                        FullPlayerView.LYRICS -> PlayerLyricsView(
                                            track = track,
                                            player = player,
                                            lyrics = lyricsResult,
                                            isLoading = isLoadingLyrics,
                                            positionMs = pos,
                                            isPlaying = isPlaying,
                                            showTranslation = showTranslation,
                                            translatedLyrics = translatedLyrics,
                                            isTranslating = isTranslating,
                                            isKeepAwake = isKeepAwake,
                                            controlsVisible = areControlsVisible,
                                            onShowControls = showControlsWithTimeout,
                                            onHideControls = { areControlsVisible = false },
                                            onToggleKeepAwake = { setKeepAwake(!isKeepAwake) },
                                            onToggleTranslation = { setTranslation(!showTranslation) },
                                            onCoverClick = { showLyrics = false },
                                            onMoreClick = openMore,
                                            onArtistClick = artistClick,
                                            dominantColor = dominantColor,
                                            contentScrollConnection = contentScrollConnection,
                                            sharedScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this
                                        )

                                        FullPlayerView.QUEUE -> PlayerQueueView(
                                            player = player,
                                            track = track,
                                            queue = queue,
                                            queueSource = queueSource,
                                            radioSeedTrack = radioSeedTrack,
                                            radioError = radioError,
                                            isRadioLoading = isRadioLoading,
                                            dominantColor = dominantColor,
                                            contentScrollConnection = contentScrollConnection,
                                            onShowControls = { areControlsVisible = true },
                                            onCoverClick = { showQueue = false },
                                            onMoreClick = openMore,
                                            onArtistClick = artistClick,
                                            sharedScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this
                                        )

                                        FullPlayerView.COVER -> PlayerCoverView(
                                            track = track,
                                            onCoverClick = { if (lyricsResult != null) showLyrics = true },
                                            onMoreClick = openMore,
                                            onArtistClick = artistClick,
                                            sharedScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            isFavorite = isFavorite,
                                            onToggleFavorite = if (library != null) ({
                                                if (!isFavorite) {
                                                    library.toggleFavorite(track)
                                                } else {
                                                    showAddToPlaylistSheet = true
                                                }
                                            }) else null
                                        )
                                    }
                                }
                            }
                        }

                        PlayerControls(
                            player = player,
                            positionMs = pos,
                            durationMs = dur,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            repeatMode = repeatMode,
                            isOverlayMode = showLyrics || showQueue,
                            controlsVisible = areControlsVisible,
                            isScrubbing = isScrubberDragging,
                            onScrubbingChange = { isScrubberDragging = it },
                            onInteraction = { lastInteractionTime = android.os.SystemClock.elapsedRealtime() },
                            onShowControls = showControlsWithTimeout,
                            onOpenSleepTimer = { showSleepTimerDialog = true },
                            showLyrics = showLyrics,
                            showQueue = showQueue,
                            onShowLyricsChange = { showLyrics = it },
                            onShowQueueChange = { showQueue = it }
                        )
                    }
                }
            }
        }

        if (showMoreSheet) {
            val albumId = track.albumId?.takeIf { it.isNotBlank() }
            PlayerMoreSheet(
                track = track,
                isFavorite = isFavorite,
                canSaveToLibrary = library != null,
                artistId = artists.firstOrNull()?.first,
                albumId = albumId,
                sleepTimerActive = SleepTimer.isActive,
                onDismiss = { showMoreSheet = false },
                onToggleFavorite = { library?.toggleFavorite(track) },
                onPlayNext = {
                    showMoreSheet = false
                    player.addToQueue(track, isNext = true)
                },
                onAddToQueue = {
                    showMoreSheet = false
                    player.addToQueue(track, isNext = false)
                },
                onStartRadio = {
                    showMoreSheet = false
                    player.startRadio(track)
                },
                onAddToPlaylist = if (library != null) {
                    {
                        showMoreSheet = false
                        showAddToPlaylistSheet = true
                    }
                } else null,
                // Multiple credited artists always go through the picker, even when none of them
                // has a catalog id yet: each name is resolved when the user picks it.
                onViewArtist = if (onOpenArtist != null && artists.isNotEmpty()) {
                    {
                        showMoreSheet = false
                        if (artists.size > 1) {
                            showSelectArtistSheet = true
                        } else {
                            navigateToArtist(artists.first().first)
                        }
                    }
                } else null,
                onViewAlbum = if (onOpenAlbum != null && albumId != null) {
                    {
                        showMoreSheet = false
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onDismiss()
                            onOpenAlbum(albumId)
                        }
                    }
                } else null,
                onShowCredits = {
                    showMoreSheet = false
                    showCreditsSheet = true
                    if (creditsData == null) {
                        isLoadingCredits = true
                        scope.launch {
                            runCatching { creditsData = api?.credits(track) }
                            isLoadingCredits = false
                        }
                    }
                },
                onShowSleepTimer = {
                    showMoreSheet = false
                    showSleepTimerDialog = true
                },
                onShowEqualizer = if (onOpenEqualizer != null) {
                    {
                        showMoreSheet = false
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onDismiss()
                            onOpenEqualizer()
                        }
                    }
                } else null
            )
        }

        if (showSelectArtistSheet) {
            SelectArtistSheet(
                artists = artists,
                onDismiss = { showSelectArtistSheet = false },
                onSelectArtist = { selectedArtistId ->
                    showSelectArtistSheet = false
                    // Callback carries the id; unresolved entries carry their name in that slot.
                    navigateToArtist(selectedArtistId)
                }
            )
        }

        if (showAddToPlaylistSheet) {
            AddToPlaylistSheet(
                track = track,
                playlists = playlists,
                isLiked = isLikedTrack,
                likedCount = favorites.size,
                onToggleLiked = if (library != null) ({ library.toggleFavorite(track) }) else null,
                onDismiss = { showAddToPlaylistSheet = false },
                onSelectPlaylist = { selectedPlaylist ->
                    val alreadyIn = selectedPlaylist.tracks.any { it.id == track.id }
                    if (alreadyIn) {
                        library?.removeTrackFromPlaylist(selectedPlaylist.id, track.id)
                    } else {
                        library?.addTrackToPlaylist(selectedPlaylist, track)
                    }
                },
                onCreatePlaylist = {
                    showAddToPlaylistSheet = false
                    newPlaylistName = ""
                    showCreatePlaylistDialog = true
                }
            )
        }

        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                name = newPlaylistName,
                onNameChange = { newPlaylistName = it },
                onDismiss = {
                    showCreatePlaylistDialog = false
                    newPlaylistName = ""
                },
                onCreate = { name ->
                    library?.createPlaylist(name, listOf(track))
                    showCreatePlaylistDialog = false
                    newPlaylistName = ""
                }
            )
        }

        if (showCreditsSheet) {
            PlayerCreditsSheet(
                track = track,
                credits = creditsData,
                isLoading = isLoadingCredits,
                onDismiss = { showCreditsSheet = false }
            )
        }

        if (showSleepTimerDialog) {
            SleepTimerDialog(
                remainingMs = dur - pos,
                track = track,
                onDismiss = { showSleepTimerDialog = false },
                onPause = { player.pause() }
            )
        }
    }
}
