package io.github.madeye.meow.ui.screens.proxies

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.net.NatBehavior
import io.github.madeye.meow.net.NatType
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.screens.home.countryName
import io.github.madeye.meow.ui.screens.home.flagEmoji
import io.github.madeye.meow.ui.screens.home.networkLabel
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters

/** A proxy's detail page: its type and UDP support, then one row per test. */
@Composable
fun ProxyDetailScreen(
    state: ProxyDetailUiState,
    contentPadding: PaddingValues,
    onTest: (ProxyProbe) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.meow
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
            .testTag("proxy_detail_root"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            InfoRow(R.string.proxy_detail_type, state.type?.ifEmpty { null } ?: PLACEHOLDER)
            HorizontalDivider(color = colors.border)
            InfoRow(
                R.string.proxy_detail_udp,
                when (state.udp) {
                    true -> stringResource(R.string.proxy_detail_supported)
                    false -> stringResource(R.string.proxy_detail_unsupported)
                    null -> PLACEHOLDER
                },
            )
        }
        if (!state.connected) {
            Text(
                text = stringResource(R.string.proxy_detail_connect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            ProxyProbe.entries.forEachIndexed { index, probe ->
                if (index > 0) HorizontalDivider(color = colors.border)
                // No relay, nothing to measure: say so rather than offer a test bound to fail.
                val noUdp = probe.needsUdp && state.udp == false
                ProbeRow(
                    probe = probe,
                    state = state.probe(probe),
                    connected = state.connected,
                    noUdp = noUdp,
                    onTest = { onTest(probe) },
                )
            }
        }
    }
}

private const val PLACEHOLDER = "—"

@Composable
private fun InfoRow(@StringRes label: Int, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.meow.mutedText,
        )
    }
}

@Composable
private fun ProbeRow(
    probe: ProxyProbe,
    state: ProbeState,
    connected: Boolean,
    noUdp: Boolean,
    onTest: () -> Unit,
) {
    val running = state == ProbeState.Running
    val enabled = connected && !noUdp && !running
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onTest)
            .padding(vertical = 10.dp)
            .testTag("proxy_probe_${probe.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(probe.title), style = MaterialTheme.typography.bodyMedium)
            ProbeValue(state = state, connected = connected, noUdp = noUdp)
        }
        Spacer(Modifier.size(8.dp))
        if (running) {
            // The button's footprint, so the row doesn't shift.
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        } else {
            TextButton(onClick = onTest, enabled = enabled) {
                Text(stringResource(R.string.proxy_detail_test))
            }
        }
    }
}

@Composable
private fun ProbeValue(state: ProbeState, connected: Boolean, noUdp: Boolean) {
    val colors = MaterialTheme.meow
    val muted = colors.mutedText
    when {
        noUdp -> ValueLine(stringResource(R.string.proxy_detail_udp_unsupported), muted)
        state is ProbeState.Done -> when (val result = state.result) {
            is ProbeResult.Latency -> ValueLine(
                text = stringResource(R.string.proxy_delay_ms, result.ms.toInt()),
                color = when {
                    result.ms < 200 -> colors.connected
                    result.ms < 500 -> colors.warning
                    else -> colors.danger
                },
            )
            is ProbeResult.Exit -> {
                val locale = LocalConfiguration.current.locales[0]
                val exitIp = result.exitIp
                ValueLine(exitIp.ip, MaterialTheme.colorScheme.onSurface)
                val details = listOfNotNull(
                    exitIp.flagEmoji(),
                    exitIp.countryName(locale),
                    exitIp.networkLabel()?.let { "· $it" },
                ).joinToString(" ")
                if (details.isNotEmpty()) DetailLine(details)
            }
            is ProbeResult.Nat -> {
                val nat = result.type
                ValueLine(stringResource(nat.natClass().label), MaterialTheme.colorScheme.onSurface)
                if (nat.sourceHidden) {
                    DetailLine(stringResource(R.string.proxy_detail_nat_source_hidden))
                } else {
                    nat.mappedAddress?.let { DetailLine(it) }
                }
            }
            is ProbeResult.Bandwidth -> {
                val throughput = result.throughput
                ValueLine(Formatters.bitRate(throughput.bitsPerSecond), MaterialTheme.colorScheme.onSurface)
                DetailLine(Formatters.rate((throughput.bitsPerSecond / 8).toLong()))
            }
        }
        state is ProbeState.Failed -> when (state.reason) {
            ProbeFailure.TimedOut -> ValueLine(stringResource(R.string.proxy_delay_timeout), colors.danger)
            ProbeFailure.UdpUnsupported -> ValueLine(stringResource(R.string.proxy_detail_udp_unsupported), muted)
            ProbeFailure.Failed -> {
                ValueLine(stringResource(R.string.proxy_detail_failed), colors.danger)
                state.detail?.let { DetailLine(it) }
            }
        }
        state == ProbeState.Running -> ValueLine(stringResource(R.string.proxy_detail_testing), muted)
        connected -> ValueLine(stringResource(R.string.proxy_detail_tap_to_test), muted)
        else -> ValueLine(PLACEHOLDER, muted)
    }
}

@Composable
private fun ValueLine(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
        color = color,
    )
}

@Composable
private fun DetailLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.meow.mutedText,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

internal val ProxyProbe.title: Int
    @StringRes get() = when (this) {
        ProxyProbe.TcpLatency -> R.string.proxy_detail_tcp_latency
        ProxyProbe.UdpLatency -> R.string.proxy_detail_udp_latency
        ProxyProbe.ExitIp -> R.string.proxy_detail_exit_ip
        ProxyProbe.Nat -> R.string.proxy_detail_nat
        ProxyProbe.Download -> R.string.proxy_detail_download
        ProxyProbe.Upload -> R.string.proxy_detail_upload
    }

private val ProxyProbe.needsUdp: Boolean
    get() = this == ProxyProbe.UdpLatency || this == ProxyProbe.Nat

/** The classic cone / symmetric names, which is what users look the result up by. */
enum class NatClass(@StringRes val label: Int) {
    FullCone(R.string.proxy_detail_nat_full_cone),
    RestrictedCone(R.string.proxy_detail_nat_restricted_cone),
    PortRestrictedCone(R.string.proxy_detail_nat_port_restricted_cone),

    /** Endpoint-independent mapping, filtering not measured. */
    Cone(R.string.proxy_detail_nat_cone),
    Symmetric(R.string.proxy_detail_nat_symmetric),
    Unknown(R.string.proxy_detail_nat_unknown),
}

internal fun NatType.natClass(): NatClass = when (mapping) {
    NatBehavior.EndpointIndependent -> when (filtering) {
        NatBehavior.EndpointIndependent -> NatClass.FullCone
        NatBehavior.AddressDependent -> NatClass.RestrictedCone
        NatBehavior.AddressAndPortDependent -> NatClass.PortRestrictedCone
        NatBehavior.Dependent, NatBehavior.Unknown -> NatClass.Cone
    }
    NatBehavior.AddressDependent, NatBehavior.AddressAndPortDependent, NatBehavior.Dependent -> NatClass.Symmetric
    NatBehavior.Unknown -> NatClass.Unknown
}
