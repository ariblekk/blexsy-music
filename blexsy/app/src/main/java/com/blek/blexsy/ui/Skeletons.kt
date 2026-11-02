package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shimmer primitives used while a screen is fetching its data.
 *
 * The animation runs entirely in the draw phase, so a moving highlight never
 * triggers recomposition of the skeleton tree.
 */

/** Rounded block painted with a slow moving light band. */
@Composable
fun Modifier.skeleton(shape: Shape = RoundedCornerShape(6.dp)): Modifier {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonShimmer"
    )

    return this
        .clip(shape)
        .drawBehind {
            drawRect(BlexsyColors.SurfaceContainerHigh)

            val band = size.width * 0.75f + 1f
            val startX = progress * (size.width + band * 2f) - band
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color(0x1FFFFFFF), Color.Transparent),
                    start = Offset(startX, 0f),
                    end = Offset(startX + band, size.height.coerceAtLeast(1f))
                )
            )
        }
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp)
) {
    Box(modifier = modifier.skeleton(shape))
}

@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    shape: Shape = RoundedCornerShape(4.dp)
) {
    Box(modifier = modifier.height(height).skeleton(shape))
}

/** One loading track row that matches [TrackTile]. */
@Composable
fun SkeletonTrackRow(
    modifier: Modifier = Modifier,
    showArtwork: Boolean = true,
    showRank: Boolean = true,
    artworkSize: Dp = 48.dp,
    hasAction: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showRank) {
            Box(
                modifier = Modifier.width(26.dp),
                contentAlignment = Alignment.Center
            ) {
                SkeletonBox(
                    modifier = Modifier.size(width = 16.dp, height = 12.dp),
                    shape = RoundedCornerShape(3.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
        }

        if (showArtwork) {
            SkeletonBox(
                modifier = Modifier.size(artworkSize),
                shape = RoundedCornerShape(4.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        } else if (!showRank) {
            SkeletonBox(
                modifier = Modifier.size(width = 22.dp, height = 12.dp),
                shape = RoundedCornerShape(3.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            SkeletonLine(modifier = Modifier.fillMaxWidth(0.62f), height = 14.dp)
            Spacer(modifier = Modifier.height(7.dp))
            SkeletonLine(modifier = Modifier.fillMaxWidth(0.38f), height = 10.dp)
        }

        if (hasAction) {
            Spacer(modifier = Modifier.width(12.dp))
            SkeletonBox(modifier = Modifier.size(22.dp), shape = RoundedCornerShape(4.dp))
        }
    }
}

/** Loading row that matches a playlist/album row with a 56dp cover. */
@Composable
fun SkeletonMediaRow(
    modifier: Modifier = Modifier,
    coverSize: Dp = 56.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkeletonBox(modifier = Modifier.size(coverSize), shape = RoundedCornerShape(4.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            SkeletonLine(modifier = Modifier.fillMaxWidth(0.58f), height = 14.dp)
            Spacer(modifier = Modifier.height(7.dp))
            SkeletonLine(modifier = Modifier.fillMaxWidth(0.34f), height = 10.dp)
        }
    }
}

private const val SKELETON_CARD_COUNT = 3

@Composable
private fun Modifier.loadingSemantics(): Modifier {
    val loadingLabel = stringResource(R.string.a11y_loading)
    return semantics { contentDescription = loadingLabel }
}

/** Loading state for the "Listen now" feed, placed under the greeting header. */
@Composable
fun HomeFeedSkeleton() {
    Column(modifier = Modifier.fillMaxWidth().loadingSemantics()) {
        // Quick access grid: three rows of two tiles.
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(3) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(2) {
                        SkeletonBox(
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(6.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Featured hero.
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(176.dp),
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(26.dp))

        // Section header.
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SkeletonLine(modifier = Modifier.width(58.dp), height = 9.dp)
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonLine(modifier = Modifier.width(172.dp), height = 20.dp)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Horizontal track cards.
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            repeat(SKELETON_CARD_COUNT) {
                Column(modifier = Modifier.width(132.dp)) {
                    SkeletonBox(
                        modifier = Modifier.size(132.dp),
                        shape = RoundedCornerShape(6.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SkeletonLine(modifier = Modifier.fillMaxWidth(0.9f), height = 12.dp)
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonLine(modifier = Modifier.fillMaxWidth(0.55f), height = 10.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Track list.
        repeat(3) {
            SkeletonTrackRow()
        }
    }
}

/** Loading state for the browse/search page (search field stays interactive). */
@Composable
fun BrowseSkeleton() {
    Column(modifier = Modifier.fillMaxWidth().loadingSemantics()) {
        // Recent searches.
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SkeletonLine(modifier = Modifier.width(132.dp), height = 16.dp)
            Spacer(modifier = Modifier.height(16.dp))
            repeat(3) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonBox(modifier = Modifier.size(18.dp), shape = RoundedCornerShape(4.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    SkeletonLine(modifier = Modifier.fillMaxWidth(0.62f), height = 12.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Browse categories header.
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SkeletonLine(modifier = Modifier.width(58.dp), height = 9.dp)
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonLine(modifier = Modifier.width(128.dp), height = 20.dp)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Category grid.
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            repeat(4) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(2) {
                        SkeletonBox(
                            modifier = Modifier.weight(1f).height(104.dp),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Full-page loading state for the Library tab (the masthead stays visible). */
@Composable
fun LibrarySkeleton(
    topPadding: Dp,
    bottomPadding: Dp
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().loadingSemantics(),
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                SkeletonMediaRow(coverSize = 52.dp)
                SkeletonMediaRow(coverSize = 52.dp)

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    SkeletonLine(modifier = Modifier.width(58.dp), height = 9.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    SkeletonLine(modifier = Modifier.width(118.dp), height = 20.dp)
                }

                repeat(7) {
                    SkeletonMediaRow(coverSize = 52.dp)
                }
            }
        }
    }
}

/** Loading state for the profile tab content below the fixed header. */
@Composable
fun ProfileListSkeleton() {
    Column(modifier = Modifier.fillMaxWidth().loadingSemantics()) {
        repeat(6) {
            SkeletonTrackRow(artworkSize = 48.dp)
        }
    }
}

/** Loading state shared by the album and playlist detail hero. */
@Composable
fun DetailHeroSkeleton(
    modifier: Modifier = Modifier,
    circleCover: Boolean = false,
    centered: Boolean = false,
    rows: Int = 6
) {
    Column(modifier = modifier.fillMaxSize().loadingSemantics()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
        ) {
            SkeletonBox(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.45f to Color.Transparent,
                            0.70f to BlexsyColors.Background.copy(alpha = 0.50f),
                            0.88f to BlexsyColors.Background.copy(alpha = 0.90f),
                            1.00f to BlexsyColors.Background
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SkeletonLine(modifier = Modifier.width(64.dp), height = 11.dp)
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonLine(modifier = Modifier.width(220.dp), height = 28.dp)
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonLine(modifier = Modifier.width(140.dp), height = 13.dp)
                Spacer(modifier = Modifier.height(18.dp))
                SkeletonBox(
                    modifier = Modifier
                        .width(180.dp)
                        .height(44.dp),
                    shape = CircleShape
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        repeat(rows) {
            SkeletonTrackRow()
        }
    }
}

/** Loading state while a search request is in flight. */
@Composable
fun SearchResultsSkeleton() {
    Column(modifier = Modifier.fillMaxWidth().loadingSemantics()) {
        repeat(8) {
            SkeletonTrackRow(showRank = false)
        }
    }
}

/** Loading state for the genre page: hero plus playlist rows. */
@Composable
fun GenreDetailSkeleton(modifier: Modifier = Modifier) {
    DetailHeroSkeleton(modifier = modifier, rows = 6)
}

/** Loading state matching the edge-to-edge ArtistDetailScreen hero layout. */
@Composable
fun ArtistDetailSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 5
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .loadingSemantics(),
        userScrollEnabled = false
    ) {
        // Full-width hero image placeholder
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
            ) {
                SkeletonBox(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(0.dp)
                )
                // Bottom gradient overlay to blend into background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                0.45f to Color.Transparent,
                                0.70f to BlexsyColors.Background.copy(alpha = 0.50f),
                                0.88f to BlexsyColors.Background.copy(alpha = 0.90f),
                                1.00f to BlexsyColors.Background
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SkeletonLine(modifier = Modifier.width(200.dp), height = 30.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    SkeletonLine(modifier = Modifier.width(130.dp), height = 13.dp)
                    Spacer(modifier = Modifier.height(18.dp))
                    SkeletonBox(
                        modifier = Modifier
                            .width(180.dp)
                            .height(44.dp),
                        shape = CircleShape
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            // Crowd Favorites Section Header
            Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)) {
                SkeletonLine(modifier = Modifier.width(54.dp), height = 9.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonLine(modifier = Modifier.width(110.dp), height = 18.dp)
            }
        }

        items(rows) {
            SkeletonTrackRow()
        }

        // Releases / Catalog Section
        item {
            Spacer(modifier = Modifier.height(14.dp))
            Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)) {
                SkeletonLine(modifier = Modifier.width(48.dp), height = 9.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonLine(modifier = Modifier.width(96.dp), height = 18.dp)
            }
            // Tab pill placeholder
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                SkeletonBox(
                    modifier = Modifier.width(190.dp).height(36.dp),
                    shape = CircleShape
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Horizontal album cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(3) {
                    Column(modifier = Modifier.width(140.dp)) {
                        SkeletonBox(
                            modifier = Modifier.size(140.dp),
                            shape = RoundedCornerShape(6.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SkeletonLine(modifier = Modifier.fillMaxWidth(0.85f), height = 13.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        SkeletonLine(modifier = Modifier.fillMaxWidth(0.55f), height = 11.dp)
                    }
                }
            }
        }

        // Similar Artists Section
        item {
            Spacer(modifier = Modifier.height(18.dp))
            Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)) {
                SkeletonLine(modifier = Modifier.width(56.dp), height = 9.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonLine(modifier = Modifier.width(120.dp), height = 18.dp)
            }
            // Circular artist avatars
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                repeat(3) {
                    Column(
                        modifier = Modifier.width(110.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SkeletonBox(
                            modifier = Modifier.size(110.dp),
                            shape = CircleShape
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SkeletonLine(modifier = Modifier.width(75.dp), height = 12.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        SkeletonLine(modifier = Modifier.width(50.dp), height = 10.dp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
