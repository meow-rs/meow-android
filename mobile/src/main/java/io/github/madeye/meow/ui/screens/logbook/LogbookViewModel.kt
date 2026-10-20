package io.github.madeye.meow.ui.screens.logbook

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.logbook.LogbookEntry
import io.github.madeye.meow.logbook.LogbookPage
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

@Immutable
data class LogbookUiState(
    /** Newest first. */
    val entries: List<LogbookEntry> = emptyList(),
    /** Older entries exist past [entries]; never while searching, which shows every match. */
    val more: Boolean = false,
    /** The search as typed. */
    val query: String = "",
    /** The search [entries] answer; empty for the newest entries. */
    val search: String = "",
    /** The event picked in the filter menu; null for every event. */
    val kind: String? = null,
    /** Why the logbook could not be read; shown only while there is nothing else to show. */
    val error: String? = null,
    /** False until the first read, so the empty hint does not flash on open. */
    val loaded: Boolean = false,
)

/**
 * The engine's logbook: what happened to it, kept across restarts — runs
 * and how they ended, config and provider failures, dialer-proxy switches. Read
 * from the database the engine keeps in its home, so it also opens while
 * the VPN is down; polled while shown, re-reading only once something was
 * written.
 *
 * One event can be picked, which the engine filters by. It has no text
 * search, so a search reads the whole logbook (a few MiB at most) once and
 * matches locally: every word must appear in the entry's message, subject,
 * details or event name.
 */
