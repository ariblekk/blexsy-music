package com.blek.blexsy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.blek.blexsy.R
import com.blek.blexsy.model.*

private fun getEffectiveWords(line: LyricLine, nextLineTimeMs: Long?): List<LyricWord> {
    if (line.hasWordSync) return line.words
    val regex = Regex("""\S+|\s+""")
    val tokens = regex.findAll(line.text).map { it.value }.toList()
    if (tokens.isEmpty()) return emptyList()

    val nextTime = nextLineTimeMs ?: (line.timeMs + 3200L)
    val lineDuration = (nextTime - line.timeMs).coerceIn(1000L, 7000L)
    val singingDuration = (lineDuration * 0.82f).toLong()
    val nonSpaceChars = line.text.count { !it.isWhitespace() }.coerceAtLeast(1)

    var currentOffsetMs = line.timeMs
    val result = ArrayList<LyricWord>(tokens.size)
    for (token in tokens) {
        result.add(LyricWord(timeMs = currentOffsetMs, text = token))
        if (token.isNotBlank()) {
            val share = (token.length.toFloat() / nonSpaceChars) * singingDuration
            currentOffsetMs += share.toLong()
        }
    }
    return result
}

private val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)

@Composable
fun SyncedLyricsView(
    lyrics: LyricsResult?,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    onSeek: ((Long) -> Unit)? = null,
    showTranslation: Boolean = false,
    translatedLines: Map<Int, String> = emptyMap(),
    isTranslating: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(top = 16.dp, bottom = 32.dp),
    isKeepAwake: Boolean = false,
    onToggleKeepAwake: (() -> Unit)? = null,
    onToggleTranslation: (() -> Unit)? = null,
    isLoading: Boolean = false
) {
    if (isLoading) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center
        ) {
            ExpressiveLoadingIndicator(
                modifier = Modifier.size(36.dp),
                color = BlexsyColors.Primary
            )
        }
        return
    }

    if (lyrics == null || !lyrics.isSynced) {
        val scrollState = rememberScrollState()
        val plainLyrics = lyrics?.let { stripLrcTimestamps(it.lyrics) }?.ifBlank { null }
        Box(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(contentPadding),
            contentAlignment = if (plainLyrics != null) Alignment.TopCenter else Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (plainLyrics != null) {
                    Text(
                        text = plainLyrics,
                        color = Color.White.copy(alpha = 0.85f),
                        style = BlexsyTypography.Body.copy(
                            fontSize = 18.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    AsyncImage(
                        model = R.drawable.lyrics_empty,
                        contentDescription = stringResource(R.string.lyrics_unavailable_desc),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.lyrics_empty_vibes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = BlexsyTypography.Body.copy(fontWeight = FontWeight.Medium),
                        textAlign = TextAlign.Center
                    )
                }
                if (plainLyrics != null) {
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        text = stringResource(R.string.lyrics_source, lyrics.displaySource),
                        style = BlexsyTypography.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    var lastReceivedPosition by remember { mutableLongStateOf(currentPositionMs) }
    var lastReceivedTime by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    val haptic = rememberBlexsyHaptic()

    LaunchedEffect(currentPositionMs) {
        lastReceivedPosition = currentPositionMs
        lastReceivedTime = android.os.SystemClock.elapsedRealtime()
    }

    // ponytail: Frame-interpolated 60/120fps presentation position for Apple Music-grade fluid lyrics animation; add when butuh audio hardware clock (AudioTrack timestamp API).
    val smoothPositionMs by produceState(
        initialValue = currentPositionMs,
        isPlaying,
        lastReceivedPosition,
        lastReceivedTime
    ) {
        if (!isPlaying) {
            value = lastReceivedPosition
            return@produceState
        }
        while (true) {
            withFrameMillis {
                val elapsed = android.os.SystemClock.elapsedRealtime() - lastReceivedTime
                value = (lastReceivedPosition + elapsed).coerceAtLeast(0L)
            }
        }
    }

    val lines = lyrics.parsedLines
    val listState = rememberLazyListState()
    var userScrolledAway by remember { mutableStateOf(false) }

    val activeIndex = lines.indexOfLast { it.timeMs <= smoothPositionMs }.coerceAtLeast(0)

    val lyricUserScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && (available.y < -3f || available.y > 3f)) {
                    userScrolledAway = true
                }
                return Offset.Zero
            }
        }
    }

    val isAutoScrollPaused by remember(listState, userScrolledAway, activeIndex) {
        derivedStateOf {
            if (!userScrolledAway) {
                false
            } else {
                val visible = listState.layoutInfo.visibleItemsInfo
                if (visible.isEmpty()) true else visible.none { it.index == activeIndex }
            }
        }
    }

    LaunchedEffect(isAutoScrollPaused) {
        if (!isAutoScrollPaused && userScrolledAway) {
            userScrolledAway = false
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportHeightPx = constraints.maxHeight

        LaunchedEffect(activeIndex, userScrolledAway) {
            if (userScrolledAway || lines.isEmpty()) return@LaunchedEffect
            if (activeIndex !in lines.indices) return@LaunchedEffect
            val targetY = (viewportHeightPx * 0.26f)
            var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
            if (item == null) {
                listState.scrollToItem(activeIndex)
                item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
            }
            if (item != null) {
                val delta = (item.offset - targetY)
                if (kotlin.math.abs(delta) > 1f) {
                    listState.animateScrollBy(
                        value = delta,
                        animationSpec = tween(durationMillis = 420, easing = LYRIC_EASING)
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // ponytail: fixed-ratio edge fade; upgrade to dynamic px-based stops if viewport height varies widely.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.07f to Color.Black,
                            0.90f to Color.Black,
                            1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
                .nestedScroll(lyricUserScrollConnection)
                .padding(horizontal = 24.dp),
            contentPadding = contentPadding
        ) {
            itemsIndexed(lines, key = { idx, line -> "${line.timeMs}_$idx" }) { idx, line ->
                val isActive = idx == activeIndex
                val translation = if (showTranslation) translatedLines[idx] else null
                val nextLineTime = lines.getOrNull(idx + 1)?.timeMs

                if (line.isGap) {
                    val until = lines.getOrNull(idx + 1)?.timeMs ?: (line.timeMs + 4000L)
                    val gapSpan = (until - line.timeMs).coerceAtLeast(1L)
                    val through = ((smoothPositionMs - line.timeMs).toFloat() / gapSpan).coerceIn(0f, 1f)

                    val swell by animateFloatAsState(
                        targetValue = if (isActive) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = if (isActive) 400 else 350,
                            easing = LYRIC_EASING
                        ),
                        label = "gap_swell"
                    )

                    val dotAlpha by animateFloatAsState(
                        targetValue = if (isActive) 1f else 0f,
                        animationSpec = tween(durationMillis = 350, easing = LYRIC_EASING),
                        label = "gap_dot_alpha"
                    )

                    if (swell > 0.01f || isActive) {
                        val isEndAligned = line.alignment == LyricAlignment.END
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp * swell)
                                .clipToBounds()
                                .clickable { onSeek?.invoke(line.timeMs) },
                            contentAlignment = if (isEndAligned) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 58.dp, height = 16.dp)
                                    .graphicsLayer {
                                        val grow = 0.76f + (1f - 0.76f) * swell
                                        scaleX = grow
                                        scaleY = grow
                                        transformOrigin = if (isEndAligned) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)
                                        alpha = dotAlpha
                                    }
                                    .drawBehind {
                                        val dotCount = 3
                                        val dotRadius = 5.dp.toPx()
                                        val stride = 18.dp.toPx()
                                        for (dot in 0 until dotCount) {
                                            val lit = (through * dotCount - dot).coerceIn(0f, 1f)
                                            val a = 0.25f + 0.75f * lit
                                            drawCircle(
                                                color = Color.White.copy(alpha = a),
                                                radius = dotRadius,
                                                center = Offset(dotRadius + dot * stride, size.height / 2f)
                                            )
                                        }
                                    }
                            )
                        }
                    }
                } else {
                    val isEndAligned = line.alignment == LyricAlignment.END
                    val textAlign = if (isEndAligned) TextAlign.End else TextAlign.Start
                    val alignment = if (isEndAligned) Alignment.End else Alignment.Start

                    val animatedAlpha by animateFloatAsState(
                        targetValue = if (isActive) 1f else 0.35f,
                        animationSpec = tween(durationMillis = 400, easing = LYRIC_EASING),
                        label = "lyric_alpha"
                    )
                    val animatedScale by animateFloatAsState(
                        targetValue = if (isActive) 1.04f else 0.96f,
                        animationSpec = spring(
                            dampingRatio = 0.68f,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "lyric_scale"
                    )
                    val animatedTranslationX by animateFloatAsState(
                        targetValue = if (isActive) (if (isEndAligned) -6f else 6f) else 0f,
                        animationSpec = spring(
                            dampingRatio = 0.65f,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "lyric_elastic_x"
                    )
                    val blurRadius by animateDpAsState(
                        targetValue = if (isActive) 0.dp else 1.2.dp,
                        animationSpec = tween(durationMillis = 400, easing = LYRIC_EASING),
                        label = "lyric_blur"
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeek?.invoke(line.timeMs) }
                            .padding(vertical = if (showTranslation && !translation.isNullOrBlank()) 10.dp else 12.dp)
                            .graphicsLayer {
                                this.alpha = animatedAlpha
                                this.scaleX = animatedScale
                                this.scaleY = animatedScale
                                this.translationX = animatedTranslationX
                                this.transformOrigin = if (isEndAligned) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)
                            }
                            .blur(radius = blurRadius),
                        horizontalAlignment = alignment
                    ) {
                        if (isActive) {
                            val words = remember(line, nextLineTime) { getEffectiveWords(line, nextLineTime) }
                            val annotated = buildAnnotatedString {
                                for ((wIdx, w) in words.withIndex()) {
                                    val nextTime = words.getOrNull(wIdx + 1)?.timeMs ?: (w.timeMs + 400L)
                                    val gap = (nextTime - w.timeMs).coerceAtLeast(60L)
                                    val duration = if (gap > 1500L) 1000L else gap
                                    val progress = when {
                                        smoothPositionMs < w.timeMs -> 0f
                                        smoothPositionMs >= w.timeMs + duration -> 1f
                                        else -> ((smoothPositionMs - w.timeMs).toFloat() / duration).coerceIn(0f, 1f)
                                    }
                                    val wordAlpha = 0.35f + 0.65f * progress
                                    withStyle(
                                        SpanStyle(
                                            color = Color.White.copy(alpha = wordAlpha)
                                        )
                                    ) {
                                        append(w.text)
                                    }
                                }
                            }
                            Text(
                                text = annotated,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 32.sp,
                                textAlign = textAlign,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = line.text,
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 32.sp,
                                textAlign = textAlign,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Vokal Latar (Background / Answering vocal)
                        if (line.background != null && line.background.text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = line.background.text,
                                color = Color.White.copy(alpha = if (isActive) 0.82f else 0.28f),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                fontStyle = FontStyle.Italic,
                                lineHeight = 24.sp,
                                textAlign = textAlign,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (!translation.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = translation,
                                color = if (isActive) BlexsyColors.Primary.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.40f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 20.sp,
                                textAlign = textAlign,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            if (showTranslation && isTranslating && translatedLines.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.lyrics_translating),
                        style = BlexsyTypography.Caption.copy(color = BlexsyColors.Primary),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.lyrics_source, lyrics.displaySource),
                    style = BlexsyTypography.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 28.dp, bottom = 40.dp)
                )
            }
        }

        // Floating Connected Button Group: [Keep Awake] | [Resume autoscroll] | [Translate]
        AnimatedVisibility(
            visible = isAutoScrollPaused,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            val hasLeading = onToggleKeepAwake != null
            val hasTrailing = onToggleTranslation != null

            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Keep Awake Button (Leading)
                if (onToggleKeepAwake != null) {
                    FilledTonalToggleButton(
                        checked = isKeepAwake,
                        onCheckedChange = {
                            haptic.click()
                            onToggleKeepAwake()
                        },
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LightMode,
                            contentDescription = stringResource(R.string.a11y_keep_screen_on),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                // 2. Resume Autoscroll Button (Middle)
                val middleShapes = when {
                    hasLeading && hasTrailing -> ButtonShapes(
                        shape = ButtonGroupDefaults.connectedMiddleButtonShapes().shape,
                        pressedShape = ButtonGroupDefaults.connectedMiddleButtonShapes().pressedShape
                    )
                    hasLeading -> ButtonShapes(
                        shape = ButtonGroupDefaults.connectedTrailingButtonShapes().shape,
                        pressedShape = ButtonGroupDefaults.connectedTrailingButtonShapes().pressedShape
                    )
                    hasTrailing -> ButtonShapes(
                        shape = ButtonGroupDefaults.connectedLeadingButtonShapes().shape,
                        pressedShape = ButtonGroupDefaults.connectedLeadingButtonShapes().pressedShape
                    )
                    else -> ButtonDefaults.shapes()
                }

                FilledTonalButton(
                    onClick = {
                        haptic.click()
                        userScrolledAway = false
                    },
                    shapes = middleShapes,
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.lyrics_resume_autoscroll),
                        style = BlexsyTypography.Label.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    )
                }

                // 3. Translate Button (Trailing)
                if (onToggleTranslation != null) {
                    FilledTonalToggleButton(
                        checked = showTranslation,
                        onCheckedChange = {
                            haptic.click()
                            onToggleTranslation()
                        },
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = stringResource(R.string.a11y_translate_lyrics),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}
