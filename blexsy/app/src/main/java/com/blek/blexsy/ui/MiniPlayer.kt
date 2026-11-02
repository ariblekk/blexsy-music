package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.player.BlexsyAudioPlayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext

/** Full-rounded pill mini player with circular progress encircling cover art. */
@Composable
fun MiniPlayer(
    player: BlexsyAudioPlayer,
    onExpand: () -> Unit,
    includeNavigationBars: Boolean = false,
    modifier: Modifier = Modifier
) {
    val track by player.currentTrack.collectAsState()
    val dominantColor = rememberDominantColor(track?.image)
    val isPlaying by player.isPlaying.collectAsState()
    val isBuffering by player.isBuffering.collectAsState()
    val pos by player.positionMs.collectAsState()
    val dur by player.durationMs.collectAsState()

    AnimatedVisibility(
        visible = track != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        track?.let { currentTrack ->
            val totalMs = if (dur > 0) dur else (currentTrack.durationMs ?: 0L)
            val progress = if (totalMs > 0) (pos.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = if (includeNavigationBars) 6.dp else 2.dp
                    )
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    // ponytail: 80% opacity glass matching player header; upgrade to RenderEffect blur when minSdk >= 31.
                    color = BlexsyColors.SurfaceContainerHigh.copy(alpha = 0.80f),
                    contentColor = BlexsyColors.TextPrimary,
                    shape = CircleShape,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = (if (includeNavigationBars) Modifier.navigationBarsPadding() else Modifier)
                            .background(
                                Brush.horizontalGradient(
                                    0.00f to dominantColor.copy(alpha = 0.45f),
                                    0.18f to dominantColor.copy(alpha = 0.38f),
                                    0.35f to dominantColor.copy(alpha = 0.28f),
                                    0.52f to dominantColor.copy(alpha = 0.17f),
                                    0.70f to dominantColor.copy(alpha = 0.08f),
                                    0.88f to dominantColor.copy(alpha = 0.02f),
                                    1.00f to dominantColor.copy(alpha = 0.00f)
                                )
                            )
                            // Dither grain: breaks up 8-bit quantization banding lines
                            .drawBehind {
                                drawRect(brush = FrostGrainBrush, alpha = FrostGrainAlpha)
                            }
                            .border(
                                width = 1.dp,
                                brush = Brush.horizontalGradient(
                                    0.00f to dominantColor.copy(alpha = 0.55f),
                                    0.35f to dominantColor.copy(alpha = 0.25f),
                                    0.70f to Color.White.copy(alpha = 0.08f),
                                    1.00f to Color.White.copy(alpha = 0.14f)
                                ),
                                shape = CircleShape
                            )
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = dominantColor.copy(alpha = 0.50f)),
                                onClick = onExpand
                            )
                            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Circular progress ring encircling the rounded cover art
                        Box(
                            modifier = Modifier.size(46.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxSize(),
                                color = BlexsyColors.Primary,
                                trackColor = Color.White.copy(alpha = 0.12f),
                                strokeWidth = 2.5.dp,
                                strokeCap = StrokeCap.Round
                            )
                            CoverImage(
                                url = currentTrack.image,
                                size = 38,
                                shapeRadius = 19,
                                modifier = Modifier.clip(CircleShape)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentTrack.displayTitle,
                                style = BlexsyTypography.Title.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    shadow = Shadow(
                                        color = Color.Black.copy(alpha = 0.30f),
                                        offset = Offset(0f, 1f),
                                        blurRadius = 8f
                                    )
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = currentTrack.artist,
                                style = BlexsyTypography.Subtitle.copy(
                                    fontSize = 12.sp,
                                    shadow = Shadow(
                                        color = Color.Black.copy(alpha = 0.25f),
                                        offset = Offset(0f, 1f),
                                        blurRadius = 6f
                                    )
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { player.togglePlayPause() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            if (isBuffering) {
                                ExpressiveLoadingIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = BlexsyColors.Primary
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                                    tint = BlexsyColors.TextPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = { player.next() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = stringResource(R.string.a11y_next),
                                tint = BlexsyColors.TextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
