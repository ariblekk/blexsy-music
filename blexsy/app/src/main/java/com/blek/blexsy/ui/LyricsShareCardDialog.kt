package com.blek.blexsy.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import coil3.compose.AsyncImage
import com.blek.blexsy.R
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.model.LyricLine
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.isCatalogArtistId
import com.blek.blexsy.model.toHighResArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private enum class ShareLyricsStep {
    SELECT_LINES,
    CUSTOMIZE_CARD
}

private enum class ActiveCustomizeTool {
    NONE,
    IMAGE_PICKER
}

/**
 * Color palette option for the Share Lyrics card.
 */
private data class LyricsCardPalette(
    val name: String,
    val cardTop: Color,
    val cardBottom: Color,
    val textColor: Color,
    val secondaryText: Color
)

/**
 * Card background source: solid/gradient color OR an image (Album Artwork, Artist Banner, Artist Profile).
 */
private enum class LyricsCardBgSource(val label: String) {
    COLOR("Warna"),
    ARTWORK("Artwork"),
    ARTIST_BANNER("Banner Artis"),
    ARTIST_PROFILE("Profil Artis")
}

/**
 * Lyric typography preset: Klasik, Lebar, Ramping, Miring.
 */
private enum class LyricsFontPreset(
    val label: String,
    val letterSpacingSp: TextUnit,
    val canvasLetterSpacing: Float,
    val scaleX: Float,
    val isItalic: Boolean,
    val weight: FontWeight
) {
    KLASIK(
        label = "Klasik",
        letterSpacingSp = (-0.3).sp,
        canvasLetterSpacing = -0.012f,
        scaleX = 1.0f,
        isItalic = false,
        weight = FontWeight.Bold
    ),
    LEBAR(
        label = "Lebar",
        letterSpacingSp = 0.8.sp,
        canvasLetterSpacing = 0.04f,
        scaleX = 1.06f,
        isItalic = false,
        weight = FontWeight.ExtraBold
    ),
    RAMPING(
        label = "Ramping",
        letterSpacingSp = (-0.6).sp,
        canvasLetterSpacing = -0.03f,
        scaleX = 0.90f,
        isItalic = false,
        weight = FontWeight.SemiBold
    ),
    MIRING(
        label = "Miring",
        letterSpacingSp = 0.sp,
        canvasLetterSpacing = 0.0f,
        scaleX = 1.0f,
        isItalic = true,
        weight = FontWeight.Medium
    )
}

private val FontFamilyKlasik = FontFamily(
    Font(R.font.sf_pro_display_bold, FontWeight.Bold)
)
private val FontFamilyLebar = FontFamily(
    Font(R.font.deezer_extrabold, FontWeight.ExtraBold),
    Font(R.font.deezer_bold, FontWeight.Bold)
)
private val FontFamilyRamping = FontFamily(
    Font(R.font.manrope_variable, FontWeight.SemiBold),
    Font(R.font.sf_pro_display_semibold, FontWeight.SemiBold)
)
private val FontFamilyMiring = FontFamily(
    Font(R.font.sf_pro_display_medium_italic, FontWeight.Medium, FontStyle.Italic)
)

private fun LyricsFontPreset.composeFontFamily(): FontFamily = when (this) {
    LyricsFontPreset.KLASIK -> FontFamilyKlasik
    LyricsFontPreset.LEBAR -> FontFamilyLebar
    LyricsFontPreset.RAMPING -> FontFamilyRamping
    LyricsFontPreset.MIRING -> FontFamilyMiring
}

