package io.github.madeye.meow.ui.screens.proxies

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.ui.components.DelayBadge
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.theme.meow

/** The Proxy Groups tab, after meow-ios's: one expandable card per group. */
@Composable
fun ProxyGroupsScreen(
    state: ProxyGroupsUiState,
    contentPadding: PaddingValues,
    onToggleExpanded: (String) -> Unit,
    onSelectNode: (String, String) -> Unit,
    onTestGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("proxy_groups_root"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.groups.isEmpty()) {
            item { Placeholder(state) }
        } else {
            items(state.groups, key = { it.name }) { group ->
                ProxyGroupCard(
                    group = group,
                    expanded = state.expandedGroup == group.name,
                    onToggleExpanded = { onToggleExpanded(group.name) },
                    onSelectNode = { node -> onSelectNode(group.name, node) },
                    onTest = { onTestGroup(group.name) },
                )
            }
        }
    }
}

/** Why there are no groups to show; the VPN switch itself lives on Home. */
@Composable
private fun Placeholder(state: ProxyGroupsUiState) {
    val message = when {
        !state.hasProfile -> R.string.proxy_no_subscription_hint
        state.state == BaseService.State.Connected -> R.string.proxy_no_groups
        state.state == BaseService.State.Connecting -> R.string.proxy_groups_connecting
        else -> R.string.proxy_groups_disconnected
    }
    GlassCard {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.meow.mutedText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun ProxyGroupCard(
    group: ProxyGroupUi,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectNode: (String) -> Unit,
    onTest: () -> Unit,
) {
    val colors = MaterialTheme.meow
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )

    GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = group.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${group.type} · ${group.now}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                )
            }
            val testProgress = group.testProgress
            if (testProgress != null) {
                // Fills as members report in; eased so a burst of results
                // doesn't make it jump.
                val shown by animateFloatAsState(targetValue = testProgress, label = "testProgress")
                val testingLabel = stringResource(R.string.proxy_testing)
                // The button's footprint, so the chevron doesn't shift.
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { shown },
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = testingLabel },
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                IconButton(onClick = onTest) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = stringResource(R.string.proxy_url_test_all),
                        tint = colors.accent,
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = colors.mutedText,
                modifier = Modifier.rotate(chevronRotation),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(color = colors.border)
                group.nodes.forEach { node ->
                    ProxyNodeRow(node = node, onClick = { onSelectNode(node.name) })
                }
            }
        }
    }
}

@Composable
private fun ProxyNodeRow(node: ProxyNodeUi, onClick: () -> Unit) {
    val colors = MaterialTheme.meow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (node.selected) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Filled.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = if (node.selected) colors.accent else colors.mutedText.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = node.name, style = MaterialTheme.typography.bodyMedium)
            if (node.type.isNotEmpty()) {
                Text(
                    text = node.type,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                )
            }
        }
        DelayBadge(
            delayMs = (node.delay as? NodeDelay.Measured)?.ms,
            loading = node.delay == NodeDelay.Testing,
            timedOut = node.delay == NodeDelay.TimedOut,
        )
    }
}
