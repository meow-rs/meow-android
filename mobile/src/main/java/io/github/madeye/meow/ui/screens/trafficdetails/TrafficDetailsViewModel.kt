package io.github.madeye.meow.ui.screens.trafficdetails

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.traffic.TrafficGroup
import io.github.madeye.meow.traffic.TrafficReport
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The spans of days the page shows, as calendar days on the device. */
enum class TrafficPeriod {
    Today,
    ThisMonth,
    LastMonth,
    ;

    /** The first and last day, both included, as `LocalDate.toEpochDay()`. */
    fun range(today: LocalDate): Pair<Long, Long> = when (this) {
        Today -> today.toEpochDay() to today.toEpochDay()
        ThisMonth -> today.withDayOfMonth(1).toEpochDay() to today.toEpochDay()
        LastMonth -> {
            val first = today.withDayOfMonth(1).minusMonths(1)
            first.toEpochDay() to first.plusMonths(1).minusDays(1).toEpochDay()
        }
    }
}

/** The rows a page is narrowed to: those of one host, app, policy or network, or several. */
@Immutable
data class TrafficFilter(
    val host: String? = null,
    val app: String? = null,
    val policy: String? = null,
    val network: String? = null,
) {
    /** The groupings the filter does not pin, which the page can still group by. */
    val groups: List<TrafficGroup>
        get() = TrafficGroup.entries.filter { group -> valueOf(group) == null }

    fun valueOf(group: TrafficGroup): String? = when (group) {
        TrafficGroup.Host -> host
        TrafficGroup.App -> app
        TrafficGroup.Policy -> policy
        TrafficGroup.Interface -> network
    }

    /** Also narrowed to the row [name] of [group]. */
    fun with(group: TrafficGroup, name: String): TrafficFilter = when (group) {
        TrafficGroup.Host -> copy(host = name)
        TrafficGroup.App -> copy(app = name)
        TrafficGroup.Policy -> copy(policy = name)
        TrafficGroup.Interface -> copy(network = name)
    }
}

@Immutable
data class TrafficDetailsUiState(
    val period: TrafficPeriod = TrafficPeriod.Today,
    val by: TrafficGroup = TrafficGroup.Host,
    /** What the page can group by; one when the filter pins the others. */
    val groups: List<TrafficGroup> = TrafficGroup.entries,
    /** Null until the first read. */
    val report: TrafficReport? = null,
    /** Why the ledger could not be read; shown only while there is no report. */
    val error: String? = null,
    /** The names of the apps on the page, by package, as they resolve. */
    val appLabels: Map<String, String> = emptyMap(),
)

/**
 * The engine's traffic ledger for one span of days, grouped by host, app,
 * policy or network, optionally narrowed by [filter] — a row picked on the
 * page before. Apps are named from [appLabels], which grows as the
 * packages [requestAppLabels] is given resolve. Read from the database the engine keeps in its home, so it
 * also opens while the VPN is down; re-read every [POLL_INTERVAL_MS] while
 * shown, as the engine writes about once a minute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficDetailsViewModel(
    val filter: TrafficFilter,
    period: TrafficPeriod,
    by: TrafficGroup,
    private val read: suspend (from: Long, to: Long, by: TrafficGroup, filter: TrafficFilter) -> TrafficReport,
    private val clearLedger: suspend () -> Boolean,
    private val appLabels: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
    private val requestAppLabels: (Iterable<String>) -> Unit = {},
    private val today: () -> LocalDate = LocalDate::now,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : ViewModel() {

    companion object {
        const val POLL_INTERVAL_MS = 15_000L
    }

    private val selectedPeriod = MutableStateFlow(period)
    private val selectedGroup = MutableStateFlow(by.takeIf { it in filter.groups } ?: filter.groups.first())
    private val reloads = MutableStateFlow(0)

    private data class Read(val report: TrafficReport?, val error: String?)

    private val reads = combine(selectedPeriod, selectedGroup, reloads) { period, by, _ -> period to by }
        .transformLatest { (period, by) ->
            // A failed poll keeps the last report of this period and grouping.
            var last: TrafficReport? = null
            while (true) {
                val (from, to) = period.range(today())
                val result = try {
                    read(from, to, by, filter).also { report ->
                        last = report
                        if (by == TrafficGroup.App) requestAppLabels(report.rows.map { it.name })
                    }.let { Read(it, null) }
                } catch (e: IOException) {
                    Read(last, e.message ?: e.toString())
                }
                emit(result)
                delay(pollIntervalMs)
            }
        }

    init {
        // The page is named after the app it was opened from.
        filter.app?.let { requestAppLabels(listOf(it)) }
    }

    val uiState: StateFlow<TrafficDetailsUiState> = combine(
        selectedPeriod,
        selectedGroup,
        reads,
        appLabels,
    ) { period, by, read, labels ->
        TrafficDetailsUiState(
            period = period,
            by = by,
            groups = filter.groups,
            report = read.report,
            error = read.error,
            appLabels = labels,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TrafficDetailsUiState(period = selectedPeriod.value, by = selectedGroup.value, groups = filter.groups),
    )

    fun onPeriodChange(period: TrafficPeriod) {
        selectedPeriod.value = period
    }

    fun onGroupChange(group: TrafficGroup) {
        if (group in filter.groups) selectedGroup.value = group
    }

    /** Deletes the whole ledger, every period and filter, then reads again. */
    fun clear() {
        viewModelScope.launch {
            clearLedger()
            reloads.update { it + 1 }
        }
    }
}
