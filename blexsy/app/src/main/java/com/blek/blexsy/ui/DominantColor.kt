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
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts dominant color from cover image, sampling the bottom 45% for seamless blending.
 * ponytail: samples bottom region + HSL darkening; upgrade path: full blur-band texture shader.
 */
@Composable
fun rememberDominantColor(imageUrl: String?, default: Color = BlexsyColors.Background): Color {
    val context = LocalContext.current
    var extractedColor by remember { mutableStateOf(default) }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            extractedColor = default
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(160, 160)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val bmp = result.image.toBitmap()
                    val sampleTop = (bmp.height * 0.55f).toInt().coerceIn(0, bmp.height - 1)
                    val palette = Palette.from(bmp)
                        .setRegion(0, sampleTop, bmp.width, bmp.height)
                        .maximumColorCount(12)
                        .generate()
                    val rgb = palette.swatches.maxByOrNull { it.population }?.rgb
                        ?: palette.darkMutedSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                    if (rgb != null) {
                        val hsl = FloatArray(3)
                        ColorUtils.colorToHSL(rgb, hsl)
                        hsl[1] = (hsl[1] * 0.85f).coerceAtMost(0.60f)
                        hsl[2] = (hsl[2] * 0.50f).coerceIn(0.08f, 0.22f)
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

