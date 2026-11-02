package com.blek.blexsy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.blek.blexsy.R
import com.blek.blexsy.model.LyricsResult
import com.blek.blexsy.model.Track
import com.blek.blexsy.player.BlexsyAudioPlayer

/** Lyrics panel of the full player, with the floating track card on top. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlayerLyricsView(
    track: Track,
    player: BlexsyAudioPlayer,
    lyrics: LyricsResult?,
    isLoading: Boolean,
    positionMs: Long,
    isPlaying: Boolean,
    showTranslation: Boolean,
    translatedLyrics: Map<Int, String>,
    isTranslating: Boolean,
    isKeepAwake: Boolean,
    controlsVisible: Boolean,
    onShowControls: () -> Unit,
    onHideControls: () -> Unit,
    onToggleKeepAwake: () -> Unit,
    onToggleTranslation: () -> Unit,
    onCoverClick: () -> Unit,
    onMoreClick: () -> Unit,
    onArtistClick: (() -> Unit)?,
    dominantColor: Color = Color.Transparent,
    contentScrollConnection: NestedScrollConnection,
    sharedScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    if (controlsVisible) onHideControls() else onShowControls()
                }
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SyncedLyricsView(
                lyrics = lyrics,
                isLoading = isLoading,
                currentPositionMs = positionMs,
                isPlaying = isPlaying,
                onSeek = {
                    onShowControls()
                    player.seekTo(it)
                },
                showTranslation = showTranslation,
                translatedLines = translatedLyrics,
                isTranslating = isTranslating,
                contentPadding = PaddingValues(top = 92.dp, bottom = 88.dp),
                isKeepAwake = isKeepAwake,
                onToggleKeepAwake = onToggleKeepAwake,
                onToggleTranslation = onToggleTranslation,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(contentScrollConnection)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            PlayerHeaderLabel(
                text = stringResource(R.string.player_header_lyrics),
                sharedScope = sharedScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
            PlayerGlassTrackCard(
                track = track,
                dominantColor = dominantColor,
                sharedScope = sharedScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onCoverClick = onCoverClick,
                onMoreClick = onMoreClick,
                onArtistClick = onArtistClick
            )
        }
    }
}
