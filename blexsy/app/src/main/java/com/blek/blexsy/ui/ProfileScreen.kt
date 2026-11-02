package com.blek.blexsy.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlinx.coroutines.launch
import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(
    userName: String,
    userEmail: String? = null,
    totalSpins: Int = 0,
    api: BlexsyApiClient,
    player: BlexsyAudioPlayer,
    library: LibraryRepository? = null,
    onBack: () -> Unit = {},
    onLogout: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenLogin: (() -> Unit)? = null,
    onOpenPlaylist: ((Playlist) -> Unit)? = null,
    onTrackOptions: ((Track, List<Track>) -> Unit)? = null,
    embedded: Boolean = false,
    embeddedTopPadding: Dp = 0.dp,
    embeddedBottomPadding: Dp = 0.dp,
    onExpandPlayer: () -> Unit = {},
    bottomNav: (@Composable () -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val activeTrack by player.currentTrack.collectAsState()
    val isGuest = api.token == null || api.token == "guest"

    var selectedTab by remember { mutableStateOf(0) } // 0: SPINS, 1: HEARTS, 2: CRATES
    val tabLabels = listOf(
        stringResource(R.string.profile_stat_spins),
        stringResource(R.string.profile_stat_hearts),
        stringResource(R.string.profile_stat_crates)
    )
    val todayLabel = stringResource(R.string.history_date_today)
    val yesterdayLabel = stringResource(R.string.history_date_yesterday)
    val recentLabel = stringResource(R.string.history_date_recent)
    var isLoading by remember { mutableStateOf(true) }

    var recentTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var favoriteTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var userPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // Mirror the local-first library so a like or a new playlist shows up here instantly,
    // without waiting for the network round-trip.
    LaunchedEffect(library) {
        val repo = library
        if (repo != null) {
            repo.favorites.collect { favs ->
                favoriteTracks = favs
            }
        }
    }
    LaunchedEffect(library) {
        val repo = library
        if (repo != null) {
            repo.playlists.collect { lists ->
                userPlaylists = lists
            }
        }
    }
    LaunchedEffect(library) {
        val repo = library
        if (repo != null) {
            repo.recentTracks.collect { recents ->
                recentTracks = recents
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            // Likes, playlists, and recent spins come from the local-first repository above; this only
            // kicks off the background reconciliation with the server.
            library?.refresh()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!embedded) {
                DetailAppBar(
                    title = stringResource(R.string.profile_title),
                    onBack = onBack,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isGuest && onOpenLogin != null) {
                                FilledTonalIconButton(
                                    onClick = onOpenLogin,
                                    shape = CircleShape,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Login,
                                        contentDescription = stringResource(R.string.profile_lock_in),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showMoreSheet = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = stringResource(R.string.a11y_more_options),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!embedded) {
                Column {
                    MiniPlayer(
                        player = player,
                        onExpand = onExpandPlayer,
                        includeNavigationBars = bottomNav == null
                    )
                    bottomNav?.invoke()
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (embedded) PaddingValues() else padding),
            contentPadding = PaddingValues(
                top = if (embedded) embeddedTopPadding else 0.dp,
                bottom = if (embedded) embeddedBottomPadding else 28.dp
            )
        ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Social media style header: Avatar on the left, Name on the right
                    val avatarSeed = userName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .skeleton(CircleShape)
                            )

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                SkeletonLine(modifier = Modifier.width(140.dp), height = 22.dp)
                                Spacer(modifier = Modifier.height(8.dp))
                                SkeletonLine(modifier = Modifier.width(180.dp), height = 14.dp)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .background(BlexsyColors.SurfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = userName.trim().take(1).uppercase().ifEmpty { "B" },
                                    color = BlexsyColors.TextPrimary,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                AsyncImage(
                                    model = dicebearAvatarUrl(userName),
                                    contentDescription = stringResource(R.string.a11y_profile_avatar),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = userName.ifEmpty { stringResource(R.string.profile_default_user) },
                                    style = BlexsyTypography.Hero.copy(fontSize = 22.sp, lineHeight = 26.sp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (!userEmail.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = userEmail,
                                        style = BlexsyTypography.Caption,
                                        color = BlexsyColors.TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    // Instagram-style stats counter row (denormalized total_spins, favorites, playlists)
                    val displaySpins = if (isGuest) recentTracks.size else maxOf(totalSpins, recentTracks.size)
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLoading) {
                            ProfileStatSkeleton()
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(26.dp)
                                    .background(BlexsyColors.Border.copy(alpha = 0.35f))
                            )
                            ProfileStatSkeleton()
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(26.dp)
                                    .background(BlexsyColors.Border.copy(alpha = 0.35f))
                            )
                            ProfileStatSkeleton()
                        } else {
                            ProfileStatItem(
                                count = displaySpins,
                                label = stringResource(R.string.profile_stat_spins)
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(26.dp)
                                    .background(BlexsyColors.Border.copy(alpha = 0.35f))
                            )
                            ProfileStatItem(
                                count = favoriteTracks.size,
                                label = stringResource(R.string.profile_stat_hearts)
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(26.dp)
                                    .background(BlexsyColors.Border.copy(alpha = 0.35f))
                            )
                            ProfileStatItem(
                                count = userPlaylists.size,
                                label = stringResource(R.string.profile_stat_crates)
                            )
                        }
                    }

                    if (isGuest && onOpenLogin != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable(onClick = onOpenLogin),
                            color = BlexsyColors.SurfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.profile_account_title),
                                        style = BlexsyTypography.Title.copy(fontSize = 15.sp),
                                        color = BlexsyColors.TextPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.profile_account_desc),
                                        style = BlexsyTypography.Caption.copy(fontSize = 12.sp),
                                        color = BlexsyColors.TextSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                BlexsyButton(
                                    text = stringResource(R.string.profile_lock_in),
                                    onClick = onOpenLogin,
                                    variant = ButtonVariant.Primary,
                                    size = ButtonSize.Sm
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Segmented Pill Tabs: Spins / Hearts / Crates
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BlexsyTabs(
                            tabs = tabLabels,
                            selectedIndex = selectedTab,
                            onSelect = { selectedTab = it },
                            fillMaxWidth = false
                        )
                    }
                }

            // Tab Content
            if (isLoading) {
                item {
                    ProfileListSkeleton()
                }
            } else {
                when (selectedTab) {
                    0 -> {
                        // Spins Tab
                        if (recentTracks.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(stringResource(R.string.profile_spins_empty_title), style = BlexsyTypography.SectionTitle)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.profile_spins_empty_desc),
                                            style = BlexsyTypography.Caption
                                        )
                                    }
                                }
                            }
                        } else {
                            val groupedRecent = recentTracks.groupBy { formatHistoryDate(it.playedAt, todayLabel, yesterdayLabel, recentLabel) }
                            groupedRecent.forEach { (dateHeader, tracksForDate) ->
                                item {
                                    Text(
                                        text = dateHeader.uppercase(),
                                        style = BlexsyTypography.Caption.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            color = BlexsyColors.TextMuted
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                                    )
                                }
                                itemsIndexed(tracksForDate) { index, track ->
                                    TrackTile(
                                        track = track,
                                        index = index,
                                        isPlayingThis = activeTrack?.id == track.id,
                                        onClick = {
                                            player.play(track, recentTracks)
                                        },
                                        onMore = onTrackOptions?.let { cb -> { cb(track, recentTracks) } }
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        // Liked Tab
                        if (favoriteTracks.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.FavoriteBorder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(stringResource(R.string.profile_hearts_empty_title), style = BlexsyTypography.SectionTitle)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.profile_hearts_empty_desc),
                                            style = BlexsyTypography.Caption
                                        )
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(favoriteTracks) { index, track ->
                                TrackTile(
                                    track = track,
                                    index = index,
                                    isPlayingThis = activeTrack?.id == track.id,
                                    isFavorite = true,
                                    onClick = {
                                        player.play(track, favoriteTracks)
                                    },
                                    onToggleFavorite = library?.let { repo -> { repo.toggleFavorite(track) } },
                                    onMore = onTrackOptions?.let { cb -> { cb(track, favoriteTracks) } }
                                )
                            }
                        }
                    }
                    2 -> {
                        // Playlists Tab
                        if (userPlaylists.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(stringResource(R.string.profile_crates_empty_title), style = BlexsyTypography.SectionTitle)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.profile_crates_empty_desc),
                                            style = BlexsyTypography.Caption
                                        )
                                    }
                                }
                            }
                        } else {
                            items(userPlaylists) { pl ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenPlaylist?.invoke(pl) }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CoverImage(url = pl.tracks.firstOrNull()?.image, size = 52, shapeRadius = 4)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = pl.name,
                                                style = BlexsyTypography.Title.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = stringResource(R.string.profile_crate_bangers, pl.tracks.size),
                                                style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMoreSheet) {
        ActionSheet(
            title = stringResource(R.string.nav_profile),
            onDismiss = { showMoreSheet = false },
            items = listOfNotNull(
                if (onOpenSettings != null) {
                    ActionSheetItem(
                        label = stringResource(R.string.profile_more_settings),
                        icon = Icons.Rounded.Settings,
                        onClick = {
                            showMoreSheet = false
                            onOpenSettings()
                        }
                    )
                } else null,
                if (onLogout != null) {
                    ActionSheetItem(
                        label = stringResource(R.string.profile_more_logout),
                        icon = Icons.AutoMirrored.Rounded.Logout,
                        tint = BlexsyColors.Danger,
                        onClick = {
                            showMoreSheet = false
                            showLogoutConfirm = true
                        }
                    )
                } else null
            )
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
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
                        showLogoutConfirm = false
                        onLogout?.invoke()
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
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(
                        text = stringResource(R.string.profile_cancel),
                        color = BlexsyColors.TextSecondary
                    )
                }
            }
        )
    }
}

