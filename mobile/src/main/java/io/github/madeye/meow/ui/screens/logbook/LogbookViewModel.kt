package io.github.madeye.meow.ui.screens.logbook

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.logbook.LogbookEntry
import io.github.madeye.meow.logbook.LogbookLevel
import io.github.madeye.meow.logbook.LogbookPage
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Immutable
data class LogbookUiState(
    /** Newest first. */
    val entries: List<LogbookEntry> = emptyList(),
    /** Older entries exist past [entries]. */
    val more: Boolean = false,
    /** The lowest level shown; null for all. */
    val level: LogbookLevel? = null,
    /** Why the logbook could not be read; shown only while there is nothing else to show. */
    val error: String? = null,
    /** False until the first read, so the empty hint does not flash on open. */
    val loaded: Boolean = false,
    /** The logbook's latest write when [entries] were read. */
    val rev: Long = 0,
)

/**
 * The engine's logbook: what happened to it, kept across restarts — runs
 * and how they ended, config and provider failures, group switches. Read
 * from the database the engine keeps in its home, so it also opens while
 * the VPN is down; polled while shown, re-reading the page only once
 * something was written.
 */
class LogbookViewModel(
    private val read: suspend (before: Long, since: Long, limit: Int, level: LogbookLevel?) -> LogbookPage,
) : ViewModel() {

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val PAGE_SIZE = 100

        /** The engine's largest page. */
        const val MAX_PAGE = 1_000
    }

    private val level = MutableStateFlow<LogbookLevel?>(null)
    private val latest = MutableStateFlow(LogbookUiState())

    /** One read at a time: a poll and a page back both rewrite [latest]. */
    private val reading = Mutex()

    /** Runs only while [uiState] is collected; a filter change restarts it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val polling: Flow<Unit> = level
        .transformLatest<LogbookLevel?, Unit> { level ->
            while (true) {
                poll(level)
                delay(POLL_INTERVAL_MS)
            }
        }
        .onStart { emit(Unit) }

    val uiState: StateFlow<LogbookUiState> =
        combine(latest, polling) { state, _ -> state }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogbookUiState())

    fun onLevelChange(value: LogbookLevel?) {
        if (level.value == value) return
        level.value = value
        latest.value = LogbookUiState(level = value)
    }

    /** Reads the page past the oldest entry shown. */
    fun loadMore() {
        viewModelScope.launch {
            reading.withLock {
                val shown = latest.value
                val oldest = shown.entries.lastOrNull()?.id
                if (!shown.more || oldest == null) return@withLock
                try {
                    val page = read(oldest, 0, PAGE_SIZE, shown.level)
                    latest.value = shown.copy(entries = shown.entries + page.entries, more = page.more)
                } catch (_: IOException) {
                    // Left as it is: scrolling back to the end tries again.
                }
            }
        }
    }

    private suspend fun poll(level: LogbookLevel?) = reading.withLock {
        val shown = latest.value.takeIf { it.loaded && it.level == level }
        try {
            // A one-entry read answers with the latest write: an unchanged
            // one means there is nothing new to read.
            if (shown != null && shown.error == null && read(0, shown.rev, 1, null).rev == shown.rev) {
                return@withLock
            }
            // As many entries as are shown, so a page back stays on screen.
            val limit = (shown?.entries?.size ?: 0).coerceIn(PAGE_SIZE, MAX_PAGE)
            val page = read(0, 0, limit, level)
            latest.value = LogbookUiState(
                entries = page.entries,
                more = page.more,
                level = level,
                loaded = true,
                rev = page.rev,
            )
        } catch (e: IOException) {
            latest.update { it.copy(loaded = true, error = e.message.orEmpty()) }
        }
    }
}
