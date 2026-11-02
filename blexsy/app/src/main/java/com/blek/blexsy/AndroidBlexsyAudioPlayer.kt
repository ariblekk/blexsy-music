package com.blek.blexsy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import com.blek.blexsy.data.ApiException
import com.blek.blexsy.data.AudioCacheManager
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.InnerTubeService
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.eq.BlexsyEqualizer
import com.blek.blexsy.model.QueueTier
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.toHighResArtwork
import com.blek.blexsy.player.BlexsyAudioPlayer
import com.blek.blexsy.player.PlaybackRepeatMode
import com.blek.blexsy.player.QueuePolicy
import com.blek.blexsy.player.QueueSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

class AndroidBlexsyAudioPlayer(
    private val context: Context,
    private val api: BlexsyApiClient,
    var library: LibraryRepository? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : BlexsyAudioPlayer {

    private val appContext = context.applicationContext

    private val trackSelector = DefaultTrackSelector(appContext).apply {
        val quality = AudioCacheManager.getAudioQuality(appContext)
        val maxBitrate = AudioCacheManager.getMaxBitrateForQuality(quality)
        setParameters(buildUponParameters().setMaxAudioBitrate(maxBitrate))
    }

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15")
        .setDefaultRequestProperties(mapOf("Referer" to "https://www.youtube.com/"))
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setAllowCrossProtocolRedirects(true)

    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(AudioCacheManager.getCache(appContext))
        .setUpstreamDataSourceFactory(httpDataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val resolvingDataSourceFactory = ResolvingDataSource.Factory(cacheDataSourceFactory) { dataSpec ->
        val uri = dataSpec.uri
        if (uri.scheme == "blexsy") {
            // ponytail: resolve directly via YouTube Music InnerTube VISIONOS client to bypass backend 307 & 403 chunk errors.
            val trackId = uri.getQueryParameter("id").orEmpty()
            val title = uri.getQueryParameter("title").orEmpty()
            val artist = uri.getQueryParameter("artist").orEmpty()
            val ytId = uri.getQueryParameter("youtube_id").orEmpty()
            val track = Track(id = trackId, title = title, artist = artist, youtubeId = ytId)
            val quality = AudioCacheManager.getAudioQuality(appContext)

            val streamUrl = kotlinx.coroutines.runBlocking {
                InnerTubeService.resolveTrackStream(appContext, track, quality)
            } ?: throw java.io.IOException("Unable to resolve audio stream for track: ${track.displayTitle}")

            dataSpec.buildUpon()
                .setUri(Uri.parse(streamUrl))
                .setKey(trackId.ifBlank { ytId.ifBlank { streamUrl } })
                .build()
        } else {
            dataSpec
        }
    }

    init {
        BlexsyEqualizer.init(appContext)
    }

    @OptIn(UnstableApi::class)
    private val renderersFactory = object : DefaultRenderersFactory(appContext) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        BlexsyEqualizer.audioProcessor
                    )
                )
                .build()
        }
    }

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
        .setTrackSelector(trackSelector)
        .setMediaSourceFactory(DefaultMediaSourceFactory(appContext).setDataSourceFactory(resolvingDataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    // MediaSession for system & lockscreen media controls.
    // Session ids must be unique inside one process, so derive one per player instance.
    private val sessionActivity = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    internal val mediaSession: MediaSession = MediaSession.Builder(appContext, exoPlayer)
        .setId("blexsy-session")
        .setSessionActivity(sessionActivity)
        .build()

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

    private var contextTitleVal: String? = null
    private val _contextTitle = MutableStateFlow<String?>(null)
    override val contextTitle: StateFlow<String?> = _contextTitle.asStateFlow()

    // Radio session lifecycle. The seed is fixed for the whole session; a new
    // session invalidates in-flight refills so a late response can never be
    // appended to a queue the user has already replaced.
    private var radioSeed: Track? = null
    private var radioSessionId: Long = 0L
    private var radioRetryAttempt = 0
    private var nextRadioRetryAtMs = 0L
    private var radioContinuation: String? = null
    private val isFetchingRadio = AtomicBoolean(false)
    private val seenRadioTrackIds = LinkedHashSet<String>()

    val currentRadioSeed: Track? get() = if (_queueSource.value == QueueSource.RADIO) radioSeed else null

    private val _isRadioLoading = MutableStateFlow(false)
    val isRadioLoading: StateFlow<Boolean> = _isRadioLoading.asStateFlow()

    private val _radioError = MutableStateFlow<String?>(null)
    val radioError: StateFlow<String?> = _radioError.asStateFlow()

    private val _repeatMode = MutableStateFlow(PlaybackRepeatMode.ALL)
    override val repeatMode: StateFlow<PlaybackRepeatMode> = _repeatMode.asStateFlow()

    private var tickerJob: Job? = null
    override var onSpinRecorded: ((Track) -> Unit)? = null
    private var spinCountedTrackId: String? = null
    private var activeListeningMs: Long = 0L

    private fun resetSpinTracking() {
        spinCountedTrackId = null
        activeListeningMs = 0L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun playerPrefs() =
        appContext.getSharedPreferences("blexsy_player", Context.MODE_PRIVATE)

    private fun recordRecent(track: Track) {
        val repo = library
        if (repo != null) {
            repo.recordRecent(track)
        } else {
            scope.launch { runCatching { api.recordRecent(track) } }
        }
        onSpinRecorded?.invoke(track)
    }

    private fun savePlaybackState(
        track: Track,
        queue: List<Track>,
        positionMs: Long = _positionMs.value,
        source: QueueSource = _queueSource.value,
        seed: Track? = radioSeed,
        contextTitle: String? = contextTitleVal
    ) {
        val appPrefs = appContext.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean("pref_save_last_state", true)) return

        scope.launch(Dispatchers.IO) {
            runCatching {
                val editor = playerPrefs().edit()
                    .putString("last_track", json.encodeToString(Track.serializer(), track))
                    .putString("last_queue", json.encodeToString(ListSerializer(Track.serializer()), queue))
                    .putLong("last_position_ms", positionMs)
                    .putString("last_queue_source", source.name)
                if (source == QueueSource.RADIO && seed != null) {
                    editor.putString("last_radio_seed", json.encodeToString(Track.serializer(), seed))
                } else {
                    editor.remove("last_radio_seed")
                }
                if (!contextTitle.isNullOrBlank()) {
                    editor.putString("last_context_title", contextTitle)
                } else {
                    editor.remove("last_context_title")
                }
                editor.apply()
            }
        }
    }

    private fun savePosition(positionMs: Long) {
        val appPrefs = appContext.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean("pref_save_last_state", true)) return

        scope.launch(Dispatchers.IO) {
            runCatching {
                playerPrefs().edit()
                    .putLong("last_position_ms", positionMs)
                    .apply()
            }
        }
    }

    @Volatile private var skipDeadAirEnabled: Boolean = false
    private var lastDeadAirSkipAtMs: Long = 0L

    init {
        val appPrefs = appContext.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        skipDeadAirEnabled = appPrefs.getBoolean("pref_skip_silence", false)
        exoPlayer.skipSilenceEnabled = false

        val shouldRestore = appPrefs.getBoolean("pref_save_last_state", true)
        val prefs = playerPrefs()
        if (shouldRestore) {
            val savedTrackJson = prefs.getString("last_track", null)
            val savedQueueJson = prefs.getString("last_queue", null)
            val savedPosition = prefs.getLong("last_position_ms", 0L)
            if (!savedTrackJson.isNullOrBlank()) {
                runCatching {
                    val restoredTrack = json.decodeFromString(Track.serializer(), savedTrackJson)
                    val decodedQueue = if (!savedQueueJson.isNullOrBlank()) {
                        json.decodeFromString(ListSerializer(Track.serializer()), savedQueueJson)
                    } else {
                        listOf(restoredTrack)
                    }
                    val restoredQueue = QueuePolicy.normalizeQueue(
                        restoredTrack,
                        decodedQueue.take(QueuePolicy.MAX_RESTORED_QUEUE)
                    )
                    val savedSource = runCatching {
                        QueueSource.valueOf(
                            prefs.getString("last_queue_source", QueueSource.CONTEXT.name)
                                ?: QueueSource.CONTEXT.name
                        )
                    }.getOrDefault(QueueSource.CONTEXT)
                    val savedSeed = prefs.getString("last_radio_seed", null)?.let {
                        runCatching { json.decodeFromString(Track.serializer(), it) }.getOrNull()
                    }

                    _currentTrack.value = restoredTrack
                    _durationMs.value = restoredTrack.durationMs ?: 0L
                    val duration = restoredTrack.durationMs ?: 0L
                    _positionMs.value = if (duration > 0 && savedPosition >= duration - 1500L) {
                        0L
                    } else {
                        savedPosition.coerceAtLeast(0L)
                    }

                    if (savedSource == QueueSource.RADIO) {
                        _queueSource.value = QueueSource.RADIO
                        radioSeed = savedSeed ?: restoredTrack
                        contextTitleVal = null
                        _contextTitle.value = null
                        _queue.value = restoredQueue.take(QueuePolicy.RADIO_QUEUE_TARGET)
                        seenRadioTrackIds.clear()
                        seenRadioTrackIds.addAll(_queue.value.map { it.id })
                        QueuePolicy.trimSeen(seenRadioTrackIds)
                    } else {
                        _queueSource.value = QueueSource.CONTEXT
                        radioSeed = null
                        val savedContextTitle = prefs.getString("last_context_title", null)
                        contextTitleVal = savedContextTitle
                        _contextTitle.value = savedContextTitle
                        _queue.value = restoredQueue
                    }
                }
            }
        } else {
            prefs.edit().clear().apply()
        }

        // Matches the app's historical default (queue loops), now handled natively by
        // ExoPlayer instead of manually restarting the first item.
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startTicker() else tickerJob?.cancel()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = exoPlayer.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("BlexsyPlayer", "onPlayerError: errorCode=${error.errorCodeName} (${error.errorCode}), msg=${error.message}", error)
                _isBuffering.value = false
                _isPlaying.value = false
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                    _positionMs.value = 0L
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _positionMs.value = 0L
                // Auto-advance (and seeking within the queue) updates the app state so the
                // mini player / full player always show the song that is actually playing.
                val originalQueue = _queue.value
                var index = exoPlayer.currentMediaItemIndex
                if (index !in originalQueue.indices) return

                // Radio only needs the current track and what comes next. Dropping played
                // items keeps the queue size meaningful for refill and avoids the old
                // "queue reached 10 items, then never refilled" failure mode.
                var trimmed = false
                if (_queueSource.value == QueueSource.RADIO && index > 0) {
                    val remaining = originalQueue.drop(index)
                    _queue.value = remaining
                    runCatching { exoPlayer.removeMediaItems(0, index) }
                    index = 0
                    trimmed = true
                } else if (_queueSource.value == QueueSource.CONTEXT && index > 0 && originalQueue.getOrNull(index)?.queueTier == QueueTier.CONTEXT) {
                    val playedUserQueueIndices = mutableListOf<Int>()
                    for (i in 0 until index) {
                        if (originalQueue.getOrNull(i)?.queueTier == QueueTier.USER_QUEUE) {
                            playedUserQueueIndices.add(i)
                        }
                    }
                    if (playedUserQueueIndices.isNotEmpty()) {
                        val mutableQueue = originalQueue.toMutableList()
                        for (i in playedUserQueueIndices.asReversed()) {
                            mutableQueue.removeAt(i)
                            runCatching { exoPlayer.removeMediaItem(i) }
                        }
                        _queue.value = mutableQueue
                        index = exoPlayer.currentMediaItemIndex
                        trimmed = true
                    }
                }

                val currentQueue = _queue.value
                val next = currentQueue.getOrNull(index) ?: return
                val trackChanged = _currentTrack.value?.id != next.id
                if (trackChanged || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    resetSpinTracking()
                    _currentTrack.value = next
                    _positionMs.value = 0L
                    _durationMs.value = next.durationMs ?: 0L
                    savePlaybackState(next, currentQueue, 0L)
                } else if (trimmed) {
                    savePlaybackState(next, currentQueue, _positionMs.value)
                }
                maybeAutoFillRadio()
            }
        })
    }

    private fun toMediaItem(track: Track): MediaItem {
        val extras = android.os.Bundle().apply {
            putString("queue_tier", track.queueTier.name)
            putString("queue_entry_id", track.queueEntryId ?: track.id)
        }
        val mediaUri = Uri.parse("blexsy://resolve")
            .buildUpon()
            .appendQueryParameter("id", track.id)
            .appendQueryParameter("title", track.displayTitle)
            .appendQueryParameter("artist", track.artist)
            .appendQueryParameter("youtube_id", track.youtubeId.orEmpty())
            .build()

        val cover = track.image
        return MediaItem.Builder()
            .setMediaId(track.queueEntryId ?: track.id)
            .setUri(mediaUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.displayTitle)
                    .setArtist(track.artist)
                    .setArtworkUri(cover.toHighResArtwork()?.let { Uri.parse(it) })
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /**
     * Replace ExoPlayer's item list while keeping queue/list indices aligned.
     * The whole upcoming queue is handed to ExoPlayer at once so the next track
     * is buffered before the current one ends.
     */
    private fun applyQueueToPlayer(
        queue: List<Track>,
        startTrack: Track,
        startPositionMs: Long,
        playWhenReady: Boolean
    ) {
        if (queue.isEmpty()) {
            runCatching { exoPlayer.clearMediaItems() }
            return
        }
        val startIndex = queue.indexOfFirst { it.id == startTrack.id }.coerceAtLeast(0)
        try {
            BlexsyMediaService.sharedPlayer = this
            BlexsyMediaService.start(appContext)
            _isBuffering.value = true
            exoPlayer.setMediaItems(queue.map(::toMediaItem), startIndex, startPositionMs.coerceAtLeast(0L))
            exoPlayer.prepare()
            if (playWhenReady) {
                exoPlayer.play()
                _isPlaying.value = true
            } else {
                exoPlayer.pause()
                _isPlaying.value = false
            }
        } catch (_: Exception) {
            // Stream playback fallback: keep the queue state so the user can retry.
        }
    }

    private fun beginContextSession() {
        radioSessionId++
        radioSeed = null
        radioContinuation = null
        seenRadioTrackIds.clear()
        radioRetryAttempt = 0
        nextRadioRetryAtMs = 0L
        _radioError.value = null
    }

    private fun beginRadioSession(seed: Track) {
        radioSessionId++
        radioSeed = seed
        radioContinuation = null
        seenRadioTrackIds.clear()
        seenRadioTrackIds.add(seed.id)
        QueuePolicy.trimSeen(seenRadioTrackIds)
        radioRetryAttempt = 0
        nextRadioRetryAtMs = 0L
        _radioError.value = null
    }

    private fun playInternal(
        track: Track,
        newQueue: List<Track>,
        startPositionMs: Long = 0L,
        source: QueueSource = QueueSource.CONTEXT,
        seed: Track? = null,
        force: Boolean = false,
        contextTitle: String? = contextTitleVal
    ) {
        if (_currentTrack.value?.id == track.id && exoPlayer.mediaItemCount > 0 && !force) {
            if (!_isPlaying.value) {
                resume()
            }
            return
        }

        val queue = QueuePolicy.normalizeQueue(track, newQueue)
        _currentTrack.value = track
        _queue.value = queue
        _queueSource.value = source
        radioSeed = if (source == QueueSource.RADIO) seed ?: track else null
        contextTitleVal = if (source == QueueSource.RADIO) null else contextTitle
        _contextTitle.value = contextTitleVal

        val duration = track.durationMs ?: 0L
        val clampedStart = if (duration > 0 && startPositionMs >= duration - 1500L) {
            0L
        } else {
            startPositionMs.coerceAtLeast(0L)
        }
        _durationMs.value = duration
        _positionMs.value = clampedStart
        resetSpinTracking()
        savePlaybackState(track, queue, clampedStart, source, radioSeed, contextTitleVal)
        BlexsyEqualizer.audioProcessor.resetAudibleTime()
        applyQueueToPlayer(queue, track, clampedStart, playWhenReady = true)
        if (source == QueueSource.RADIO) {
            maybeAutoFillRadio()
        }
    }

    override fun play(track: Track, newQueue: List<Track>) {
        play(track, newQueue, null)
    }

    override fun play(track: Track, newQueue: List<Track>, contextTitle: String?) {
        beginContextSession()
        contextTitleVal = contextTitle
        _contextTitle.value = contextTitle
        val normalized = QueuePolicy.normalizeQueue(track, newQueue)
        val sameTrack = _currentTrack.value?.id == track.id && exoPlayer.mediaItemCount > 0
        val sameQueue = _queue.value.map { it.id } == normalized.map { it.id }
        val alreadyContext = _queueSource.value == QueueSource.CONTEXT
        // Tapping the already-playing song should not restart it, but switching
        // from radio to a context queue (or to a different context) must replace
        // the upcoming queue while keeping the current playback position.
        val force = sameTrack && (!alreadyContext || !sameQueue)
        val startPosition = if (force) exoPlayer.currentPosition else 0L
        playInternal(
            track = track,
            newQueue = normalized,
            startPositionMs = startPosition,
            source = QueueSource.CONTEXT,
            seed = null,
            force = force,
            contextTitle = contextTitle
        )
    }

    override fun togglePlayPause() {
        if (exoPlayer.mediaItemCount == 0 && _currentTrack.value != null) {
            playInternal(
                _currentTrack.value!!,
                _queue.value,
                _positionMs.value,
                _queueSource.value,
                radioSeed,
                force = true
            )
            return
        }
        if (exoPlayer.playWhenReady && exoPlayer.playbackState != Player.STATE_ENDED) {
            pause()
        } else {
            resume()
        }
    }

    override fun pause() {
        exoPlayer.pause()
        _isPlaying.value = false
        // ponytail: save on pause/seek/track change only; add periodic flush if process kill during active playback is common
        savePosition(exoPlayer.currentPosition)
    }

    override fun setSkipSilence(enabled: Boolean) {
        skipDeadAirEnabled = enabled
        exoPlayer.skipSilenceEnabled = false
    }

    override fun resume() {
        BlexsyEqualizer.audioProcessor.resetAudibleTime()
        val cur = _currentTrack.value
        if (cur != null && exoPlayer.mediaItemCount == 0) {
            playInternal(
                cur,
                _queue.value,
                _positionMs.value,
                _queueSource.value,
                radioSeed,
                force = true
            )
            return
        }
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekToDefaultPosition()
        }
        exoPlayer.play()
        _isPlaying.value = true
        maybeAutoFillRadio()
    }

    override fun seekTo(positionMs: Long) {
        BlexsyEqualizer.audioProcessor.resetAudibleTime()
        if (positionMs <= 0L) {
            resetSpinTracking()
        }
        if (exoPlayer.mediaItemCount == 0 && _currentTrack.value != null) {
            _positionMs.value = positionMs
            savePosition(positionMs)
            return
        }
        exoPlayer.seekTo(positionMs)
        _positionMs.value = positionMs
        savePosition(positionMs)
    }

    private fun playQueueAt(index: Int) {
        val queue = _queue.value
        if (index !in queue.indices) return
        playInternal(
            track = queue[index],
            newQueue = queue,
            startPositionMs = 0L,
            source = _queueSource.value,
            seed = radioSeed,
            force = true
        )
    }

    override fun next() {
        val count = exoPlayer.mediaItemCount
        if (count == 0) {
            val cur = _currentTrack.value ?: return
            val queue = _queue.value
            val index = queue.indexOfFirst { it.id == cur.id }
            val nextIndex = when {
                index != -1 && index + 1 < queue.size -> index + 1
                _repeatMode.value == PlaybackRepeatMode.ALL && queue.isNotEmpty() -> 0
                else -> {
                    pause()
                    return
                }
            }
            playQueueAt(nextIndex)
            return
        }
        // Explicit "next" must always advance, even while repeat-one is active.
        // At the end of the queue it wraps only in repeat-all mode, matching the
        // previous player behavior; auto-advance is still handled by ExoPlayer.
        val current = exoPlayer.currentMediaItemIndex
        val nextIndex = when {
            current + 1 < count -> current + 1
            _repeatMode.value == PlaybackRepeatMode.ALL -> 0
            else -> {
                pause()
                return
            }
        }
        exoPlayer.seekToDefaultPosition(nextIndex)
        _positionMs.value = 0L
        exoPlayer.play()
        maybeAutoFillRadio()
    }

    override fun previous() {
        val count = exoPlayer.mediaItemCount
        if (count == 0) {
            val cur = _currentTrack.value ?: return
            val queue = _queue.value
            val index = queue.indexOfFirst { it.id == cur.id }
            if (index > 0) {
                playQueueAt(index - 1)
            } else {
                seekTo(0L)
            }
            return
        }
        if (exoPlayer.currentMediaItemIndex > 0) {
            exoPlayer.seekToDefaultPosition(exoPlayer.currentMediaItemIndex - 1)
            _positionMs.value = 0L
            exoPlayer.play()
        } else {
            seekTo(0L)
        }
    }

    override fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            PlaybackRepeatMode.OFF -> {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
                PlaybackRepeatMode.ALL
            }
            PlaybackRepeatMode.ALL -> {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                PlaybackRepeatMode.ONE
            }
            PlaybackRepeatMode.ONE -> {
                exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                PlaybackRepeatMode.OFF
            }
        }
    }

    override fun playAtIndex(index: Int) {
        val queue = _queue.value
        if (index !in queue.indices) return
        val target = queue[index]
        val curIndex = if (exoPlayer.mediaItemCount > 0) exoPlayer.currentMediaItemIndex else 0
        if (_currentTrack.value?.id == target.id) {
            if (!_isPlaying.value) {
                resume()
            } else {
                seekTo(0L)
            }
            return
        }

        val plan = QueuePolicy.computeJumpOrder(queue.map { it.queueTier }, curIndex, index)
        if (plan != null && plan.isPureSeek && plan.newTargetIndex in 0 until exoPlayer.mediaItemCount) {
            exoPlayer.seekTo(plan.newTargetIndex, 0L)
            exoPlayer.play()
            resetSpinTracking()
            _currentTrack.value = target
            _positionMs.value = 0L
            _durationMs.value = target.durationMs ?: 0L
            _isPlaying.value = true
            savePlaybackState(target, queue, 0L, _queueSource.value, radioSeed)
            maybeAutoFillRadio()
            return
        }

        val newQueue = plan?.newOrderIndices?.map { queue[it] } ?: queue.drop(index)
        val targetTrack = plan?.let { newQueue.getOrNull(it.newTargetIndex) } ?: target
        _queue.value = newQueue
        resetSpinTracking()
        _currentTrack.value = targetTrack
        _positionMs.value = 0L
        _durationMs.value = targetTrack.durationMs ?: 0L
        _isBuffering.value = true
        _isPlaying.value = true

        applyQueueToPlayer(newQueue, targetTrack, 0L, playWhenReady = true)
        savePlaybackState(targetTrack, newQueue, 0L, _queueSource.value, radioSeed)
        maybeAutoFillRadio()
    }

    override fun removeFromQueue(index: Int) {
        val queueBefore = _queue.value
        val currentQueue = queueBefore.toMutableList()
        if (index !in currentQueue.indices) return
        val removedTrack = currentQueue.removeAt(index)
        val wasCurrent = _currentTrack.value?.id == removedTrack.id
        val aligned = exoPlayer.mediaItemCount == queueBefore.size
        _queue.value = currentQueue

        if (currentQueue.isEmpty()) {
            beginContextSession()
            _queueSource.value = QueueSource.CONTEXT
            _currentTrack.value = null
            _isPlaying.value = false
            _positionMs.value = 0L
            _durationMs.value = 0L
            tickerJob?.cancel()
            runCatching { exoPlayer.clearMediaItems() }
            runCatching { exoPlayer.stop() }
            return
        }

        if (aligned) {
            if (index < exoPlayer.mediaItemCount) {
                exoPlayer.removeMediaItem(index)
            }
            if (wasCurrent) {
                // ExoPlayer advances to the next item; update state eagerly so the
                // persisted queue never points at the removed track.
                val next = currentQueue.getOrNull(index) ?: currentQueue.last()
                resetSpinTracking()
                _currentTrack.value = next
                _positionMs.value = 0L
                _durationMs.value = next.durationMs ?: 0L
            }
        } else {
            val anchor = _currentTrack.value
                ?.takeIf { current -> currentQueue.any { it.id == current.id } }
                ?: currentQueue.getOrNull(index)
                ?: currentQueue.first()
            _currentTrack.value = anchor
            _positionMs.value = 0L
            _durationMs.value = anchor.durationMs ?: 0L
            applyQueueToPlayer(currentQueue, anchor, 0L, _isPlaying.value)
        }

        _currentTrack.value?.let {
            savePlaybackState(it, currentQueue, _positionMs.value, _queueSource.value, radioSeed)
        }
        maybeAutoFillRadio()
    }

    override fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val queue = _queue.value.toMutableList()
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val aligned = exoPlayer.mediaItemCount == queue.size
        val item = queue.removeAt(fromIndex)
        queue.add(toIndex, item)
        _queue.value = queue

        if (aligned && fromIndex < exoPlayer.mediaItemCount && toIndex < exoPlayer.mediaItemCount) {
            exoPlayer.moveMediaItem(fromIndex, toIndex)
        } else {
            val anchor = _currentTrack.value
                ?.takeIf { current -> queue.any { it.id == current.id } }
                ?: queue.first()
            applyQueueToPlayer(queue, anchor, exoPlayer.currentPosition, _isPlaying.value)
        }
        _currentTrack.value?.let {
            savePlaybackState(it, queue, _positionMs.value, _queueSource.value, radioSeed)
        }
    }

    override fun clearQueue() {
        beginContextSession()
        _queueSource.value = QueueSource.CONTEXT
        contextTitleVal = null
        _contextTitle.value = null
        val current = _currentTrack.value
        if (current != null) {
            _queue.value = listOf(current)
            applyQueueToPlayer(
                listOf(current),
                current,
                exoPlayer.currentPosition.coerceAtLeast(0L),
                _isPlaying.value
            )
            savePlaybackState(current, listOf(current), _positionMs.value, QueueSource.CONTEXT, null)
        } else {
            _queue.value = emptyList()
            runCatching { exoPlayer.clearMediaItems() }
            _positionMs.value = 0L
            _durationMs.value = 0L
            _isPlaying.value = false
        }
    }

    override fun addToQueue(track: Track, isNext: Boolean) {
        val entry = QueuePolicy.asQueueEntry(track, QueueTier.USER_QUEUE)
        val currentQueue = _queue.value.toMutableList()
        val currentIndex = if (exoPlayer.mediaItemCount > 0) exoPlayer.currentMediaItemIndex else 0
        val insertIndex = QueuePolicy.findUserQueueInsertionIndex(currentQueue, currentIndex, isNext)
        currentQueue.add(insertIndex.coerceIn(0, currentQueue.size), entry)
        _queue.value = currentQueue

        val mediaItem = toMediaItem(entry)
        if (insertIndex in 0..exoPlayer.mediaItemCount) {
            exoPlayer.addMediaItem(insertIndex, mediaItem)
        } else {
            exoPlayer.addMediaItem(mediaItem)
        }
        _currentTrack.value?.let {
            savePlaybackState(it, currentQueue, _positionMs.value, _queueSource.value, radioSeed)
        }
    }

    override fun clearUserQueue() {
        val currentQueue = _queue.value
        val currentIndex = if (exoPlayer.mediaItemCount > 0) exoPlayer.currentMediaItemIndex else 0
        val newQueue = QueuePolicy.clearUserQueue(currentQueue, currentIndex)
        _queue.value = newQueue

        val count = exoPlayer.mediaItemCount
        val userIndices = mutableListOf<Int>()
        for (i in (currentIndex + 1) until count) {
            if (currentQueue.getOrNull(i)?.queueTier == QueueTier.USER_QUEUE) {
                userIndices.add(i)
            }
        }
        for (i in userIndices.asReversed()) {
            exoPlayer.removeMediaItem(i)
        }
        _currentTrack.value?.let {
            savePlaybackState(it, newQueue, _positionMs.value, _queueSource.value, radioSeed)
        }
    }

    override fun appendToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val currentQueue = _queue.value
        val maxSize = if (_queueSource.value == QueueSource.RADIO) {
            QueuePolicy.RADIO_QUEUE_TARGET
        } else {
            Int.MAX_VALUE
        }
        val merged = QueuePolicy.mergeUnique(currentQueue, tracks, maxSize)
        val added = merged.drop(currentQueue.size)
        if (added.isEmpty()) return

        val aligned = exoPlayer.mediaItemCount == currentQueue.size
        _queue.value = merged
        if (aligned) {
            runCatching { exoPlayer.addMediaItems(added.map(::toMediaItem)) }
        } else {
            // Self-heal if a previous operation left ExoPlayer and _queue out of
            // sync. Preserve the current track and position.
            val anchor = _currentTrack.value ?: merged.first()
            applyQueueToPlayer(merged, anchor, exoPlayer.currentPosition, _isPlaying.value)
        }

        if (_queueSource.value == QueueSource.RADIO) {
            added.forEach { seenRadioTrackIds.add(it.id) }
            QueuePolicy.trimSeen(seenRadioTrackIds)
        }
        _currentTrack.value?.let {
            savePlaybackState(it, merged, _positionMs.value, _queueSource.value, radioSeed)
        }
    }

    private fun isCurrentRadioSession(seed: Track, sessionId: Long): Boolean =
        sessionId == radioSessionId &&
            _queueSource.value == QueueSource.RADIO &&
            radioSeed?.id == seed.id

    private fun scheduleRadioRetry(message: String) {
        radioRetryAttempt = (radioRetryAttempt + 1).coerceAtMost(10)
        nextRadioRetryAtMs = SystemClock.elapsedRealtime() +
            QueuePolicy.radioRetryDelayMs(radioRetryAttempt)
        _radioError.value = message
    }

    private suspend fun fetchAndAppendRadio(seed: Track, sessionId: Long) {
        if (!isFetchingRadio.compareAndSet(false, true)) return
        _isRadioLoading.value = true
        try {
            if (!isCurrentRadioSession(seed, sessionId)) return
            val target = QueuePolicy.RADIO_QUEUE_TARGET
            val current = _queue.value
            val slotsNeeded = (target - current.size).coerceAtLeast(0)
            if (slotsNeeded <= 0 && radioContinuation != null) return

            val videoId = seed.youtubeId?.takeIf { it.isNotBlank() && it.length == 11 }
                ?: seed.id.takeIf { it.length == 11 && !it.contains("-") }
                ?: run {
                    val searchResults = InnerTubeService.searchTracks("${seed.displayTitle} ${seed.artist}", limit = 1)
                    searchResults.firstOrNull()?.youtubeId ?: searchResults.firstOrNull()?.id
                }

            if (videoId == null) {
                scheduleRadioRetry("Radio track not found.")
                return
            }

            val (recommendations, nextContinuation) = InnerTubeService.getRadioTracks(videoId, radioContinuation)
            radioContinuation = nextContinuation

            if (!isCurrentRadioSession(seed, sessionId)) return

            val latest = _queue.value
            val latestIds = latest.map { it.id }.toSet()
            val toAdd = recommendations.asSequence()
                .filter { it.id.isNotBlank() && !latestIds.contains(it.id) }
                .distinctBy { it.id }
                .toList()

            if (toAdd.isNotEmpty()) {
                appendToQueue(toAdd)
                radioRetryAttempt = 0
                nextRadioRetryAtMs = 0L
                _radioError.value = null
            } else if (recommendations.isNotEmpty()) {
                scheduleRadioRetry("Radio's out of fresh tracks.")
            } else {
                scheduleRadioRetry("Couldn't find matching radio jams.")
            }
        } catch (_: Exception) {
            if (isCurrentRadioSession(seed, sessionId)) {
                scheduleRadioRetry("Radio glitched. Retrying in a sec…")
            }
        } finally {
            _isRadioLoading.value = false
            isFetchingRadio.set(false)
        }
    }

    override fun startRadio(seed: Track) {
        beginRadioSession(seed)
        val sameTrack = _currentTrack.value?.id == seed.id && exoPlayer.mediaItemCount > 0
        val startPosition = if (sameTrack) exoPlayer.currentPosition.coerceAtLeast(0L) else 0L
        playInternal(
            track = seed,
            newQueue = listOf(seed),
            startPositionMs = startPosition,
            source = QueueSource.RADIO,
            seed = seed,
            force = true
        )
        scope.launch { fetchAndAppendRadio(seed, radioSessionId) }
    }

    private fun maybeAutoFillRadio() {
        if (_queueSource.value != QueueSource.RADIO) {
            val prefs = appContext.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
            val autoplay = prefs.getBoolean("autoplay_enabled", true)
            if (autoplay && _repeatMode.value == PlaybackRepeatMode.OFF) {
                val q = _queue.value
                val curIdx = exoPlayer.currentMediaItemIndex
                if (q.isNotEmpty() && curIdx == q.lastIndex) {
                    val currentTrack = _currentTrack.value ?: q.last()
                    _queueSource.value = QueueSource.RADIO
                    beginRadioSession(currentTrack)
                    scope.launch { fetchAndAppendRadio(currentTrack, radioSessionId) }
                }
            }
            return
        }
        val seed = radioSeed ?: return
        val queue = _queue.value
        if (queue.isEmpty()) return
        val remaining = queue.size - (exoPlayer.currentMediaItemIndex + 1)
        if (remaining > QueuePolicy.RADIO_REFILL_THRESHOLD) return
        if (isFetchingRadio.get()) return
        if (SystemClock.elapsedRealtime() < nextRadioRetryAtMs) return
        scope.launch { fetchAndAppendRadio(seed, radioSessionId) }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var lastFlushMs = android.os.SystemClock.elapsedRealtime()
            var lastTickMs = android.os.SystemClock.elapsedRealtime()
            BlexsyEqualizer.audioProcessor.resetAudibleTime()
            while (isActive && exoPlayer.isPlaying) {
                val current = exoPlayer.currentPosition
                _positionMs.value = current
                val now = android.os.SystemClock.elapsedRealtime()
                val delta = (now - lastTickMs).coerceAtLeast(0L)
                lastTickMs = now

                // ponytail: count spin only after >=20s active listening (or 80% if track <20s). Upgrade when scrobble spec demands percentage thresholds.
                val track = _currentTrack.value
                if (track != null && spinCountedTrackId != track.id) {
                    activeListeningMs += delta
                    val dur = _durationMs.value.takeIf { it > 0 } ?: track.durationMs ?: 0L
                    val thresholdMs = if (dur in 1..19_999L) (dur * 0.8f).toLong() else 20_000L
                    if (activeListeningMs >= thresholdMs) {
                        spinCountedTrackId = track.id
                        recordRecent(track)
                    }
                }

                if (skipDeadAirEnabled && exoPlayer.playbackState == Player.STATE_READY) {
                    val duration = _durationMs.value.takeIf { it > 0 } ?: exoPlayer.duration.takeIf { it > 0 } ?: 0L
                    val isOutro = when {
                        duration > 30_000L -> current >= duration * 0.70f || (duration - current) <= 45_000L
                        duration > 10_000L -> current >= duration * 0.80f
                        else -> false
                    }
                    if (isOutro) {
                        val silentDurationMs = now - BlexsyEqualizer.audioProcessor.lastAudibleTimeMs
                        if (silentDurationMs >= 2500L && now - lastDeadAirSkipAtMs > 3000L) {
                            lastDeadAirSkipAtMs = now
                            BlexsyEqualizer.audioProcessor.resetAudibleTime()
                            withContext(Dispatchers.Main) {
                                if (_repeatMode.value == PlaybackRepeatMode.ONE) {
                                    seekTo(0L)
                                    exoPlayer.play()
                                } else {
                                    next()
                                }
                            }
                        }
                    }
                }

                // ponytail: 4s disk throttle prevents I/O contention; upgrade to lifecycle flush if process termination is detected
                if (now - lastFlushMs >= 4000L) {
                    lastFlushMs = now
                    savePosition(current)
                }
                delay(40)
            }
        }
    }

    fun release() {
        if (exoPlayer.mediaItemCount > 0) {
            savePosition(exoPlayer.currentPosition)
        }
        tickerJob?.cancel()
        mediaSession.release()
        exoPlayer.release()
    }
}