internal fun formatHistoryDate(
    playedAt: String?,
    todayLabel: String = "Today",
    yesterdayLabel: String = "Yesterday",
    recentLabel: String = "Recent"
): String {
    if (playedAt.isNullOrBlank()) return recentLabel
    val formats = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )
    for (pattern in formats) {
        try {
            val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = sdf.parse(playedAt)
            if (date != null) return formatHistoryCalendar(date, todayLabel, yesterdayLabel)
        } catch (_: Exception) {}
    }
    if (playedAt.length >= 10 && playedAt[4] == '-' && playedAt[7] == '-') {
        try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val date = sdf.parse(playedAt.substring(0, 10))
            if (date != null) return formatHistoryCalendar(date, todayLabel, yesterdayLabel)
        } catch (_: Exception) {}
    }
    playedAt.toLongOrNull()?.let { millis ->
        return formatHistoryCalendar(java.util.Date(millis), todayLabel, yesterdayLabel)
    }
    return recentLabel
}

private fun formatHistoryCalendar(
    date: java.util.Date,
    todayLabel: String,
    yesterdayLabel: String
): String {
    val todayStart = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val yesterdayStart = (todayStart.clone() as java.util.Calendar).apply {
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    if (date.time >= todayStart.timeInMillis) return todayLabel
    if (date.time >= yesterdayStart.timeInMillis) return yesterdayLabel

    val now = java.util.Calendar.getInstance()
    val itemCal = java.util.Calendar.getInstance().apply { time = date }
    val pattern = if (now.get(java.util.Calendar.YEAR) == itemCal.get(java.util.Calendar.YEAR)) "MMM d" else "MMM d, yyyy"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(date)
}

@Composable
private fun ProfileStatItem(
    count: Int,
    label: String
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = formatStatCount(count),
            style = BlexsyTypography.Hero.copy(
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            ),
            color = BlexsyColors.TextPrimary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = BlexsyTypography.Caption.copy(fontSize = 12.sp),
            color = BlexsyColors.TextSecondary
        )
    }
}

@Composable
private fun ProfileStatSkeleton() {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SkeletonLine(modifier = Modifier.width(36.dp), height = 18.dp)
        Spacer(modifier = Modifier.height(4.dp))
        SkeletonLine(modifier = Modifier.width(44.dp), height = 11.dp)
    }
}

// ponytail: format large count like Instagram (e.g. 1.2k, 1M). Upgrade when localization requires full ICU NumberFormatter.
internal fun formatStatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> "${count / 1_000_000}M"
        count >= 10_000 -> "${count / 1_000}k"
        count >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", count / 1000.0)
        else -> count.toString()
    }
}
