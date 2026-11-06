package io.github.madeye.meow.ui.screens.traffic

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.repo.TrafficHistoryRepository
import io.github.madeye.meow.repo.TrafficTotals
import io.github.madeye.meow.vpn.VpnStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Traffic page's data usage cards. */
@Immutable
data class TrafficUiState(
    val today: TrafficTotals = TrafficTotals.Zero,
    val thisMonth: TrafficTotals = TrafficTotals.Zero,
)

class TrafficViewModel(
    private val vpn: VpnStateRepository,
    private val history: TrafficHistoryRepository,
) : ViewModel() {

    private val today = MutableStateFlow(TrafficTotals.Zero)
    private val thisMonth = MutableStateFlow(TrafficTotals.Zero)

    val uiState: StateFlow<TrafficUiState> = combine(today, thisMonth) { day, month ->
        TrafficUiState(today = day, thisMonth = month)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrafficUiState())

    init {
        // The aggregates are cheap but not free, so they refresh on each new
        // traffic sample rather than being recomputed per frame.
        viewModelScope.launch {
            vpn.traffic.collect { refreshTotals() }
        }
        viewModelScope.launch {
            history.prune()
            refreshTotals()
        }
    }

    private suspend fun refreshTotals() {
        today.value = history.today()
        thisMonth.value = history.thisMonth()
    }
}
