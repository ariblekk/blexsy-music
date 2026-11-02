package com.blek.blexsy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.R
import com.blek.blexsy.model.Track
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz

/** Small all-caps label above the player panel ("Now playing", "Lyrics", "Queue"). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlayerHeaderLabel(
    text: String,
    sharedScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    bottomPadding: Dp = 4.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        with(sharedScope) {
            Text(
                text = text,
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
    }
}

/**
 * Frosted card showing the current track inside the lyrics and queue panels.
 * The cover and the text block participate in the shared-element morph back to
 * the artwork view.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlayerGlassTrackCard(
    track: Track,
    dominantColor: Color = Color.Transparent,
    sharedScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onCoverClick: () -> Unit,
    onMoreClick: () -> Unit,
    onArtistClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        // ponytail: 80% opacity glass matching mini player; upgrade to RenderEffect blur when minSdk >= 31.
        color = BlexsyColors.SurfaceContainerHigh.copy(alpha = 0.80f),
        contentColor = BlexsyColors.TextPrimary,
        shape = CircleShape,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
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
                    onClick = onCoverClick
                )
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        with(sharedScope) {
            CoverImage(
                url = track.image,
                size = 46,
                shapeRadius = 23,
                modifier = Modifier
                    .clip(CircleShape)
                    .sharedElement(
                        sharedContentState = rememberSharedContentState(key = "player_cover_art_${track.id}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "player_track_info_${track.id}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
            ) {
                Text(
                    text = track.displayTitle,
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
                    text = track.artist,
                    style = BlexsyTypography.Subtitle.copy(
                        fontSize = 12.sp,
                        color = if (onArtistClick != null) BlexsyColors.TextPrimary
                        else BlexsyColors.TextSecondary,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.25f),
                            offset = Offset(0f, 1f),
                            blurRadius = 6f
                        )
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onArtistClick != null) {
                        Modifier.clickable(onClick = onArtistClick)
                    } else Modifier
                )
            }
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier
                    .size(40.dp)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "player_more_options"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.a11y_more_options),
                    tint = BlexsyColors.TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
}
