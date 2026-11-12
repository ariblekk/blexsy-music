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
import com.blek.blexsy.model.AlbumSummary
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.FeaturedItem
import com.blek.blexsy.model.Genre
import com.blek.blexsy.model.HomeSection
import com.blek.blexsy.model.HomeSectionItem
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
    var popularArtists by remember { mutableStateOf<List<ArtistSummary>>(emptyList()) }
    var newReleases by remember { mutableStateOf<List<AlbumSummary>>(emptyList()) }
    var genres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var featuredItem by remember { mutableStateOf<FeaturedItem?>(null) }
    var dynamicSections by remember { mutableStateOf<List<HomeSectionItem>>(emptyList()) }

    fun applyHomeSections(sections: List<HomeSection>) {
        val parsedList = mutableListOf<HomeSectionItem>()
        for (sec in sections) {
            val songs = sec.items.mapNotNull { it.track }
            val artists = sec.items.mapNotNull { item ->
                val id = item.artistId ?: return@mapNotNull null
                ArtistSummary(id = id, name = item.title, image = item.image)
            }
            val albums = sec.items.mapNotNull { item ->
                val id = item.albumId ?: return@mapNotNull null
                AlbumSummary(id = id, name = item.title, artist = item.subtitle, image = item.image, type = "ALBUM")
            }
            val playlists = sec.items.mapNotNull { item ->
                val id = item.playlistId ?: return@mapNotNull null
                Playlist(
                    id = id,
                    name = item.title,
                    tracks = item.image?.let { listOf(Track(id = "", image = it)) } ?: emptyList()
                )
            }
            when (sec.variant.lowercase()) {
                "song" -> if (songs.isNotEmpty()) {
                    parsedList += HomeSectionItem(id = sec.id, title = sec.title, variant = "chart", tracks = songs)
                }
                "artist" -> if (artists.isNotEmpty()) {
                    parsedList += HomeSectionItem(id = sec.id, title = sec.title, variant = "artists", artists = artists)
                }
                "album" -> if (albums.isNotEmpty()) {
                    parsedList += HomeSectionItem(id = sec.id, title = sec.title, variant = "albums", albums = albums)
                }
                "playlist" -> if (playlists.isNotEmpty()) {
                    parsedList += HomeSectionItem(id = sec.id, title = sec.title, variant = "playlists", playlists = playlists)
                }
                else -> {
                    if (songs.isNotEmpty()) {
                        parsedList += HomeSectionItem(id = sec.id + "_songs", title = sec.title, variant = "chart", tracks = songs)
                    }
                    if (albums.isNotEmpty()) {
                        parsedList += HomeSectionItem(id = sec.id + "_albums", title = sec.title, variant = "albums", albums = albums)
                    }
                    if (playlists.isNotEmpty()) {
                        parsedList += HomeSectionItem(id = sec.id + "_playlists", title = sec.title, variant = "playlists", playlists = playlists)
                    }
                    if (artists.isNotEmpty() && songs.isEmpty() && albums.isEmpty() && playlists.isEmpty()) {
                        parsedList += HomeSectionItem(id = sec.id, title = sec.title, variant = "artists", artists = artists)
                    }
                }
            }
        }
        dynamicSections = parsedList

        val firstChart = parsedList.firstOrNull { it.variant == "chart" }
        if (firstChart != null) {
            tracks = firstChart.tracks
            chartTitle = firstChart.title.ifBlank { null }
        }

        val firstArtists = parsedList.firstOrNull { it.variant == "artists" }
        popularArtists = firstArtists?.artists ?: emptyList()

        if (featuredItem == null) {
            val heroAlbum = parsedList.firstOrNull { it.variant == "albums" }?.albums?.firstOrNull()
            val heroPlaylist = parsedList.firstOrNull { it.variant == "playlists" }?.playlists?.firstOrNull()
            featuredItem = heroAlbum?.let {
                FeaturedItem(
                    id = it.id,
                    kicker = "ALBUM BARU",
                    title = it.name,
                    subtitle = it.artist.orEmpty(),
                    imageUrl = it.image,
                    type = "album",
                    target = it.id,
                    active = true,
                    layout = "banner"
                )
            } ?: heroPlaylist?.let {
                FeaturedItem(
                    id = it.id,
                    kicker = "PLAYLIST",
                    title = it.name,
                    subtitle = "",
                    imageUrl = it.tracks.firstOrNull()?.image,
                    type = "playlist",
                    target = it.id,
                    active = true,
                    layout = "banner"
                )
            }
        }
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
    val newReleasesTitle = stringResource(R.string.listen_drops_title)

    // Artists the user actually hearted. Recent spins are NOT favourites, so whatever is playing
    // must never appear in a section labelled "favourite artists" — that is why only `favorites`
    // feeds this list. Only artists with a real channel id are kept, otherwise the card could not
    // open a page.
    val favoriteArtistList = remember(favorites, popularArtists) {
        val idsByName = buildMap {
            favorites.forEach { tr ->
                val names = tr.artist.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val ids = tr.artistIds.filter {
                    it.startsWith("UC") || it.startsWith("FEmusic_artist")
                }
                names.forEachIndexed { index, name ->
                    ids.getOrNull(index)?.let { putIfAbsent(name.lowercase(), it) }
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
                val popular = popularArtists.firstOrNull { it.name.equals(name, ignoreCase = true) }
                val channelId = popular?.channelId ?: idsByName[name.lowercase()] ?: return@mapNotNull null
                popular ?: ArtistSummary(
                    id = channelId,
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
    var showFullPlayer by remember { mutableStateOf(false) }

    // One three-dot context sheet shared by every track list (tabs and detail screens).
    var optionsTrack by remember { mutableStateOf<Track?>(null) }
    var optionsQueue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }
    var trackForNewPlaylist by remember { mutableStateOf<Track?>(null) }

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

            // The three feed calls are independent: run them together so the home
            // screen waits for the slowest one (~0.3s) instead of their sum.
            val feed = listOf(
                launch {
                    val sectionsResult = runCatching { api.homeSections(refresh = forceRefresh) }
                    if (sectionsResult.isSuccess) {
                        applyHomeSections(sectionsResult.getOrNull().orEmpty())
                    } else {
                        // Fallback to legacy endpoints if home_sections collection not found (e.g. 404)
                        runCatching { api.chart(limit = 50, refresh = forceRefresh) }.onSuccess { feed ->
                            tracks = feed.tracks
                            chartTitle = feed.title
                        }
                        runCatching { api.topArtists(refresh = forceRefresh) }.onSuccess { popularArtists = it }
                    }
                },
                launch {
                    // Always keep a chart around: it feeds the hero carousel even when the
                    // YouTube home feed has no song rows.
                    runCatching { api.chart(limit = 50, refresh = forceRefresh) }.onSuccess { feed ->
                        if (tracks.isEmpty()) {
                            tracks = feed.tracks
                            chartTitle = feed.title.ifBlank { null }
                        }
                    }
                },
                launch {
                    runCatching { api.newReleases(refresh = forceRefresh) }.onSuccess { newReleases = it }
                },
                launch {
                    runCatching { api.genres(refresh = forceRefresh) }.onSuccess { genres = it }
                }
            )
            withTimeoutOrNull(8_000) { feed.joinAll() }
            isLoadingHome = false

            // Profile bits are local-only now.
            userTotalSpins = playCounts.values.sum()
            userEmail = localAccountLabel

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
        val cachedSections = runCatching { api.homeSectionsSnapshot() }.getOrNull()
        if (!cachedSections.isNullOrEmpty()) {
            applyHomeSections(cachedSections)
            isLoadingHome = false
        } else {
            runCatching { api.chartSnapshot() }.getOrNull()?.let { feed ->
                if (feed.tracks.isNotEmpty()) {
                    tracks = feed.tracks
                    chartTitle = feed.title
                    isLoadingHome = false
                }
            }
            runCatching { api.topArtistsSnapshot() }.getOrNull()?.let {
                if (it.isNotEmpty()) popularArtists = it
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
                // Playlists coming from the YouTube home feed only carry a placeholder track, kept
                // for the cover image. Opening one used to show that placeholder: "1 lagu", titled
                // "Unknown track". Fetch the real playlist whenever it is a YouTube id and there
                // is nothing substantial yet. `isUserCreated` cannot be used here: the home tab
                // marks every non-"liked" playlist as user created, including remote ones.
                val remotePlaylistId = current.playlist.id
                    .takeIf { it.startsWith("VL") || it.startsWith("PL") }
                // A remote playlist has no local row to delete, and `isUserCreated` is true for
                // every home card, so the header trash button is gated on being local instead.
                val isLocalPlaylist = remotePlaylistId == null && current.isUserCreated
                // A remote playlist credits the app, not the account, otherwise a YouTube
                // playlist would claim to be created by this user.
                val creatorName = if (isLocalPlaylist) userName else BlexsyThemeConfig.appName
                var detail by remember(current.playlist.id) { mutableStateOf(current.playlist) }
                var isLoadingTracks by remember(current.playlist.id) { mutableStateOf(false) }
                LaunchedEffect(remotePlaylistId) {
                    if (remotePlaylistId == null) return@LaunchedEffect
                    if (current.playlist.tracks.any { it.id.isNotBlank() }) return@LaunchedEffect
                    isLoadingTracks = true
                    try {
                        val (remoteName, remoteTracks) = api.playlistDetail(remotePlaylistId)
                        if (remoteTracks.isNotEmpty()) {
                            detail = Playlist(
                                id = remotePlaylistId,
                                name = remoteName.ifBlank { current.playlist.name },
                                tracks = remoteTracks
                            )
                        }
                    } finally {
                        isLoadingTracks = false
                    }
                }
                PlaylistDetailScreen(
                    title = detail.name,
                    imageUrl = detail.tracks.firstOrNull()?.image ?: current.playlist.tracks.firstOrNull()?.image,
                    description = "By $creatorName",
                    tracks = detail.tracks,
                    isLoading = isLoadingTracks,
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
                            tracks = tracks,
                            newReleases = newReleases,
                            recentTracks = recentTracks,
                            favorites = favorites,
                            popularArtists = popularArtists,
                            favoriteArtists = favoriteArtistList,
                            collectionPlaylists = collectionPlaylists,
                            featuredItem = featuredItem,
                            dynamicSections = dynamicSections,
                            playCounts = playCounts,
                            activeTrack = activeTrack,
                            isPlaying = isPlaying,
                            loadingAlbumId = loadingAlbumId,
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
                            onOpenPlaylist = { p -> navigateTo(BlexsyScreen.PlaylistDetail(p, isUserCreated = p.id != "liked")) },
                            onSeeAllPlaylists = { selectedTab = 2 },
                            onFeaturedClick = { item ->
                                val target = item.target.trim()
                                val isWebUrl = item.type.equals("url", ignoreCase = true) ||
                                        target.startsWith("http://", ignoreCase = true) ||
                                        target.startsWith("https://", ignoreCase = true) ||
                                        (item.layout.equals("banner", ignoreCase = true) && target.isNotBlank() && (target.contains(".") || target.startsWith("www.")))

                                if (isWebUrl && target.isNotBlank()) {
                                    val url = when {
                                        target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true) -> target
                                        else -> "https://$target"
                                    }
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                } else {
                                    when (item.type.lowercase()) {
                                        "album" -> {
                                            if (target.isNotBlank()) {
                                                navigateTo(BlexsyScreen.Album(target))
                                            }
                                        }
                                        "playlist" -> {
                                            if (target.isNotBlank()) {
                                                scope.launch {
                                                    val (pName, pTracks) = runCatching {
                                                        api.playlistDetail(target)
                                                    }.getOrNull() ?: Pair(item.title.ifBlank { "Featured Playlist" }, emptyList())
                                                    navigateTo(
                                                        BlexsyScreen.PlaylistDetail(
                                                            Playlist(
                                                                id = target,
                                                                name = pName,
                                                                tracks = pTracks
                                                            ),
                                                            isUserCreated = false
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        "track" -> {
                                            val tr = Track(
                                                id = target.ifBlank { item.id },
                                                title = item.title,
                                                artist = item.subtitle,
                                                image = item.imageUrl
                                            )
                                            playTrack(tr, listOf(tr), "Featured")
                                        }
                                        else -> {
                                            if (target.startsWith("http://") || target.startsWith("https://")) {
                                                runCatching {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
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
                    onHistoryClick = { showSearchPage = true; searchState.submit(it, immediate = true) },
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
            genres = genres,
            favorites = favorites,
            activeTrack = activeTrack,
            onClose = {
                showSearchPage = false
                searchState.clearQuery()
            },
            onGenreClick = { g -> navigateTo(BlexsyScreen.GenreDetail(g.id, g.name, g.image)) },
            onPlay = { track, _ ->
                player.startRadio(track)
            },
            onToggleFavorite = { track -> library.toggleFavorite(track) },
            onOpenArtist = { artistId -> navigateTo(BlexsyScreen.Artist(artistId)) },
            onOpenAlbum = { albumId -> navigateTo(BlexsyScreen.Album(albumId)) },
            onOpenPlaylist = { playlist ->
                // A search result carries the playlist id only; the screen needs its tracks.
                scope.launch {
                    runCatching { api.playlist(playlist.id) }.getOrNull()?.let { tracks ->
                        navigateTo(
                            BlexsyScreen.PlaylistDetail(
                                Playlist(id = playlist.id, name = playlist.name, tracks = tracks),
                                isUserCreated = false
                            )
                        )
                    }
                }
            },
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
                        importSpotifyError = e.message ?: "Failed to import YouTube Music playlist."
                    } finally {
                        isImportingSpotify = false
                    }
                }
            }
        )
    }
}
