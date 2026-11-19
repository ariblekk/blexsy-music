package com.blek.blexsy.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.SearchResult
import com.blek.blexsy.model.ArtistSummary
import com.blek.blexsy.model.GenrePlaylist
import com.blek.blexsy.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SEARCH_CACHE_MAX = 60
private const val PREFS_SEARCH = "blexsy_search"
private const val KEY_HISTORY = "recent_queries"
private const val TAG = "BlexsySearch"

/** Two characters before a request: a single letter matches everything and costs a round trip. */
private const val MIN_QUERY_LENGTH = 2

/**
 * Typing is debounced so a burst of keystrokes is one request instead of one per letter. A
 * committed search (keyboard Search, recent-search tap, retry) skips the wait.
 */
private const val TYPING_DEBOUNCE_MS = 300L

/** Autocomplete suggestions come from locally known names only, so they never hit the network. */
private const val SUGGESTION_LIMIT = 5

/** History entries are sticky and user-visible, so a single letter is not worth keeping. */
private const val MIN_HISTORY_LENGTH = 2

/** Tracks per page. */
private const val SEARCH_PAGE_SIZE = 20

/**
 * Everything the results surface can be showing. Collapsing these into one sealed type is
 * what lets the UI tell "still loading" apart from "nothing matched" apart from "the
 * request failed" — previously all three rendered as the same blank screen.
 */
internal sealed interface SearchUiState {
    /** No usable query yet — the field is blank. */
    data object Idle : SearchUiState

    data object Loading : SearchUiState

    data class Loaded(
        val tracks: List<Track> = emptyList(),
        val artists: List<ArtistSummary> = emptyList(),
        val playlists: List<GenrePlaylist> = emptyList(),
        /** Non-null while another page can be fetched; drives the footer trigger. */
        val nextOffset: Int? = null,
        /** Opaque token for the next page; Spotify offset for search. */
        val nextContinuation: String? = null,
        /** True while the *next* page is in flight; the list stays visible underneath. */
        val appending: Boolean = false,
        /** Set when appending failed, so the footer can offer a retry without blanking results. */
        val appendFailed: Boolean = false,
        /**
         * True while a *new* query is being fetched over the top of these results. The list stays
         * on screen so typing swaps results instead of flickering through a skeleton.
         */
        val refreshing: Boolean = false
    ) : SearchUiState {
        val isEmpty: Boolean
            get() = tracks.isEmpty() && artists.isEmpty() && playlists.isEmpty()
    }

    data class Failed(val message: String? = null) : SearchUiState
}

/** Outcome of one page request, used internally to fold a new page into the existing list. */
private sealed interface PageOutcome {
    data class Success(val result: SearchResult) : PageOutcome
    data class Failure(val message: String?) : PageOutcome
}

/** One keystroke or committed search. [immediate] bypasses the typing debounce. */
private data class QueryInput(val text: String, val immediate: Boolean = false)

/**
 * Prefix matches from names the app already knows: past queries, queries whose results are
 * cached, and the artist/song names already on screen. Pure list work, no request.
 */
internal fun suggestQueries(
    query: String,
    known: List<String>,
    limit: Int = SUGGESTION_LIMIT
): List<String> {
    val q = query.trim()
    if (q.length < MIN_QUERY_LENGTH) return emptyList()
    return known
        .filter { it.length > q.length && it.startsWith(q, ignoreCase = true) }
        .distinctBy { it.lowercase() }
        .take(limit)
}

