package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.model.AlbumSummary
import com.blek.blexsy.model.Genre
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    api: BlexsyApiClient,
    player: BlexsyAudioPlayer,
    library: LibraryRepository,
    initialUserName: String = "User Blexsy",
    favoriteArtists: Set<String> = emptySet(),
    onSaveToken: ((String?) -> Unit)? = null,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var screenStack by remember { mutableStateOf<List<BlexsyScreen>>(listOf(BlexsyScreen.Main)) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Home, 1: Search, 2: Library, 3: Profile

    val homeListState = rememberLazyListState()
    val homeHeaderAlpha by remember {
        derivedStateOf {
            if (homeListState.firstVisibleItemIndex > 0) 1f
            else ((homeListState.firstVisibleItemScrollOffset - 80f) / 160f).coerceIn(0f, 1f)
        }
    }

    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var favoriteArtistTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var newReleases by remember { mutableStateOf<List<AlbumSummary>>(emptyList()) }
    var genres by remember { mutableStateOf<List<Genre>>(emptyList()) }

    // Likes, playlists, and recent spins come from the local-first repository: changes show up instantly
    // and are synced to the server in the background.
    val favorites by library.favorites.collectAsState()
    val playlists by library.playlists.collectAsState()
    val recentSpins by library.recentTracks.collectAsState()
    val playCounts by library.playCounts.collectAsState()
    val recentTracks = remember(recentSpins) { recentSpins.take(10) }

    // Search lives outside this shell: the tab is display-only and the actual typing
    // happens on a full-screen page layered on top of it.
    val searchState = rememberBlexsySearchState(api)
    var showSearchPage by remember { mutableStateOf(false) }

    var isLoadingHome by remember { mutableStateOf(true) }
    var showFullPlayer by remember { mutableStateOf(false) }

    // One three-dot context sheet shared by every track list (tabs and detail screens).
    var optionsTrack by remember { mutableStateOf<Track?>(null) }
    var optionsQueue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }
    var trackForNewPlaylist by remember { mutableStateOf<Track?>(null) }

    val localAccountLabel = stringResource(R.string.profile_local_account)
    val likedBangersTitle = stringResource(R.string.library_liked_bangers)
    val newReleasesTitle = stringResource(R.string.listen_drops_title)

    // User profile state
    var userName by remember { mutableStateOf(initialUserName) }
    var userEmail by remember { mutableStateOf(if (api.token == null || api.token == "guest") localAccountLabel else "") }
    var userTotalSpins by remember { mutableIntStateOf(0) }

    // Dialogs
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showImportSpotifyDialog by remember { mutableStateOf(false) }
    var spotifyPlaylistInput by remember { mutableStateOf("") }
    var importSpotifyError by remember { mutableStateOf<String?>(null) }
    var isImportingSpotify by remember { mutableStateOf(false) }
    var showPlaylistOptionsSheet by remember { mutableStateOf(false) }
    var showProfileOptionsSheet by remember { mutableStateOf(false) }
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }
    var isHomeRefreshing by remember { mutableStateOf(false) }

    fun refreshAllData() {
        // Library data is local-first, so it is shown immediately and synced in the
        // background; only the feed below keeps the loading skeleton.
        library.refresh()
        scope.launch {
            if (tracks.isEmpty()) isLoadingHome = true

            // The three feed calls are independent: run them together so the home
            // screen waits for the slowest one (~0.3s) instead of their sum.
            val feed = listOf(
                launch {
                    runCatching { api.chart(limit = 50) }.onSuccess { tracks = it }
                },
                launch {
                    runCatching { api.newReleases() }.onSuccess { newReleases = it }
                },
                launch {
                    runCatching { api.genres() }.onSuccess { genres = it }
                },
                launch {
                    if (favoriteArtists.isNotEmpty()) {
                        val artistsToFetch = favoriteArtists.toList().shuffled().take(4)
                        val collected = mutableListOf<Track>()
                        for (art in artistsToFetch) {
                            val list = runCatching { api.search(art, limit = 5).tracks }.getOrNull().orEmpty()
                            val filtered = list.filter { it.artist.contains(art, ignoreCase = true) || list.size <= 2 }.take(3)
                            collected.addAll(if (filtered.isNotEmpty()) filtered else list.take(2))
                        }
                        if (collected.isNotEmpty()) {
                            val mixed = collected.distinctBy { it.id }.shuffled()
                            favoriteArtistTracks = mixed
                        }
                    }
                }
            )
            withTimeoutOrNull(8_000) { feed.joinAll() }
            isLoadingHome = false

            // Profile bits are not needed to paint the feed, so they must never hold
            // the skeleton back.
            if (api.token != null && api.token != "guest") {
                launch {
                    runCatching { api.me() }.onSuccess { me ->
                        val u = me["user"]?.jsonObject
                        val fetchedName = u?.get("name")?.jsonPrimitive?.content
                        if (!fetchedName.isNullOrBlank()) {
                            userName = fetchedName
                        }
                        userEmail = u?.get("email")?.jsonPrimitive?.content ?: ""
                        userTotalSpins = u?.get("total_spins")?.jsonPrimitive?.intOrNull ?: 0
                    }
                }
            } else {
                userEmail = localAccountLabel
            }

            // Warm the first album pages in the background so opening one from the
            // chart does not pay the cold server fetch.
            launch {
                delay(2_000)
                tracks.asSequence()
                    .mapNotNull { it.albumId?.takeIf { id -> id.isNotBlank() } }
                    .distinct()
                    .take(3)
                    .forEach { albumId -> runCatching { api.album(albumId) } }
            }
            isHomeRefreshing = false
        }
    }

    LaunchedEffect(player) {
        player.onSpinRecorded = {
            userTotalSpins++
        }
    }

    LaunchedEffect(Unit) {
        // Paint immediately from the disk cache, then refresh from the network.
        // Cold start therefore shows the last known feed instead of a skeleton.
        runCatching { api.chartSnapshot() }.getOrNull()?.let {
            if (it.isNotEmpty()) {
                tracks = it
                isLoadingHome = false
            }
        }
        runCatching { api.newReleasesSnapshot() }.getOrNull()?.let {
            if (it.isNotEmpty()) newReleases = it
        }
        runCatching { api.genresSnapshot() }.getOrNull()?.let {
            if (it.isNotEmpty()) genres = it
        }
        refreshAllData()
    }

    val activeTrack by player.currentTrack.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    var loadingAlbumId by remember { mutableStateOf<String?>(null) }

    fun navigateTo(screen: BlexsyScreen) {
        screenStack = screenStack + screen
    }

    fun popBack() {
        if (screenStack.size > 1) {
            screenStack = screenStack.dropLast(1)
        }
    }

    fun playTrack(track: Track, queue: List<Track>, contextTitle: String? = null) {
        player.play(track, queue, contextTitle)
    }

    fun showTrackOptions(track: Track, queue: List<Track>) {
        optionsQueue = queue
        optionsTrack = track
    }

    // Placed before the screen switch on purpose: detail screens `return` early, and this
    // sheet has to be able to open on top of them too.
    optionsTrack?.let { track ->
        TrackOptionsSheet(
            track = track,
            isFavorite = favorites.any { it.id == track.id },
            onDismiss = { optionsTrack = null },
            onPlay = { playTrack(track, optionsQueue.ifEmpty { listOf(track) }) },
            onPlayNext = { player.addToQueue(track, isNext = true) },
            onAddToQueue = { player.addToQueue(track, isNext = false) },
            onStartRadio = { player.startRadio(track) },
            onToggleFavorite = { library.toggleFavorite(track) },
            onAddToPlaylist = { addToPlaylistTrack = track },
            onOpenArtist = track.primaryArtistRef?.let { artistRef ->
                { navigateTo(BlexsyScreen.Artist(artistRef)) }
            },
            onOpenAlbum = track.albumId?.takeIf { it.isNotBlank() }?.let { albumId ->
                { navigateTo(BlexsyScreen.Album(albumId)) }
            }
        )
    }

    addToPlaylistTrack?.let { track ->
        AddToPlaylistSheet(
            track = track,
            playlists = playlists,
            onDismiss = { addToPlaylistTrack = null },
            onSelectPlaylist = { selectedPlaylist ->
                library.addTrackToPlaylist(selectedPlaylist, track)
            },
            onCreatePlaylist = {
                trackForNewPlaylist = track
                newPlaylistName = ""
                showCreatePlaylistDialog = true
            }
        )
    }

    if (showPlaylistOptionsSheet) {
        ActionSheet(
            title = stringResource(R.string.profile_stat_crates),
            onDismiss = { showPlaylistOptionsSheet = false },
            items = listOf(
                ActionSheetItem(
                    label = stringResource(R.string.action_cook_crate),
                    icon = Icons.Rounded.Add,
                    onClick = {
                        newPlaylistName = ""
                        showCreatePlaylistDialog = true
                    }
                ),
                ActionSheetItem(
                    label = stringResource(R.string.action_port_spotify),
                    icon = Icons.Rounded.LibraryMusic,
                    onClick = {
                        spotifyPlaylistInput = ""
                        importSpotifyError = null
                        showImportSpotifyDialog = true
                    }
                )
            )
        )
    }

    val handleLogout: () -> Unit = {
        showProfileOptionsSheet = false
        userName = "User Blexsy"
        userEmail = localAccountLabel
        userTotalSpins = 0
        screenStack = listOf(BlexsyScreen.Main)
        onLogout()
    }

    if (showProfileOptionsSheet) {
        ActionSheet(
            title = stringResource(R.string.nav_profile),
            onDismiss = { showProfileOptionsSheet = false },
            items = listOf(
                ActionSheetItem(
                    label = stringResource(R.string.profile_more_settings),
                    icon = Icons.Rounded.Settings,
                    onClick = {
                        showProfileOptionsSheet = false
                        navigateTo(BlexsyScreen.Settings)
                    }
                ),
                ActionSheetItem(
                    label = stringResource(R.string.profile_more_logout),
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    tint = BlexsyColors.Danger,
                    onClick = {
                        showProfileOptionsSheet = false
                        showLogoutConfirmDialog = true
                    }
                )
            )
        )
    }

    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            containerColor = BlexsyColors.SurfaceContainerHigh,
            title = {
                Text(
                    text = stringResource(R.string.profile_logout_title),
                    style = BlexsyTypography.Title.copy(fontWeight = FontWeight.Bold),
                    color = BlexsyColors.TextPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.profile_logout_desc),
                    style = BlexsyTypography.Body.copy(color = BlexsyColors.TextSecondary)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmDialog = false
                        handleLogout()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.profile_sign_out),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmDialog = false }) {
                    Text(
                        text = stringResource(R.string.profile_cancel),
                        color = BlexsyColors.TextSecondary
                    )
                }
            }
        )
    }

    // System back / predictive back: pop the detail screen instead of exiting the app.
    BackHandler(enabled = screenStack.size > 1) { popBack() }

    // Detail screens rendering
    if (screenStack.last() != BlexsyScreen.Main) {
        when (val current = screenStack.last()) {
            is BlexsyScreen.Artist -> {
                key(current.id) {
                    ArtistDetailScreen(
                        artistId = current.id,
                        api = api,
                        player = player,
                        onBack = ::popBack,
                        onOpenAlbum = { albumId -> navigateTo(BlexsyScreen.Album(albumId)) },
                        onOpenArtist = { artistId -> navigateTo(BlexsyScreen.Artist(artistId)) },
                        onTrackOptions = ::showTrackOptions,
                        onExpandPlayer = { showFullPlayer = true },
                        bottomNav = {
                            BottomNav(
                                selectedIndex = selectedTab,
                                onSelect = { index ->
                                    selectedTab = index
                                    screenStack = listOf(BlexsyScreen.Main)
                                }
                            )
                        }
                    )
                }
            }
            is BlexsyScreen.Album -> {
                AlbumDetailScreen(
                    albumId = current.id,
                    api = api,
                    player = player,
                    onBack = ::popBack,
                    onOpenArtist = { artistId -> navigateTo(BlexsyScreen.Artist(artistId)) },
                    onTrackOptions = ::showTrackOptions,
                    onExpandPlayer = { showFullPlayer = true },
                    bottomNav = {
                        BottomNav(
                            selectedIndex = selectedTab,
                            onSelect = { index ->
                                selectedTab = index
                                screenStack = listOf(BlexsyScreen.Main)
                            }
                        )
                    }
                )
            }
            is BlexsyScreen.GenreDetail -> {
                GenrePlaylistsScreen(
                    genreId = current.id,
                    genreName = current.name,
                    genreImage = current.image,
                    api = api,
                    player = player,
                    onBack = ::popBack,
                    onOpenPlaylist = { p -> navigateTo(BlexsyScreen.PlaylistDetail(p, false)) },
                    onExpandPlayer = { showFullPlayer = true },
                    bottomNav = {
                        BottomNav(
                            selectedIndex = selectedTab,
                            onSelect = { index ->
                                selectedTab = index
                                screenStack = listOf(BlexsyScreen.Main)
                            }
                        )
                    }
                )
            }
            is BlexsyScreen.Profile -> {
                ProfileScreen(
                    userName = userName,
                    userEmail = userEmail,
                    totalSpins = userTotalSpins,
                    api = api,
                    player = player,
                    library = library,
                    onBack = ::popBack,
                    onLogout = handleLogout,
                    onOpenLogin = { navigateTo(BlexsyScreen.Login) },
                    onOpenSettings = { navigateTo(BlexsyScreen.Settings) },
                    onOpenPlaylist = { p -> navigateTo(BlexsyScreen.PlaylistDetail(p, false)) },
                    onTrackOptions = ::showTrackOptions,
                    onExpandPlayer = { showFullPlayer = true },
                    bottomNav = {
                        BottomNav(
                            selectedIndex = selectedTab,
                            onSelect = { index ->
                                selectedTab = index
                                screenStack = listOf(BlexsyScreen.Main)
                            }
                        )
                    }
                )
            }
            is BlexsyScreen.Login -> {
                AuthScreen(
                    api = api,
                    suggestedName = userName.takeIf { it.isNotBlank() && it != "User Blexsy" },
                    onBack = ::popBack,
                    onAuthenticated = { token ->
                        onSaveToken?.invoke(token)
                        api.token = token
                        library.refresh()
                        refreshAllData()
                        popBack()
                    },
                    onContinueGuest = ::popBack
                )
            }
            is BlexsyScreen.Settings -> {
                SettingsScreen(
                    player = player,
                    onBack = ::popBack,
                    onOpenEqualizer = { navigateTo(BlexsyScreen.Equalizer) }
                )
            }
            is BlexsyScreen.Equalizer -> {
                EqualizerScreen(onBack = ::popBack)
            }
            is BlexsyScreen.PlaylistDetail -> {
                val creatorName = if (current.isUserCreated) userName else BlexsyThemeConfig.appName
                PlaylistDetailScreen(
                    title = current.playlist.name,
                    imageUrl = current.playlist.tracks.firstOrNull()?.image,
                    description = "By $creatorName",
                    tracks = current.playlist.tracks,
                    player = player,
                    onBack = ::popBack,
                    onDeletePlaylist = if (current.isUserCreated) {
                        {
                            // Optimistic: the row disappears now, the server delete is queued.
                            library.deletePlaylist(current.playlist)
                        }
                    } else null,
                    onTrackOptions = ::showTrackOptions,
                    onExpandPlayer = { showFullPlayer = true },
                    bottomNav = {
                        BottomNav(
                            selectedIndex = selectedTab,
                            onSelect = { index ->
                                selectedTab = index
                                // Tapping a tab from a playlist/detail page returns to the
                                // tab root instead of keeping the detail screen on top.
                                screenStack = listOf(BlexsyScreen.Main)
                            }
                        )
                    }
                )
            }
            is BlexsyScreen.Main -> {}
        }
    } else {

    val tabTitle = when (selectedTab) {
        0 -> stringResource(R.string.nav_home)
        1 -> stringResource(R.string.nav_search)
        2 -> stringResource(R.string.tab_your_stash)
        else -> stringResource(R.string.nav_profile)
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentTopPadding = topInset + 64.dp
    val contentBottomPadding = bottomInset + if (activeTrack != null) 160.dp else 90.dp

    Box(modifier = Modifier.fillMaxSize().background(BlexsyColors.Background)) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                0 -> {
                    val pullRefreshState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = isHomeRefreshing,
                        onRefresh = {
                            isHomeRefreshing = true
                            refreshAllData()
                        },
                        state = pullRefreshState,
                        indicator = {
                            PullToRefreshDefaults.LoadingIndicator(
                                state = pullRefreshState,
                                isRefreshing = isHomeRefreshing,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = topInset + 8.dp),
                                containerColor = BlexsyColors.SurfaceContainerHigh,
                                color = BlexsyColors.Primary
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        ListenNowTab(
                            contentTopPadding = contentTopPadding,
                            contentBottomPadding = contentBottomPadding,
                            topInset = topInset,
                            listState = homeListState,
                            tracks = tracks,
                            favoriteArtistTracks = favoriteArtistTracks,
                            newReleases = newReleases,
                            recentTracks = recentTracks,
                            favorites = favorites,
                            playCounts = playCounts,
                            activeTrack = activeTrack,
                            isPlaying = isPlaying,
                            loadingAlbumId = loadingAlbumId,
                            isLoading = isLoadingHome,
                            onPlayChart = { track, queue -> playTrack(track, queue, "Top 50 Indonesia") },
                            onTogglePlayPause = { player.togglePlayPause() },
                            onSeeAllChart = {
                                navigateTo(
                                    BlexsyScreen.PlaylistDetail(
                                        Playlist(
                                            id = "top50",
                                            name = "Top 50 Indonesia",
                                            tracks = tracks
                                        ),
                                        false
                                    )
                                )
                            },
                            onSeeAllNewReleases = {
                                navigateTo(
                                    BlexsyScreen.PlaylistDetail(
                                        Playlist(
                                            id = "new_releases",
                                            name = newReleasesTitle,
                                            tracks = newReleases.map { album ->
                                                Track(
                                                    id = album.id,
                                                    title = album.name,
                                                    artist = album.artist.orEmpty(),
                                                    album = album.name,
                                                    albumId = album.id,
                                                    image = album.image
                                                )
                                            }
                                        ),
                                        false
                                    )
                                )
                            },
                            onOpenAlbum = { albumId -> navigateTo(BlexsyScreen.Album(albumId)) },
                            onPlayAlbum = { album ->
                                val isThisAlbumPlaying = activeTrack?.albumId == album.id || activeTrack?.album.equals(album.name, ignoreCase = true)
                                if (isThisAlbumPlaying) {
                                    player.togglePlayPause()
                                } else {
                                    scope.launch {
                                        loadingAlbumId = album.id
                                        try {
                                            val detail = api.album(album.id)
                                            if (detail.tracks.isNotEmpty()) {
                                                playTrack(detail.tracks.first(), detail.tracks)
                                            }
                                        } catch (_: Exception) {
                                        } finally {
                                            loadingAlbumId = null
                                        }
                                    }
                                }
                            },
                            onPlayRecent = { list -> player.play(list.first(), list) },
                            onSeeAllLiked = {
                                navigateTo(
                                    BlexsyScreen.PlaylistDetail(
                                        Playlist(
                                            id = "liked-songs",
                                            name = likedBangersTitle,
                                            tracks = favorites
                                        ),
                                        isUserCreated = false
                                    )
                                )
                            },
                            onToggleFavorite = { track -> library.toggleFavorite(track) },
                            onOpenArtist = { artistId -> navigateTo(BlexsyScreen.Artist(artistId)) },
                            onTrackOptions = ::showTrackOptions
                        )
                    }
                }

                1 -> SearchTab(
                    contentBottomPadding = contentBottomPadding,
                    searchHistory = searchState.history,
                    isLoading = isLoadingHome,
                    genres = genres,
                    onOpenSearch = { showSearchPage = true },
                    onHistoryClick = { showSearchPage = true; searchState.submit(it) },
                    onClearHistory = searchState::clearHistory,
                    onRemoveHistory = searchState::removeHistory,
                    onGenreClick = { g -> navigateTo(BlexsyScreen.GenreDetail(g.id, g.name, g.image)) }
                )

                2 -> LibraryTab(
                    contentTopPadding = contentTopPadding,
                    contentBottomPadding = contentBottomPadding,
                    isLoading = isLoadingHome,
                    favorites = favorites,
                    playlists = playlists,
                    onOpenLiked = {
                        navigateTo(
                            BlexsyScreen.PlaylistDetail(
                                Playlist(
                                    id = "liked-songs",
                                    name = likedBangersTitle,
                                    tracks = favorites
                                ),
                                isUserCreated = false
                            )
                        )
                    },
                    onOpenPlaylist = { p -> navigateTo(BlexsyScreen.PlaylistDetail(p, isUserCreated = true)) }
                )

                else -> ProfileScreen(
                    userName = userName,
                    userEmail = userEmail,
                    totalSpins = userTotalSpins,
                    api = api,
                    player = player,
                    library = library,
                    onBack = {},
                    onLogout = handleLogout,
                    onOpenLogin = { navigateTo(BlexsyScreen.Login) },
                    onOpenSettings = { navigateTo(BlexsyScreen.Settings) },
                    onOpenPlaylist = { p -> navigateTo(BlexsyScreen.PlaylistDetail(p, false)) },
                    onTrackOptions = ::showTrackOptions,
                    embedded = true,
                    embeddedTopPadding = contentTopPadding,
                    embeddedBottomPadding = contentBottomPadding,
                    onExpandPlayer = { showFullPlayer = true }
                )
            }
        }

        // Top app bar (Search provides its own sticky header; Home fades in on scroll)
        val showMasthead = when (selectedTab) {
            0 -> homeHeaderAlpha > 0.01f
            1 -> false
            else -> true
        }

        if (showMasthead) {
            Masthead(
                title = tabTitle,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(
                        if (selectedTab == 0) Modifier.graphicsLayer { alpha = homeHeaderAlpha }
                        else Modifier
                    ),
                actions = {
                    when (selectedTab) {
                        2 -> IconButton(
                            onClick = { showPlaylistOptionsSheet = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.a11y_menu_playlist),
                                tint = BlexsyColors.TextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        3 -> IconButton(
                            onClick = { showProfileOptionsSheet = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.a11y_profile_options),
                                tint = BlexsyColors.TextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            )
        }

        // Mini player + navigation bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            MiniPlayer(
                player = player,
                onExpand = { showFullPlayer = true }
            )
            BottomNav(
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )
        }
    }
    // Full-screen search page: the Search tab only shows a display surface, typing
    // happens here (Spotify behaviour).
    BackHandler(enabled = showSearchPage) { showSearchPage = false }
    if (showSearchPage) {
        SearchPage(
            state = searchState,
            contentBottomPadding = bottomInset + if (activeTrack != null) 160.dp else 90.dp,
            isLoading = isLoadingHome,
            genres = genres,
            favorites = favorites,
            activeTrack = activeTrack,
            onClose = { showSearchPage = false },
            onGenreClick = { g -> navigateTo(BlexsyScreen.GenreDetail(g.id, g.name, g.image)) },
            onPlay = { track, _ ->
                player.startRadio(track)
            },
            onToggleFavorite = { track -> library.toggleFavorite(track) },
            onOpenArtist = { artistId -> navigateTo(BlexsyScreen.Artist(artistId)) },
            onTrackOptions = { track, _ -> showTrackOptions(track, emptyList()) },
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MiniPlayer(
                        player = player,
                        onExpand = { showFullPlayer = true }
                    )
                    BottomNav(
                        selectedIndex = selectedTab,
                        onSelect = { index ->
                            selectedTab = index
                            if (index != 1) {
                                showSearchPage = false
                            }
                        }
                    )
                }
            }
        )
    }

    }

    // Full Player Modal
    if (showFullPlayer) {
        FullPlayerModal(
            player = player,
            api = api,
            library = library,
            onDismiss = { showFullPlayer = false },
            onOpenArtist = { artistId ->
                showFullPlayer = false
                navigateTo(BlexsyScreen.Artist(artistId))
            },
            onOpenAlbum = { albumId ->
                showFullPlayer = false
                navigateTo(BlexsyScreen.Album(albumId))
            },
            onOpenEqualizer = {
                showFullPlayer = false
                navigateTo(BlexsyScreen.Equalizer)
            }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            name = newPlaylistName,
            onNameChange = { newPlaylistName = it },
            onDismiss = {
                showCreatePlaylistDialog = false
                trackForNewPlaylist = null
            },
            onCreate = { name ->
                val track = trackForNewPlaylist
                if (track != null) {
                    library.createPlaylist(name, listOf(track))
                } else {
                    library.createPlaylist(name)
                }
                newPlaylistName = ""
                trackForNewPlaylist = null
                showCreatePlaylistDialog = false
            }
        )
    }

    if (showImportSpotifyDialog) {
        ImportSpotifyDialog(
            input = spotifyPlaylistInput,
            onInputChange = {
                spotifyPlaylistInput = it
                importSpotifyError = null
            },
            error = importSpotifyError,
            isImporting = isImportingSpotify,
            onDismiss = {
                showImportSpotifyDialog = false
                importSpotifyError = null
            },
            onInvalidInput = { importSpotifyError = "Enter a valid playlist link or ID." },
            onImport = { playlistId ->
                scope.launch {
                    isImportingSpotify = true
                    importSpotifyError = null
                    try {
                        val (name, tracks) = api.spotifyPlaylistDetail(playlistId)
                        if (tracks.isEmpty()) {
                            importSpotifyError = "Playlist is empty or not found."
                        } else {
                            library.createPlaylist(name, tracks)
                            showImportSpotifyDialog = false
                            spotifyPlaylistInput = ""
                        }
                    } catch (e: Exception) {
                        importSpotifyError = e.message ?: "Failed to import Spotify playlist."
                    } finally {
                        isImportingSpotify = false
                    }
                }
            }
        )
    }
}
