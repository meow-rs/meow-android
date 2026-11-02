package io.github.madeye.meow.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.madeye.meow.R
import io.github.madeye.meow.net.ExitIp
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.screens.proxies.ProbeFailure
import io.github.madeye.meow.ui.screens.proxies.natClass
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.regionFlag
import io.github.madeye.meow.ui.util.regionName
import java.util.Locale

private const val PLACEHOLDER = "—"

/**
 * Where traffic leaves from, in two halves laid out like the traffic tiles
 * above it: the public address with its country and network, and the NAT
 * type UDP meets on the way out. Tap to re-check both.
 */
@Composable
fun ExitIpCard(state: ExitIpUiState, nat: NatUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.meow
    val checking = state is ExitIpUiState.Checking || nat is NatUiState.Checking

    GlassCard(
        modifier = modifier.testTag("exit_ip_card"),
        // Mid-check a tap would only restart the same checks.
        onClick = onRefresh.takeUnless { checking },
    ) {
        // Min height, so the divider spans the taller half.
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Column(modifier = Modifier.weight(1f)) {
                Header(
                    label = stringResource(R.string.home_exit_ip),
                    icon = {
                        val flag = (state as? ExitIpUiState.Found)?.exitIp?.flagEmoji()
                        if (flag != null) {
                            // Sized like the icons it stands in for, which do not grow with the font scale.
                            val density = LocalDensity.current
                            Text(
                                text = flag,
                                fontSize = with(density) { 14.dp.toSp() },
                                lineHeight = with(density) { 18.dp.toSp() },
                            )
                        } else {
                            HeaderIcon(if (state is ExitIpUiState.Failed) Icons.Filled.CloudOff else Icons.Filled.Public)
                        }
                    },
                )
                when (state) {
                    is ExitIpUiState.Found -> IpLines(state.exitIp)
                    ExitIpUiState.Failed -> {
                        Value(stringResource(R.string.home_exit_ip_failed))
                        Detail(stringResource(R.string.home_exit_ip_retry))
                    }
                    else -> Value(stringResource(R.string.home_exit_ip_checking), colors.mutedText)
                }
            }
            VerticalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = colors.border)
            Column(modifier = Modifier.weight(1f).testTag("exit_ip_nat")) {
                Header(
                    label = stringResource(R.string.proxy_detail_nat),
                    icon = { HeaderIcon(Icons.Filled.Router) },
                    trailing = {
                        if (checking) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.home_exit_ip_refresh),
                                tint = colors.mutedText,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
                NatLines(nat)
            }
        }
        if ((state as? ExitIpUiState.Found)?.bypassesVpn == true) {
            // Under both halves: the NAT half still measured the VPN's route.
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_exit_ip_bypassed),
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
            )
        }
    }
}

/** A half's label row, as on the traffic tiles: an 18 dp icon slot, the label, [trailing]. */
@Composable
private fun Header(label: String, icon: @Composable () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        // At least the icons' height, so both halves' values start level.
        modifier = Modifier.heightIn(min = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.meow.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun HeaderIcon(icon: ImageVector) {
    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.meow.accent, modifier = Modifier.size(18.dp))
}

@Composable
private fun Value(text: String, color: Color = Color.Unspecified) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = color)
}

@Composable
private fun Detail(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.meow.mutedText,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Half a card is narrow: an IPv4 address shrinks rather than wrap mid-number,
 * and an IPv6 one wraps only between its groups.
 */
@Composable
private fun IpValue(ip: String) {
    val style = MaterialTheme.typography.titleMedium.merge(MeowTextStyles.monoDigits)
    val v6 = ':' in ip
    Text(
        text = if (v6) ip.replace(":", ":\u200B") else ip,
        style = style,
        maxLines = if (v6) Int.MAX_VALUE else 1,
        autoSize = if (v6) null else TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
    )
}

@Composable
private fun IpLines(exitIp: ExitIp) {
    val locale = LocalConfiguration.current.locales[0]
    IpValue(exitIp.ip)
    exitIp.countryName(locale)?.let { Detail(it, maxLines = 1) }
    exitIp.networkLabel()?.let { Detail(it, maxLines = 2) }
}

@Composable
private fun NatLines(nat: NatUiState) {
    val colors = MaterialTheme.meow
    when (nat) {
        is NatUiState.Found -> Value(stringResource(nat.type.natClass().label))
        is NatUiState.Failed -> when (nat.reason) {
            ProbeFailure.TimedOut -> Value(stringResource(R.string.proxy_delay_timeout), colors.danger)
            ProbeFailure.UdpUnsupported -> {
                Value(PLACEHOLDER, colors.mutedText)
                Detail(stringResource(R.string.proxy_detail_udp_unsupported))
            }
            ProbeFailure.Failed -> {
                Value(stringResource(R.string.proxy_detail_failed), colors.danger)
                Detail(stringResource(R.string.home_exit_ip_retry))
            }
        }
        NatUiState.Checking -> Value(stringResource(R.string.proxy_detail_testing), colors.mutedText)
        // The direct route has no engine to test through.
        NatUiState.Unavailable -> Value(PLACEHOLDER, colors.mutedText)
    }
}

/** The two regional-indicator symbols that render as the country's flag. */
internal fun ExitIp.flagEmoji(): String? = countryCode?.let(::regionFlag)

/**
 * The country in the UI language. The services answer in English (ipinfo.io
 * not at all), so the platform names the code; their name is the fallback
 * for codes it does not know.
 */
internal fun ExitIp.countryName(locale: Locale): String? {
    val code = countryCode ?: return country
    return regionName(code, locale) ?: country ?: code
}

/** `AS24940 Hetzner Online GmbH`, or whichever half is known. */
internal fun ExitIp.networkLabel(): String? = when {
    asn != null && org != null -> "AS$asn $org"
    asn != null -> "AS$asn"
    else -> org
}
