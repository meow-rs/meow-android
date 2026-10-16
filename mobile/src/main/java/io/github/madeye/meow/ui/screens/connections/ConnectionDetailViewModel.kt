package io.github.madeye.meow.ui.screens.connections

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.api.Connection
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.Rule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The engine's names for a route no listed rule chose. */
enum class UnlistedRoute(val wire: String) {
    /** No rule matched, so the flow went DIRECT. */
    Final("Final"),

    /** Global route mode: everything through the GLOBAL group. */
    Global("Global"),

    /** Direct route mode. */
    Direct("Direct"),
    ;

    companion object {
        fun of(connection: Connection): UnlistedRoute? = entries.firstOrNull { it.wire == connection.rule }
    }
}

@Immutable
data class ConnectionDetailUiState(
    /** Null once the connection is in neither list, e.g. after Recent was cleared. */
    val connection: Connection? = null,
    /** When a poll last listed it, once it has closed; null while it is open. */
    val lastSeenAtMillis: Long? = null,
    /** The matched rule's place in the engine's rule list (0-based), once found. */
    val ruleIndex: Int? = null,
) {
    val active: Boolean get() = connection != null && lastSeenAtMillis == null
}

/**
 * One connection's detail page: where it went, and which rule sent it there.
 *
 * Reads the same process-scoped [store] as the list, polling it while shown,
 * so a connection that closes under the page turns into its Recent row.
 */
class ConnectionDetailViewModel(
    private val id: String,
    private val store: RecentConnectionsStore,
    polling: Flow<Unit>,
    private val rules: suspend () -> List<Rule>,
    private val closeConnection: suspend (String) -> Unit,
) : ViewModel() {

    private val ruleIndex = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<ConnectionDetailUiState> =
        combine(store.lists, ruleIndex, polling) { lists, index, _ -> lists.detail(index) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.lists.value.detail(null))

    init {
        viewModelScope.launch {
            // Once: the rule was picked when the flow opened, and later rule
            // lists can only be staler, not more right.
            val connection = store.lists.mapNotNull { it.detail(null).connection }.first()
            if (UnlistedRoute.of(connection) != null || connection.rule.isEmpty()) return@launch
            ruleIndex.value = try {
                rules().indexOfMatch(connection)
            } catch (e: MeowApiException) {
                null
            }
        }
    }

    fun close() {
        viewModelScope.launch {
            store.closeLocal(id)
            runCatching { closeConnection(id) }
        }
    }

    private fun ConnectionLists.detail(index: Int?): ConnectionDetailUiState {
        active.firstOrNull { it.id == id }?.let { return ConnectionDetailUiState(it, null, index) }
        recent.firstOrNull { it.connection.id == id }?.let {
            return ConnectionDetailUiState(it.connection, it.lastSeenAtMillis, index)
        }
        return ConnectionDetailUiState(ruleIndex = index)
    }
}

/**
 * Where [connection]'s rule sits in the engine's rule list: the first rule of
 * its type and payload that targets its policy, as matching runs top-down.
 * Null when there is none, e.g. after a config reload.
 */
internal fun List<Rule>.indexOfMatch(connection: Connection): Int? {
    val policy = connection.chains.lastOrNull() ?: return null
    return indexOfFirst {
        it.type == connection.rule && it.payload == connection.rulePayload && it.proxy == policy
    }.takeIf { it >= 0 }
}
