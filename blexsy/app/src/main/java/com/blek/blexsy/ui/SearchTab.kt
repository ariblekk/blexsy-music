package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.model.Genre

/**
 * Search tab in display mode, matching Spotify: the tab itself is just a showcase of
 * recent searches and browse categories. Tapping the search bar opens [SearchPage],
 * where the actual typing and results happen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SearchTab(
    contentBottomPadding: Dp,
    searchHistory: List<String>,
    isLoading: Boolean,
    genres: List<Genre>,
    onOpenSearch: () -> Unit,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveHistory: (String) -> Unit = {},
    onGenreClick: (Genre) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BlexsyColors.Background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = contentBottomPadding)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BlexsyColors.Background)
                    .height(64.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.search_title),
                    style = BlexsyTypography.SectionTitle.copy(
                        fontFamily = DeezerExtraBoldFont,
                        fontSize = 24.sp,
                        lineHeight = 32.sp
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        stickyHeader {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BlexsyColors.Background)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                SearchBarButton(onClick = onOpenSearch)
            }
        }

        if (isLoading) {
            item { BrowseSkeleton() }
        } else {
            searchHistorySection(
                history = searchHistory,
                onHistoryClick = onHistoryClick,
                onClearHistory = onClearHistory,
                onRemoveHistory = onRemoveHistory
            )
            browseAllSection(
                genres = genres,
                onGenreClick = onGenreClick
            )
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}