class LogbookViewModel(
    private val read: suspend (before: Long, since: Long, limit: Int, kind: String?) -> LogbookPage,
    /** The event name shown for a kind, so a search can find it; null for a kind without one. */
    private val kindLabel: (String) -> String? = { null },
    /** Where matching runs; off the main thread, since it walks every entry. */
    private val compute: CoroutineContext = Dispatchers.Default,
) : ViewModel() {

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val PAGE_SIZE = 100

        /** The engine's largest page. */
        const val MAX_PAGE = 1_000

        /** Typing restarts the search; waiting for a pause reads the logbook once, not once per key. */
        const val DEBOUNCE_MS = 300L

        private val WHITESPACE = Regex("\\s+")
    }

    /** What is shown, the logbook's latest write when it was read, and the event it is of. */
    private data class Shown(val state: LogbookUiState = LogbookUiState(), val rev: Long = 0, val kind: String? = null)

    private data class Filter(val search: String, val kind: String?)

    /** The whole logbook, newest first, with each entry's text to search; kept while searching. */
    private class Everything(val entries: List<LogbookEntry>, val texts: List<String>, val rev: Long)

    private val query = MutableStateFlow("")
    private val kind = MutableStateFlow<String?>(null)
    private val latest = MutableStateFlow(Shown())
    private var everything: Everything? = null

    /** One read at a time: a poll and a page back both rewrite [latest]. */
    private val reading = Mutex()

    /** Runs only while [uiState] is collected; a new search or event restarts it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val polling: Flow<Unit> = combine(query.map { it.trim() }, kind) { search, kind -> Filter(search, kind) }
        .distinctUntilChanged()
        .transformLatest<Filter, Unit> { filter ->
            // Typing waits for a pause; picking an event does not.
            if (filter.search.isNotEmpty() && filter.search != latest.value.state.search) delay(DEBOUNCE_MS)
            while (true) {
                if (filter.search.isEmpty()) poll(filter.kind) else poll(filter.search, filter.kind)
                delay(POLL_INTERVAL_MS)
            }
        }
        .onStart { emit(Unit) }

    val uiState: StateFlow<LogbookUiState> =
        combine(latest, query, kind, polling) { shown, typed, picked, _ ->
            shown.state.copy(query = typed, kind = picked)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogbookUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Shows only [value]'s entries; null shows every event. */
    fun onKindChange(value: String?) {
        kind.value = value
    }

    /** Reads the page past the oldest entry shown. */
    fun loadMore() {
        viewModelScope.launch {
            reading.withLock {
                val shown = latest.value
                val oldest = shown.state.entries.lastOrNull()?.id
                if (!shown.state.more || oldest == null) return@withLock
                try {
                    val page = read(oldest, 0, PAGE_SIZE, shown.kind)
                    latest.value = shown.copy(
                        state = shown.state.copy(entries = shown.state.entries + page.entries, more = page.more),
                    )
                } catch (_: IOException) {
                    // Left as it is: scrolling back to the end tries again.
                }
            }
        }
    }

    /** The newest entries of [kind], and any shown pages back. */
    private suspend fun poll(kind: String?) = reading.withLock {
        everything = null
        val shown = latest.value.takeIf { it.state.loaded && it.state.search.isEmpty() && it.kind == kind }
        try {
            if (shown != null && shown.state.error == null && !written(shown.rev)) return@withLock
            // As many entries as are shown, so a page back stays on screen.
            val limit = (shown?.state?.entries?.size ?: 0).coerceIn(PAGE_SIZE, MAX_PAGE)
            val page = read(0, 0, limit, kind)
            latest.value = Shown(
                LogbookUiState(entries = page.entries, more = page.more, loaded = true),
                page.rev,
                kind,
            )
        } catch (e: IOException) {
            failed(e, Filter("", kind))
        }
    }

    /** Every entry of [kind] matching [search]. */
    private suspend fun poll(search: String, kind: String?) = reading.withLock {
        val shown = latest.value.takeIf { it.state.loaded && it.state.search == search && it.kind == kind }
        try {
            var book = everything
            if (book == null || written(book.rev)) {
                book = readEverything()
                everything = book
            } else if (shown != null && shown.state.error == null) {
                return@withLock
            }
            val terms = search.lowercase(Locale.ROOT).split(WHITESPACE)
            val matches = withContext(compute) {
                book.entries.filterIndexed { i, entry ->
                    (kind == null || isOf(entry.kind, kind)) && terms.all { it in book.texts[i] }
                }
            }
            latest.value = Shown(LogbookUiState(entries = matches, search = search, loaded = true), book.rev, kind)
        } catch (e: IOException) {
            failed(e, Filter(search, kind))
        }
    }

    /** Whether anything was written after [rev]: a one-entry read answers with the latest write. */
    private suspend fun written(rev: Long) = read(0, rev, 1, null).rev != rev

    private suspend fun readEverything(): Everything {
        val entries = mutableListOf<LogbookEntry>()
        // The first page's: a write between pages is read again by the next poll.
        var rev = 0L
        var before = 0L
        do {
            val page = read(before, 0, MAX_PAGE, null)
            if (before == 0L) rev = page.rev
            entries += page.entries
            before = page.entries.lastOrNull()?.id ?: break
        } while (page.more)
        val texts = withContext(compute) { entries.map(::searchText) }
        return Everything(entries, texts, rev)
    }

    /** What a search looks in, one line per part so a word cannot span two. */
    private fun searchText(entry: LogbookEntry): String = buildString {
        append(entry.message).append('\n').append(entry.subject).append('\n').append(entry.kind)
        kindLabel(entry.kind)?.let { append('\n').append(it) }
        for ((key, value) in entry.fields) {
            append('\n').append(key).append(' ').append((value as? JsonPrimitive)?.content ?: value.toString())
        }
    }.lowercase(Locale.ROOT)

    private fun failed(e: IOException, filter: Filter) {
        latest.update {
            // What another filter found does not answer this one.
            val answers = it.state.search == filter.search && it.kind == filter.kind
            val shown = if (answers) it else Shown(LogbookUiState(search = filter.search), kind = filter.kind)
            shown.copy(state = shown.state.copy(loaded = true, error = e.message.orEmpty()))
        }
    }
}

/** The engine's kind filter: a kind, or its area (`config` matches `config.reload`). */
private fun isOf(entryKind: String, kind: String) =
    entryKind == kind || (entryKind.startsWith(kind) && entryKind.getOrNull(kind.length) == '.')
