package io.github.madeye.meow.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.madeye.meow.R
import io.github.madeye.meow.net.ExitIp
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import java.util.IllformedLocaleException
import java.util.Locale

/** The public address traffic leaves from, with its country and network. Tap to re-check. */
@Composable
fun ExitIpCard(state: ExitIpUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.meow
    val checking = state is ExitIpUiState.Checking

    GlassCard(
        modifier = modifier.testTag("exit_ip_card"),
        // Mid-check a tap would only restart the same lookup.
        onClick = onRefresh.takeUnless { checking },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(colors.accent.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val flag = (state as? ExitIpUiState.Found)?.exitIp?.flagEmoji()
                if (flag != null) {
                    Text(text = flag, fontSize = 22.sp)
                } else {
                    Icon(
                        imageVector = if (state is ExitIpUiState.Failed) Icons.Filled.CloudOff else Icons.Filled.Public,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_exit_ip).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.mutedText,
                )
                when (state) {
                    is ExitIpUiState.Found -> FoundLines(state)
                    ExitIpUiState.Failed -> {
                        Text(
                            text = stringResource(R.string.home_exit_ip_failed),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.home_exit_ip_retry),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.mutedText,
                        )
                    }
                    else -> Text(
                        text = stringResource(R.string.home_exit_ip_checking),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.mutedText,
                    )
                }
            }
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.home_exit_ip_refresh),
                    tint = colors.mutedText,
                )
            }
        }
    }
}

@Composable
private fun FoundLines(state: ExitIpUiState.Found) {
    val colors = MaterialTheme.meow
    val locale = LocalConfiguration.current.locales[0]
    val details = remember(state.exitIp, locale) {
        listOfNotNull(state.exitIp.countryName(locale), state.exitIp.networkLabel())
            .joinToString(" · ")
    }
    Text(
        text = state.exitIp.ip,
        style = MaterialTheme.typography.titleMedium.merge(MeowTextStyles.monoDigits),
    )
    if (details.isNotEmpty()) {
        Text(text = details, style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
    }
    if (state.bypassesVpn) {
        Text(
            text = stringResource(R.string.home_exit_ip_bypassed),
            style = MaterialTheme.typography.bodySmall,
            color = colors.warning,
        )
    }
}

/** The two regional-indicator symbols that render as the country's flag. */
internal fun ExitIp.flagEmoji(): String? = countryCode?.let { code ->
    buildString { code.forEach { appendCodePoint(0x1F1E6 + (it - 'A')) } }
}

/**
 * The country in the UI language. The services answer in English (ipinfo.io
 * not at all), so the platform names the code; their name is the fallback
 * for codes it does not know.
 */
internal fun ExitIp.countryName(locale: Locale): String? {
    val code = countryCode ?: return country
    val name = try {
        Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
    } catch (e: IllformedLocaleException) {
        ""
    }
    // An unknown region comes back as the code itself.
    return name.takeIf { it.isNotEmpty() && it != code } ?: country ?: code
}

/** `AS24940 Hetzner Online GmbH`, or whichever half is known. */
internal fun ExitIp.networkLabel(): String? = when {
    asn != null && org != null -> "AS$asn $org"
    asn != null -> "AS$asn"
    else -> org
}