/**
 * Search state shared by the Search tab (display mode) and the full-screen search page.
 *
 * Kept outside [HomeScreen] so the tab can stay a dumb "browse" surface while the
 * page owns the actual query, results and recent-search list.
 *
 * Queries are funnelled through a single flow rather than a hand-managed [kotlinx.coroutines.Job]:
 * every keystroke feeds the pipeline and [flatMapLatest] cancels the previous request
 * structurally, so a stale response can never land after a newer one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Stable
internal class BlexsySearchState(
    private val api: BlexsyApiClient,
    private val scope: CoroutineScope,
    context: Context? = null
) {
    var query by mutableStateOf("")
        private set

    var uiState by mutableStateOf<SearchUiState>(SearchUiState.Idle)
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

    /** In-memory result cache keyed by query; holds the pages accumulated so far, not just page one. */
    private val cache = mutableMapOf<String, SearchUiState.Loaded>()

    /** Continuations already requested per query, so a stuck footer cannot re-request a page. */
    private val requestedContinuations = mutableMapOf<String, MutableSet<String>>()

    private val inputs = MutableSharedFlow<QueryInput>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var remoteSuggestions by mutableStateOf<List<String>>(emptyList())

    /**
     * Autocomplete for what is being typed: combines Spotify remote suggestions with locally
     * known names (past queries, cached queries, names already on screen).
     */
    val suggestions: List<String>
        get() {
            val loaded = uiState as? SearchUiState.Loaded
            // Names already rendered as result rows are dropped so nothing shows twice.
            val shown = buildSet {
                loaded?.artists?.forEach { add(it.name.lowercase()) }
                loaded?.tracks?.forEach { add(it.displayTitle.lowercase()) }
            }
            val local = suggestQueries(
                query = query,
                known = buildList {
                    loaded?.artists?.forEach { add(it.name) }
                    loaded?.tracks?.forEach { add(it.displayTitle) }
                    addAll(cache.keys)
                    addAll(history)
                }
            )
            val trimmedQ = query.trim()
            return (remoteSuggestions + local)
                .distinctBy { it.lowercase() }
                .filterNot { item ->
                    item.lowercase() in shown || item.trim().equals(trimmedQ, ignoreCase = true)
                }
                .take(SUGGESTION_LIMIT)
        }

    init {
        inputs
            // A repeat of the query already on screen is not a new search. Dropping it here is what
            // makes the keyboard's Search action feel instant: with results already showing it
            // resolves to nothing, instead of cancelling the request and running the same one again.
            //
            // A failure is the exception — that query is not "already shown" in any useful sense, so
            // pressing Search (or the retry button) must actually re-run it.
            .distinctUntilChanged { old, new -> old.text == new.text && uiState !is SearchUiState.Failed }
            // Typing collapses into a single request; a committed search goes through at once.
            // `flatMapLatest` still cancels whatever is in flight, so a stale response can never
            // land after a newer one.
            .debounce { if (it.immediate) 0L else TYPING_DEBOUNCE_MS }
            .flatMapLatest { input -> searchFlow(input.text) }
            .onEach { uiState = it }
            .launchIn(scope)

        inputs
            .map { it.text.trim() }
            .distinctUntilChanged()
            .debounce(180L)
            .flatMapLatest { q ->
                flow {
                    if (q.length >= MIN_QUERY_LENGTH) {
                        emit(api.searchSuggest(q))
                    } else {
                        emit(emptyList())
                    }
                }
            }
            .onEach { remoteSuggestions = it }
            .launchIn(scope)
    }

    /**
     * Emits the states a single query passes through. Cancellation of an in-flight query is
     * normal control flow here ([flatMapLatest] doing its job), so it is rethrown untouched and
     * never surfaces as a failure.
     */
    private fun searchFlow(rawQuery: String): Flow<SearchUiState> = flow {
        val q = rawQuery.trim()
        if (q.length < MIN_QUERY_LENGTH) {
            emit(SearchUiState.Idle)
            return@flow
        }

        cache[q]?.let { cached ->
            emit(cached)
            return@flow
        }

        // Keep whatever is already on screen while the new query is in flight. Swapping results in
        // place is what makes typing feel instant; a skeleton on every keystroke just flickers.
        //
        // The paging flags are cleared deliberately: nextOffset and appendFailed both describe the
        // *previous* query. Left set, the footer would either page the new query at the old query's
        // offset or offer a retry button whose offset is gone.
        val previous = uiState as? SearchUiState.Loaded
        if (previous != null && !previous.isEmpty) {
            emit(
                previous.copy(
                    refreshing = true,
                    nextOffset = null,
                    appending = false,
                    appendFailed = false
                )
            )
        } else {
            emit(SearchUiState.Loading)
        }

        when (val outcome = fetchPage(q, continuation = null)) {
            is PageOutcome.Failure -> emit(SearchUiState.Failed(outcome.message))
            is PageOutcome.Success -> {
                val result = outcome.result
                val loaded = SearchUiState.Loaded(
                    tracks = result.tracks,
                    artists = result.artists,
                    playlists = result.playlists,
                    nextOffset = result.continuation?.let { 1 },
                    nextContinuation = result.continuation
                )
                // Only a non-empty first page is worth caching: caching a miss would replay it
                // forever, and caching a failure would hide a recovered server.
                if (!loaded.isEmpty) {
                    if (cache.size > SEARCH_CACHE_MAX) cache.clear()
                    cache[q] = loaded
                }
                emit(loaded)
            }
        }
    }.catch { e ->
        if (e is CancellationException) throw e
        Log.e(TAG, "Search failed for '$rawQuery'", e)
        emit(SearchUiState.Failed(e.message))
    }

    /** Runs one page request, mapping the service's own failure flag onto a [PageOutcome]. */
    private suspend fun fetchPage(q: String, continuation: String?): PageOutcome {
        val result = api.search(q, continuation = continuation, limit = SEARCH_PAGE_SIZE)
        return if (result.failed) PageOutcome.Failure(null) else PageOutcome.Success(result)
    }

    /**
     * Appends the next page to the current results. Called from the list's scroll threshold, so it
     * must stay cheap to call repeatedly: any call while a page is already in flight, or after the
     * last page, returns without doing anything.
     *
     * Offsets already fetched for a query are remembered, so a footer that happens to stay on
     * screen after an append cannot spiral into fetching every remaining page at once.
     */
    fun loadMore() {
        val current = uiState as? SearchUiState.Loaded ?: return
        val continuation = current.nextContinuation ?: return
        if (current.appending) return

        val q = query.trim()
        if (q.length < MIN_QUERY_LENGTH) return
        if (!requestedContinuations.getOrPut(q) { mutableSetOf() }.add(continuation)) return

        uiState = current.copy(appending = true, appendFailed = false)
        scope.launch {
            val outcome = fetchPage(q, continuation)
            // This launch sits outside flatMapLatest, so it is not cancelled when the query
            // changes. Re-check the query before writing: a page fetched for the old query must
            // never land on top of a newer one's results.
            if (query.trim() != q) return@launch
            when (outcome) {
                is PageOutcome.Failure -> {
                    // Forget the continuation so the footer's retry button can ask for it again.
                    requestedContinuations[q]?.remove(continuation)
                    val visible = uiState as? SearchUiState.Loaded ?: return@launch
                    uiState = visible.copy(appending = false, appendFailed = true)
                }
                is PageOutcome.Success -> {
                    val visible = uiState as? SearchUiState.Loaded ?: return@launch
                    val merged = (visible.tracks + outcome.result.tracks).distinctBy { it.id }
                    val updated = visible.copy(
                        tracks = merged,
                        // Artist stays from the first page: search shows one,
                        // and continuation pages only extend the track list.
                        nextOffset = outcome.result.continuation?.let { 1 },
                        nextContinuation = outcome.result.continuation,
                        appending = false,
                        appendFailed = false
                    )
                    cache[q] = updated
                    uiState = updated
                }
            }
        }
    }

    /** Re-runs the current query after a failure. Failures are never cached, so this re-hits the network. */
    fun retry() {
        val q = query.trim()
        if (q.length < MIN_QUERY_LENGTH) return
        cache.remove(q)
        inputs.tryEmit(QueryInput(q, immediate = true))
    }

    /**
     * Records [term] as something the user deliberately searched for. Kept separate from the fetch
     * path: with a search firing while typing, recording there would fill the list with every
     * prefix the user typed through — `b`, `bm`, `bmt`, `bmth`.
     */
    private fun recordHistory(term: String) {
        if (term.length < MIN_HISTORY_LENGTH) return
        history.remove(term)
        history.add(0, term)
        if (history.size > 20) history.removeAt(history.lastIndex)
        persistHistory()
    }

    /**
     * Feeds a new query into the pipeline. [immediate] marks a *committed* search — the keyboard's
     * Search action, a recent-search tap, a retry — as opposed to ordinary typing. Only committed
     * queries are written to [history].
     */
    fun submit(q: String, immediate: Boolean = false) {
        query = q
        if (immediate) recordHistory(q.trim())
        inputs.tryEmit(QueryInput(q, immediate))
    }

    fun clearQuery() {
        remoteSuggestions = emptyList()
        submit("")
    }

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
