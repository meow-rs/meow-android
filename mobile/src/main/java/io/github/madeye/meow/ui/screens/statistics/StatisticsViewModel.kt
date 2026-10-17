package io.github.madeye.meow.ui.screens.statistics

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.aidl.ByteTotals
import io.github.madeye.meow.aidl.SessionStats
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** What one proxy carried this session, under the name the engine knows it by. */
@Immutable
data class PolicyTraffic(val name: String, val totals: ByteTotals)

@Immutable
data class StatisticsUiState(
    /** Null while the VPN is down: there is no session to show. */
    val session: SessionStats? = null,
    /** When [session] was read, for its uptime. */
    val now: Long = 0L,
    /**
     * The proxies that carried traffic this session, by name. Null for an
     * engine that does not count per proxy, or until it first answers.
     */
    val policies: List<PolicyTraffic>? = null,
    /** False until the first poll, so the offline hint does not flash on open. */
    val loaded: Boolean = false,
)

/**
 * Surge's Statistics screen: the running session's start and uptime, its
 * traffic by the kind of network it left on, and by the proxy that carried
 * it. Polled every second while shown.
 */
class StatisticsViewModel(
    private val session: suspend () -> SessionStats?,
    private val proxyTraffic: suspend () -> Map<String, ByteTotals>?,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    companion object {
        /** The uptime ticks in seconds. */
        const val POLL_INTERVAL_MS = 1_000L
    }

    val uiState: StateFlow<StatisticsUiState> = flow {
        // A failed request keeps the last answer on screen rather than
        // dropping the section for a second.
        var policies: List<PolicyTraffic>? = null
        while (true) {
            val current = session()
            if (current == null) {
                policies = null
                emit(StatisticsUiState(loaded = true))
            } else {
                policies = try {
                    proxyTraffic()?.let(::byName)
                } catch (_: IOException) {
                    policies
                }
                emit(StatisticsUiState(session = current, now = clock(), policies = policies, loaded = true))
            }
            delay(POLL_INTERVAL_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState())

    /** By name, capitals first as Surge lists them ("DIRECT" before "daniel"); a proxy that carried nothing is left out. */
    private fun byName(traffic: Map<String, ByteTotals>): List<PolicyTraffic> =
        traffic.entries
            .filter { it.value.total > 0 }
            .sortedBy { it.key }
            .map { PolicyTraffic(it.key, it.value) }
}
