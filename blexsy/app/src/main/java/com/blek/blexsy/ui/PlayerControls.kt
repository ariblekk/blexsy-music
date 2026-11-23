package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.blek.blexsy.player.BlexsyAudioPlayer
import com.blek.blexsy.player.PlaybackRepeatMode
import kotlin.math.abs
import kotlin.math.sin

/**
 * SoundCloud-style waveform progress scrubber, transport buttons, volume slider and the
 * lyrics / queue toggles that live under the player panels.
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
    val currentTrack by player.currentTrack.collectAsState()
    val maxDur = (if (durationMs > 0) durationMs else 1L).toFloat()
    var dragFraction by remember(durationMs) { mutableFloatStateOf(0f) }
    val currentFraction = if (isScrubbing) dragFraction else if (durationMs > 0) (positionMs.toFloat() / maxDur).coerceIn(0f, 1f) else 0f

    // Generate a deterministic, authentic SoundCloud-style track waveform signature per song
    val waveformBars = remember(currentTrack?.id, currentTrack?.displayTitle) {
        buildTrackWaveformProfile(
            seedKey = "${currentTrack?.id.orEmpty()}-${currentTrack?.displayTitle.orEmpty()}-${currentTrack?.artist.orEmpty()}",
            barCount = 68
        )
    }

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

            // SoundCloud-style Waveform Progress Scrubber
            SoundCloudWaveformScrubber(
                bars = waveformBars,
                progressFraction = currentFraction,
                isScrubbing = isScrubbing,
                playedColor = BlexsyColors.Primary,
                unplayedColor = BlexsyColors.TextPrimary.copy(alpha = 0.24f),
                onTapFraction = { fraction ->
                    haptic.tick()
                    player.seekTo((fraction * maxDur).toLong())
                },
                onDragStartFraction = { fraction ->
                    haptic.tick()
                    onScrubbingChange(true)
                    dragFraction = fraction
                },
                onDragFraction = { fraction ->
                    val prevBucket = (dragFraction * waveformBars.size).toInt()
                    val newBucket = (fraction * waveformBars.size).toInt()
                    if (newBucket != prevBucket) {
                        haptic.tick()
                    }
                    dragFraction = fraction
                },
                onDragEnd = {
                    onScrubbingChange(false)
                    player.seekTo((dragFraction * maxDur).toLong())
                },
                onDragCancel = {
                    onScrubbingChange(false)
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val displayPos = if (isScrubbing) (dragFraction * maxDur).toLong() else positionMs
                Text(
                    text = formatDuration(displayPos),
                    style = BlexsyTypography.Caption.copy(
                        fontFamily = MonoFont,
                        color = if (isScrubbing) BlexsyColors.Primary else BlexsyColors.TextSecondary
                    )
                )
                Text(
                    text = formatDuration(durationMs),
                    style = BlexsyTypography.Caption.copy(fontFamily = MonoFont)
                )
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
                        imageVector = if (repeatMode == PlaybackRepeatMode.ONE) IcRepeatOnce else IcRepeat,
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
                        imageVector = IcSkipBack,
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
                        imageVector = IcSkipForward,
                        contentDescription = stringResource(R.string.a11y_next),
                        tint = BlexsyColors.TextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(onClick = onOpenSleepTimer) {
                    Icon(
                        imageVector = IcTimer,
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
                            imageVector = IcSubtitles,
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
                            imageVector = IcQueue,
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

/**
 * SoundCloud-inspired dual-layer (upper main waveform + lower mirrored reflection) progress bar.
 */
