package io.github.madeye.meow.ui.screens.connections

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.api.Connection
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters
import io.github.madeye.meow.ui.util.regionFlag
import io.github.madeye.meow.ui.util.regionName
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * One connection: where it went and when, the policy and rule that sent it
 * there, and its traffic. A tap on the rule opens it in the rule list.
 */
@Composable
fun ConnectionDetailScreen(
    state: ConnectionDetailUiState,
    contentPadding: PaddingValues,
    onOpenRule: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection = state.connection
    if (connection == null) {
        Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.connection_detail_gone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.meow.mutedText,
            )
        }
        return
    }
    val colors = MaterialTheme.meow
    val meta = connection.metadata
    // Ticks an open connection's duration between polls.
    val now by produceState(Instant.now(), state.active) {
        while (state.active) {
            delay(1_000)
            value = Instant.now()
        }
    }
    val lastSeen = state.lastSeenAtMillis

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            )
            .testTag("connection_detail_root"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(
            Line(R.string.connection_detail_host, connection.address),
            appLine(connection, state.appLabel),
            Line(R.string.connection_detail_date, Formatters.dateTime(connection.start)),
            if (lastSeen == null) {
                Line(R.string.connection_detail_status, stringResource(R.string.connection_detail_active), colors.connected)
            } else {
                Line(
                    label = R.string.connection_detail_status,
                    value = stringResource(R.string.connection_detail_closed),
                    detail = stringResource(R.string.connections_last_seen, Formatters.timestamp(lastSeen)),
                )
            },
            Line(
                R.string.connection_detail_duration,
                Formatters.elapsedSince(connection.start, lastSeen?.let(Instant::ofEpochMilli) ?: now),
            ),
        )
        Section(
            Line(
                R.string.connection_detail_policy,
                connection.chains.reversed().joinToString(" → ").ifEmpty { PLACEHOLDER },
            ),
            ruleLine(connection, state.ruleIndex, onOpenRule),
        )
        IpSection(localIp = meta.sourceIP, remote = state.remote)
        Section(
            Line(R.string.connection_detail_network, meta.network.uppercase().ifEmpty { PLACEHOLDER }),
            if (meta.sniffHost.isNotEmpty() && meta.sniffHost != meta.host) {
                Line(R.string.connection_detail_sniff_host, meta.sniffHost)
            } else {
                null
            },
            if (meta.dnsMode.isNotEmpty()) {
                // `mapping` is the engine's wire name for what configs call redir-host.
                Line(R.string.connection_detail_dns_mode, if (meta.dnsMode == "mapping") "redir-host" else meta.dnsMode)
            } else {
                null
            },
        )
        Section(
            Line(R.string.traffic_upload, Formatters.bytes(connection.upload)),
            Line(R.string.traffic_download, Formatters.bytes(connection.download)),
        )
    }
}

private const val PLACEHOLDER = "—"

/** One row: a label, its value (with an optional line under it), and where a tap goes. */
private data class Line(
    @StringRes val label: Int,
    val value: String,
    val valueColor: Color? = null,
    val detail: String? = null,
    val onClick: (() -> Unit)? = null,
    val testTag: String? = null,
)

/**
 * Both ends' addresses, and who holds the far one: its country and network,
 * from the GeoX databases on the device.
 */
@Composable
private fun IpSection(localIp: String, remote: RemoteIp?) {
    val geo = (remote as? RemoteIp.Known)?.geo
    Section(
        if (localIp.isNotEmpty()) Line(R.string.connection_detail_local_ip, localIp) else null,
        when (remote) {
            is RemoteIp.Known -> Line(
                label = R.string.connection_detail_remote_ip,
                value = remote.ip,
                detail = if (remote.fromDnsCache) stringResource(R.string.connection_detail_remote_ip_dns_cache) else null,
                testTag = "connection_detail_remote_ip",
            )
            RemoteIp.ResolvedByProxy -> Line(
                label = R.string.connection_detail_remote_ip,
                value = stringResource(R.string.connection_detail_remote_ip_by_proxy),
                testTag = "connection_detail_remote_ip",
            )
            null -> null
        },
        geo?.country?.let { Line(R.string.connection_detail_remote_region, regionLabel(it)) },
        geo?.asn?.let { Line(R.string.connection_detail_remote_asn, "AS$it") },
        geo?.org?.let { Line(R.string.connection_detail_remote_org, it) },
    )
}

/**
 * A country as its flag and name in the UI language. The database's own codes
 * for ranges filed by owner (`GOOGLE`, `CLOUDFLARE`…) are what GEOIP rules
 * match on, so they stay as they are.
 */
@Composable
private fun regionLabel(code: String): String {
    if (code == PRIVATE_REGION) return stringResource(R.string.connection_detail_region_private)
    val name = regionName(code, LocalConfiguration.current.locales[0]) ?: return code
    return listOfNotNull(regionFlag(code), name).joinToString(" ")
}

private const val PRIVATE_REGION = "PRIVATE"

/** A card of [lines], dividers between them; null lines are left out, and a card with none. */
@Composable
private fun Section(vararg lines: Line?) {
    val shown = lines.filterNotNull()
    if (shown.isEmpty()) return
    GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        shown.forEachIndexed { index, line ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.meow.border)
            LineRow(line)
        }
    }
}

/**
 * The app that opened the connection: its name over its package, or just the
 * uid for a system process no package owns. Null when the engine did not say
 * (Android 9 and older, or the system could not tell in time).
 */
private fun appLine(connection: Connection, label: String?): Line? {
    val meta = connection.metadata
    val pkg = connection.appPackage
    return when {
        pkg != null -> Line(
            label = R.string.connection_detail_app,
            value = label ?: pkg,
            detail = pkg.takeIf { label != null && label != pkg },
            testTag = "connection_detail_app",
        )
        meta.uid != null -> Line(R.string.connection_detail_app, "UID ${meta.uid}", testTag = "connection_detail_app")
        else -> null
    }
}

@Composable
private fun ruleLine(connection: Connection, index: Int?, onOpenRule: (Int) -> Unit): Line {
    val value = when (UnlistedRoute.of(connection)) {
        UnlistedRoute.Final -> stringResource(R.string.connection_detail_rule_final)
        UnlistedRoute.Global -> stringResource(R.string.connection_detail_rule_global)
        UnlistedRoute.Direct -> stringResource(R.string.connection_detail_rule_direct)
        null -> listOf(connection.rule, connection.rulePayload).filter { it.isNotEmpty() }
            .joinToString(" ").ifEmpty { PLACEHOLDER }
    }
    return Line(
        label = R.string.connection_detail_rule,
        value = value,
        detail = index?.let { stringResource(R.string.connection_detail_rule_number, it + 1) },
        onClick = index?.let { { onOpenRule(it) } },
        testTag = "connection_detail_rule",
    )
}

@Composable
private fun LineRow(line: Line) {
    val muted = MaterialTheme.meow.mutedText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (line.onClick != null) Modifier.clickable(onClick = line.onClick) else Modifier)
            .then(if (line.testTag != null) Modifier.testTag(line.testTag) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = stringResource(line.label), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = line.value,
                style = MaterialTheme.typography.bodyMedium.merge(MeowTextStyles.monoDigits),
                color = line.valueColor ?: muted,
                textAlign = TextAlign.End,
            )
            if (line.detail != null) {
                Text(
                    text = line.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    textAlign = TextAlign.End,
                )
            }
        }
        if (line.onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
            )
        }
    }
}
