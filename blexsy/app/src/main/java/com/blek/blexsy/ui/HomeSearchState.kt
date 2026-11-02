package com.blek.blexsy.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.SearchResult
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SEARCH_DEBOUNCE_MS = 450L
private const val PREFS_SEARCH = "blexsy_search"
private const val KEY_HISTORY = "recent_queries"

/**
 * Search state shared by the Search tab (display mode) and the full-screen search page.
 *
 * Kept outside [HomeScreen] so the tab can stay a dumb "browse" surface while the
 * page owns the actual query, results and recent-search list.
 */
@Stable
internal class BlexsySearchState(
    private val api: BlexsyApiClient,
    private val scope: CoroutineScope,
    context: Context? = null
) {
    var query by mutableStateOf("")
        private set

    var results by mutableStateOf<List<Track>>(emptyList())
        private set

    var artists by mutableStateOf<List<ArtistSummary>>(emptyList())
        private set

    var isSearching by mutableStateOf(false)
        private set

    private val prefs = context?.getSharedPreferences(PREFS_SEARCH, Context.MODE_PRIVATE)

    val history = mutableStateListOf<String>().apply {
        val raw = prefs?.getString(KEY_HISTORY, null)
        if (!raw.isNullOrBlank()) {
            val list = runCatching { Json.decodeFromString<List<String>>(raw) }.getOrNull()
            if (list != null) addAll(list)
        }
    }

    private fun persistHistory() {
        val raw = runCatching { Json.encodeToString(history.toList()) }.getOrNull() ?: return
        prefs?.edit()?.putString(KEY_HISTORY, raw)?.apply()
    }

    private val cache = mutableStateMapOf<String, SearchResult>()
    private var job: Job? = null

    fun submit(q: String, immediate: Boolean = false) {
        query = q

        // Any in-flight search belongs to the previous keystroke; cancelling it keeps
        // old results from landing after newer ones.
        job?.cancel()

        val trimmed = q.trim()
        if (trimmed.length < 2) {
            results = emptyList()
            artists = emptyList()
            isSearching = false
            return
        }

        // Repeating a query (or backspacing) should not hit the network at all.
        cache[trimmed]?.let {
            results = it.tracks
            artists = it.artists
            isSearching = false
            return
        }

        job = scope.launch {
            if (!immediate) {
                delay(SEARCH_DEBOUNCE_MS)
            }
            isSearching = true
            try {
                val res = api.search(trimmed, limit = 25)
                if (cache.size > 60) cache.clear()
                cache[trimmed] = res
                if (query == q) {
                    results = res.tracks
                    artists = res.artists
                    history.remove(trimmed)
                    history.add(0, trimmed)
                    if (history.size > 20) history.removeAt(history.lastIndex)
                    persistHistory()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                if (query == q) isSearching = false
            }
        }
    }

    fun clearQuery() = submit("")

    fun removeHistory(item: String) {
        history.remove(item)
        persistHistory()
    }

    fun clearHistory() {
        history.clear()
        persistHistory()
    }
}

@Composable
internal fun rememberBlexsySearchState(api: BlexsyApiClient): BlexsySearchState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(api, scope, context) { BlexsySearchState(api, scope, context) }
}
