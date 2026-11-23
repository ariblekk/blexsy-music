package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Full-rounded pill mini player with circular progress encircling cover art
 * and fluid horizontal swipe gestures (Artwork + Title + Artist glide together).
 */
@Composable
fun MiniPlayer(
    player: BlexsyAudioPlayer,
    onExpand: () -> Unit,
    includeNavigationBars: Boolean = false,
    modifier: Modifier = Modifier
) {
    val track by player.currentTrack.collectAsState()
    val (gradPrimary, gradSecondary) = rememberArtworkGradientColors(track?.image)
    val dominantColor = gradPrimary
    val isPlaying by player.isPlaying.collectAsState()
    val isBuffering by player.isBuffering.collectAsState()
    val pos by player.positionMs.collectAsState()
    val dur by player.durationMs.collectAsState()
    val haptic = rememberBlexsyHaptic()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 54.dp.toPx() }
    val maxDragPx = with(density) { 120.dp.toPx() }
    val dragOffsetX = remember { Animatable(0f) }
    var lastSwipeDirection by remember { mutableIntStateOf(-1) } // -1 = next (left), +1 = prev (right)

    AnimatedVisibility(
        visible = track != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        track?.let { currentTrack ->
            val layout = rememberBlexsyLayoutSpec()
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
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth(),
                    color = BlexsyColors.SurfaceContainerHigh.copy(alpha = 0.84f),
                    contentColor = BlexsyColors.TextPrimary,
                    shape = CircleShape,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = (if (includeNavigationBars) Modifier.navigationBarsPadding() else Modifier)
                            .background(
                                Brush.horizontalGradient(
                                    0.00f to gradPrimary.copy(alpha = 0.42f),
                                    0.28f to gradPrimary.copy(alpha = 0.28f),
                                    0.58f to gradSecondary.copy(alpha = 0.14f),
                                    0.85f to gradSecondary.copy(alpha = 0.04f),
                                    1.00f to Color.Transparent
                                )
                            )
                            .drawBehind {
                                drawRect(brush = FrostGrainBrush, alpha = FrostGrainAlpha)
                            }
                            .border(
                                width = 1.dp,
                                brush = Brush.horizontalGradient(
                                    0.00f to gradPrimary.copy(alpha = 0.48f),
                                    0.40f to gradPrimary.copy(alpha = 0.22f),
                                    0.75f to Color.White.copy(alpha = 0.08f),
                                    1.00f to Color.White.copy(alpha = 0.12f)
                                ),
                                shape = CircleShape
                            )
                            .clip(CircleShape)
                            .pointerInput(currentTrack.id) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        val finalOffset = dragOffsetX.value
                                        when {
                                            finalOffset <= -swipeThresholdPx -> {
                                                lastSwipeDirection = -1
                                                haptic.click()
                                                scope.launch {
                                                    // Snap back smoothly while AnimatedContent slides the new track in
                                                    dragOffsetX.snapTo(0f)
                                                }
                                                player.next()
                                            }
                                            finalOffset >= swipeThresholdPx -> {
                                                lastSwipeDirection = 1
                                                haptic.click()
                                                scope.launch {
                                                    dragOffsetX.snapTo(0f)
                                                }
                                                player.previous()
                                            }
                                            else -> {
                                                scope.launch {
                                                    dragOffsetX.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = spring(dampingRatio = 0.76f, stiffness = 420f)
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        scope.launch {
                                            dragOffsetX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(dampingRatio = 0.76f, stiffness = 420f)
                                            )
                                        }
                                    },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        scope.launch {
                                            // Progressive rubber-band resistance as the user drags further
                                            val currentRatio = (abs(dragOffsetX.value) / maxDragPx).coerceIn(0f, 1f)
                                            val resistance = 0.78f * (1f - currentRatio * 0.55f)
                                            val next = (dragOffsetX.value + dragAmount * resistance)
                                                .coerceIn(-maxDragPx, maxDragPx)
                                            dragOffsetX.snapTo(next)
                                        }
                                    }
                                )
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = dominantColor.copy(alpha = 0.50f)),
                                onClick = onExpand
                            )
                            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Artwork + Circular Progress + Title + Artist slide & morph together as one unit
                        AnimatedContent(
                            targetState = currentTrack,
                            transitionSpec = {
                                val dir = if (lastSwipeDirection < 0) 1 else -1
                                val enterSlide = slideInHorizontally(
                                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                                    initialOffsetX = { fullWidth -> dir * (fullWidth * 2 / 5) }
                                ) + fadeIn(
                                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                                ) + scaleIn(
                                    initialScale = 0.96f,
                                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                                )

                                val exitSlide = slideOutHorizontally(
                                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                    targetOffsetX = { fullWidth -> -dir * (fullWidth * 2 / 5) }
                                ) + fadeOut(
                                    animationSpec = tween(durationMillis = 160)
                                ) + scaleOut(
                                    targetScale = 0.96f,
                                    animationSpec = tween(durationMillis = 180)
                                )

                                enterSlide togetherWith exitSlide
                            },
                            contentKey = { it.id },
                            label = "mini_player_full_track_slide",
                            modifier = Modifier
                                .weight(1f)
                                .graphicsLayer {
                                    val offset = dragOffsetX.value
                                    val progressRatio = (abs(offset) / maxDragPx).coerceIn(0f, 1f)
                                    translationX = offset
                                    alpha = (1f - progressRatio * 0.42f).coerceIn(0.45f, 1f)
                                    val scale = 1f - progressRatio * 0.04f
                                    scaleX = scale
                                    scaleY = scale
                                }
                        ) { displayedTrack ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Circular progress ring encircling the rounded cover art
                                Box(
                                    modifier = Modifier.size(46.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        progress = { if (displayedTrack.id == currentTrack.id) progress else 0f },
                                        modifier = Modifier.fillMaxSize(),
                                        color = BlexsyColors.Primary,
                                        trackColor = Color.White.copy(alpha = 0.12f),
                                        strokeWidth = 2.5.dp,
                                        strokeCap = StrokeCap.Round
                                    )
                                    CoverImage(
                                        url = displayedTrack.image,
                                        size = 38,
                                        shapeRadius = 19,
                                        modifier = Modifier.clip(CircleShape)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = displayedTrack.displayTitle,
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
                                        text = displayedTrack.artist,
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
                            }
                        }

                        if (layout.isTablet) {
                            IconButton(
                                onClick = {
                                    lastSwipeDirection = 1
                                    player.previous()
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = IcSkipBack,
                                    contentDescription = stringResource(R.string.a11y_previous),
                                    tint = BlexsyColors.TextPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
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
                                    imageVector = if (isPlaying) IcPause else IcPlay,
                                    contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                                    tint = BlexsyColors.TextPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                lastSwipeDirection = -1
                                player.next()
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = IcSkipForward,
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
