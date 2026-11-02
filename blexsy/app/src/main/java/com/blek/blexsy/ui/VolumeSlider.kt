package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
/**
 * Spotify-styled volume seekbar connected to the system AudioManager stream.
 */
@Composable
fun VolumeSlider(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var currentVolume by remember(audioManager) {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    DisposableEffect(context, audioManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        try {
            context.registerReceiver(receiver, filter)
        } catch (_: Exception) {}
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    val volumeFraction = (currentVolume.toFloat() / maxVolume).coerceIn(0f, 1f)
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val displayFraction = if (isDragging) dragFraction else volumeFraction

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                val newVol = (currentVolume - 1).coerceAtLeast(0)
                if (newVol != currentVolume) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                    currentVolume = newVol
                }
            }
        ) {
            Icon(
                imageVector = if (currentVolume == 0) Icons.AutoMirrored.Rounded.VolumeMute else Icons.AutoMirrored.Rounded.VolumeDown,
                contentDescription = stringResource(R.string.a11y_volume_down),
                tint = BlexsyColors.TextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(maxVolume) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val targetVol = (fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                        if (targetVol != currentVolume) {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            currentVolume = targetVol
                        }
                    }
                }
                .pointerInput(maxVolume) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            isDragging = false
                            val targetVol = (dragFraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            currentVolume = targetVol
                        },
                        onDragCancel = { isDragging = false },
                        onDrag = { change, _ ->
                            change.consume()
                            val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            dragFraction = frac
                            val targetVol = (frac * maxVolume).roundToInt().coerceIn(0, maxVolume)
                            if (targetVol != currentVolume) {
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                currentVolume = targetVol
                            }
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val trackWidthPx = constraints.maxWidth.toFloat()
            val thumbDiameter = 12.dp
            val thumbDiameterPx = with(LocalDensity.current) { thumbDiameter.toPx() }
            val thumbOffsetPx = (displayFraction * (trackWidthPx - thumbDiameterPx)).coerceIn(0f, trackWidthPx - thumbDiameterPx)

            // Inactive track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BlexsyColors.SurfaceContainerHighest)
            )

            // Active track
            Box(
                modifier = Modifier
                    .fillMaxWidth(displayFraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BlexsyColors.TextPrimary)
            )

            // Thumb
            Box(
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(thumbOffsetPx.toInt(), 0) }
                    .size(thumbDiameter)
                    .clip(CircleShape)
                    .background(BlexsyColors.TextPrimary)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(
            onClick = {
                val newVol = (currentVolume + 1).coerceAtMost(maxVolume)
                if (newVol != currentVolume) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                    currentVolume = newVol
                }
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = stringResource(R.string.a11y_volume_up),
                tint = BlexsyColors.TextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
