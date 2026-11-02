package com.blek.blexsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import com.blek.blexsy.model.Track
import kotlinx.coroutines.launch

/**
 * Spotify-style sleep timer bottom sheet.
 * Stops audio after a duration or at the end of the current track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimerDialog(
    remainingMs: Long,
    track: Track? = null,
    onDismiss: () -> Unit,
    onPause: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    val closeThen: (() -> Unit) -> Unit = { action ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    val timerOptions = listOf(
        5 to stringResource(R.string.sleep_timer_minutes, 5),
        10 to stringResource(R.string.sleep_timer_minutes, 10),
        15 to stringResource(R.string.sleep_timer_minutes, 15),
        30 to stringResource(R.string.sleep_timer_minutes, 30),
        45 to stringResource(R.string.sleep_timer_minutes, 45),
        60 to stringResource(R.string.sleep_timer_1_hour)
    )

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
            if (track != null) {
                PlayerSheetHeader(track = track)
                Spacer(modifier = Modifier.height(14.dp))
            }

            Text(
                text = stringResource(R.string.sleep_timer_stop_audio_in),
                style = BlexsyTypography.Title.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BlexsyColors.SurfaceContainerHigh)
            ) {
                timerOptions.forEachIndexed { index, (minutes, label) ->
                    val isSelected = SleepTimer.activeLabel == "$minutes min"
                    Surface(
                        onClick = {
                            closeThen {
                                SleepTimer.start(minutes) { onPause() }
                                onDismiss()
                            }
                        },
                        color = Color.Transparent,
                        shape = groupedCardShape(index, timerOptions.size + 1, radius = 14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = if (isSelected) {
                                    BlexsyTypography.TitleActive.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                } else {
                                    BlexsyTypography.Title.copy(fontSize = 15.sp)
                                },
                                color = if (isSelected) BlexsyColors.Primary else BlexsyColors.TextPrimary
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.a11y_selected),
                                    tint = BlexsyColors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        thickness = 0.5.dp
                    )
                }

                // End of track option
                val isEndTrackSelected = SleepTimer.activeLabel == "End of track"
                Surface(
                    onClick = {
                        closeThen {
                            SleepTimer.startForTrack(remainingMs.coerceAtLeast(1000L)) { onPause() }
                            onDismiss()
                        }
                    },
                    color = Color.Transparent,
                    shape = groupedCardShape(timerOptions.size, timerOptions.size + 1, radius = 14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.sleep_timer_end_of_track),
                            style = if (isEndTrackSelected) {
                                BlexsyTypography.TitleActive.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            } else {
                                BlexsyTypography.Title.copy(fontSize = 15.sp)
                            },
                            color = if (isEndTrackSelected) BlexsyColors.Primary else BlexsyColors.TextPrimary
                        )
                        if (isEndTrackSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.a11y_selected),
                                tint = BlexsyColors.Primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            if (SleepTimer.isActive) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    onClick = {
                        closeThen {
                            SleepTimer.stop()
                            onDismiss()
                        }
                    },
                    color = BlexsyColors.SurfaceContainerHigh,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.TimerOff,
                            contentDescription = null,
                            tint = BlexsyColors.Danger,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = stringResource(R.string.sleep_timer_turn_off),
                            style = BlexsyTypography.Body.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BlexsyColors.Danger
                            )
                        )
                    }
                }
            }
        }
    }
}
