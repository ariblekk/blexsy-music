package com.blek.blexsy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.blek.blexsy.model.QueueTier
import com.blek.blexsy.model.Track
import com.blek.blexsy.player.BlexsyAudioPlayer
import com.blek.blexsy.player.QueueSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu

/**
 * Up-next panel: reorderable queue rows with the "next from" context separator,
 * plus the floating track card.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlayerQueueView(
    player: BlexsyAudioPlayer,
    track: Track,
    queue: List<Track>,
    queueSource: QueueSource,
    radioSeedTrack: Track?,
    radioError: String?,
    isRadioLoading: Boolean,
    dominantColor: Color = Color.Transparent,
    contentScrollConnection: NestedScrollConnection,
    onShowControls: () -> Unit,
    onCoverClick: () -> Unit,
    onMoreClick: () -> Unit,
    onArtistClick: (() -> Unit)?,
    sharedScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val contextTitle by player.contextTitle.collectAsState()
        val currentContextTitle = contextTitle
        var nextId by remember { mutableLongStateOf(1L) }
        var queueEntries by remember {
            mutableStateOf(queue.map { QueueEntry(nextId++, it) })
        }
        var draggedInstanceId by remember { mutableStateOf<Long?>(null) }
        var dragTranslationY by remember { mutableFloatStateOf(0f) }
        var initialDragIndex by remember { mutableIntStateOf(-1) }
        val rowHeightPx = with(LocalDensity.current) { 56.dp.toPx() }

        LaunchedEffect(queue) {
            if (draggedInstanceId == null) {
                val oldEntries = queueEntries.toMutableList()
                queueEntries = queue.map { trackItem ->
                    val match = oldEntries.indexOfFirst { it.track.id == trackItem.id }
                    if (match != -1) {
                        val entry = oldEntries.removeAt(match)
                        entry.copy(track = trackItem)
                    } else {
                        QueueEntry(nextId++, trackItem)
                    }
                }
            }
        }

        if (queueEntries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.queue_empty),
                    style = BlexsyTypography.BodyMuted
                )
            }
        } else {
            val currentQueueIndex = queue.indexOfFirst { it.id == track.id }
            val upcomingEntries = if (currentQueueIndex >= 0) {
                queueEntries.filter { e ->
                    val actual = queue.indexOfFirst { it.id == e.track.id }
                    actual > currentQueueIndex
                }
            } else queueEntries
            val hasUserQueue = upcomingEntries.any { it.track.queueTier == QueueTier.USER_QUEUE }
            val firstContextEntryId = upcomingEntries.firstOrNull { it.track.queueTier != QueueTier.USER_QUEUE }?.instanceId
            val radioLabel = stringResource(R.string.queue_context_radio)
            val playlistLabel = stringResource(R.string.type_playlist)
            val contextName = when {
                queueSource == QueueSource.RADIO -> radioLabel
                !currentContextTitle.isNullOrBlank() -> currentContextTitle
                track.album.isNotBlank() -> track.album
                else -> playlistLabel
            }
            // ponytail: dynamic top offset for radio / playlist subtitle; upgrade to onGloballyPositioned if header content varies further.
            val hasSubtitle = (queueSource == QueueSource.RADIO && radioSeedTrack != null) || !currentContextTitle.isNullOrBlank()
            val listTopPadding = if (hasSubtitle) 116.dp else 96.dp

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(contentScrollConnection),
                contentPadding = PaddingValues(top = listTopPadding, bottom = 44.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val headerTitle = if (hasUserQueue) {
                                stringResource(R.string.queue_up_next)
                            } else {
                                stringResource(R.string.queue_next_from, contextName)
                            }
                            Text(
                                text = headerTitle,
                                style = BlexsyTypography.Title.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isRadioLoading) {
                                Spacer(modifier = Modifier.width(8.dp))
                                ExpressiveLoadingIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = BlexsyColors.Primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (hasUserQueue) {
                            Text(
                                text = stringResource(R.string.queue_clear),
                                style = BlexsyTypography.Caption.copy(
                                    color = BlexsyColors.Primary,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier
                                    .clickable { player.clearUserQueue() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.playlist_tracks_count, upcomingEntries.size),
                                style = BlexsyTypography.Caption,
                                maxLines = 1
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = queueEntries,
                    key = { _, entry -> entry.instanceId }
                ) { index, entry ->
                    val qTrack = entry.track
                    val actualIndex = queue.indexOfFirst { it.id == qTrack.id }
                    if (currentQueueIndex >= 0 && actualIndex <= currentQueueIndex) return@itemsIndexed
                    if (qTrack.id == track.id) return@itemsIndexed
                    val isBeingDragged = entry.instanceId == draggedInstanceId

                    if (hasUserQueue && entry.instanceId == firstContextEntryId) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.queue_next_from, contextName),
                                style = BlexsyTypography.Title.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isBeingDragged) 10f else 1f)
                            .graphicsLayer {
                                if (isBeingDragged) {
                                    translationY = dragTranslationY
                                    shadowElevation = 8f
                                }
                            }
                            .background(
                                if (isBeingDragged) BlexsyColors.SurfaceContainerHighest else Color.Transparent
                            )
                            .clickable(enabled = draggedInstanceId == null) {
                                onShowControls()
                                val actual = queue.indexOfFirst { it.id == qTrack.id }
                                if (actual >= 0) {
                                    player.playAtIndex(actual)
                                } else {
                                    player.playAtIndex(index)
                                }
                            }
                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverImage(url = qTrack.image, size = 42, shapeRadius = 6)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = qTrack.displayTitle,
                                style = BlexsyTypography.Title.copy(fontSize = 14.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = qTrack.artist,
                                style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Delete button
                        IconButton(
                            onClick = {
                                if (draggedInstanceId == null) {
                                    val actual = queue.indexOfFirst { it.id == qTrack.id }
                                    if (actual >= 0) {
                                        player.removeFromQueue(actual)
                                    } else {
                                        player.removeFromQueue(index)
                                    }
                                }
                            },
                            enabled = draggedInstanceId == null,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.queue_remove_content_desc),
                                tint = BlexsyColors.TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Drag handle
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .pointerInput(entry.instanceId) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggedInstanceId = entry.instanceId
                                            initialDragIndex = queueEntries.indexOfFirst { it.instanceId == entry.instanceId }
                                            dragTranslationY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragTranslationY += dragAmount.y
                                            var currentIdx = queueEntries.indexOfFirst { it.instanceId == draggedInstanceId }
                                            if (currentIdx != -1) {
                                                while (dragTranslationY > rowHeightPx * 0.5f && currentIdx < queueEntries.lastIndex) {
                                                    val mutable = queueEntries.toMutableList()
                                                    val item = mutable.removeAt(currentIdx)
                                                    mutable.add(currentIdx + 1, item)
                                                    queueEntries = mutable
                                                    dragTranslationY -= rowHeightPx
                                                    currentIdx++
                                                }
                                                while (dragTranslationY < -rowHeightPx * 0.5f && currentIdx > 0) {
                                                    val mutable = queueEntries.toMutableList()
                                                    val item = mutable.removeAt(currentIdx)
                                                    mutable.add(currentIdx - 1, item)
                                                    queueEntries = mutable
                                                    dragTranslationY += rowHeightPx
                                                    currentIdx--
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            val finalIdx = queueEntries.indexOfFirst { it.instanceId == draggedInstanceId }
                                            if (initialDragIndex != -1 && finalIdx != -1 && initialDragIndex != finalIdx) {
                                                val fromTrackId = queueEntries.getOrNull(initialDragIndex)?.track?.id
                                                val toTrackId = queueEntries.getOrNull(finalIdx)?.track?.id
                                                val actualFrom = if (fromTrackId != null) queue.indexOfFirst { it.id == fromTrackId } else initialDragIndex
                                                val actualTo = if (toTrackId != null) queue.indexOfFirst { it.id == toTrackId } else finalIdx
                                                if (actualFrom >= 0 && actualTo >= 0 && actualFrom != actualTo) {
                                                    player.reorderQueue(actualFrom, actualTo)
                                                } else {
                                                    player.reorderQueue(initialDragIndex, finalIdx)
                                                }
                                            }
                                            draggedInstanceId = null
                                            dragTranslationY = 0f
                                            initialDragIndex = -1
                                        },
                                        onDragCancel = {
                                            draggedInstanceId = null
                                            dragTranslationY = 0f
                                            initialDragIndex = -1
                                            val oldEntries = queueEntries.toMutableList()
                                            queueEntries = queue.map { trackItem ->
                                                val match = oldEntries.indexOfFirst { it.track.id == trackItem.id }
                                                if (match != -1) oldEntries.removeAt(match).copy(track = trackItem)
                                                else QueueEntry(nextId++, trackItem)
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Menu,
                                contentDescription = stringResource(R.string.a11y_reorder),
                                tint = if (isBeingDragged) BlexsyColors.Primary else BlexsyColors.TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Queue Mode: label + glass card, outside the list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                with(sharedScope) {
                    Text(
                        text = stringResource(if (queueSource == QueueSource.RADIO) R.string.player_header_radio else R.string.player_header_queue).uppercase(),
                        style = BlexsyTypography.Caption.copy(
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "player_header_label"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    )
                }
                if (queueSource == QueueSource.RADIO && radioSeedTrack != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    val seedLabel = if (radioSeedTrack.displayTitle.isNotBlank() && radioSeedTrack.artist.isNotBlank()) {
                        "${radioSeedTrack.displayTitle} • ${radioSeedTrack.artist}"
                    } else {
                        radioSeedTrack.displayTitle.ifBlank { radioSeedTrack.artist }
                    }
                    Text(
                        text = stringResource(R.string.queue_based_on, seedLabel),
                        style = BlexsyTypography.Caption.copy(
                            fontSize = 10.sp,
                            color = BlexsyColors.TextSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (!currentContextTitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    val playlistLabel = if (currentContextTitle.equals("Playlist", ignoreCase = true)) {
                        stringResource(R.string.queue_based_on_playlist)
                    } else {
                        stringResource(R.string.queue_based_on_playlist_name, currentContextTitle)
                    }
                    Text(
                        text = playlistLabel,
                        style = BlexsyTypography.Caption.copy(
                            fontSize = 10.sp,
                            color = BlexsyColors.TextSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (queueSource == QueueSource.RADIO && radioError != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = radioError,
                        style = BlexsyTypography.Caption.copy(
                            fontSize = 10.sp,
                            color = BlexsyColors.Danger
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

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
