package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.model.AlbumSummary
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.toHighResArtwork
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search

fun getBlexsyDateline(): String {
    val cal = java.util.Calendar.getInstance()
    val days = arrayOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")
    val months = arrayOf(
        "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
        "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"
    )
    val dayOfWeek = days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val month = months[cal.get(java.util.Calendar.MONTH)]
    val year = cal.get(java.util.Calendar.YEAR)
    return "$dayOfWeek, $day $month $year"
}

fun getBlexsyGreeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour in 4..10 -> "What's good."
        hour in 11..14 -> "Afternoon flex."
        hour in 15..17 -> "Golden hour beats."
        else -> "Still bumpin'?"
    }
}

val BLEXSY_MUSIC_JOKES = listOf(
    "500-track playlist and you still run the same 5 back on repeat. Real.",
    "Blasting sad songs while having the time of your life. It's about the chord progression, no cap.",
    "Volume cranked to max isn't hearing loss, it's just a certified banger.",
    "Two ears, one earbud... cause the homie walked off with the other one and ghosted.",
    "Nodding along like you're listening, but really just waiting for that bass drop.",
    "Guitar solos hitting clean every single day, but nobody hitting your heartstrings.",
    "Lyrics memorized word-for-word, but yesterday's study notes? Completely wiped.",
    "Life without tunes is like a drop without 808s: straight up empty.",
    "Sad? Drop a beat. Hyped? Drop a beat. Starving? Instant noodles time."
)

private fun String.titleCased(): String =
    lowercase().split(" ").joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

/** Spotify-style brand mark: green circle with a black note. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(BlexsyColors.Primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = BlexsyThemeConfig.appName.uppercase(),
            color = BlexsyColors.TextPrimary,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            letterSpacing = (-0.6).sp
        )
    }
}

fun dicebearAvatarUrl(seed: String): String {
    val clean = seed.trim().ifEmpty { "Blexsy" }
    val encoded = java.net.URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
    return "https://api.dicebear.com/10.x/bottts-neutral/png?seed=$encoded"
}

@Composable
fun Avatar(
    initial: String,
    modifier: Modifier = Modifier,
    size: Int = 36,
    seed: String? = null
) {
    val cleanSeed = seed?.trim().orEmpty()
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(BlexsyColors.SurfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        if (cleanSeed.isNotEmpty()) {
            AsyncImage(
                model = dicebearAvatarUrl(cleanSeed),
                contentDescription = stringResource(R.string.a11y_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initial.uppercase().take(1).ifEmpty { "?" },
                color = BlexsyColors.TextPrimary,
                fontSize = (size * 0.42).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Spotify-style top bar: page title and optional actions. */
@Composable
fun Masthead(
    title: String,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BlexsyColors.Background)
            .statusBarsPadding()
            .height(64.dp)
            .padding(start = if (onMenu != null) 4.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onMenu != null) {
            IconButton(
                onClick = onMenu,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.a11y_menu),
                    tint = BlexsyColors.TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            style = BlexsyTypography.SectionTitle.copy(
                fontFamily = DeezerExtraBoldFont,
                fontSize = 24.sp,
                lineHeight = 30.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (actions != null) {
            actions()
        }
    }
}

interface BlexsyHaptic {
    fun click()
    fun tick()
}

@Composable
fun rememberBlexsyHaptic(): BlexsyHaptic {
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view, context) {
        object : BlexsyHaptic {
            private val vibrator: Vibrator? by lazy {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            }

            private val audioAttrs by lazy {
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build()
            }

            @Suppress("DEPRECATION")
            private fun forceVibrate(isTick: Boolean) {
                runCatching {
                    val vib = vibrator ?: return
                    if (!vib.hasVibrator()) return
                    val duration = if (isTick) 20L else 50L
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            vib.areAllEffectsSupported(if (isTick) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
                        ) {
                            VibrationEffect.createPredefined(if (isTick) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK)
                        } else {
                            // ponytail: higher amplitude+duration for budget motors; tune down for flagship
                            val amplitude = if (isTick) 120 else 180
                            VibrationEffect.createOneShot(duration, amplitude)
                        }
                        vib.vibrate(effect, audioAttrs)
                    } else {
                        vib.vibrate(duration)
                    }
                }
            }

            @Suppress("DEPRECATION")
            private fun trigger(isTick: Boolean) {
                view.isHapticFeedbackEnabled = true
                val constant = if (isTick) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.KEYBOARD_TAP
                val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                val played = view.performHapticFeedback(constant, flags)
                if (!played) {
                    forceVibrate(isTick)
                } else if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                    // Samsung mid-range often reports success but produces no tactile output
                    forceVibrate(isTick)
                }
            }

            override fun click() = trigger(isTick = false)
            override fun tick() = trigger(isTick = true)
        }
    }
}

