package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.blek.blexsy.player.BlexsyAudioPlayer
import com.blek.blexsy.player.PlaybackRepeatMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer

/**
 * Scrubber, transport buttons, volume slider and the lyrics / queue toggles that
 * live under the player panels.
 */
@Composable
internal fun PlayerControls(
    player: BlexsyAudioPlayer,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isBuffering: Boolean,
    repeatMode: PlaybackRepeatMode,
    isOverlayMode: Boolean,
    controlsVisible: Boolean,
    isScrubbing: Boolean,
    onScrubbingChange: (Boolean) -> Unit,
    onInteraction: () -> Unit,
    onShowControls: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    showLyrics: Boolean,
    showQueue: Boolean,
    onShowLyricsChange: (Boolean) -> Unit,
    onShowQueueChange: (Boolean) -> Unit
) {
    val haptic = rememberBlexsyHaptic()
    val maxDur = (if (durationMs > 0) durationMs else 1L).toFloat()
    var dragFraction by remember(durationMs) { mutableFloatStateOf(0f) }
    val currentFraction = if (isScrubbing) dragFraction else if (durationMs > 0) (positionMs.toFloat() / maxDur).coerceIn(0f, 1f) else 0f

    AnimatedVisibility(
        visible = !isOverlayMode || controlsVisible,
        enter = expandVertically(animationSpec = tween(durationMillis = 280)) + fadeIn(animationSpec = tween(durationMillis = 280)),
        exit = shrinkVertically(animationSpec = tween(durationMillis = 280)) + fadeOut(animationSpec = tween(durationMillis = 280))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            onInteraction()
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .pointerInput(maxDur) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            player.seekTo((fraction * maxDur).toLong())
                        }
                    }
                    .pointerInput(maxDur) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                onScrubbingChange(true)
                                dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                onScrubbingChange(false)
                                player.seekTo((dragFraction * maxDur).toLong())
                            },
                            onDragCancel = { onScrubbingChange(false) },
                            onDrag = { change, _ ->
                                change.consume()
                                dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            }
                        )
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                val trackWidthPx = constraints.maxWidth.toFloat()
                val thumbDiameter = 12.dp
                val thumbDiameterPx = with(LocalDensity.current) { thumbDiameter.toPx() }
                val thumbOffsetPx = (currentFraction * (trackWidthPx - thumbDiameterPx)).coerceIn(0f, trackWidthPx - thumbDiameterPx)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(BlexsyColors.SurfaceContainerHighest.copy(alpha = 0.50f))
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth(currentFraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(BlexsyColors.TextPrimary)
                )

                Box(
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(thumbOffsetPx.toInt(), 0) }
                        .size(thumbDiameter)
                        .clip(CircleShape)
                        .background(BlexsyColors.TextPrimary)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val displayPos = if (isScrubbing) (dragFraction * maxDur).toLong() else positionMs
                Text(text = formatDuration(displayPos), style = BlexsyTypography.Caption)
                Text(text = formatDuration(durationMs), style = BlexsyTypography.Caption)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    haptic.click()
                    player.toggleRepeat()
                }) {
                    Icon(
                        imageVector = if (repeatMode == PlaybackRepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = stringResource(R.string.a11y_repeat),
                        tint = if (repeatMode != PlaybackRepeatMode.OFF) BlexsyColors.Primary else BlexsyColors.TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = {
                        haptic.click()
                        player.previous()
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = stringResource(R.string.a11y_previous),
                        tint = BlexsyColors.TextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                PlayCircleButton(
                    onClick = { player.togglePlayPause() },
                    size = 64,
                    isPlaying = isPlaying,
                    isLoading = isBuffering
                )

                IconButton(
                    onClick = {
                        haptic.click()
                        player.next()
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.a11y_next),
                        tint = BlexsyColors.TextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(onClick = onOpenSleepTimer) {
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = stringResource(R.string.player_sleep_timer),
                        tint = if (SleepTimer.isActive) BlexsyColors.Primary else BlexsyColors.TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            VolumeSlider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Material 3 Expressive: connected toggle button group with shape morphing
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalToggleButton(
                    checked = showLyrics,
                    onCheckedChange = {
                        haptic.click()
                        onShowLyricsChange(it)
                        onShowControls()
                        if (it) onShowQueueChange(false)
                    },
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                ) {
                    Text(stringResource(R.string.player_lyrics))
                }

                FilledTonalToggleButton(
                    checked = showQueue,
                    onCheckedChange = {
                        haptic.click()
                        onShowQueueChange(it)
                        onShowControls()
                        if (it) onShowLyricsChange(false)
                    },
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                ) {
                    Text(stringResource(R.string.player_queue))
                }
            }
        }
    }
}
