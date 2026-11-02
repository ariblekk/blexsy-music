package com.blek.blexsy.player

import com.blek.blexsy.model.QueueTier
import com.blek.blexsy.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ponytail: Basic in-memory player state ticker for JVM/Previews; replace with VLCJ/GStreamer if desktop playback is required.
class DefaultBlexsyAudioPlayer(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val onStreamRequest: (suspend (Track) -> String)? = null
) : BlexsyAudioPlayer {

    private val _currentTrack = MutableStateFlow<Track?>(null)
    override val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    override val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _queueSource = MutableStateFlow(QueueSource.CONTEXT)
    override val queueSource: StateFlow<QueueSource> = _queueSource.asStateFlow()

    private val _contextTitle = MutableStateFlow<String?>(null)
    override val contextTitle: StateFlow<String?> = _contextTitle.asStateFlow()

    private val _repeatMode = MutableStateFlow(PlaybackRepeatMode.ALL)
    override val repeatMode: StateFlow<PlaybackRepeatMode> = _repeatMode.asStateFlow()

    private var tickerJob: Job? = null

    override fun play(track: Track, newQueue: List<Track>) {
        play(track, newQueue, null)
    }

    override fun play(track: Track, newQueue: List<Track>, contextTitle: String?) {
        _queueSource.value = QueueSource.CONTEXT
        _contextTitle.value = contextTitle
        if (_currentTrack.value?.id == track.id) {
            if (!_isPlaying.value) {
                resume()
            }
            return
        }
        val rawQueue = if (newQueue.isEmpty()) listOf(track) else newQueue
        val trackIdx = rawQueue.indexOfFirst { it.id == track.id }
        val queue = if (trackIdx != -1) {
            rawQueue.drop(trackIdx)
        } else {
            listOf(track) + rawQueue.filterNot { it.id == track.id }
        }
        _currentTrack.value = track
        _queue.value = queue
        _durationMs.value = track.durationMs ?: 0L
        _positionMs.value = 0L
        _isPlaying.value = true
        startTicker()
    }

    override fun addToQueue(track: Track, isNext: Boolean) {
        val q = _queue.value.toMutableList()
        val cur = _currentTrack.value
        val curIdx = if (cur != null) q.indexOfFirst { it.id == cur.id } else 0
        val entry = QueuePolicy.asQueueEntry(track, QueueTier.USER_QUEUE)
        val insertIdx = QueuePolicy.findUserQueueInsertionIndex(q, curIdx, isNext)
        q.add(insertIdx.coerceIn(0, q.size), entry)
        _queue.value = q
    }

    override fun clearUserQueue() {
        val q = _queue.value
        val cur = _currentTrack.value
        val curIdx = if (cur != null) q.indexOfFirst { it.id == cur.id } else 0
        _queue.value = QueuePolicy.clearUserQueue(q, curIdx)
    }

    override fun togglePlayPause() {
        if (_isPlaying.value) pause() else resume()
    }

    override fun pause() {
        _isPlaying.value = false
        tickerJob?.cancel()
    }

    override fun resume() {
        if (_currentTrack.value != null) {
            _isPlaying.value = true
            startTicker()
        }
    }

    override fun seekTo(positionMs: Long) {
        _positionMs.value = positionMs.coerceIn(0L, _durationMs.value.coerceAtLeast(1L))
    }

    override fun next() {
        val q = _queue.value
        val cur = _currentTrack.value ?: return
        val idx = q.indexOfFirst { it.id == cur.id }
        if (idx != -1 && idx + 1 < q.size) {
            play(q[idx + 1], q)
        } else if (_repeatMode.value == PlaybackRepeatMode.ALL && q.isNotEmpty()) {
            play(q.first(), q)
        } else {
            pause()
        }
    }

    override fun previous() {
        val q = _queue.value
        val cur = _currentTrack.value ?: return
        val idx = q.indexOfFirst { it.id == cur.id }
        if (idx > 0) {
            play(q[idx - 1], q)
        } else {
            seekTo(0L)
        }
    }

    override fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            PlaybackRepeatMode.OFF -> PlaybackRepeatMode.ALL
            PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
            PlaybackRepeatMode.ONE -> PlaybackRepeatMode.OFF
        }
    }

    override fun removeFromQueue(index: Int) {
        val currentQueue = _queue.value.toMutableList()
        if (index in currentQueue.indices) {
            currentQueue.removeAt(index)
            _queue.value = currentQueue
        }
    }

    override fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val currentQueue = _queue.value.toMutableList()
        if (fromIndex in currentQueue.indices && toIndex in currentQueue.indices && fromIndex != toIndex) {
            val item = currentQueue.removeAt(fromIndex)
            currentQueue.add(toIndex, item)
            _queue.value = currentQueue
        }
    }

    override fun clearQueue() {
        val current = _currentTrack.value
        _queueSource.value = QueueSource.CONTEXT
        _contextTitle.value = null
        if (current != null) {
            _queue.value = listOf(current)
        } else {
            _queue.value = emptyList()
        }
    }

    override fun startRadio(seed: Track) {
        _queueSource.value = QueueSource.RADIO
        _contextTitle.value = null
        _currentTrack.value = seed
        _queue.value = listOf(seed)
        _positionMs.value = 0L
        _durationMs.value = seed.durationMs ?: 0L
        _isPlaying.value = true
        startTicker()
    }

    override fun appendToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val currentQueue = _queue.value.toMutableList()
        val existingIds = currentQueue.map { it.id }.toHashSet()
        val toAdd = tracks.filter { it.id.isNotBlank() && !existingIds.contains(it.id) }
        if (toAdd.isEmpty()) return
        currentQueue.addAll(toAdd)
        _queue.value = currentQueue
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && _isPlaying.value) {
                delay(50)
                val curPos = _positionMs.value + 50
                val total = _durationMs.value
                if (total > 0 && curPos >= total) {
                    if (_repeatMode.value == PlaybackRepeatMode.ONE) {
                        _positionMs.value = 0L
                    } else {
                        next()
                    }
                } else {
                    _positionMs.value = curPos
                }
            }
        }
    }
}
