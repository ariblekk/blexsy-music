package com.blek.blexsy.ui

import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Loops muted artist canvas video (HLS/MP4), fading in smoothly over static artwork.
 * ponytail: TextureView without aspect ratio layout; upgrade path: custom crop layout if non-standard ratio.
 */
@Composable
fun ArtistCanvasPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isReady by remember(videoUrl) { mutableStateOf(false) }

    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer, videoUrl) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isReady = true
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isReady = false
            }
        }
        exoPlayer.addListener(listener)

        val isMp4 = videoUrl.contains(".mp4", ignoreCase = true)
        val mediaItem = MediaItem.Builder()
            .setUri(videoUrl.trim())
            .setMimeType(if (isMp4) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8)
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.clearVideoTextureView(null)
            exoPlayer.release()
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (isReady) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "canvas_video_alpha"
    )

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                exoPlayer.setVideoTextureView(this)
            }
        },
        update = { textureView ->
            exoPlayer.setVideoTextureView(textureView)
        },
        modifier = modifier.graphicsLayer { this.alpha = alpha }
    )
}
