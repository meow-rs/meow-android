package io.github.madeye.meow.ui.screens.dns

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.R
import io.github.madeye.meow.api.DnsResult
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
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

/** Why the latest poll failed. The previous results stay on screen meanwhile, as on iOS. */
enum class DnsError { Offline, Failed }

data class DnsUiState(
    val results: List<DnsResult> = emptyList(),
    val query: String = "",
    /** False until the first answer, so the empty state does not flash on open. */
    val loaded: Boolean = false,
    val error: DnsError? = null,
)

/**
 * The engine's DNS cache, polled like meow-ios's DNS screen. Search is done by
 * the engine (it also matches answer IPs and upstreams), so a new query means a
 * new request rather than a local filter.
 */
class DnsViewModel(
    private val fetch: suspend (search: String?) -> List<DnsResult>,
) : ViewModel() {

    companion object {
        /** Same cadence as iOS: the cache churns, so the list keeps refreshing while shown. */
        const val POLL_INTERVAL_MS = 2_000L

        /** Typing restarts the poll; waiting for a pause costs one request, not one per key. */
        const val DEBOUNCE_MS = 300L
    }

    private val query = MutableStateFlow("")
    private val latest = MutableStateFlow(DnsUiState())

    /**
     * Runs only while [uiState] is collected. A query change cancels the
     * running poll — including a request in flight — so an answer for the old
     * query can never land after the new one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val polling: Flow<Unit> = query
        .transformLatest<String, Unit> { search ->
            if (search.isNotBlank()) delay(DEBOUNCE_MS)
            while (true) {
                poll(search.trim().ifEmpty { null })
                delay(POLL_INTERVAL_MS)
            }
        }
        .onStart { emit(Unit) }

    val uiState: StateFlow<DnsUiState> =
        combine(latest, query, polling) { state, search, _ -> state.copy(query = search) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DnsUiState())

    fun onQueryChange(value: String) { query.value = value }

    private suspend fun poll(search: String?) {
        try {
            latest.value = DnsUiState(results = fetch(search), loaded = true)
        } catch (e: IOException) {
            // MeowApiException is an IOException, and so is a body cut off mid-read.
            val error = if (e is MeowApiException.Unreachable) DnsError.Offline else DnsError.Failed
            latest.update { it.copy(loaded = true, error = error) }
        }
    }
}

@Composable
fun DnsScreen(
    state: DnsUiState,
    contentPadding: PaddingValues,
    onQueryChange: (String) -> Unit,
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
            placeholder = { Text(stringResource(R.string.dns_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("dns_search"),
        )
        state.error?.let { error ->
            Text(
                text = stringResource(
                    when (error) {
                        DnsError.Offline -> R.string.settings_engine_offline
                        DnsError.Failed -> R.string.dns_load_error
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.meow.danger,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("dns_error"),
            )
        }
        Spacer(Modifier.height(8.dp))

        when {
            !state.loaded -> Unit

            state.results.isEmpty() -> DnsEmptyState(state.query)

            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // No item keys: the engine does not promise unique names, and a
                // duplicate key would crash the list.
                items(state.results) { result -> DnsRow(result) }
            }
        }
    }
}

@Composable
private fun DnsEmptyState(query: String) {
    val muted = MaterialTheme.meow.mutedText
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .testTag("dns_empty"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (query.isBlank()) {
                Text(
                    text = stringResource(R.string.dns_empty_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dns_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = stringResource(R.string.dns_search_empty, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DnsRow(result: DnsResult) {
    val muted = MaterialTheme.meow.mutedText
    GlassCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Text(
            text = result.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = result.ips.joinToString(", "),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Storage,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = result.fromServer ?: stringResource(R.string.dns_unknown_upstream),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.dns_ttl, result.ttl),
                style = MaterialTheme.typography.labelSmall.merge(MeowTextStyles.monoDigits),
                color = muted,
            )
        }
    }
}
