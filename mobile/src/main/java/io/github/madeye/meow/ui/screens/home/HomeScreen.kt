package io.github.madeye.meow.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnKeyOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.madeye.meow.R
import io.github.madeye.meow.api.RouteMode
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.components.NavRow
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters
import java.util.Locale

/**
 * The Home tab: the VPN switch and what it is doing, then the way into the
 * rules it routes by — the first tab of meow-ios. Subscriptions are a page of
 * their own, opened from the app bar; proxy groups have a tab.
 *
 * @param onOpenSubscriptions opens the Subscriptions page, also from the
 *   prompt shown while no profile is selected.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    contentPadding: PaddingValues,
    onToggle: (Boolean) -> Unit,
    onSelectRouteMode: (RouteMode) -> Unit,
    exitIp: ExitIpUiState,
    onRefreshExitIp: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offlineHint = stringResource(R.string.settings_engine_offline)

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            // The e2e harness waits on this tag rather than on localized text.
            .testTag("home_root"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(state = state, onToggle = onToggle) }

        // The switch stays disabled until a profile is selected, and the list
        // to pick one from is on the Subscriptions page, so point there.
        if (state.profileLoaded && !state.hasProfile) {
            item { NoProfileCard(onAdd = onOpenSubscriptions) }
        }

        if (state.isConnected) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TrafficTile(
                        label = stringResource(R.string.home_upload),
                        icon = Icons.Filled.ArrowUpward,
                        rate = state.traffic.txRate,
                        total = state.traffic.txTotal,
                        tint = MaterialTheme.meow.upload,
                        modifier = Modifier.weight(1f),
                    )
                    TrafficTile(
                        label = stringResource(R.string.home_download),
                        icon = Icons.Filled.ArrowDownward,
                        rate = state.traffic.rxRate,
                        total = state.traffic.rxTotal,
                        tint = MaterialTheme.meow.download,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (exitIp != ExitIpUiState.Hidden) {
            item { ExitIpCard(state = exitIp, onRefresh = onRefreshExitIp) }
        }

        item {
            RouteModeCard(
                mode = state.routeMode,
                // Usable while disconnected too: the pick applies on the next connect.
                enabled = !state.isBusy,
                onSelect = onSelectRouteMode,
            )
        }

        // Rules reads the engine's controller API, so it is only reachable
        // while the VPN is up.
        item {
            GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                NavRow(
                    title = stringResource(R.string.rules_title),
                    icon = Icons.AutoMirrored.Filled.List,
                    onClick = onRules,
                    enabled = state.isConnected,
                    subtitle = offlineHint.takeUnless { state.isConnected },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: HomeUiState, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.meow
    val label = stringResource(
        when (state.state) {
            BaseService.State.Connected -> R.string.home_connected
            BaseService.State.Connecting -> R.string.home_connecting
            BaseService.State.Stopping -> R.string.home_disconnecting
            BaseService.State.Stopped -> R.string.home_disconnected
            BaseService.State.Idle -> R.string.home_not_connected
        },
    )
    val tint = when (state.state) {
        BaseService.State.Connected -> colors.connected
        BaseService.State.Connecting, BaseService.State.Stopping -> colors.warning
        else -> colors.mutedText
    }

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(tint.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.isConnected) Icons.Filled.VpnKey else Icons.Filled.VpnKeyOff,
                    contentDescription = null,
                    tint = tint,
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = tint,
                )
                Text(
                    text = state.profileName.ifEmpty { stringResource(R.string.subs_none) },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                )
            }
            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                val toggleLabel = stringResource(R.string.home_vpn_toggle)
                Switch(
                    checked = state.isConnected,
                    onCheckedChange = onToggle,
                    enabled = state.hasProfile || state.isConnected,
                    modifier = Modifier
                        .testTag("vpn_switch")
                        .semantics { contentDescription = toggleLabel },
                )
            }
        }
    }
}

@Composable
private fun NoProfileCard(onAdd: () -> Unit) {
    GlassCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.meow.mutedText.copy(alpha = 0.6f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.subs_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.meow.mutedText,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAdd) { Text(stringResource(R.string.subs_add)) }
        }
    }
}

@Composable
private fun RouteModeCard(mode: RouteMode, enabled: Boolean, onSelect: (RouteMode) -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Route,
                contentDescription = null,
                tint = MaterialTheme.meow.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.home_route_mode),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Spacer(Modifier.height(10.dp))
        // Three segments leave ~75dp per label on a 360dp phone, so the fill
        // marks the selection rather than a checkmark (which would take 26dp),
        // and a label that still does not fit (Russian "Глобальный") shrinks
        // instead of wrapping into a taller segment. Each segment otherwise
        // takes its own label's height, and Burmese lines are taller than the
        // Latin "Global" between them, so all three fill the tallest.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            RouteMode.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = mode == entry,
                    onClick = { if (mode != entry) onSelect(entry) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, RouteMode.entries.size),
                    modifier = Modifier.fillMaxHeight().testTag("route_mode_${entry.wire}"),
                    icon = {},
                ) {
                    Text(
                        stringResource(
                            when (entry) {
                                RouteMode.Rule -> R.string.route_mode_rule
                                RouteMode.Global -> R.string.route_mode_global
                                RouteMode.Direct -> R.string.route_mode_direct
                            },
                        ),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 10.sp,
                            maxFontSize = LocalTextStyle.current.fontSize,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    rate: Long,
    total: Long,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                text = label.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.meow.mutedText,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = Formatters.rate(rate),
            style = MaterialTheme.typography.titleMedium.merge(MeowTextStyles.monoDigits),
        )
        Text(
            text = stringResource(R.string.traffic_total, Formatters.bytes(total)),
            style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
            color = MaterialTheme.meow.mutedText,
        )
    }
}
