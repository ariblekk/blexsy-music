package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.model.Track
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Timer

/** Overflow menu for the currently playing track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerMoreSheet(
    track: Track,
    isFavorite: Boolean,
    canSaveToLibrary: Boolean,
    artistId: String?,
    albumId: String?,
    sleepTimerActive: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onViewArtist: (() -> Unit)?,
    onViewAlbum: (() -> Unit)?,
    onShowCredits: () -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowEqualizer: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    // Hides this sheet first so the caller can chain its own dismissal / navigation.
    val closeThen: (() -> Unit) -> Unit = { action ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BlexsyColors.Background,
        contentColor = BlexsyColors.TextPrimary,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = BlexsyColors.TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            PlayerSheetHeader(track = track)

            Spacer(modifier = Modifier.height(14.dp))

            // Group 1: playback & library
            val playbackItems = listOfNotNull(
                if (canSaveToLibrary) {
                    SheetOptionItem(
                        icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        label = if (isFavorite) stringResource(R.string.player_remove_favorite) else stringResource(R.string.player_save_favorite),
                        tint = if (isFavorite) BlexsyColors.Primary else BlexsyColors.TextPrimary,
                        onClick = onToggleFavorite
                    )
                } else null,
                if (onAddToPlaylist != null) {
                    SheetOptionItem(
                        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        label = stringResource(R.string.player_add_to_playlist),
                        onClick = { closeThen(onAddToPlaylist) }
                    )
                } else null,
                SheetOptionItem(
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    label = stringResource(R.string.player_queue_next),
                    onClick = { closeThen(onPlayNext) }
                ),
                SheetOptionItem(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    label = stringResource(R.string.player_toss_queue),
                    onClick = { closeThen(onAddToQueue) }
                ),
                SheetOptionItem(
                    icon = Icons.Rounded.Radio,
                    label = stringResource(R.string.player_start_radio),
                    onClick = { closeThen(onStartRadio) }
                )
            )
            GroupedOptionCard(items = playbackItems)

            // Group 2: discovery & info
            val infoItems = listOfNotNull(
                if (onViewArtist != null && (artistId != null || track.artist.isNotBlank())) {
                    SheetOptionItem(
                        icon = Icons.Rounded.Person,
                        label = stringResource(R.string.player_view_artist),
                        onClick = { closeThen(onViewArtist) }
                    )
                } else null,
                if (onViewAlbum != null && albumId != null) {
                    SheetOptionItem(
                        icon = Icons.Rounded.Album,
                        label = stringResource(R.string.player_view_album),
                        onClick = { closeThen(onViewAlbum) }
                    )
                } else null,
                SheetOptionItem(
                    icon = Icons.Rounded.Info,
                    label = stringResource(R.string.player_track_credits),
                    subtitle = stringResource(R.string.player_credits_subtitle),
                    onClick = { closeThen(onShowCredits) }
                )
            )

            if (infoItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                GroupedOptionCard(items = infoItems)
            }

            // Group 3: utility
            val utilityItems = listOfNotNull(
                if (onShowEqualizer != null) {
                    SheetOptionItem(
                        icon = Icons.Rounded.GraphicEq,
                        label = stringResource(R.string.player_equalizer),
                        subtitle = stringResource(R.string.player_equalizer_subtitle),
                        onClick = { closeThen(onShowEqualizer) }
                    )
                } else null,
                SheetOptionItem(
                    icon = Icons.Rounded.Timer,
                    label = if (sleepTimerActive) stringResource(R.string.player_sleep_timer_active) else stringResource(R.string.player_sleep_timer),
                    tint = if (sleepTimerActive) BlexsyColors.Primary else BlexsyColors.TextPrimary,
                    onClick = { closeThen(onShowSleepTimer) }
                )
            )
            if (utilityItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                GroupedOptionCard(items = utilityItems)
            }
        }
    }
}

/** Cover + title / artist row shared by the player bottom sheets. */
@Composable
internal fun PlayerSheetHeader(track: Track) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(url = track.image, size = 48, shapeRadius = 6)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.displayTitle,
                style = BlexsyTypography.Title.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.artist,
                style = BlexsyTypography.Subtitle.copy(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
