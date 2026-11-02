package com.blek.blexsy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
/** App-wide sleep timer for audio playback. */
object SleepTimer {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null
    var activeLabel by mutableStateOf<String?>(null)
        private set

    val isActive: Boolean get() = activeLabel != null

    fun start(minutes: Int, onFinish: () -> Unit) {
        job?.cancel()
        activeLabel = "$minutes min"
        job = scope.launch {
            delay(minutes * 60 * 1000L)
            activeLabel = null
            onFinish()
        }
    }

    fun startForTrack(remainingMs: Long, onFinish: () -> Unit) {
        job?.cancel()
        activeLabel = "End of track"
        job = scope.launch {
            delay(remainingMs)
            activeLabel = null
            onFinish()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        activeLabel = null
    }
}
