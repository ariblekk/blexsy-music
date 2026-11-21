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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.data.SpotifyBrowseCategory
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.Track
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
                imageVector = IcHistory,
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
                    imageVector = IcClose,
                    contentDescription = stringResource(R.string.a11y_clear),
                    tint = BlexsyColors.TextTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * Autocomplete rows shown while a query is being typed: locally known names that start with
 * what is already typed. Tapping one runs it as a committed search.
 */
internal fun LazyListScope.searchSuggestionsSection(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit
) {
    items(suggestions, key = { "suggestion:$it" }) { item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSuggestionClick(item) }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = IcMagnifyingGlass,
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
        }
    }
}

/**
 * Renders the search shelves: matching artists first, then the songs. Album and playlist
 * shelves are not part of search — the catalog drops them before they reach the UI.
 */
internal fun LazyListScope.searchResultsSection(
    query: String,
    uiState: SearchUiState,
    favorites: List<Track>,
    activeTrack: Track?,
    onPlay: (Track, List<Track>) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onOpenArtist: (String) -> Unit,
    onTrackOptions: (Track, List<Track>) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit
) {
    // A query too short to search yet: nothing to show, and a skeleton would just flicker.
    if (uiState is SearchUiState.Idle) return

    if (uiState is SearchUiState.Loading) {
        item { SearchResultsSkeleton() }
        return
    }

    if (uiState is SearchUiState.Failed) {
        item { SearchFailureState(uiState.message, onRetry) }
        return
    }

    val loaded = uiState as SearchUiState.Loaded

    if (loaded.isEmpty) {
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

    if (loaded.artists.isNotEmpty()) {
        item {
            SearchSectionTitle(
                if (loaded.artists.size == 1) R.string.search_artist else R.string.search_artists
            )
        }
        items(loaded.artists, key = { "artist:${it.id}" }) { artist ->
            SearchResultRow(
                title = artist.name,
                subtitle = artist.subscribers?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.search_artist),
                image = artist.image,
                circle = true,
                onClick = { artist.channelId?.let(onOpenArtist) }
            )
        }
    }

    if (loaded.tracks.isNotEmpty()) {
        item { SearchSectionTitle(R.string.search_tracks) }
        itemsIndexed(loaded.tracks, key = { _, track -> "track:${track.id}" }) { index, track ->
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

        // Sits directly under the last track. Reaching it is what asks for the next page; the
        // effect is keyed on identity so an append that keeps it on screen does not re-trigger.
        item(key = "search-footer") {
            SearchResultsFooter(
                nextOffset = loaded.nextOffset,
                appending = loaded.appending,
                appendFailed = loaded.appendFailed,
                refreshing = loaded.refreshing,
                onLoadMore = onLoadMore
            )
        }
    }
}

@Composable
private fun SearchSectionTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = BlexsyTypography.SectionTitle.copy(fontSize = 18.sp),
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
    )
}