/** Compact floating rounded bottom navigation with iOS-style spring sliding pill. */
@Composable
fun BottomNav(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        Triple(stringResource(R.string.nav_home), Icons.Rounded.Home, Icons.Rounded.Home),
        Triple(stringResource(R.string.nav_search), Icons.Rounded.Search, Icons.Rounded.Search),
        Triple(stringResource(R.string.nav_library), Icons.Rounded.LibraryMusic, Icons.Rounded.LibraryMusic),
        Triple(stringResource(R.string.nav_profile), Icons.Rounded.Person, Icons.Rounded.Person)
    )

    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // ponytail: iOS-style sliding pill with spring physics; upgrade to full HorizontalPager if screen content supports horizontal swipe.
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 450f),
        label = "nav_pill_index"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = CircleShape,
            color = BlexsyColors.SurfaceContainerHigh.copy(alpha = 0.80f),
            contentColor = BlexsyColors.TextPrimary,
            shadowElevation = 6.dp
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(brush = FrostGrainBrush, alpha = FrostGrainAlpha)
                    }
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.10f),
                        shape = CircleShape
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .pointerInput(tabs.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val tabW = size.width / tabs.size
                            fun tabAt(x: Float) = (x / tabW).toInt().coerceIn(0, tabs.size - 1)
                            var activeTab = tabAt(down.position.x)
                            currentOnSelect(activeTab)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val nextTab = tabAt(change.position.x)
                                if (nextTab != activeTab) {
                                    activeTab = nextTab
                                    currentOnSelect(nextTab)
                                }
                            }
                        }
                    }
            ) {
                val tabWidth = maxWidth / tabs.size

                // Sliding pill indicator (iOS fluid spring pill)
                Box(
                    modifier = Modifier
                        .offset(x = tabWidth * animatedIndex)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(BlexsyColors.Primary.copy(alpha = 0.16f))
                )

                // Tab items
                Row(modifier = Modifier.fillMaxSize()) {
                    tabs.forEachIndexed { index, (label, unselectedIcon, selectedIcon) ->
                        val selected = selectedIndex == index
                        val animatedColor by animateColorAsState(
                            targetValue = if (selected) BlexsyColors.Primary else BlexsyColors.TextSecondary.copy(alpha = 0.65f),
                            label = "nav_tab_color"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (selected) selectedIcon else unselectedIcon,
                                    contentDescription = label,
                                    tint = animatedColor,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(modifier = Modifier.height(1.dp))
                                Text(
                                    text = label,
                                    color = animatedColor,
                                    fontSize = 10.sp,
                                    lineHeight = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Spotify-style section header: bold title and a quiet "Show all" link. */
@Composable
fun SectionHeader(
    kicker: String,
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = BlexsyTypography.SectionTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            Spacer(modifier = Modifier.width(10.dp))
            val label = when (action.uppercase()) {
                "SEE ALL", "SHOW ALL" -> stringResource(R.string.action_show_all)
                "PLAY ALL" -> stringResource(R.string.action_play_all)
                "LESS" -> stringResource(R.string.action_show_less)
                else -> action.titleCased()
            }
            Text(
                text = label,
                color = BlexsyColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        } else {
            // Keep the parameter meaningful for callers that only pass a kicker.
            if (kicker.isNotBlank()) Spacer(modifier = Modifier.width(0.dp))
        }
    }
}

/** Square artwork with Spotify-tight rounded corners. */
@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    size: Int = 48,
    shapeRadius: Int = 4
) {
    val displayUrl = remember(url) { url.toHighResArtwork() }
    Box(
        modifier = modifier
            .then(if (size > 0) Modifier.size(size.dp) else Modifier)
            .clip(RoundedCornerShape(shapeRadius.dp))
            .background(BlexsyColors.SurfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        if (!displayUrl.isNullOrBlank()) {
            AsyncImage(
                model = displayUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = BlexsyColors.TextTertiary,
                modifier = Modifier.size((size * 0.4).coerceIn(18.0, 44.0).dp)
            )
        }
    }
}

/**
 * Material 3 Expressive loading indicator using Google's standard API.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = BlexsyColors.Primary
) {
    // ponytail: wraps official LoadingIndicator; replace call sites directly when API stabilizes
    LoadingIndicator(modifier = modifier, color = color)
}

/** Big green circular play button used across hero headers and cards. */
@Composable
fun PlayCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 52,
    isPlaying: Boolean = false,
    isLoading: Boolean = false,
    enabled: Boolean = true
) {
    val haptic = rememberBlexsyHaptic()
    FilledIconButton(
        onClick = {
            haptic.click()
            onClick()
        },
        enabled = enabled,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = BlexsyColors.Primary,
            contentColor = Color.Black,
            disabledContainerColor = BlexsyColors.SurfaceContainerHigh,
            disabledContentColor = BlexsyColors.TextTertiary
        ),
        modifier = modifier.size(size.dp)
    ) {
        if (isLoading) {
            ExpressiveLoadingIndicator(
                modifier = Modifier.size((size * 0.44).dp),
                color = Color.Black
            )
        } else {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                modifier = Modifier.size((size * 0.5).dp)
            )
        }
    }
}

@Composable
fun TrackCard(
    track: Track,
    onClick: () -> Unit,
    rank: Int? = null,
    isPlayingThis: Boolean = false,
    showRank: Boolean = rank != null
) {
    Column(
        modifier = Modifier
            .width(152.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BlexsyColors.Surface)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            CoverImage(
                url = track.image,
                size = 0,
                shapeRadius = 0,
                modifier = Modifier.fillMaxSize()
            )
            val rankNum = if (showRank) (rank ?: track.rank) else null
            if (rankNum != null) {
                Row(
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(BlexsyColors.BackgroundDeep.copy(alpha = 0.8f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rankNum.toString().padStart(2, '0'),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (rankNum == 1) BlexsyColors.Primary else Color.White
                    )
                    when (track.rankStatus?.lowercase()) {
                        "up" -> {
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Rounded.ArrowDropUp,
                                contentDescription = stringResource(R.string.a11y_rank_up),
                                tint = BlexsyColors.Success,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        "down" -> {
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Rounded.ArrowDropDown,
                                contentDescription = stringResource(R.string.a11y_rank_down),
                                tint = BlexsyColors.Danger,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        "new" -> {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.rank_new),
                                color = BlexsyColors.Primary,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Text(
                text = track.displayTitle,
                style = BlexsyTypography.Title.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = if (isPlayingThis) BlexsyColors.Primary else BlexsyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.artist,
                style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AlbumCard(
    album: AlbumSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(152.dp),
    isPlaying: Boolean = false,
    isLoading: Boolean = false,
    onPlay: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BlexsyColors.Surface)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            CoverImage(
                url = album.image,
                size = 0,
                shapeRadius = 0,
                modifier = Modifier.fillMaxSize()
            )
            if (onPlay != null) {
                PlayCircleButton(
                    onClick = onPlay,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    size = 36,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .shadow(4.dp, CircleShape)
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Text(
                text = album.name,
                style = BlexsyTypography.Title.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = if (isPlaying) BlexsyColors.Primary else BlexsyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = album.artist ?: album.type,
                style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TrackTile(
    track: Track,
    isPlayingThis: Boolean = false,
    onClick: () -> Unit,
    index: Int? = null,
    onOpenArtist: ((String) -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    showArtwork: Boolean = true,
    showRank: Boolean = true,
    onMore: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val rankNumber = track.rank ?: (if (index != null) index + 1 else null)

        if (showRank && rankNumber != null) {
            Column(
                modifier = Modifier.width(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = rankNumber.toString(),
                    style = BlexsyTypography.Subtitle.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPlayingThis) BlexsyColors.Primary else BlexsyColors.TextMuted
                    ),
                    maxLines = 1
                )
                when (track.rankStatus?.lowercase()) {
                    "up" -> {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropUp,
                            contentDescription = stringResource(R.string.a11y_rank_up),
                            tint = BlexsyColors.Success,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    "down" -> {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = stringResource(R.string.a11y_rank_down),
                            tint = BlexsyColors.Danger,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    "new" -> {
                        Text(
                            text = stringResource(R.string.rank_new),
                            color = BlexsyColors.Primary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 10.sp
                        )
                    }
                    "same", "equal" -> {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .width(6.dp)
                                .height(1.5.dp)
                                .background(BlexsyColors.TextMuted.copy(alpha = 0.5f), RoundedCornerShape(1.dp))
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
        }

        if (showArtwork) {
            CoverImage(url = track.image, size = 48, shapeRadius = 4)
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.displayTitle,
                style = if (isPlayingThis) BlexsyTypography.TitleActive else BlexsyTypography.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlayingThis) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.a11y_playing),
                        tint = BlexsyColors.Primary,
                        modifier = Modifier
                            .padding(end = 5.dp)
                            .size(13.dp)
                    )
                }
                val artistRef = track.primaryArtistRef
                Text(
                    text = track.artist,
                    style = BlexsyTypography.Subtitle.copy(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onOpenArtist != null && artistRef != null) {
                        Modifier
                            .clip(RoundedCornerShape(2.dp))
                            .clickable { onOpenArtist(artistRef) }
                    } else Modifier
                )
            }
        }

        if (onToggleFavorite != null) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(if (isFavorite) R.string.player_remove_favorite else R.string.player_save_favorite),
                    tint = if (isFavorite) BlexsyColors.Primary else BlexsyColors.TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (onMore != null) {
            IconButton(
                onClick = onMore,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.a11y_more_options),
                    tint = BlexsyColors.TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Spotify-style track context sheet. Opened from the three-dot button in every track list;
 * only the actions that make sense for the caller are rendered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOptionsSheet(
    track: Track,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
    onOpenAlbum: (() -> Unit)? = null
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val scope = rememberCoroutineScope()

    // Play the hide animation first so the sheet never vanishes mid-swipe.
    fun runThenDismiss(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            action()
        }
    }

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
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(url = track.image, size = 48, shapeRadius = 6)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.displayTitle,
                        style = BlexsyTypography.Title.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        style = BlexsyTypography.Subtitle.copy(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val playbackItems = listOfNotNull(
                onPlay?.let { SheetOptionItem(Icons.Rounded.PlayArrow, stringResource(R.string.player_play)) { runThenDismiss(it) } },
                onPlayNext?.let { SheetOptionItem(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.player_queue_next)) { runThenDismiss(it) } },
                onAddToQueue?.let { SheetOptionItem(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.player_toss_queue)) { runThenDismiss(it) } },
                onAddToPlaylist?.let { SheetOptionItem(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.player_add_to_playlist)) { runThenDismiss(it) } },
                onStartRadio?.let { SheetOptionItem(Icons.Rounded.Radio, stringResource(R.string.player_start_radio)) { runThenDismiss(it) } },
                onToggleFavorite?.let {
                    SheetOptionItem(
                        icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        label = if (isFavorite) stringResource(R.string.player_remove_favorite) else stringResource(R.string.player_save_favorite),
                        tint = if (isFavorite) BlexsyColors.Primary else BlexsyColors.TextPrimary
                    ) { runThenDismiss(it) }
                }
            )

            if (playbackItems.isNotEmpty()) {
                GroupedOptionCard(items = playbackItems)
            }

            val navItems = listOfNotNull(
                onOpenArtist?.let { SheetOptionItem(Icons.Rounded.Person, stringResource(R.string.player_view_artist)) { runThenDismiss(it) } },
                onOpenAlbum?.let { SheetOptionItem(Icons.Rounded.Album, stringResource(R.string.player_view_album)) { runThenDismiss(it) } }
            )

            if (navItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                GroupedOptionCard(items = navItems)
            }
        }
    }
}

/** Bottom sheet to pick an existing playlist for a track, with a button to create one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    track: Track,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: () -> Unit
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val scope = rememberCoroutineScope()

    fun runThenDismiss(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            action()
        }
    }

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
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = stringResource(R.string.playlist_add_to_title),
                style = BlexsyTypography.SectionTitle.copy(fontSize = 18.sp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (playlists.isEmpty()) {
                Text(
                    text = stringResource(R.string.playlist_empty_title),
                    style = BlexsyTypography.BodyMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    items(playlists) { playlist ->
                        val isAlreadyIn = playlist.tracks.any { it.id == track.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { runThenDismiss { onSelectPlaylist(playlist) } }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverImage(
                                url = playlist.tracks.firstOrNull()?.image,
                                size = 48,
                                shapeRadius = 6
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = BlexsyTypography.Title.copy(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.playlist_tracks_count, playlist.tracks.size),
                                    style = BlexsyTypography.Subtitle.copy(fontSize = 12.sp)
                                )
                            }
                            if (isAlreadyIn) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.playlist_track_added),
                                    tint = BlexsyColors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            BlexsyButton(
                text = stringResource(R.string.playlist_create_new),
                leadingIcon = Icons.Rounded.Add,
                onClick = { runThenDismiss(onCreatePlaylist) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Lg,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Bottom sheet to pick an artist when a track features multiple artists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectArtistSheet(
    artists: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSelectArtist: (String) -> Unit
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val scope = rememberCoroutineScope()

    fun runThenDismiss(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            action()
        }
    }

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
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = stringResource(R.string.player_select_artist_title),
                style = BlexsyTypography.SectionTitle.copy(fontSize = 18.sp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            val items = artists.map { (id, name) ->
                SheetOptionItem(
                    icon = Icons.Rounded.Person,
                    label = name,
                    onClick = { runThenDismiss { onSelectArtist(id) } }
                )
            }
            GroupedOptionCard(items = items)
        }
    }
}

/** Option item for grouped card lists in bottom sheets. */
data class SheetOptionItem(
    val icon: ImageVector,
    val label: String,
    val subtitle: String? = null,
    val tint: Color = BlexsyColors.TextPrimary,
    val onClick: () -> Unit
)

/** Returns top-rounded, rectangular, or bottom-rounded shape depending on item position. */
fun groupedCardShape(index: Int, count: Int, radius: Dp = 14.dp): Shape = when {
    count <= 1 -> RoundedCornerShape(radius)
    index == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = 0.dp, bottomEnd = 0.dp)
    index == count - 1 -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = radius, bottomEnd = radius)
    else -> RectangleShape
}

/** Card container holding a group of connected list items. */
@Composable
fun GroupedOptionCard(
    items: List<SheetOptionItem>,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    containerColor: Color = BlexsyColors.SurfaceContainerHigh
) {
    if (items.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor)
    ) {
        items.forEachIndexed { index, item ->
            val itemShape = groupedCardShape(index, items.size, radius = cornerRadius)
            Surface(
                onClick = item.onClick,
                shape = itemShape,
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = item.tint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.label,
                                style = BlexsyTypography.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                                color = item.tint
                            )
                            if (item.subtitle != null) {
                                Text(
                                    text = item.subtitle,
                                    style = BlexsyTypography.Caption.copy(fontSize = 11.sp, color = BlexsyColors.TextSecondary)
                                )
                            }
                        }
                    }
                    if (index < items.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 54.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

/** One row inside [ActionSheet]. */
data class ActionSheetItem(
    val label: String,
    val icon: ImageVector,
    val tint: Color = BlexsyColors.TextPrimary,
    val onClick: () -> Unit
)

/** Generic Spotify-style bottom sheet for page-level actions (profile menu, etc.). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    title: String,
    items: List<ActionSheetItem>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val scope = rememberCoroutineScope()

    fun runThenDismiss(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            action()
        }
    }

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
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = title,
                style = BlexsyTypography.SectionTitle.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            GroupedOptionCard(
                items = items.map { item ->
                    SheetOptionItem(
                        icon = item.icon,
                        label = item.label,
                        tint = item.tint,
                        onClick = { runThenDismiss(item.onClick) }
                    )
                }
            )
        }
    }
}
