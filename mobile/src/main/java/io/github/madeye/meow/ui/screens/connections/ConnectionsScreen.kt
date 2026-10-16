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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the list: an open connection, or one that closed after [lastSeenAtMillis]. */
data class ConnectionRow(
    val connection: Connection,
    /** When a poll last listed it, once it has closed; null while it is open. */
    val lastSeenAtMillis: Long? = null,
)

data class ConnectionsUiState(
    val rows: List<ConnectionRow> = emptyList(),
    val query: String = "",
) {
    val visibleRows: List<ConnectionRow>
        get() = rows.filter { it.connection.matches(query) }

    val hasActive: Boolean get() = rows.any { it.lastSeenAtMillis == null }

    val hasClosed: Boolean get() = rows.any { it.lastSeenAtMillis != null }
}

private fun Connection.matches(query: String): Boolean {
    if (query.isBlank()) return true
    return metadata.host.contains(query, ignoreCase = true) ||
        metadata.destinationIP.contains(query, ignoreCase = true)
}

/**
 * Open and closed connections in one list, newest start first, so a
 * connection that closes keeps its place and only its status changes.
 */
internal fun ConnectionLists.rows(): List<ConnectionRow> =
    (active.map { ConnectionRow(it) } + recent.map { ConnectionRow(it.connection, it.lastSeenAtMillis) })
        .sortedByDescending { runCatching { Instant.parse(it.connection.start) }.getOrDefault(Instant.MIN) }

class ConnectionsViewModel(
    private val api: MeowApi,
    private val recentConnections: RecentConnectionsStore,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /** Runs only while [uiState] is collected, not for the ViewModel's lifetime. */
    private val polling = connectionsPolling(api, recentConnections)

    val uiState: StateFlow<ConnectionsUiState> =
        combine(recentConnections.lists, query, polling) { lists, search, _ ->
            ConnectionsUiState(rows = lists.rows(), query = search)
        }.stateIn(
            viewModelScope,
            // Polling stops when the screen leaves the composition, rather
            // than running forever as the Flutter version's timer did.
            SharingStarted.WhileSubscribed(5_000),
            // So the first frame already shows history kept by the process-scoped store.
            ConnectionsUiState(rows = recentConnections.lists.value.rows()),
        )

    fun onQueryChange(value: String) { query.value = value }

    fun closeAll() {
        viewModelScope.launch {
            recentConnections.closeAllLocal()
            runCatching { api.closeAllConnections() }
        }
    }

    fun clearClosed() { recentConnections.clearRecent() }
}

private const val POLL_INTERVAL_MS = 1_000L

/**
 * Feeds [store] a `/connections` snapshot every second, only while collected,
 * so polling starts and stops with the screen collecting it: the list, or one
 * connection's detail page. Diffing lives in [store], which outlives both.
 */
internal fun connectionsPolling(api: MeowApi, store: RecentConnectionsStore): Flow<Unit> = flow<Unit> {
    while (true) {
        val ticket = store.snapshotTicket()
        try {
            store.onSnapshot(api.connections().connections, ticket)
        } catch (e: MeowApiException) {
            if (e is MeowApiException.Unreachable) {
                // The controller is gone (VPN stopped). The flows from the
                // last snapshot are done; file them as closed. A later
                // successful poll puts back anything still open.
                store.onSnapshot(emptyList(), ticket)
            }
        }
        delay(POLL_INTERVAL_MS)
    }
}.onStart { emit(Unit) }

@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    contentPadding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onOpen: (String) -> Unit,
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

        val rows = state.visibleRows
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.connections_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.meow.mutedText,
                )
            }
        } else {
            LazyColumn(
                // The bottom inset goes inside the list, as on the other screens, so rows
                // scroll under the transparent navigation bar instead of stopping above it.
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.connection.id }) { row ->
                    ConnectionCard(row = row, onOpen = { onOpen(row.connection.id) })
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(row: ConnectionRow, onOpen: () -> Unit) {
    val colors = MaterialTheme.meow
    val connection = row.connection
    val lastSeen = row.lastSeenAtMillis
    val elapsed = if (lastSeen == null) {
        Formatters.elapsedSince(connection.start)
    } else {
        Formatters.elapsedSince(connection.start, Instant.ofEpochMilli(lastSeen))
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = connection.address,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (lastSeen == null) {
                Text(
                    text = stringResource(R.string.connection_detail_active),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.connected,
                    modifier = Modifier.testTag("connection_status_active"),
                )
            } else {
                Text(
                    text = stringResource(R.string.connections_closed_at, Formatters.clockTime(lastSeen)),
                    style = MaterialTheme.typography.labelMedium.merge(MeowTextStyles.monoDigits),
                    color = colors.mutedText,
                    modifier = Modifier.testTag("connection_status_closed"),
                )
            }
        }
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
    }
}

/** `host:port`, or the destination IP's when the flow has no host name. */
internal val Connection.address: String
    get() = "${metadata.host.ifEmpty { metadata.destinationIP }}:${metadata.destinationPort}"

/** The app bar's menu: close every open connection, or forget the closed ones. */
@Composable
fun ConnectionsActions(
    hasActive: Boolean,
    hasClosed: Boolean,
    onCloseAll: () -> Unit,
    onClearClosed: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    if (hasActive || hasClosed) {
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("connections_menu")) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more_options))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (hasActive) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.connections_close_all), color = MaterialTheme.meow.danger) },
                        onClick = { menuOpen = false; confirmCloseAll = true },
                        modifier = Modifier.testTag("connections_close_all"),
                    )
                }
                if (hasClosed) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.connections_recent_clear)) },
                        onClick = { menuOpen = false; confirmClear = true },
                        modifier = Modifier.testTag("connections_clear_closed"),
                    )
                }
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
                TextButton(onClick = { confirmClear = false; onClearClosed() }) {
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