@Composable
private fun SoundCloudWaveformScrubber(
    bars: FloatArray,
    progressFraction: Float,
    isScrubbing: Boolean,
    playedColor: Color,
    unplayedColor: Color,
    onTapFraction: (Float) -> Unit,
    onDragStartFraction: (Float) -> Unit,
    onDragFraction: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .padding(vertical = 4.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onTapFraction(fraction)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onDragStartFraction(fraction)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                    onDrag = { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onDragFraction(fraction)
                    }
                )
            }
    ) {
        val count = bars.size
        if (count == 0) return@Canvas

        val totalW = size.width
        val totalH = size.height
        val slotW = totalW / count
        val barW = (slotW * 0.56f).coerceIn(2.dp.toPx(), 4.5.dp.toPx())
        val corner = CornerRadius(barW / 2f, barW / 2f)

        // SoundCloud split: 66% upper main peak, 4% gap, 30% lower reflection
        val baselineY = totalH * 0.68f
        val gapPx = 1.5.dp.toPx()
        val maxUpperH = (baselineY - gapPx * 0.5f).coerceAtLeast(4f)
        val maxLowerH = (totalH - baselineY - gapPx * 0.5f).coerceAtLeast(2f)

        val playheadX = progressFraction.coerceIn(0f, 1f) * totalW

        for (i in 0 until count) {
            val x = i * slotW + (slotW - barW) / 2f
            val barCenter = x + barW / 2f
            val amp = bars[i].coerceIn(0.14f, 1f)

            // Slight magnification near the playhead when scrubbing
            val scrubBoost = if (isScrubbing) {
                val distPx = abs(barCenter - playheadX)
                val radiusPx = slotW * 4.5f
                if (distPx < radiusPx) 1f + 0.18f * (1f - distPx / radiusPx) else 1f
            } else 1f

            val upperH = (maxUpperH * amp * scrubBoost).coerceIn(barW, maxUpperH)
            val lowerH = (maxLowerH * amp * 0.72f * scrubBoost).coerceIn(barW * 0.7f, maxLowerH)

            val isPlayed = barCenter <= playheadX
            val upperColor = if (isPlayed) playedColor else unplayedColor
            val lowerColor = if (isPlayed) playedColor.copy(alpha = 0.42f) else unplayedColor.copy(alpha = 0.13f)

            // Upper main bar
            drawRoundRect(
                color = upperColor,
                topLeft = Offset(x, baselineY - gapPx * 0.5f - upperH),
                size = Size(barW, upperH),
                cornerRadius = corner
            )

            // Lower SoundCloud reflection bar
            drawRoundRect(
                color = lowerColor,
                topLeft = Offset(x, baselineY + gapPx * 0.5f),
                size = Size(barW, lowerH),
                cornerRadius = corner
            )
        }
    }
}

/**
 * Builds a realistic song waveform envelope (intro -> verse -> chorus peaks -> bridge -> outro)
 * seeded deterministically from the track ID & title so each song has its own consistent waveform.
 */
private fun buildTrackWaveformProfile(seedKey: String, barCount: Int): FloatArray {
    var hash = 2166136261L
    for (ch in seedKey) {
        hash = (hash xor ch.code.toLong()) * 16777619L
    }

    fun nextRand(): Float {
        hash = hash xor (hash shl 13)
        hash = hash xor (hash ushr 7)
        hash = hash xor (hash shl 17)
        return ((hash and 0x7FFFFFFFL).toFloat() / 0x7FFFFFFFL.toFloat())
    }

    val p1 = nextRand() * 6.28f
    val p2 = nextRand() * 6.28f
    val p3 = nextRand() * 6.28f

    return FloatArray(barCount) { idx ->
        val t = idx.toFloat() / (barCount - 1).coerceAtLeast(1).toFloat()
        // Song structure envelope (quieter intro & outro, energetic sections)
        val introOutroEnv = when {
            t < 0.08f -> 0.35f + (t / 0.08f) * 0.65f
            t > 0.90f -> 0.30f + ((1f - t) / 0.10f) * 0.70f
            else -> 1f
        }
        val sectionWave = 0.62f +
            0.22f * sin(t * 14f + p1) +
            0.16f * sin(t * 31f + p2) +
            0.10f * sin(t * 57f + p3)
        val transient = 0.72f + 0.48f * nextRand()
        (introOutroEnv * sectionWave * transient).coerceIn(0.16f, 1.0f)
    }
}
