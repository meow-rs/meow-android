package io.github.madeye.meow.ui.screens.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.ui.components.DelayBadge
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.theme.meow

/** Proxy-provider nodes, with provider-wide and per-node health checks. */
@Composable
fun ProxyServersScreen(
    state: ProxyServersUiState,
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
    onHealthCheck: (String) -> Unit,
    onTestNode: (String, String) -> Unit,
    onEditProfile: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading && state.providers.isEmpty() -> Box(
            modifier = modifier.fillMaxSize().testTag("proxy_servers_root"),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        state.failed && state.providers.isEmpty() -> Box(
            modifier = modifier.fillMaxSize().padding(contentPadding).testTag("proxy_servers_root"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.proxy_servers_load_error),
                    color = MaterialTheme.meow.danger,
                    textAlign = TextAlign.Center,
                )
                androidx.compose.material3.TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.common_retry))
                }
            }
        }

        state.providers.isEmpty() -> Box(
            modifier = modifier.fillMaxSize().padding(contentPadding).testTag("proxy_servers_root"),
            contentAlignment = Alignment.Center,
        ) {
            GlassCard {
                Text(
                    text = stringResource(R.string.proxy_servers_empty),
                    color = MaterialTheme.meow.mutedText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        else -> LazyColumn(
            modifier = modifier.fillMaxWidth().testTag("proxy_servers_root"),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    editableProfileId = state.editableProfileId,
                    onHealthCheck = { onHealthCheck(provider.id) },
                    onTestNode = { node -> onTestNode(provider.id, node) },
                    onEditProfile = onEditProfile,
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProxyServerProviderUi,
    editableProfileId: Long?,
    onHealthCheck: () -> Unit,
    onTestNode: (String) -> Unit,
    onEditProfile: (Long, String) -> Unit,
) {
    val colors = MaterialTheme.meow
    GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (provider.kind == ProxyServerKind.Inline) {
                        stringResource(R.string.proxy_servers_inline_title)
                    } else {
                        provider.name
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (provider.vehicleType.isNotEmpty()) {
                    Text(
                        text = provider.vehicleType,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                    )
                }
            }
            if (provider.kind == ProxyServerKind.Inline) {
                Spacer(Modifier.size(20.dp))
            } else if (provider.checking) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(
                    onClick = onHealthCheck,
                    modifier = Modifier.testTag("proxy_servers_health_${provider.name}"),
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = stringResource(R.string.proxy_servers_health_check),
                        tint = colors.accent,
                    )
                }
            }
        }
        if (provider.nodes.isEmpty()) {
            Text(
                text = stringResource(R.string.proxy_servers_no_nodes),
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedText,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            HorizontalDivider(color = colors.border)
            provider.nodes.forEach { node ->
                ProviderNodeRow(
                    node = node,
                    editable = provider.kind == ProxyServerKind.Inline && editableProfileId != null,
                    onEdit = { editableProfileId?.let { onEditProfile(it, node.name) } },
                    onTest = { onTestNode(node.name) },
                )
            }
        }
    }
}

@Composable
private fun ProviderNodeRow(
    node: ProxyServerNodeUi,
    editable: Boolean,
    onEdit: () -> Unit,
    onTest: () -> Unit,
) {
    val colors = MaterialTheme.meow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = editable, onClick = onEdit)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (node.type.isNotEmpty()) {
                Text(text = node.type, style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
            }
        }
        DelayBadge(delayMs = node.delayMs, timedOut = node.timedOut, loading = node.testing)
        Spacer(Modifier.size(4.dp))
        if (editable) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.testTag("proxy_servers_edit_${node.name}"),
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.common_edit),
                    tint = colors.accent,
                )
            }
        }
        IconButton(
            onClick = onTest,
            enabled = !node.testing,
            modifier = Modifier.testTag("proxy_servers_test_${node.name}"),
        ) {
            Icon(
                Icons.Filled.Bolt,
                contentDescription = stringResource(R.string.proxy_servers_test),
                tint = colors.accent,
            )
        }
    }
}