private fun LyricsFontPreset.resolveAndroidTypeface(context: Context): Typeface {
    fun load(resId: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull() ?: fallback

    return when (this) {
        LyricsFontPreset.KLASIK -> load(
            R.font.sf_pro_display_bold,
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        )
        LyricsFontPreset.LEBAR -> load(
            R.font.deezer_extrabold,
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        )
        LyricsFontPreset.RAMPING -> load(
            R.font.sf_pro_display_semibold,
            Typeface.create("sans-serif-condensed", Typeface.BOLD)
        )
        LyricsFontPreset.MIRING -> load(
            R.font.sf_pro_display_medium_italic,
            Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        )
    }
}

private fun lyricScaleStep(totalChars: Int): Int = when {
    totalChars <= 55 -> 0
    totalChars <= 115 -> 1
    totalChars <= 185 -> 2
    else -> 3
}

private fun buildPalettes(dominantColor: Color, accent: Color): List<LyricsCardPalette> {
    val base = if (dominantColor == Color.Transparent || dominantColor == BlexsyColors.Background) {
        Color(0xFF474754)
    } else dominantColor
    val readableBase = if (base.luminance() > 0.42f) lerp(base, Color.Black, 0.35f) else lerp(base, Color(0xFF4A4A58), 0.25f)
    val accentBase = if (accent.luminance() > 0.55f) lerp(accent, Color.Black, 0.25f) else accent
    val accentText = if (accentBase.luminance() > 0.5f) Color.Black else Color.White

    return listOf(
        LyricsCardPalette(
            name = "Slate",
            cardTop = lerp(readableBase, Color.White, 0.08f),
            cardBottom = lerp(readableBase, Color.Black, 0.16f),
            textColor = Color.White,
            secondaryText = Color.White.copy(alpha = 0.72f)
        ),
        LyricsCardPalette(
            name = "Deep",
            cardTop = lerp(readableBase, Color.Black, 0.38f),
            cardBottom = lerp(readableBase, Color.Black, 0.64f),
            textColor = Color.White,
            secondaryText = Color.White.copy(alpha = 0.68f)
        ),
        LyricsCardPalette(
            name = "Crimson",
            cardTop = Color(0xFF8C2430),
            cardBottom = Color(0xFF56121A),
            textColor = Color.White,
            secondaryText = Color.White.copy(alpha = 0.74f)
        ),
        LyricsCardPalette(
            name = "Ocean",
            cardTop = Color(0xFF234D73),
            cardBottom = Color(0xFF132D46),
            textColor = Color.White,
            secondaryText = Color.White.copy(alpha = 0.74f)
        ),
        LyricsCardPalette(
            name = "Emerald",
            cardTop = Color(0xFF1C5E48),
            cardBottom = Color(0xFF0E382A),
            textColor = Color.White,
            secondaryText = Color.White.copy(alpha = 0.74f)
        ),
        LyricsCardPalette(
            name = "Purple",
            cardTop = Color(0xFF523878),
            cardBottom = Color(0xFF311F4C),
            textColor = Color.White,
            secondaryText = Color.White.copy(alpha = 0.74f)
        ),
        LyricsCardPalette(
            name = "Accent",
            cardTop = accentBase,
            cardBottom = lerp(accentBase, Color.Black, 0.22f),
            textColor = accentText,
            secondaryText = accentText.copy(alpha = 0.70f)
        ),
        LyricsCardPalette(
            name = "Midnight",
            cardTop = Color(0xFF28282C),
            cardBottom = Color(0xFF18181B),
            textColor = Color.White,
            secondaryText = Color.White.copy(alpha = 0.68f)
        ),
        LyricsCardPalette(
            name = "Light",
            cardTop = Color(0xFFF6F6F9),
            cardBottom = Color(0xFFE6E6EC),
            textColor = Color(0xFF121216),
            secondaryText = Color(0xFF121216).copy(alpha = 0.62f)
        )
    )
}

/**
 * Enforces contiguous (consecutive) line selection in [selectableLines] order (`0..selectableLines.lastIndex`).
 * Users cannot skip a lyric line in the middle:
 * - Tapping a line adjacent to the current range extends the range (up to 5 lines).
 * - Tapping the first or last line of the current range shrinks it (down to 1 line).
 * - Tapping a line inside the range trims the range to end at that line.
 * - Tapping a non-adjacent line resets the selection to start fresh at that line.
 */
private fun updateContiguousSelection(
    currentPositions: IntRange,
    tappedPos: Int,
    maxLines: Int = 5,
    onMaxReached: () -> Unit
): IntRange {
    val first = currentPositions.first
    val last = currentPositions.last
    val count = last - first + 1

    return when {
        // Single selected line tapped again -> keep it selected
        count == 1 && tappedPos == first -> currentPositions
        // Tapped the top edge of a multi-line range -> shrink from top
        tappedPos == first && count > 1 -> (first + 1)..last
        // Tapped the bottom edge of a multi-line range -> shrink from bottom
        tappedPos == last && count > 1 -> first..(last - 1)
        // Tapped immediately above the range -> extend upwards by 1
        tappedPos == first - 1 -> {
            if (count < maxLines) {
                tappedPos..last
            } else {
                onMaxReached()
                currentPositions
            }
        }
        // Tapped immediately below the range -> extend downwards by 1
        tappedPos == last + 1 -> {
            if (count < maxLines) {
                first..tappedPos
            } else {
                onMaxReached()
                currentPositions
            }
        }
        // Tapped inside the range -> trim range to first..tappedPos
        tappedPos in (first + 1) until last -> first..tappedPos
        // Tapped a non-adjacent line (skipping lines) -> if within maxLines from an edge, fill contiguously; otherwise start new range at tappedPos
        else -> tappedPos..tappedPos
    }
}

@Composable
fun LyricsShareCardDialog(
    track: Track,
    lines: List<LyricLine>,
    initialLineIndex: Int,
    translatedLines: Map<Int, String> = emptyMap(),
    dominantColor: Color,
    api: BlexsyApiClient? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberBlexsyHaptic()

    val selectableLines = remember(lines) {
        lines.mapIndexedNotNull { index, line ->
            if (!line.isGap && line.text.isNotBlank()) index to line else null
        }
    }

    // Store selection as a contiguous index range into `selectableLines` so lines can NEVER be skipped
    var selectedPosRange by remember(initialLineIndex, selectableLines) {
        val initPos = selectableLines.indexOfFirst { it.first == initialLineIndex }.coerceAtLeast(0)
        // Default: select up to 2 consecutive lines starting from the long-pressed line if available
        val endPos = (initPos + 1).coerceAtMost((selectableLines.size - 1).coerceAtLeast(0))
        mutableStateOf(initPos..endPos)
    }

    var currentStep by remember { mutableStateOf(ShareLyricsStep.CUSTOMIZE_CARD) }
    var activeTool by remember { mutableStateOf(ActiveCustomizeTool.NONE) }

    val hasAnyTranslation = remember(translatedLines) { translatedLines.isNotEmpty() }
    var includeTranslation by remember { mutableStateOf(false) }
    var selectedPaletteIndex by remember { mutableIntStateOf(0) }
    var selectedBgSource by remember { mutableStateOf(LyricsCardBgSource.COLOR) }
    var selectedFontPreset by remember { mutableStateOf(LyricsFontPreset.KLASIK) }
    var isExporting by remember { mutableStateOf(false) }

    // Fetch artist banner & artist profile avatar in background
    var artistBannerUrl by remember(track.id) { mutableStateOf<String?>(null) }
    var artistProfileUrl by remember(track.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(track.id, api) {
        if (api == null) return@LaunchedEffect
        runCatching {
            val rawRef = track.primaryArtistRef
                ?: track.artistRefs.firstOrNull()?.first
                ?: track.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim()
            if (!rawRef.isNullOrBlank()) {
                val resolvedId = if (rawRef.isCatalogArtistId()) rawRef else api.resolveArtistId(rawRef)
                if (!resolvedId.isNullOrBlank()) {
                    val detail = api.artist(resolvedId)
                    artistBannerUrl = (detail.banner ?: detail.gallery.firstOrNull() ?: detail.image)?.toHighResArtwork()
                    artistProfileUrl = (detail.image ?: detail.banner)?.toHighResArtwork()
                }
            }
        }
    }

    val primaryAccent = BlexsyColors.Primary
    val palettes = remember(dominantColor, primaryAccent) { buildPalettes(dominantColor, primaryAccent) }
    val palette = palettes.getOrElse(selectedPaletteIndex) { palettes.first() }

    val cardBgImageUrl: String? = remember(selectedBgSource, track.image, artistBannerUrl, artistProfileUrl) {
        when (selectedBgSource) {
            LyricsCardBgSource.COLOR -> null
            LyricsCardBgSource.ARTWORK -> track.image.toHighResArtwork() ?: track.image
            LyricsCardBgSource.ARTIST_BANNER -> artistBannerUrl ?: track.image.toHighResArtwork() ?: track.image
            LyricsCardBgSource.ARTIST_PROFILE -> artistProfileUrl ?: track.image.toHighResArtwork() ?: track.image
        }
    }

    val chosenLines = remember(selectedPosRange, selectableLines, translatedLines, includeTranslation) {
        if (selectableLines.isEmpty()) emptyList()
        else {
            val safeFirst = selectedPosRange.first.coerceIn(0, selectableLines.lastIndex)
            val safeLast = selectedPosRange.last.coerceIn(safeFirst, selectableLines.lastIndex)
            (safeFirst..safeLast).map { pos ->
                val (origIdx, line) = selectableLines[pos]
                val trans = if (includeTranslation) translatedLines[origIdx] else null
                line.text to trans
            }
        }
    }
    val scaleStep = lyricScaleStep(chosenLines.sumOf { it.first.length })

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler {
            if (currentStep == ShareLyricsStep.SELECT_LINES) {
                currentStep = ShareLyricsStep.CUSTOMIZE_CARD
            } else if (activeTool != ActiveCustomizeTool.NONE) {
                activeTool = ActiveCustomizeTool.NONE
            } else {
                onDismiss()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0xFF121214),
                        0.6f to Color(0xFF1A1A20),
                        1.0f to Color(0xFF202028)
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(180))
                },
                label = "lyrics_share_step",
                modifier = Modifier.fillMaxSize()
            ) { step ->
                when (step) {
                    ShareLyricsStep.CUSTOMIZE_CARD -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Top Bar: Back arrow on left, optional translation toggle on right
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = IcArrowLeft,
                                        contentDescription = "Kembali",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                if (hasAnyTranslation) {
                                    FilterChip(
                                        selected = includeTranslation,
                                        onClick = {
                                            haptic.tick()
                                            includeTranslation = !includeTranslation
                                        },
                                        label = {
                                            Text(
                                                text = "Terjemahan",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    )
                                }
                            }

                            // Center area: The Spotify-style Lyrics Card
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                SpotifyLyricsCardPreview(
                                    track = track,
                                    lines = chosenLines,
                                    palette = palette,
                                    cardBgImageUrl = cardBgImageUrl,
                                    fontPreset = selectedFontPreset,
                                    scaleStep = scaleStep,
                                    onClickCard = {
                                        haptic.tick()
                                        currentStep = ShareLyricsStep.SELECT_LINES
                                    }
                                )
                            }

                            // Sub-picker for Image Source when "gambar" tool is active
                            if (activeTool == ActiveCustomizeTool.IMAGE_PICKER) {
                                Row(
                                    modifier = Modifier
                                        .padding(bottom = 14.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF16161A))
                                        .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(
                                        LyricsCardBgSource.ARTWORK,
                                        LyricsCardBgSource.ARTIST_BANNER,
                                        LyricsCardBgSource.ARTIST_PROFILE
                                    ).forEach { source ->
                                        val isSelected = selectedBgSource == source
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(
                                                    if (isSelected) Color.White else Color.Transparent
                                                )
                                                .clickable {
                                                    haptic.tick()
                                                    selectedBgSource = source
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = source.label,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.85f)
                                            )
                                        }
                                    }
                                }
                            }

                            // 4 Square Rounded Tool Buttons: warna | gambar | teks | lirik
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. WARNA (cycles through color palettes & sets BG to COLOR)
                                SpotifyToolButton(
                                    label = "warna",
                                    isSelected = selectedBgSource == LyricsCardBgSource.COLOR && activeTool == ActiveCustomizeTool.NONE,
                                    onClick = {
                                        haptic.tick()
                                        activeTool = ActiveCustomizeTool.NONE
                                        if (selectedBgSource != LyricsCardBgSource.COLOR) {
                                            selectedBgSource = LyricsCardBgSource.COLOR
                                        } else {
                                            selectedPaletteIndex = (selectedPaletteIndex + 1) % palettes.size
                                        }
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(Brush.verticalGradient(listOf(palette.cardTop, palette.cardBottom)))
                                            .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                                    )
                                }

                                // 2. GAMBAR (switches to image BG & toggles Artwork / Banner Artis / Profil Artis)
                                SpotifyToolButton(
                                    label = "gambar",
                                    isSelected = selectedBgSource != LyricsCardBgSource.COLOR || activeTool == ActiveCustomizeTool.IMAGE_PICKER,
                                    onClick = {
                                        haptic.tick()
                                        if (selectedBgSource == LyricsCardBgSource.COLOR) {
                                            selectedBgSource = LyricsCardBgSource.ARTWORK
                                            activeTool = ActiveCustomizeTool.IMAGE_PICKER
                                        } else if (activeTool != ActiveCustomizeTool.IMAGE_PICKER) {
                                            activeTool = ActiveCustomizeTool.IMAGE_PICKER
                                        } else {
                                            // Cycle Artwork -> Banner Artis -> Profil Artis
                                            selectedBgSource = when (selectedBgSource) {
                                                LyricsCardBgSource.ARTWORK -> LyricsCardBgSource.ARTIST_BANNER
                                                LyricsCardBgSource.ARTIST_BANNER -> LyricsCardBgSource.ARTIST_PROFILE
                                                else -> LyricsCardBgSource.ARTWORK
                                            }
                                        }
                                    }
                                ) {
                                    LandscapeImageToolIcon()
                                }

                                // 3. TEKS (cycles font: Klasik -> Lebar -> Ramping -> Miring)
                                SpotifyToolButton(
                                    label = selectedFontPreset.label.lowercase(),
                                    isSelected = false,
                                    onClick = {
                                        haptic.tick()
                                        activeTool = ActiveCustomizeTool.NONE
                                        val allPresets = LyricsFontPreset.entries
                                        val nextIdx = (allPresets.indexOf(selectedFontPreset) + 1) % allPresets.size
                                        selectedFontPreset = allPresets[nextIdx]
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "A",
                                            fontFamily = selectedFontPreset.composeFontFamily(),
                                            fontStyle = if (selectedFontPreset.isItalic) FontStyle.Italic else FontStyle.Normal,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 17.sp,
                                            color = Color(0xFF141418)
                                        )
                                    }
                                }

                                // 4. LIRIK (opens contiguous lyric line selector)
                                SpotifyToolButton(
                                    label = "lirik",
                                    isSelected = false,
                                    onClick = {
                                        haptic.tick()
                                        activeTool = ActiveCustomizeTool.NONE
                                        currentStep = ShareLyricsStep.SELECT_LINES
                                    }
                                ) {
                                    StickerLyricsToolIcon()
                                }
                            }

                            // Bottom White Pill Button "Selesai"
                            Button(
                                onClick = {
                                    if (chosenLines.isEmpty() || isExporting) return@Button
                                    haptic.click()
                                    isExporting = true
                                    scope.launch {
                                        val uri = renderAndSaveLyricsCard(
                                            context = context,
                                            track = track,
                                            lines = chosenLines,
                                            palette = palette,
                                            cardBgImageUrl = cardBgImageUrl,
                                            fontPreset = selectedFontPreset,
                                            appName = BlexsyThemeConfig.appName
                                        )
                                        isExporting = false
                                        if (uri != null) {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "image/png"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(
                                                    Intent.EXTRA_TEXT,
                                                    "\"${chosenLines.joinToString(" / ") { it.first }}\" — ${track.displayTitle} • ${track.artist}"
                                                )
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Bagikan Lirik"))
                                        } else {
                                            Toast.makeText(context, "Gagal membuat kartu lirik", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !isExporting && chosenLines.isNotEmpty(),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 14.dp),
                                modifier = Modifier.padding(bottom = 26.dp)
                            ) {
                                if (isExporting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color.Black,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        text = "Selesai",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }

                    ShareLyricsStep.SELECT_LINES -> {
                        val listState = rememberLazyListState()
                        LaunchedEffect(Unit) {
                            val target = (selectedPosRange.first - 1).coerceAtLeast(0)
                            listState.scrollToItem(target)
                        }

                        val selectedCount = selectedPosRange.last - selectedPosRange.first + 1
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { currentStep = ShareLyricsStep.CUSTOMIZE_CARD },
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = IcArrowLeft,
                                        contentDescription = "Kembali",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Pilih Lirik Berurutan",
                                        style = BlexsyTypography.Title.copy(
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    )
                                    Text(
                                        text = "$selectedCount dari maks. 5 baris dipilih",
                                        style = BlexsyTypography.Caption.copy(
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.65f)
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.width(44.dp))
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(selectableLines) { pos, (_, line) ->
                                    val isSelected = pos in selectedPosRange
                                    val isAdjacent = pos == selectedPosRange.first - 1 || pos == selectedPosRange.last + 1
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                when {
                                                    isSelected -> Color.White.copy(alpha = 0.16f)
                                                    isAdjacent -> Color.White.copy(alpha = 0.05f)
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = when {
                                                    isSelected -> Color.White
                                                    isAdjacent -> Color.White.copy(alpha = 0.15f)
                                                    else -> Color.Transparent
                                                },
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                            .clickable {
                                                haptic.tick()
                                                selectedPosRange = updateContiguousSelection(
                                                    currentPositions = selectedPosRange,
                                                    tappedPos = pos,
                                                    maxLines = 5,
                                                    onMaxReached = {
                                                        Toast.makeText(
                                                            context,
                                                            "Maksimal 5 baris lirik berurutan",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                )
                                            }
                                            .padding(horizontal = 16.dp, vertical = 14.dp)
                                    ) {
                                        Text(
                                            text = line.text,
                                            style = BlexsyTypography.Title.copy(
                                                fontFamily = LyricsFont,
                                                fontSize = 18.sp,
                                                lineHeight = 23.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.42f)
                                            )
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = {
                                        haptic.click()
                                        currentStep = ShareLyricsStep.CUSTOMIZE_CARD
                                    },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Color.Black
                                    ),
                                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 14.dp)
                                ) {
                                    Text(
                                        text = "Lanjut",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyToolButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .size(width = 74.dp, height = 78.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141417))
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = if (isSelected) Color.White.copy(alpha = 0.55f) else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier.size(30.dp),
            contentAlignment = Alignment.Center
        ) {
            iconContent()
        }
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.90f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LandscapeImageToolIcon() {
    ComposeCanvas(modifier = Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color.White,
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
        )
        // Sun dot
        drawCircle(
            color = Color(0xFF141418),
            radius = w * 0.09f,
            center = Offset(w * 0.30f, h * 0.32f)
        )
        // Mountain silhouette
        val mountain = Path().apply {
            moveTo(w * 0.10f, h * 0.82f)
            lineTo(w * 0.42f, h * 0.48f)
            lineTo(w * 0.62f, h * 0.68f)
            lineTo(w * 0.78f, h * 0.52f)
            lineTo(w * 0.92f, h * 0.82f)
            close()
        }
        drawPath(path = mountain, color = Color(0xFF141418))
    }
}

@Composable
private fun StickerLyricsToolIcon() {
    ComposeCanvas(modifier = Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color.White,
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )
        // Eyes
        drawCircle(
            color = Color(0xFF141418),
            radius = w * 0.075f,
            center = Offset(w * 0.34f, h * 0.38f)
        )
        drawCircle(
            color = Color(0xFF141418),
            radius = w * 0.075f,
            center = Offset(w * 0.66f, h * 0.38f)
        )
        // Smile arc
        drawArc(
            color = Color(0xFF141418),
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(w * 0.30f, h * 0.38f),
            size = Size(w * 0.40f, h * 0.34f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Authentic Spotify Lyrics Card preview matching the uploaded design:
 * - Top row: `[Rounded Album Art] [Song Title (Bold) / Lagu · Artist Name]`
 * - Center body: Bold left-aligned consecutive lyrics
 * - Bottom-left footer: `[Logo + App Name]`
 */
@Composable
private fun SpotifyLyricsCardPreview(
    track: Track,
    lines: List<Pair<String, String?>>,
    palette: LyricsCardPalette,
    cardBgImageUrl: String?,
    fontPreset: LyricsFontPreset,
    scaleStep: Int,
    onClickCard: () -> Unit
) {
    val lyricSize = when (scaleStep) {
        0 -> 26.sp
        1 -> 23.sp
        2 -> 20.sp
        else -> 17.5.sp
    }
    val lyricLineHeight = lyricSize * 1.18f
    val translationSize = lyricSize * 0.56f
    val hasImageBg = !cardBgImageUrl.isNullOrBlank()
    val effectiveTextColor = if (hasImageBg) Color.White else palette.textColor
    val effectiveSecondaryColor = if (hasImageBg) Color.White.copy(alpha = 0.78f) else palette.secondaryText

    Box(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .widthIn(max = 380.dp)
            .shadow(20.dp, RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(palette.cardTop, palette.cardBottom)))
            .clickable(onClick = onClickCard)
            .clipToBounds()
    ) {
        if (hasImageBg) {
            AsyncImage(
                model = cardBgImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = 0.45f),
                            0.50f to Color.Black.copy(alpha = 0.55f),
                            1.00f to Color.Black.copy(alpha = 0.78f)
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 22.dp)
        ) {
            // 1. TOP HEADER: Album Cover + Title & "Lagu · Artist"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(
                    url = track.image,
                    size = 42,
                    shapeRadius = 6
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.displayTitle,
                        style = BlexsyTypography.Title.copy(
                            fontSize = 14.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = effectiveTextColor
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "Lagu • ${track.artist}",
                        style = BlexsyTypography.Caption.copy(
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            color = effectiveSecondaryColor
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 2. CENTER BODY: Big Bold Consecutive Lyrics
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                lines.forEach { (lyricText, transText) ->
                    Text(
                        text = lyricText,
                        style = BlexsyTypography.Display.copy(
                            fontFamily = fontPreset.composeFontFamily(),
                            fontStyle = if (fontPreset.isItalic) FontStyle.Italic else FontStyle.Normal,
                            fontSize = lyricSize,
                            lineHeight = lyricLineHeight,
                            fontWeight = fontPreset.weight,
                            letterSpacing = fontPreset.letterSpacingSp,
                            color = effectiveTextColor
                        ),
                        modifier = Modifier.graphicsLayer {
                            scaleX = fontPreset.scaleX
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                    )
                    if (!transText.isNullOrBlank()) {
                        Text(
                            text = transText,
                            style = BlexsyTypography.Body.copy(
                                fontFamily = LyricsFont,
                                fontSize = translationSize,
                                lineHeight = translationSize * 1.22f,
                                fontWeight = FontWeight.Medium,
                                color = effectiveSecondaryColor
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // 3. BOTTOM-LEFT BRAND FOOTER (App Launcher Icon only, larger with subtle rounded corners)
            AppLauncherIcon(
                size = 32.dp,
                shape = RoundedCornerShape(7.dp)
            )
        }
    }
}

/**
 * Renders the 1080×1920 story image matching the Spotify Lyrics Share Card layout:
 * - Top inside card: `[Rounded Album Art] [Song Title / Lagu • Artist]`
 * - Middle inside card: Big bold consecutive lyrics with chosen [fontPreset]
 * - Bottom-left inside card: `[Brand Circle Icon + App Name]`
 */
private suspend fun renderAndSaveLyricsCard(
    context: Context,
    track: Track,
    lines: List<Pair<String, String?>>,
    palette: LyricsCardPalette,
    cardBgImageUrl: String?,
    fontPreset: LyricsFontPreset,
    appName: String
): Uri? = withContext(Dispatchers.IO) {
    runCatching {
        val cardWidth = 1080
        val pad = 80
        val rawTextWidth = cardWidth - pad * 2

        fun font(id: Int, fallback: Typeface): Typeface =
            runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: fallback

        val lyricTypeface = fontPreset.resolveAndroidTypeface(context)
        val displayMedium = font(R.font.sf_pro_display_medium, Typeface.DEFAULT)
        val textBold = font(R.font.sf_pro_bold, Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
        val textRegular = font(R.font.sf_pro_regular, Typeface.DEFAULT)

        val lyricSizePx = when (lyricScaleStep(lines.sumOf { it.first.length })) {
            0 -> 86f
            1 -> 74f
            2 -> 64f
            else -> 56f
        }

        val hasImageBg = !cardBgImageUrl.isNullOrBlank()
        val effectiveTextColor = if (hasImageBg) Color.White else palette.textColor
        val effectiveSecondaryColor = if (hasImageBg) Color.White.copy(alpha = 0.78f) else palette.secondaryText

        val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = effectiveTextColor.toArgb()
            textSize = lyricSizePx
            typeface = lyricTypeface
            letterSpacing = fontPreset.canvasLetterSpacing
            textScaleX = fontPreset.scaleX
        }
        val transPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = effectiveSecondaryColor.toArgb()
            textSize = lyricSizePx * 0.56f
            typeface = displayMedium
        }

        val blocks = lines.map { (lyric, trans) ->
            val l = StaticLayout.Builder.obtain(lyric, 0, lyric.length, lyricPaint, rawTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .build()
            val t = trans?.takeIf { it.isNotBlank() }?.let {
                StaticLayout.Builder.obtain(it, 0, it.length, transPaint, rawTextWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.1f)
                    .setIncludePad(false)
                    .build()
            }
            l to t
        }

        val blockGap = 24
        val transGap = 10
        var lyricsHeight = 0
        blocks.forEachIndexed { i, (l, t) ->
            lyricsHeight += l.height
            if (t != null) lyricsHeight += transGap + t.height
            if (i < blocks.lastIndex) lyricsHeight += blockGap
        }

        val headerArtSize = 124
        val headerToLyricsGap = 72
        val lyricsToBrandGap = 84
        val brandRowHeight = 84
        val cardHeight = pad + headerArtSize + headerToLyricsGap + lyricsHeight + lyricsToBrandGap + brandRowHeight + pad

        val cardRect = RectF(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat())
        val radius = 56f

        val bitmap = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val trackArtwork = fetchTrackBitmap(track.image.toHighResArtwork() ?: track.image)
        val cardBgBitmap = if (hasImageBg) {
            if (cardBgImageUrl == (track.image.toHighResArtwork() ?: track.image)) {
                trackArtwork
            } else {
                fetchTrackBitmap(cardBgImageUrl) ?: trackArtwork
            }
        } else null

        // 1. Full-bleed Card surface: image background or vertical color gradient (no blank margin outside)
        if (cardBgBitmap != null) {
            val roundedCardImg = createCenterCropRoundedBitmap(
                src = cardBgBitmap,
                targetW = cardWidth,
                targetH = cardHeight,
                cornerRadius = radius
            )
            canvas.drawBitmap(roundedCardImg, 0f, 0f, null)

            val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, cardHeight.toFloat(),
                    intArrayOf(
                        android.graphics.Color.argb(115, 0, 0, 0),
                        android.graphics.Color.argb(140, 0, 0, 0),
                        android.graphics.Color.argb(198, 0, 0, 0)
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(cardRect, radius, radius, overlayPaint)
        } else {
            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, cardHeight.toFloat(),
                    palette.cardTop.toArgb(),
                    palette.cardBottom.toArgb(),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(cardRect, radius, radius, cardPaint)
        }

        // 2. TOP HEADER ROW: Album Cover + Title & "Lagu • Artist"
        val contentX = pad.toFloat()
        val headerTop = pad.toFloat()
        val artRect = RectF(contentX, headerTop, contentX + headerArtSize, headerTop + headerArtSize)
        if (trackArtwork != null) {
            canvas.drawBitmap(createRoundedBitmap(trackArtwork, headerArtSize, 18f), contentX, headerTop, null)
        } else {
            val ph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(60, 255, 255, 255) }
            canvas.drawRoundRect(artRect, 18f, 18f, ph)
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = effectiveTextColor.toArgb()
            textSize = 42f
            typeface = textBold
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = effectiveSecondaryColor.toArgb()
            textSize = 36f
            typeface = textRegular
        }
        val metaX = contentX + headerArtSize + 32f
        val metaWidth = (cardRect.right - pad - metaX).coerceAtLeast(140f)
        val titleText = TextUtils.ellipsize(track.displayTitle, titlePaint, metaWidth, TextUtils.TruncateAt.END).toString()
        val subtitleText = TextUtils.ellipsize("Lagu • ${track.artist}", artistPaint, metaWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(titleText, metaX, headerTop + 52f, titlePaint)
        canvas.drawText(subtitleText, metaX, headerTop + 102f, artistPaint)

        // 3. MIDDLE LYRICS
        var cursorY = headerTop + headerArtSize + headerToLyricsGap
        blocks.forEachIndexed { i, (l, t) ->
            canvas.save()
            canvas.translate(contentX, cursorY)
            l.draw(canvas)
            canvas.restore()
            cursorY += l.height
            if (t != null) {
                cursorY += transGap
                canvas.save()
                canvas.translate(contentX, cursorY)
                t.draw(canvas)
                canvas.restore()
                cursorY += t.height
            }
            if (i < blocks.lastIndex) cursorY += blockGap
        }

        // 4. BOTTOM-LEFT BRAND FOOTER (App Launcher Icon only, larger with subtle rounded corners)
        val brandCenterY = cardRect.bottom - pad - brandRowHeight / 2f
        val iconSizePx = 84
        val iconCornerRadiusPx = 18f
        val appIconBitmap = runCatching {
            val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
                ?: context.packageManager.getApplicationIcon(context.applicationInfo)
            val rawBmp = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
            val iconCanvas = Canvas(rawBmp)
            drawable.setBounds(0, 0, iconSizePx, iconSizePx)
            drawable.draw(iconCanvas)
            createRoundedBitmap(rawBmp, iconSizePx, iconCornerRadiusPx)
        }.getOrNull()

        if (appIconBitmap != null) {
            canvas.drawBitmap(appIconBitmap, contentX, brandCenterY - iconSizePx / 2f, null)
        } else {
            val badgeRect = RectF(
                contentX,
                brandCenterY - iconSizePx / 2f,
                contentX + iconSizePx,
                brandCenterY + iconSizePx / 2f
            )
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = effectiveTextColor.toArgb()
            }
            canvas.drawRoundRect(badgeRect, iconCornerRadiusPx, iconCornerRadiusPx, badgePaint)
        }

        // 5. Save to MediaStore as PNG so rounded corners stay clean without blank outer canvas
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "BLEXSY_LYRICS_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Blexsy")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        uri
    }.getOrNull()
}

private fun createCenterCropRoundedBitmap(
    src: Bitmap,
    targetW: Int,
    targetH: Int,
    cornerRadius: Float
): Bitmap {
    val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val rect = RectF(0f, 0f, targetW.toFloat(), targetH.toFloat())
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

    val scale = maxOf(targetW.toFloat() / src.width.toFloat(), targetH.toFloat() / src.height.toFloat())
    val dx = (targetW - src.width * scale) / 2f
    val dy = (targetH - src.height * scale) / 2f
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postTranslate(dx, dy)
    }
    canvas.drawBitmap(src, matrix, paint)
    return output
}

private fun fetchTrackBitmap(urlStr: String?): Bitmap? {
    if (urlStr.isNullOrBlank()) return null
    return runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            setRequestProperty("User-Agent", "Blexsy/1.0 (Android)")
        }
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}

private fun createRoundedBitmap(src: Bitmap, targetSize: Int, cornerRadius: Float): Bitmap {
    val scaled = Bitmap.createScaledBitmap(src, targetSize, targetSize, true)
    val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = Rect(0, 0, targetSize, targetSize)
    canvas.drawRoundRect(RectF(rect), cornerRadius, cornerRadius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(scaled, rect, rect, paint)
    return output
}
