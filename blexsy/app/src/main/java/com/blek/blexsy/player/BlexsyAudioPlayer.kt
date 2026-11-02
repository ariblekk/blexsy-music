package com.blek.blexsy.player

import com.blek.blexsy.model.Track
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackRepeatMode {
    OFF, ALL, ONE
}

enum class QueueSource {
    CONTEXT,
    RADIO
}

interface BlexsyAudioPlayer {
    val currentTrack: StateFlow<Track?>
    val isPlaying: StateFlow<Boolean>
    val isBuffering: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val queue: StateFlow<List<Track>>
    val repeatMode: StateFlow<PlaybackRepeatMode>
    val queueSource: StateFlow<QueueSource> get() = kotlinx.coroutines.flow.MutableStateFlow(QueueSource.CONTEXT)
    val contextTitle: StateFlow<String?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)
    var onSpinRecorded: ((Track) -> Unit)?
        get() = null
        set(value) {}

    fun play(track: Track, newQueue: List<Track> = listOf(track))
    fun play(track: Track, newQueue: List<Track>, contextTitle: String?) {
        play(track, newQueue)
    }
    fun playAtIndex(index: Int) {
        val q = queue.value
        if (index in q.indices) {
            play(q[index], q.drop(index))
        }
    }
    fun startRadio(seed: Track) {}
    fun appendToQueue(tracks: List<Track>) {}
    fun addToQueue(track: Track, isNext: Boolean = false) {}
    fun clearUserQueue() {}
    fun togglePlayPause()
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun toggleRepeat()
    fun setSkipSilence(enabled: Boolean) {}
    fun removeFromQueue(index: Int) {}
    fun reorderQueue(fromIndex: Int, toIndex: Int) {}
    fun clearQueue() {}
}
