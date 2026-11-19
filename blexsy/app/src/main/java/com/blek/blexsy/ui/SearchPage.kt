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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    recentTracks: List<Track> = emptyList(),
    favorites: List<Track>,
    activeTrack: Track?,
    onClose: () -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onOpenArtist: (String) -> Unit,
    onTrackOptions: (Track, List<Track>) -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.query,
                selection = TextRange(state.query.length)
            )
        )
    }

    LaunchedEffect(state.query) {
        if (state.query != textFieldValue.text) {
            textFieldValue = TextFieldValue(
                text = state.query,
                selection = TextRange(state.query.length)
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && (available.y != 0f || available.x != 0f)) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
                return Offset.Zero
            }
        }
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
                    imageVector = IcArrowLeft,
                    contentDescription = stringResource(R.string.action_back),
                    tint = BlexsyColors.TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            SearchField(
                value = textFieldValue,
                onValueChange = { newTfv ->
                    textFieldValue = newTfv
                    state.submit(newTfv.text)
                },
                onClear = {
                    textFieldValue = TextFieldValue("", TextRange.Zero)
                    state.clearQuery()
                },
                onSearch = { state.submit(textFieldValue.text, immediate = true) },
                modifier = Modifier.weight(1f),
                focusRequester = focusRequester
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection),
                contentPadding = PaddingValues(bottom = contentBottomPadding)
            ) {
                if (state.query.isBlank()) {
                    recentPlayedSection(
                        recentTracks = recentTracks,
                        activeTrack = activeTrack,
                        favorites = favorites,
                        onPlay = onPlay,
                        onToggleFavorite = onToggleFavorite,
                        onOpenArtist = onOpenArtist,
                        onTrackOptions = onTrackOptions
                    )
                } else {
                    // Suggestions are the first thing under the field, tap = committed search.
                    searchSuggestionsSection(
                        suggestions = state.suggestions,
                        onSuggestionClick = { suggestion ->
                            textFieldValue = TextFieldValue(
                                text = suggestion,
                                selection = TextRange(suggestion.length)
                            )
                            state.submit(suggestion, immediate = true)
                        }
                    )
                    searchResultsSection(
                        query = state.query,
                        uiState = state.uiState,
                        favorites = favorites,
                        activeTrack = activeTrack,
                        onPlay = onPlay,
                        onToggleFavorite = onToggleFavorite,
                        onOpenArtist = onOpenArtist,
                        onTrackOptions = onTrackOptions,
                        onRetry = state::retry,
                        onLoadMore = state::loadMore
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
