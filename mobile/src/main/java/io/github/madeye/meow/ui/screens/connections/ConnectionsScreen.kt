package io.github.madeye.meow.ui.screens.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.R
import io.github.madeye.meow.api.Connection
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ConnectionsTab { Active, Recent }

data class ConnectionsUiState(
    val connections: List<Connection> = emptyList(),
    val recent: List<RecentConnection> = emptyList(),
    val query: String = "",
    val tab: ConnectionsTab = ConnectionsTab.Active,
) {
    val visibleConnections: List<Connection>
        get() = connections.filter { it.matches(query) }

    val visibleRecent: List<RecentConnection>
        get() = recent.filter { it.connection.matches(query) }
}

private fun Connection.matches(query: String): Boolean {
    if (query.isBlank()) return true
    return metadata.host.contains(query, ignoreCase = true) ||
        metadata.destinationIP.contains(query, ignoreCase = true)
}

class ConnectionsViewModel(
    private val api: MeowApi,
    private val recentConnections: RecentConnectionsStore,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val tab = MutableStateFlow(ConnectionsTab.Active)

    /**
     * Polls the engine only while collected, so it starts and stops with
     * [uiState]'s subscription instead of running for the ViewModel's lifetime.
     * Diffing lives in [recentConnections], which outlives this ViewModel.
     */
    private val polling = flow<Unit> {
        while (true) {
            val ticket = recentConnections.snapshotTicket()
            try {
                recentConnections.onSnapshot(api.connections().connections, ticket)
            } catch (e: MeowApiException) {
                if (e is MeowApiException.Unreachable) {
                    // The controller is gone (VPN stopped). The flows from the
                    // last snapshot are done; file them under Recent. A later
                    // successful poll puts back anything still open.
                    recentConnections.onSnapshot(emptyList(), ticket)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }.onStart { emit(Unit) }

    val uiState: StateFlow<ConnectionsUiState> =
        combine(recentConnections.lists, query, tab, polling) { lists, search, selected, _ ->
            ConnectionsUiState(
                connections = lists.active,
                recent = lists.recent,
                query = search,
                tab = selected,
            )
        }.stateIn(
            viewModelScope,
            // Polling stops when the screen leaves the composition, rather
            // than running forever as the Flutter version's timer did.
            SharingStarted.WhileSubscribed(5_000),
            initialValue(),
        )

    fun onQueryChange(value: String) { query.value = value }

    fun onTabChange(value: ConnectionsTab) { tab.value = value }

    fun close(id: String) {
        viewModelScope.launch {
            recentConnections.closeLocal(id)
            runCatching { api.closeConnection(id) }
        }
    }

    fun closeAll() {
        viewModelScope.launch {
            recentConnections.closeAllLocal()
            runCatching { api.closeAllConnections() }
        }
    }

    fun clearRecent() { recentConnections.clearRecent() }

    /** So the first frame already shows history kept by the process-scoped store. */
    private fun initialValue(): ConnectionsUiState {
        val lists = recentConnections.lists.value
        return ConnectionsUiState(connections = lists.active, recent = lists.recent)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
    }
}

@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    contentPadding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onTabChange: (ConnectionsTab) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.connections_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            ConnectionsTab.entries.forEachIndexed { index, tab ->
                val count = when (tab) {
                    ConnectionsTab.Active -> state.connections.size
                    ConnectionsTab.Recent -> state.recent.size
                }
                SegmentedButton(
                    selected = state.tab == tab,
                    onClick = { if (state.tab != tab) onTabChange(tab) },
                    shape = SegmentedButtonDefaults.itemShape(index, ConnectionsTab.entries.size),
                    modifier = Modifier.testTag(
                        when (tab) {
                            ConnectionsTab.Active -> "connections_tab_active"
                            ConnectionsTab.Recent -> "connections_tab_recent"
                        },
                    ),
                ) {
                    Text(tabLabel(tab, count), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val showingRecent = state.tab == ConnectionsTab.Recent
        // The bottom inset goes inside the list, as on the other screens, so rows
        // scroll under the transparent navigation bar instead of stopping above it.
        val listPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        )
        val empty = if (showingRecent) state.visibleRecent.isEmpty() else state.visibleConnections.isEmpty()
        if (empty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(
                        if (showingRecent) R.string.connections_recent_empty else R.string.connections_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.meow.mutedText,
                )
            }
        } else if (showingRecent) {
            LazyColumn(
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.visibleRecent, key = { it.connection.id }) { recent ->
                    ConnectionCard(
                        connection = recent.connection,
                        elapsed = Formatters.elapsedSince(
                            recent.connection.start,
                            Instant.ofEpochMilli(recent.lastSeenAtMillis),
                        ),
                        footer = stringResource(
                            R.string.connections_last_seen,
                            Formatters.timestamp(recent.lastSeenAtMillis),
                        ),
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.visibleConnections, key = { it.id }) { connection ->
                    ConnectionCard(
                        connection = connection,
                        elapsed = Formatters.elapsedSince(connection.start),
                        onClose = { onClose(connection.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun tabLabel(tab: ConnectionsTab, count: Int): String {
    val name = stringResource(
        when (tab) {
            ConnectionsTab.Active -> R.string.connections_tab_active
            ConnectionsTab.Recent -> R.string.connections_tab_recent
        },
    )
    return if (count == 0) name else "$name ($count)"
}

@Composable
private fun ConnectionCard(
    connection: Connection,
    elapsed: String,
    footer: String? = null,
    onClose: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.meow
    val host = connection.metadata.host.ifEmpty { connection.metadata.destinationIP }

    GlassCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$host:${connection.metadata.destinationPort}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (connection.chains.isNotEmpty()) {
                    Text(
                        text = connection.chains.reversed().joinToString(" → "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = buildString {
                        append("↑ ${Formatters.bytes(connection.upload)}")
                        append("  ↓ ${Formatters.bytes(connection.download)}")
                        if (elapsed.isNotEmpty()) append("  $elapsed")
                    },
                    style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                    color = colors.mutedText,
                )
                if (!footer.isNullOrEmpty()) {
                    Text(
                        text = footer,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = colors.danger)
                }
            }
        }
    }
}

@Composable
fun ConnectionsActions(
    tab: ConnectionsTab,
    hasConnections: Boolean,
    hasRecent: Boolean,
    onCloseAll: () -> Unit,
    onClearRecent: () -> Unit,
) {
    var confirmCloseAll by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    when (tab) {
        ConnectionsTab.Active -> if (hasConnections) {
            IconButton(onClick = { confirmCloseAll = true }) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = stringResource(R.string.connections_close_all),
                )
            }
        }
        ConnectionsTab.Recent -> if (hasRecent) {
            IconButton(onClick = { confirmClear = true }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.connections_recent_clear),
                )
            }
        }
    }
    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text(stringResource(R.string.connections_close_all_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmCloseAll = false; onCloseAll() }) {
                    Text(stringResource(R.string.connections_close_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCloseAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.connections_recent_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearRecent() }) {
                    Text(stringResource(R.string.connections_recent_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
