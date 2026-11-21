package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.BuildConfig
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.model.AlbumSummary
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.toHighResArtwork
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

private enum class LibraryFilterTab(val label: String) {
    ALL("All"),
    CRATES("Crates"),
    ALBUMS("Albums"),
    ARTISTS("Artists")
}

@Composable
internal fun LibraryTab(
    contentTopPadding: androidx.compose.ui.unit.Dp,
    contentBottomPadding: androidx.compose.ui.unit.Dp,
    isLoading: Boolean,
    favorites: List<Track>,
    playlists: List<Playlist>,
    savedAlbums: List<AlbumSummary> = emptyList(),
    followedArtists: List<ArtistSummary> = emptyList(),
    onOpenLiked: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {}
) {
    if (isLoading) {
        LibrarySkeleton(
            topPadding = contentTopPadding,
            bottomPadding = contentBottomPadding
        )
        return
    }

    val layout = rememberBlexsyLayoutSpec()
    val crateColumns = if (!layout.isTablet) 1 else if (layout.isExpanded) 3 else 2

    val availableTabs = remember(savedAlbums.isNotEmpty(), followedArtists.isNotEmpty()) {
        buildList {
            add(LibraryFilterTab.ALL)
            add(LibraryFilterTab.CRATES)
            if (savedAlbums.isNotEmpty()) add(LibraryFilterTab.ALBUMS)
            if (followedArtists.isNotEmpty()) add(LibraryFilterTab.ARTISTS)
        }
    }
    var selectedFilterIndex by remember { mutableIntStateOf(0) }
    val activeFilter = availableTabs.getOrElse(selectedFilterIndex) { LibraryFilterTab.ALL }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = contentTopPadding, bottom = contentBottomPadding)
    ) {
        if (availableTabs.size > 2) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BlexsyTabs(
                        tabs = availableTabs.map { it.label },
                        selectedIndex = availableTabs.indexOf(activeFilter).coerceAtLeast(0),
                        onSelect = { selectedFilterIndex = it },
                        fillMaxWidth = false
                    )
                }
            }
        }

        val showCrates = activeFilter == LibraryFilterTab.ALL || activeFilter == LibraryFilterTab.CRATES
        val showAlbums = (activeFilter == LibraryFilterTab.ALL || activeFilter == LibraryFilterTab.ALBUMS) && savedAlbums.isNotEmpty()
        val showArtists = (activeFilter == LibraryFilterTab.ALL || activeFilter == LibraryFilterTab.ARTISTS) && followedArtists.isNotEmpty()

        if (showCrates) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLiked)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF6A4BA8), Color(0xFF3B2A5C))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = IcHeartFilled,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.library_liked_bangers),
                            style = BlexsyTypography.Title.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.profile_crate_bangers, favorites.size),
                            style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp)
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    kicker = stringResource(R.string.library_kicker_stash),
                    title = stringResource(R.string.library_title_crates)
                )
            }

            if (playlists.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.library_crates_empty),
                        style = BlexsyTypography.BodyMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else if (crateColumns > 1) {
                items(playlists.chunked(crateColumns)) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (playlist in row) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenPlaylist(playlist) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverImage(
                                    url = playlist.coverImage,
                                    size = 56,
                                    shapeRadius = 6
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.name,
                                        style = BlexsyTypography.Title.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.profile_crate_bangers, playlist.tracks.size),
                                        style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp)
                                    )
                                }
                            }
                        }
                        repeat(crateColumns - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(playlists) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPlaylist(playlist) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverImage(
                            url = playlist.coverImage,
                            size = 52,
                            shapeRadius = 4
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = BlexsyTypography.Title.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.profile_crate_bangers, playlist.tracks.size),
                                style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp)
                            )
                        }
                    }
                }
            }
        }

        if (showAlbums) {
            item {
                SectionHeader(
                    kicker = "SAVED ALBUMS",
                    title = "Saved Albums"
                )
            }
            if (crateColumns > 1) {
                items(savedAlbums.chunked(crateColumns)) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (album in row) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenAlbum(album.id) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverImage(
                                    url = album.image,
                                    size = 56,
                                    shapeRadius = 6
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = album.name,
                                        style = BlexsyTypography.Title.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = listOfNotNull(album.artist, album.year).joinToString(" • ").ifBlank { album.type },
                                        style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        repeat(crateColumns - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(savedAlbums, key = { "saved-album:${it.id}" }) { album ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenAlbum(album.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverImage(
                            url = album.image,
                            size = 52,
                            shapeRadius = 4
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = album.name,
                                style = BlexsyTypography.Title.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = listOfNotNull(album.artist, album.year).joinToString(" • ").ifBlank { album.type },
                                style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (showArtists) {
            item {
                SectionHeader(
                    kicker = "FOLLOWING",
                    title = "Followed Artists"
                )
            }
            if (crateColumns > 1) {
                items(followedArtists.chunked(crateColumns)) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (artist in row) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { artist.channelId?.let(onOpenArtist) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverImage(
                                    url = artist.image,
                                    size = 56,
                                    shapeRadius = 28
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = artist.name,
                                        style = BlexsyTypography.Title.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = artist.subscribers ?: stringResource(R.string.search_artist),
                                        style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        repeat(crateColumns - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(followedArtists, key = { "followed-artist:${it.id}" }) { artist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { artist.channelId?.let(onOpenArtist) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverImage(
                            url = artist.image,
                            size = 52,
                            shapeRadius = 26
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = artist.name,
                                style = BlexsyTypography.Title.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = artist.subscribers ?: stringResource(R.string.search_artist),
                                style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}
