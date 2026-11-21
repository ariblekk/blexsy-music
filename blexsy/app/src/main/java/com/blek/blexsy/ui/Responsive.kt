package com.blek.blexsy.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blek.blexsy.R

enum class BlexsyWindowWidthSize {
    Compact,
    Medium,
    Expanded
}

@Immutable
data class BlexsyLayoutSpec(
    val widthDp: Int,
    val heightDp: Int,
    val widthSize: BlexsyWindowWidthSize
) {
    val isTablet: Boolean get() = widthDp >= 600
    val isExpanded: Boolean get() = widthDp >= 840
    val isLandscape: Boolean get() = widthDp > heightDp

    /** Tablets (>= 600dp) use a left navigation rail / sidebar instead of a bottom bar. */
    val useSideNav: Boolean get() = widthDp >= 600

    /** Large tablets in landscape (>= 960dp) expand the rail into a labeled sidebar. */
    val sideNavExpanded: Boolean get() = widthDp >= 960

    /** Album & Playlist detail screens split into a left hero card + right track list on wide screens. */
    val useTwoPaneDetail: Boolean get() = widthDp >= 700

    /** Full Player modal splits into left cover/controls + right lyrics/queue panel on tablet / wide screens. */
    val useTwoPanePlayer: Boolean get() = widthDp >= 600 && (widthDp >= 720 || widthDp > heightDp)

    val horizontalPadding: Dp get() = if (isTablet) 24.dp else 16.dp
    val cardWidth: Dp get() = if (isTablet) 168.dp else 152.dp

    val contentWidthDp: Int
        get() {
            val navWidth = when {
                !useSideNav -> 0
                sideNavExpanded -> 228
                else -> 92
            }
            return (widthDp - navWidth).coerceAtLeast(320)
        }

    fun gridColumns(minItemWidthDp: Int = 170, maxColumns: Int = 6): Int {
        if (!isTablet) return 2
        val available = (contentWidthDp - 48).coerceAtLeast(minItemWidthDp * 2)
        return (available / minItemWidthDp).coerceIn(2, maxColumns)
    }
}

@Composable
fun rememberBlexsyLayoutSpec(): BlexsyLayoutSpec {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    return remember(widthDp, heightDp) {
        val widthSize = when {
            widthDp < 600 -> BlexsyWindowWidthSize.Compact
            widthDp < 840 -> BlexsyWindowWidthSize.Medium
            else -> BlexsyWindowWidthSize.Expanded
        }
        BlexsyLayoutSpec(
            widthDp = widthDp,
            heightDp = heightDp,
            widthSize = widthSize
        )
    }
}

/**
 * Renders the Android Adaptive Launcher Icon (`@mipmap/ic_launcher_round` / `@mipmap/ic_launcher`)
 * safely inside Jetpack Compose by rasterizing the Drawable at high resolution.
 */
@Composable
fun AppLauncherIcon(
    size: Dp = 40.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val iconBitmap: ImageBitmap? = remember(context) {
        runCatching {
            val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
                ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                ?: context.packageManager.getApplicationIcon(context.applicationInfo)
            val px = 192
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }

    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = BlexsyThemeConfig.appName,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(BlexsyColors.Primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = IcMusicNote,
                contentDescription = BlexsyThemeConfig.appName,
                tint = Color.Black,
                modifier = Modifier.size(size * 0.55f)
            )
        }
    }
}

/**
 * Adaptive left navigation rail / sidebar for tablet & large-screen layouts.
 * Matches Blexsy's dark frosted-glass aesthetic with spring-animated selection state.
 */
@Composable
fun BlexsySideNav(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    expanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptic = rememberBlexsyHaptic()
    val tabs = listOf(
        Triple(stringResource(R.string.nav_home), IcHouse, IcHouse),
        Triple(stringResource(R.string.nav_search), IcMagnifyingGlass, IcMagnifyingGlass),
        Triple(stringResource(R.string.nav_library), IcMusicNotes, IcMusicNotes),
        Triple(stringResource(R.string.nav_profile), IcUser, IcUser)
    )

    val railWidth by animateDpAsState(
        targetValue = if (expanded) 220.dp else 84.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "side_nav_width"
    )

    Surface(
        modifier = modifier
            .width(railWidth)
            .fillMaxHeight(),
        color = BlexsyColors.SurfaceContainerLow,
        contentColor = BlexsyColors.TextPrimary
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .drawBehind {
                        drawRect(brush = FrostGrainBrush, alpha = FrostGrainAlpha)
                    }
                    .padding(
                        horizontal = if (expanded) 14.dp else 10.dp,
                        vertical = 16.dp
                    ),
                horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally
            ) {
                // Brand identity header using the actual application launcher icon
                if (expanded) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppLauncherIcon(
                            size = 36.dp,
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = BlexsyThemeConfig.appName.uppercase(),
                            color = BlexsyColors.TextPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            letterSpacing = (-0.5).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    AppLauncherIcon(
                        size = 42.dp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Navigation items
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, (label, unselectedIcon, selectedIcon) ->
                        val selected = selectedIndex == index
                        val contentColor by animateColorAsState(
                            targetValue = if (selected) BlexsyColors.Primary else BlexsyColors.TextSecondary,
                            label = "side_nav_item_color"
                        )
                        val containerColor by animateColorAsState(
                            targetValue = if (selected) BlexsyColors.Primary.copy(alpha = 0.16f) else Color.Transparent,
                            label = "side_nav_item_bg"
                        )
                        val itemShape = if (expanded) RoundedCornerShape(14.dp) else RoundedCornerShape(16.dp)

                        if (expanded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(itemShape)
                                    .background(containerColor)
                                    .then(
                                        if (selected) {
                                            Modifier.border(
                                                width = 1.dp,
                                                color = BlexsyColors.Primary.copy(alpha = 0.30f),
                                                shape = itemShape
                                            )
                                        } else Modifier
                                    )
                                    .clickable {
                                        if (selectedIndex != index) haptic.tick()
                                        onSelect(index)
                                    }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (selected) selectedIcon else unselectedIcon,
                                    contentDescription = label,
                                    tint = contentColor,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = label,
                                    color = if (selected) BlexsyColors.TextPrimary else contentColor,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(itemShape)
                                    .background(containerColor)
                                    .then(
                                        if (selected) {
                                            Modifier.border(
                                                width = 1.dp,
                                                color = BlexsyColors.Primary.copy(alpha = 0.30f),
                                                shape = itemShape
                                            )
                                        } else Modifier
                                    )
                                    .clickable {
                                        if (selectedIndex != index) haptic.tick()
                                        onSelect(index)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (selected) selectedIcon else unselectedIcon,
                                    contentDescription = label,
                                    tint = contentColor,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = label,
                                    color = contentColor,
                                    fontSize = 11.sp,
                                    lineHeight = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Subtle vertical divider separating side nav from content area
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(BlexsyColors.Hairline)
            )
        }
    }
}
