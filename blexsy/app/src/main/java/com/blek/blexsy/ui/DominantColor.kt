package com.blek.blexsy.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.blek.blexsy.model.normalizeSpotifyImageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts dominant color from cover image, sampling the bottom 45% for seamless blending.
 */
@Composable
fun rememberDominantColor(imageUrl: String?, default: Color = BlexsyColors.Background): Color {
    val context = LocalContext.current
    val normalizedUrl = remember(imageUrl) { imageUrl.normalizeSpotifyImageUrl() }
    var extractedColor by remember { mutableStateOf(default) }

    LaunchedEffect(normalizedUrl) {
        if (normalizedUrl.isNullOrBlank()) {
            extractedColor = default
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val loader = SingletonImageLoader.get(context)
                val request = ImageRequest.Builder(context)
                    .data(normalizedUrl)
                    .size(160, 160)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val rawBmp = result.image.toBitmap()
                    val bmp = if (rawBmp.config == Bitmap.Config.HARDWARE) {
                        rawBmp.copy(Bitmap.Config.ARGB_8888, false)
                    } else rawBmp
                    val sampleTop = (bmp.height * 0.55f).toInt().coerceIn(0, bmp.height - 1)
                    val palette = Palette.from(bmp)
                        .setRegion(0, sampleTop, bmp.width, bmp.height)
                        .maximumColorCount(16)
                        .generate()
                    val rgb = palette.vibrantSwatch?.rgb
                        ?: palette.swatches.maxByOrNull { it.population }?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                    if (rgb != null) {
                        val hsl = FloatArray(3)
                        ColorUtils.colorToHSL(rgb, hsl)
                        hsl[1] = (hsl[1] * 0.90f).coerceIn(0.25f, 0.72f)
                        hsl[2] = (hsl[2] * 0.55f).coerceIn(0.14f, 0.28f)
                        extractedColor = Color(ColorUtils.HSLToColor(hsl))
                    }
                }
            }
        }
    }

    return animateColorAsState(
        targetValue = extractedColor,
        animationSpec = tween(durationMillis = 600),
        label = "dominant_color"
    ).value
}

/**
 * Extracts a rich, vibrant artwork accent color ideal for MiniPlayer and BottomNav gradients.
 */
@Composable
fun rememberArtworkGradientColors(
    imageUrl: String?,
    defaultPrimary: Color = BlexsyColors.SurfaceContainerHigh,
    defaultSecondary: Color = BlexsyColors.Surface
): Pair<Color, Color> {
    val context = LocalContext.current
    val normalizedUrl = remember(imageUrl) { imageUrl.normalizeSpotifyImageUrl() }
    var primaryExtracted by remember { mutableStateOf(defaultPrimary) }
    var secondaryExtracted by remember { mutableStateOf(defaultSecondary) }

    LaunchedEffect(normalizedUrl) {
        if (normalizedUrl.isNullOrBlank()) {
            primaryExtracted = defaultPrimary
            secondaryExtracted = defaultSecondary
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val loader = SingletonImageLoader.get(context)
                val request = ImageRequest.Builder(context)
                    .data(normalizedUrl)
                    .size(160, 160)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val rawBmp = result.image.toBitmap()
                    val bmp = if (rawBmp.config == Bitmap.Config.HARDWARE) {
                        rawBmp.copy(Bitmap.Config.ARGB_8888, false)
                    } else rawBmp
                    val palette = Palette.from(bmp)
                        .maximumColorCount(24)
                        .generate()

                    val primaryRgb = palette.vibrantSwatch?.rgb
                        ?: palette.lightVibrantSwatch?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                        ?: palette.swatches.maxByOrNull { it.population }?.rgb

                    val secondaryRgb = palette.darkVibrantSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                        ?: palette.darkMutedSwatch?.rgb
                        ?: primaryRgb

                    if (primaryRgb != null) {
                        val hsl1 = FloatArray(3)
                        ColorUtils.colorToHSL(primaryRgb, hsl1)
                        // Subtle, tasteful saturation & dark-toned lightness so it blends with the dark UI theme
                        if (hsl1[1] > 0.06f) {
                            hsl1[1] = (hsl1[1] * 0.90f).coerceIn(0.28f, 0.62f)
                        }
                        hsl1[2] = hsl1[2].coerceIn(0.18f, 0.30f)
                        primaryExtracted = Color(ColorUtils.HSLToColor(hsl1))

                        val hsl2 = FloatArray(3)
                        ColorUtils.colorToHSL(secondaryRgb ?: primaryRgb, hsl2)
                        if (hsl2[1] > 0.06f) {
                            hsl2[1] = (hsl2[1] * 0.80f).coerceIn(0.20f, 0.50f)
                        }
                        hsl2[2] = hsl2[2].coerceIn(0.12f, 0.20f)
                        secondaryExtracted = Color(ColorUtils.HSLToColor(hsl2))
                    }
                }
            }
        }
    }

    val animPrimary by animateColorAsState(
        targetValue = primaryExtracted,
        animationSpec = tween(durationMillis = 520),
        label = "artwork_grad_primary"
    )
    val animSecondary by animateColorAsState(
        targetValue = secondaryExtracted,
        animationSpec = tween(durationMillis = 520),
        label = "artwork_grad_secondary"
    )
    return animPrimary to animSecondary
}
