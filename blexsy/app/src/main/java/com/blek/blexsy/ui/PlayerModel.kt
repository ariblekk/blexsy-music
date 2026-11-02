package com.blek.blexsy.ui

import com.blek.blexsy.model.Track

/**
 * Identity wrapper that keeps a stable key for a queue row while the underlying
 * queue list is reordered.
 */
internal data class QueueEntry(
    val instanceId: Long,
    val track: Track
)

/** Which panel the full player currently shows. */
internal enum class FullPlayerView {
    LYRICS,
    QUEUE,
    COVER
}

internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
