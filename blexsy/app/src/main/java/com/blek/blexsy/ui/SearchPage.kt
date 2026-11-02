package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blek.blexsy.model.Genre
import com.blek.blexsy.model.Track

/**
 * Full-screen search page opened from the Search tab. Owns the keyboard and the focus
 * so the tab behind it can stay a plain browse surface.
 */
@Composable
internal fun SearchPage(
    state: BlexsySearchState,
    contentBottomPadding: Dp,
    isLoading: Boolean,
    genres: List<Genre>,
    favorites: List<Track>,
    activeTrack: Track?,
    onClose: () -> Unit,
    onGenreClick: (Genre) -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onOpenArtist: (String) -> Unit,
    onTrackOptions: (Track, List<Track>) -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BlexsyColors.Background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BlexsyColors.Background)
                .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = BlexsyColors.TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            SearchField(
                value = state.query,
                onValueChange = state::submit,
                onClear = state::clearQuery,
                onSearch = { state.submit(state.query, immediate = true) },
                modifier = Modifier.weight(1f),
                focusRequester = focusRequester
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentBottomPadding)
            ) {
                if (state.query.isBlank()) {
                    searchHistorySection(
                        history = state.history,
                        onHistoryClick = { state.submit(it, immediate = true) },
                        onClearHistory = state::clearHistory,
                        onRemoveHistory = state::removeHistory
                    )
                } else {
                    searchResultsSection(
                        query = state.query,
                        results = state.results,
                        artists = state.artists,
                        isSearching = state.isSearching,
                        favorites = favorites,
                        activeTrack = activeTrack,
                        onPlay = onPlay,
                        onToggleFavorite = onToggleFavorite,
                        onOpenArtist = onOpenArtist,
                        onTrackOptions = onTrackOptions
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                bottomBar()
            }
        }
    }
}