/** One artist/album/playlist result: artwork, name, subtitle, and a tap that opens it. */
@Composable
private fun SearchResultRow(
    title: String,
    subtitle: String?,
    image: String?,
    circle: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = image,
            contentDescription = title,
            modifier = Modifier
                .size(56.dp)
                .clip(if (circle) CircleShape else RoundedCornerShape(6.dp))
                .background(BlexsyColors.SurfaceContainerHigh),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = BlexsyTypography.Hero.copy(fontSize = 17.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = BlexsyTypography.Caption.copy(color = BlexsyColors.TextSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = IcCaretRight,
            contentDescription = null,
            tint = BlexsyColors.TextTertiary,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * Bottom-of-list marker for paginated results. Retry and load-more are the same call: `loadMore`
 * ignores an offset it has already requested, so the failure branch only has to forget it first.
 */
@Composable
private fun SearchResultsFooter(
    nextOffset: Int?,
    appending: Boolean,
    appendFailed: Boolean,
    refreshing: Boolean,
    onLoadMore: () -> Unit
) {
    // Pending means "a next page exists and nothing is in flight" — the one state worth fetching
    // from. `appendFailed` deliberately does not qualify, so a failing server is never hammered.
    if (nextOffset != null && !appending && !appendFailed) {
        LaunchedEffect(nextOffset) { onLoadMore() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            appendFailed -> BlexsyButton(
                text = stringResource(R.string.search_failed_retry),
                onClick = onLoadMore,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                leadingIcon = IcRefresh
            )

            // A refresh has no next page yet — saying "that's everything" here would be a lie.
            refreshing || nextOffset != null || appending -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = BlexsyColors.TextTertiary
            )

            else -> Text(
                text = stringResource(R.string.search_no_more),
                style = BlexsyTypography.Caption.copy(color = BlexsyColors.TextTertiary)
            )
        }
    }
}

/** Shown when a query never reached the server, so a retry is worth offering. */
@Composable
private fun SearchFailureState(message: String?, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = IcCloudSlash,
                contentDescription = null,
                tint = BlexsyColors.TextTertiary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.search_failed),
                style = BlexsyTypography.BodyMuted,
                textAlign = TextAlign.Center
            )
            if (!message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = BlexsyTypography.Caption.copy(color = BlexsyColors.TextTertiary),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            BlexsyButton(
                text = stringResource(R.string.search_failed_retry),
                onClick = onRetry,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                leadingIcon = IcRefresh
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
            imageVector = IcMagnifyingGlass,
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

internal fun LazyListScope.recentPlayedSection(
    recentTracks: List<Track>,
    activeTrack: Track?,
    favorites: List<Track>,
    onPlay: (Track, List<Track>) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onOpenArtist: (String) -> Unit,
    onTrackOptions: (Track, List<Track>) -> Unit
) {
    if (recentTracks.isEmpty()) return

    item {
        Text(
            text = stringResource(R.string.search_recent_played),
            style = BlexsyTypography.SectionTitle.copy(fontSize = 18.sp),
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
        )
    }

    itemsIndexed(recentTracks, key = { index, track -> "recent:${track.id}:$index" }) { index, track ->
        TrackTile(
            track = track,
            index = index,
            showArtwork = true,
            showRank = false,
            isPlayingThis = activeTrack?.id == track.id,
            isFavorite = favorites.any { it.id == track.id },
            onClick = { onPlay(track, recentTracks) },
            onToggleFavorite = { onToggleFavorite(track) },
            onOpenArtist = onOpenArtist,
            onMore = { onTrackOptions(track, recentTracks) }
        )
    }
}

internal fun LazyListScope.browseCategoriesSection(
    categories: List<SpotifyBrowseCategory>,
    onCategoryClick: (SpotifyBrowseCategory) -> Unit,
    columns: Int = 2
) {
    if (categories.isEmpty()) return
    val colCount = columns.coerceAtLeast(2)

    item {
        Text(
            text = stringResource(R.string.search_title_browse_all),
            style = BlexsyTypography.SectionTitle.copy(fontSize = 18.sp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)
        )
    }

    items(categories.chunked(colCount), key = { pair -> pair.joinToString("-") { it.id } }) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            for (category in row) {
                BrowseCategoryCard(
                    category = category,
                    modifier = Modifier.weight(1f),
                    onClick = { onCategoryClick(category) }
                )
            }
            repeat(colCount - row.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun BrowseCategoryCard(
    category: SpotifyBrowseCategory,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor = remember(category.color) {
        category.color?.let {
            runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
        } ?: Color(0xFF282828)
    }

    Box(
        modifier = modifier
            .height(98.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
    ) {
        if (!category.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = category.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(64.dp)
                    .offset(x = 16.dp, y = 6.dp)
                    .rotate(24f)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
        Text(
            text = category.title,
            style = BlexsyTypography.Hero.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.72f)
                .padding(12.dp)
        )
    }
}

/** Editable search field used on the full-screen search page. */
@Composable
internal fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
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
                imageVector = IcMagnifyingGlass,
                contentDescription = null,
                tint = BlexsyColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingContent = if (value.text.isNotEmpty()) {
            {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = onClear),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = IcClose,
                        contentDescription = stringResource(R.string.a11y_clear),
                        tint = BlexsyColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else null
    )
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
    var tfv by remember(value) {
        mutableStateOf(TextFieldValue(text = value, selection = androidx.compose.ui.text.TextRange(value.length)))
    }
    SearchField(
        value = tfv,
        onValueChange = {
            tfv = it
            onValueChange(it.text)
        },
        onClear = onClear,
        onSearch = onSearch,
        modifier = modifier,
        focusRequester = focusRequester
    )
}
