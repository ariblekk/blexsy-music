package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.Genre
import com.blek.blexsy.model.Track

/**
 * List builders shared by the Search tab (display mode) and the full-screen search page,
 * so both surfaces show the exact same recent-search and browse rows.
 */

internal fun LazyListScope.searchHistorySection(
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistory: (String) -> Unit = {}
) {
    if (history.isEmpty()) return

    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_recent_digs),
                style = BlexsyTypography.SectionTitle.copy(fontSize = 18.sp),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClearHistory) {
                Text(
                    stringResource(R.string.search_clear_history),
                    color = BlexsyColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    items(history.take(6)) { item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onHistoryClick(item) }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = BlexsyColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = item,
                style = BlexsyTypography.Body,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onRemoveHistory(item) }
                    .padding(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.a11y_clear),
                    tint = BlexsyColors.TextTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

internal fun LazyListScope.browseAllSection(
    genres: List<Genre>,
    onGenreClick: (Genre) -> Unit
) {
    item {
        SectionHeader(
            kicker = stringResource(R.string.search_kicker_discover),
            title = stringResource(R.string.search_title_browse_all)
        )
    }
    items(genres.chunked(2)) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            row.forEach { genre ->
                val globalIndex = genres.indexOf(genre)
                CategoryTile(
                    name = genre.name,
                    index = globalIndex,
                    modifier = Modifier
                        .weight(1f)
                        .height(104.dp),
                    onClick = { onGenreClick(genre) }
                )
            }
            if (row.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

internal fun matchesArtistQuery(artistName: String, query: String): Boolean {
    val q = query.trim().lowercase()
    val a = artistName.trim().lowercase()
    if (q.length < 2 || a.isBlank()) return false
    if (a == q || a.startsWith(q) || q.startsWith(a) || a.contains(q)) return true

    val artistWords = a.split(Regex("[\\s,.-]+")).filter { it.isNotBlank() }
    if (artistWords.any { it == q || (q.length >= 3 && it.startsWith(q)) }) return true

    val queryWords = q.split(Regex("[\\s,.-]+")).filter { it.isNotBlank() }
    return queryWords.isNotEmpty() && queryWords.all { qw -> artistWords.any { aw -> aw.startsWith(qw) } }
}

internal fun filterDisplayedArtists(
    query: String,
    artists: List<ArtistSummary>,
    tracks: List<Track>
): List<ArtistSummary> {
    val trimmedQuery = query.trim().lowercase()
    val topTrack = tracks.firstOrNull()
    val topTrackTitle = topTrack?.title?.trim()?.lowercase()
    val topTrackArtist = topTrack?.artist?.trim()?.lowercase()

    val isTopTrackTitleMatch = topTrackTitle != null && (
        topTrackTitle == trimmedQuery ||
        topTrackTitle.startsWith(trimmedQuery) ||
        trimmedQuery.startsWith(topTrackTitle)
    )
    val isTopTrackArtistMatch = topTrackArtist != null && matchesArtistQuery(topTrackArtist, query)

    if (isTopTrackTitleMatch && !isTopTrackArtistMatch) {
        return emptyList()
    }

    return artists.filter { matchesArtistQuery(it.name, query) }
}

internal fun LazyListScope.searchResultsSection(
    query: String,
    results: List<Track>,
    artists: List<ArtistSummary> = emptyList(),
    isSearching: Boolean,
    favorites: List<Track>,
    activeTrack: Track?,
    onPlay: (Track, List<Track>) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onOpenArtist: (String) -> Unit,
    onTrackOptions: (Track, List<Track>) -> Unit
) {
    if (isSearching) {
        item { SearchResultsSkeleton() }
        return
    }

    val displayedArtists = filterDisplayedArtists(query, artists, results)

    if (results.isEmpty() && displayedArtists.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.search_zero_hits, query),
                    style = BlexsyTypography.BodyMuted
                )
            }
        }
        return
    }

    if (displayedArtists.isNotEmpty()) {
        item {
            Text(
                text = if (displayedArtists.size == 1) stringResource(R.string.search_artist) else stringResource(R.string.search_artists),
                style = BlexsyTypography.SectionTitle.copy(fontSize = 18.sp),
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            )
        }
        items(displayedArtists) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenArtist(artist.id.ifBlank { artist.name }) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = artist.image,
                    contentDescription = artist.name,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(BlexsyColors.SurfaceContainerHigh),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artist.name,
                        style = BlexsyTypography.Hero.copy(fontSize = 17.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = artist.subscribers?.takeIf { it.isNotBlank() } ?: stringResource(R.string.search_artist),
                        style = BlexsyTypography.Caption.copy(color = BlexsyColors.TextSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = null,
                    tint = BlexsyColors.TextTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    } else {
        val topTrack = results.firstOrNull()
        val topTrackTitle = topTrack?.title?.trim()?.lowercase()
        val topTrackArtist = topTrack?.artist?.trim()?.lowercase()
        val trimmedQuery = query.trim().lowercase()

        val isTopTrackTitleMatch = topTrackTitle != null && (
            topTrackTitle == trimmedQuery ||
            topTrackTitle.startsWith(trimmedQuery) ||
            trimmedQuery.startsWith(topTrackTitle)
        )
        val isTopTrackArtistMatch = topTrackArtist != null && matchesArtistQuery(topTrackArtist, query)
        val isSongTitleSearch = isTopTrackTitleMatch && !isTopTrackArtistMatch

        val topArtistName = topTrack?.artist?.takeIf { it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true) }
        val topArtistRef = topTrack?.primaryArtistRef
        val isArtistMatch = !isSongTitleSearch && topArtistName != null && topArtistRef != null && matchesArtistQuery(topArtistName, query)

        if (isArtistMatch) {
            item {
                Text(
                    text = stringResource(R.string.search_artist),
                    style = BlexsyTypography.SectionTitle.copy(fontSize = 18.sp),
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenArtist(topArtistRef) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = topTrack.highResImage ?: topTrack.image,
                        contentDescription = topArtistName,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(BlexsyColors.SurfaceContainerHigh),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = topArtistName,
                            style = BlexsyTypography.Hero.copy(fontSize = 17.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.search_artist),
                            style = BlexsyTypography.Caption.copy(color = BlexsyColors.TextSecondary)
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                        contentDescription = null,
                        tint = BlexsyColors.TextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }

    if (results.isNotEmpty()) {
        item {
            Text(
                text = stringResource(R.string.search_tracks),
                style = BlexsyTypography.SectionTitle.copy(fontSize = 18.sp),
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            )
        }
        itemsIndexed(results) { index, track ->
            TrackTile(
                track = track,
                index = index,
                showArtwork = true,
                showRank = false,
                isPlayingThis = activeTrack?.id == track.id,
                isFavorite = favorites.any { it.id == track.id },
                onClick = { onPlay(track, emptyList()) },
                onToggleFavorite = { onToggleFavorite(track) },
                onOpenArtist = onOpenArtist,
                onMore = { onTrackOptions(track, emptyList()) }
            )
        }
    }
}

/** Read-only search bar used on the Search tab; tapping it opens the search page. */
@Composable
internal fun SearchBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Color(0xFF282828))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = BlexsyColors.TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.search_bar_placeholder),
            style = BlexsyTypography.Body.copy(color = Color(0xFF666666)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Editable search field used on the full-screen search page. */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val keyboard = LocalSoftwareKeyboardController.current
    BlexsyInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = stringResource(R.string.search_field_placeholder),
        shape = InputShape.Pill,
        textStyle = BlexsyTypography.Body.copy(color = Color.White, fontSize = 15.sp),
        modifier = modifier,
        focusRequester = focusRequester,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            onSearch?.invoke()
        }),
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = BlexsyColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingContent = if (value.isNotEmpty()) {
            {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = onClear),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.a11y_clear),
                        tint = BlexsyColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else null
    )
}
