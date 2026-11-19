package com.blek.blexsy.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.R
import com.blek.blexsy.model.Track

/** Artwork panel: large cover, title / artist line and the more-options button. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlayerCoverView(
    track: Track,
    onCoverClick: () -> Unit,
    onMoreClick: () -> Unit,
    onArtistClick: (() -> Unit)?,
    sharedScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PlayerHeaderLabel(
            text = stringResource(R.string.player_header_now_playing),
            sharedScope = sharedScope,
            animatedVisibilityScope = animatedVisibilityScope,
            bottomPadding = 8.dp
        )

        val displayCover = track.image
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Short square: never wider than the panel and never taller than 80% of the free
            // height, so the cover stops dominating its column on tall screens.
            val coverSize = minOf(maxWidth * 0.9f, maxHeight * 0.8f)
            with(sharedScope) {
                Box(
                    modifier = Modifier
                        .sharedElement(
                            sharedContentState = rememberSharedContentState(key = "player_cover_art_${track.id}"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                        .size(coverSize)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onCoverClick)
                ) {
                    // The video (when present) is the panel backdrop, so the square stays artwork.
                    CoverImage(
                        url = displayCover,
                        size = 0,
                        shapeRadius = 12,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            with(sharedScope) {
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
                            fontSize = 21.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        style = BlexsyTypography.Subtitle.copy(
                            fontSize = 14.sp,
                            color = if (onArtistClick != null) BlexsyColors.TextPrimary
                            else BlexsyColors.TextSecondary
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
                        imageVector = IcDotsThree,
                        contentDescription = stringResource(R.string.a11y_more_options),
                        tint = BlexsyColors.TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
