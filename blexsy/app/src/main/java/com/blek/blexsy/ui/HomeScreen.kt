package com.blek.blexsy.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
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
import com.blek.blexsy.data.SpotifyBrowseCategory
import com.blek.blexsy.data.SpotifyMetadata
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.ChartFeed
import com.blek.blexsy.model.HomeSection
import com.blek.blexsy.model.HomeSectionItem
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.TrackCredits
import com.blek.blexsy.model.isCatalogArtistId
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    api: BlexsyApiClient,
    player: BlexsyAudioPlayer,
    library: LibraryRepository,
    initialUserName: String = "User Blexsy"
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
    var chartTitle by remember { mutableStateOf<String?>(null) }
    // Spotify's ranked chart, when one loaded: its tracks already carry rank position and
    // movement, so the shelves only need the feed to decorate their headers.
    var chartFeed by remember { mutableStateOf<ChartFeed?>(null) }
    var dynamicSections by remember { mutableStateOf<List<HomeSectionItem>>(emptyList()) }

    // Song shelves only: album/artist/playlist cards would need a YouTube detail page, and this
    // app reads metadata from Spotify, so those cards are dropped instead of half-working.
    fun applyHomeSections(sections: List<HomeSection>) {
        val parsedList = sections.mapNotNull { sec ->
            // Skip trending / charts from YouTube home since Spotify supplies canonical charts & trending
            val titleLower = sec.title.lowercase()
            if (titleLower.contains("trending") || titleLower.contains("tangga") || titleLower.contains("chart")) {
                return@mapNotNull null
            }
            val songs = sec.items.mapNotNull { it.track }
            if (songs.isEmpty()) {
                null
            } else {
                HomeSectionItem(id = sec.id, title = sec.title, variant = "song", tracks = songs)
            }
        }
        dynamicSections = parsedList
    }

    // Likes, playlists, and recent spins come from the local-first repository: changes show up instantly
    // and are synced to the server in the background.
    val favorites by library.favorites.collectAsState()
    val playlists by library.playlists.collectAsState()
    val recentSpins by library.recentTracks.collectAsState()
    val playCounts by library.playCounts.collectAsState()
    val recentTracks = remember(recentSpins) { recentSpins.take(10) }

    val localAccountLabel = stringResource(R.string.profile_local_account)
    val likedBangersTitle = stringResource(R.string.library_liked_bangers)

    // Artists the user actually hearted. Recent spins are NOT favourites, so whatever is playing
    // must never appear in a section labelled "favourite artists". Only artists with a real
    // catalog id (Spotify, since this app reads Spotify metadata) are kept, otherwise the card
    // could not open a page.
    val favoriteArtistList = remember(favorites) {
        val idsByName = buildMap {
            favorites.forEach { tr ->
                tr.artistRefs.forEach { (id, name) ->
                    if (id.isCatalogArtistId()) putIfAbsent(name.lowercase(), id)
                }
            }
        }
        favorites
            .mapNotNull { tr ->
                val primary = tr.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim()
                if (primary.isNullOrBlank() || primary.equals("Unknown artist", ignoreCase = true)) null else primary
            }
            .distinct()
            .take(10)
            .mapNotNull { name ->
                val artistId = idsByName[name.lowercase()] ?: return@mapNotNull null
                ArtistSummary(
                    id = artistId,
                    name = name,
                    image = favorites.firstOrNull { it.artist.contains(name, ignoreCase = true) }?.image
                )
            }
    }
    val collectionPlaylists = remember(playlists, favorites, likedBangersTitle) {
        buildList {
            if (favorites.isNotEmpty()) {
                add(Playlist(id = "liked", name = likedBangersTitle, tracks = favorites))
            }
            addAll(playlists)
        }
    }

    // Search lives outside this shell: the tab is display-only and the actual typing
    // happens on a full-screen page layered on top of it.
    val searchState = rememberBlexsySearchState(api)
    var showSearchPage by remember { mutableStateOf(false) }

    var isLoadingHome by remember { mutableStateOf(true) }
    var browseCategories by remember { mutableStateOf<List<SpotifyBrowseCategory>>(emptyList()) }
    var isLoadingCategories by remember { mutableStateOf(false) }
    var showFullPlayer by remember { mutableStateOf(false) }

    // One three-dot context sheet shared by every track list (tabs and detail screens).
    var optionsTrack by remember { mutableStateOf<Track?>(null) }
    var optionsQueue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }
    var trackForNewPlaylist by remember { mutableStateOf<Track?>(null) }
    var creditsTrack by remember { mutableStateOf<Track?>(null) }
    var creditsData by remember { mutableStateOf<TrackCredits?>(null) }
    var isLoadingCredits by remember { mutableStateOf(false) }

    // User profile state
    var userName by remember { mutableStateOf(initialUserName) }
    var userEmail by remember { mutableStateOf(localAccountLabel) }
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

    fun refreshAllData(forceRefresh: Boolean = false) {
        // Library data is local-first, so it is shown immediately and synced in the
        // background; only the feed below keeps the loading skeleton.
        library.refresh()
        scope.launch {
            if (forceRefresh) {
                runCatching { api.clearHomeFeedCache() }
            }
            if (tracks.isEmpty()) isLoadingHome = true

            // Both feed calls are independent: run them together so the home screen waits for
            // the slowest one instead of their sum.
            val feed = listOf(
                launch {
                    val sectionsResult = runCatching { api.homeSections(refresh = forceRefresh) }
                    if (sectionsResult.isSuccess) {
                        applyHomeSections(sectionsResult.getOrNull().orEmpty())
                    }
                },
                launch {
                    // Always keep a chart around: it feeds the hero carousel and the chart shelf.
                    runCatching { api.chart(limit = 50, refresh = forceRefresh) }.onSuccess { feed ->
                        chartFeed = feed
                        if (feed.tracks.isNotEmpty()) {
                            tracks = feed.tracks
                            chartTitle = feed.title.ifBlank { null }
                        }
                    }
                },
                launch {
                    isLoadingCategories = true
                    runCatching { SpotifyMetadata.getBrowse() }.onSuccess { cats ->
                        if (cats.isNotEmpty()) browseCategories = cats
                    }
                    isLoadingCategories = false
                }
            )
            withTimeoutOrNull(8_000) { feed.joinAll() }
            isLoadingHome = false

            // Profile bits are local-only now.
            userTotalSpins = playCounts.values.sum()
            userEmail = localAccountLabel
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
        val cachedSections = runCatching { api.homeSectionsSnapshot() }.getOrNull()
        if (!cachedSections.isNullOrEmpty()) {
            applyHomeSections(cachedSections)
        }
        runCatching { api.chartSnapshot() }.getOrNull()?.let { feed ->
            if (feed.tracks.isNotEmpty()) {
                chartFeed = feed
                tracks = feed.tracks
                chartTitle = feed.title
                isLoadingHome = false
            }
        }
        refreshAllData()
    }

    val activeTrack by player.currentTrack.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()

    fun navigateTo(screen: BlexsyScreen) {
        screenStack = screenStack + screen
    }

    fun popBack() {
        if (screenStack.size > 1) {
            screenStack = screenStack.dropLast(1)
        }
    }

    fun openArtistFor(rawArtistRefOrName: String) {
        val trimmed = rawArtistRefOrName.trim()
        if (trimmed.isBlank()) return
        // Open straight away: the artist page resolves a bare name itself while it loads,
        // so the tap never sits on the current screen waiting for a lookup.
        navigateTo(BlexsyScreen.Artist(trimmed))
    }

    fun openAlbumFor(track: Track) {
        val directAlbumId = track.albumId?.trim()?.takeIf { it.isNotBlank() }
        if (directAlbumId != null) {
            navigateTo(BlexsyScreen.Album(directAlbumId))
            return
        }
        scope.launch {
            val albumName = track.album.trim()
            val query = if (albumName.isNotBlank()) {
                "$albumName ${track.artist}"
            } else {
                val spTrack = runCatching { SpotifyMetadata.getTrack(track.id) }.getOrNull()
                val spAlbum = spTrack?.track?.album?.trim().orEmpty()
                if (spAlbum.isNotBlank()) "$spAlbum ${track.artist}" else "${track.title} ${track.artist}"
            }
            val searchRes = runCatching { SpotifyMetadata.search(query, limit = 3) }.getOrNull()
            val foundAlbumId = searchRes?.albums?.firstOrNull()?.id
            if (foundAlbumId != null) {
                navigateTo(BlexsyScreen.Album(foundAlbumId))
            }
        }
    }

    fun playTrack(track: Track, queue: List<Track>, contextTitle: String? = null) {
        player.play(track, queue, contextTitle)
    }

    fun showTrackOptions(track: Track, queue: List<Track>) {
        optionsQueue = queue
        optionsTrack = track
    }

    val currentScreen = screenStack.lastOrNull()
    val currentArtistId = (currentScreen as? BlexsyScreen.Artist)?.id
    val currentAlbumId = (currentScreen as? BlexsyScreen.Album)?.id

    // Placed before the screen switch on purpose: detail screens `return` early, and this
    // sheet has to be able to open on top of them too.
    optionsTrack?.let { track ->
        val artistRef = track.primaryArtistRef
            ?: track.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: track.artist.trim().takeIf { it.isNotBlank() }
        val canOpenArtist = currentScreen !is BlexsyScreen.Artist
        val openableArtistRef = artistRef.takeIf { canOpenArtist }

        TrackOptionsSheet(
            track = track,
            isFavorite = favorites.any { it.id == track.id },
            onDismiss = { optionsTrack = null },
            onAddToPlaylist = { addToPlaylistTrack = track },
            onAddToQueue = { player.addToQueue(track, isNext = false) },
            onOpenAlbum = { openAlbumFor(track) },
            onOpenArtist = openableArtistRef?.let { ref -> { openArtistFor(ref) } },
            onStartRadio = { player.startRadio(track) },
            onViewCredits = {
                val targetTrack = track
                creditsTrack = targetTrack
                creditsData = null
                isLoadingCredits = true
                scope.launch {
                    creditsData = runCatching { api.credits(targetTrack) }.getOrNull()
                    isLoadingCredits = false
                }
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

    creditsTrack?.let { trk ->
        PlayerCreditsSheet(
            track = trk,
            credits = creditsData,
            isLoading = isLoadingCredits,
            onDismiss = {
                creditsTrack = null
                creditsData = null
                isLoadingCredits = false
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
                    icon = IcPlus,
                    onClick = {
                        newPlaylistName = ""
                        showCreatePlaylistDialog = true
                    }
                ),
                ActionSheetItem(
                    label = stringResource(R.string.action_port_spotify),
                    icon = IcMusicNotes,
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
        library.signOut()
        userName = initialUserName
        userEmail = localAccountLabel
        userTotalSpins = 0
        screenStack = listOf(BlexsyScreen.Main)
    }

    if (showProfileOptionsSheet) {
        ActionSheet(
            title = stringResource(R.string.nav_profile),
            onDismiss = { showProfileOptionsSheet = false },
            items = listOf(
                ActionSheetItem(
                    label = stringResource(R.string.profile_more_settings),
                    icon = IcGearSix,
                    onClick = {
                        showProfileOptionsSheet = false
                        navigateTo(BlexsyScreen.Settings)
                    }
                ),
                ActionSheetItem(
                    label = stringResource(R.string.profile_more_logout),
                    icon = IcSignOut,
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
            is BlexsyScreen.Login -> {}
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
            is BlexsyScreen.Profile -> {
                ProfileScreen(
                    userName = userName,
                    userEmail = userEmail,
                    totalSpins = userTotalSpins,
                    player = player,
                    library = library,
                    onBack = ::popBack,
                    onLogout = handleLogout,
                    onOpenLogin = { navigateTo(BlexsyScreen.Login) },
                    onOpenSettings = { navigateTo(BlexsyScreen.Settings) },
                    onOpenPlaylist = { p -> navigateTo(BlexsyScreen.PlaylistDetail(p, false)) },
                    onTrackOptions = ::showTrackOptions,
                    onOpenArtist = ::openArtistFor,
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
                // Every playlist here is local now (liked songs, profile playlists, library rows):
                // the YouTube home feed no longer supplies remote ones.
                val isLocalPlaylist = current.isUserCreated
                PlaylistDetailScreen(
                    title = current.playlist.name,
                    imageUrl = current.playlist.tracks.firstOrNull()?.image,
                    description = "By ${if (isLocalPlaylist) userName else BlexsyThemeConfig.appName}",
                    tracks = current.playlist.tracks,
                    isLoading = false,
                    player = player,
                    onBack = ::popBack,
                    onDeletePlaylist = if (isLocalPlaylist) {
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
            is BlexsyScreen.Genre -> key(current.id) {
                GenreDetailScreen(
                    genreId = current.id,
                    genreTitle = current.title,
                    genreImage = current.imageUrl,
                    api = api,
                    player = player,
                    onBack = ::popBack,
                    onOpenPlaylist = { playlistId, name ->
                        scope.launch {
                            runCatching { api.playlistDetail(playlistId) }.onSuccess { (fetchedName, tracks) ->
                                navigateTo(
                                    BlexsyScreen.PlaylistDetail(
                                        Playlist(
                                            id = playlistId,
                                            name = fetchedName.ifBlank { name },
                                            tracks = tracks
                                        ),
                                        isUserCreated = false
                                    )
                                )
                            }
                        }
                    },
                    onOpenAlbum = { albumId -> navigateTo(BlexsyScreen.Album(albumId)) },
                    onOpenGenre = { nextId, nextTitle, nextImage ->
                        navigateTo(BlexsyScreen.Genre(nextId, nextTitle, imageUrl = nextImage))
                    },
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
                            refreshAllData(forceRefresh = true)
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
                        val displayChartTitle = chartTitle?.ifBlank { null } ?: "Top 50 Indonesia"
                        ListenNowTab(
                            contentTopPadding = contentTopPadding,
                            contentBottomPadding = contentBottomPadding,
                            topInset = topInset,
                            listState = homeListState,
                            chartTitle = chartTitle,
                            chartFeed = chartFeed,
                            tracks = tracks,
                            recentTracks = recentTracks,
                            favorites = favorites,
                            favoriteArtists = favoriteArtistList,
                            collectionPlaylists = collectionPlaylists,
                            dynamicSections = dynamicSections,
                            playCounts = playCounts,
                            activeTrack = activeTrack,
                            isPlaying = isPlaying,
                            isLoading = isLoadingHome,
                            onPlayChart = { track, queue -> playTrack(track, queue, displayChartTitle) },
                            onTogglePlayPause = { player.togglePlayPause() },
                            onSeeAllChart = {
                                navigateTo(
                                    BlexsyScreen.PlaylistDetail(
                                        Playlist(
                                            id = "top50",
                                            name = displayChartTitle,
                                            tracks = tracks
                                        ),
                                        false
                                    )
                                )
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
                            onOpenPlaylist = { p -> navigateTo(BlexsyScreen.PlaylistDetail(p, isUserCreated = p.id != "liked")) },
                            onSeeAllPlaylists = { selectedTab = 2 },
                            onTrackOptions = ::showTrackOptions
                        )
                    }
                }

                1 -> SearchTab(
                    contentBottomPadding = contentBottomPadding,
                    searchHistory = searchState.history,
                    categories = browseCategories,
                    isLoading = isLoadingHome || (isLoadingCategories && browseCategories.isEmpty()),
                    onOpenSearch = { showSearchPage = true },
                    onHistoryClick = { showSearchPage = true; searchState.submit(it, immediate = true) },
                    onCategoryClick = { category ->
                        navigateTo(
                            BlexsyScreen.Genre(
                                id = category.id,
                                title = category.title,
                                imageUrl = category.imageUrl
                            )
                        )
                    },
                    onClearHistory = searchState::clearHistory,
                    onRemoveHistory = searchState::removeHistory
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
                    player = player,
                    library = library,
                    onBack = {},
                    onLogout = handleLogout,
                    onOpenLogin = { navigateTo(BlexsyScreen.Login) },
                    onOpenSettings = { navigateTo(BlexsyScreen.Settings) },
                    onOpenPlaylist = { p -> navigateTo(BlexsyScreen.PlaylistDetail(p, false)) },
                    onTrackOptions = ::showTrackOptions,
                    onOpenArtist = ::openArtistFor,
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
                                imageVector = IcPlus,
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
                                imageVector = IcDotsThreeVertical,
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
                onSelect = { index ->
                    selectedTab = index
                    if (index != 1) {
                        searchState.clearQuery()
                    }
                }
            )
        }
    }
    // Full-screen search page: the Search tab only shows a display surface, typing
    // happens here (Spotify behaviour).
    BackHandler(enabled = showSearchPage) {
        showSearchPage = false
        searchState.clearQuery()
    }
    if (showSearchPage) {
        SearchPage(
            state = searchState,
            contentBottomPadding = bottomInset + if (activeTrack != null) 160.dp else 90.dp,
            isLoading = isLoadingHome,
            recentTracks = recentSpins.take(20),
            favorites = favorites,
            activeTrack = activeTrack,
            onClose = {
                showSearchPage = false
                searchState.clearQuery()
            },
            onPlay = { track, list ->
                if (list.isNotEmpty()) {
                    player.play(track, list)
                } else {
                    player.startRadio(track)
                }
            },
            onToggleFavorite = { track -> library.toggleFavorite(track) },
            onOpenArtist = { artistId -> navigateTo(BlexsyScreen.Artist(artistId)) },
            onTrackOptions = { track, list -> showTrackOptions(track, list) },
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
                                searchState.clearQuery()
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
                        val (name, tracks) = api.playlistDetail(playlistId)
                        if (tracks.isEmpty()) {
                            importSpotifyError = "Playlist is empty or not found."
                        } else {
                            library.createPlaylist(name, tracks)
                            showImportSpotifyDialog = false
                            spotifyPlaylistInput = ""
                        }
                    } catch (e: Exception) {
                        importSpotifyError = e.message ?: "Failed to import playlist."
                    } finally {
                        isImportingSpotify = false
                    }
                }
            }
        )
    }
}
