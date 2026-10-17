package io.github.madeye.meow.ui.screens.statistics

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.aidl.ByteTotals
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.components.SectionHeader
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters
import java.util.Locale

@Composable
fun StatisticsScreen(
    state: StatisticsUiState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (!state.loaded) return
    val session = state.session
    if (session == null) {
        // The VPN went down while the screen was open.
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 32.dp)
                .testTag("statistics_offline"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.settings_engine_offline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.meow.mutedText,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val border = MaterialTheme.meow.border

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            )
            .padding(horizontal = 16.dp),
    ) {
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            InfoRow(R.string.statistics_start_date, Formatters.dateTime(session.startedAt))
            HorizontalDivider(color = border)
            InfoRow(
                R.string.statistics_uptime,
                uptime(state.now - session.startedAt),
                Modifier.testTag("statistics_uptime"),
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader(stringResource(R.string.statistics_interfaces))
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            TrafficRow(stringResource(R.string.statistics_wifi_ethernet), session.wifiEthernet)
            HorizontalDivider(color = border)
            TrafficRow(stringResource(R.string.statistics_cellular), session.cellular)
            // A VPN under the VPN, Bluetooth tethering, …: rare enough to only
            // show once it carried something.
            if (session.other.total > 0) {
                HorizontalDivider(color = border)
                TrafficRow(stringResource(R.string.statistics_other_network), session.other)
            }
        }

        val policies = state.policies
        if (!policies.isNullOrEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.statistics_policies))
            GlassCard(
                modifier = Modifier.testTag("statistics_policies"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                policies.forEachIndexed { index, policy ->
                    // A proxy seen for the first time can land above an
                    // expanded one; the key keeps the expansion with its name.
                    key(policy.name) {
                        if (index > 0) HorizontalDivider(color = border)
                        TrafficRow(policy.name, policy.totals)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(@StringRes label: Int, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.merge(MeowTextStyles.monoDigits),
            color = MaterialTheme.meow.mutedText,
            textAlign = TextAlign.End,
        )
    }
}

/** A total that expands, on a tap, into its upload and download. */
@Composable
private fun TrafficRow(title: String, totals: ByteTotals) {
    val muted = MaterialTheme.meow.mutedText
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.statistics_total, Formatters.bytes(totals.total)),
                style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                color = muted,
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 32.dp, bottom = 8.dp)) {
                DirectionRow(R.string.traffic_upload, totals.upload)
                DirectionRow(R.string.traffic_download, totals.download)
            }
        }
    }
}

@Composable
private fun DirectionRow(@StringRes label: Int, bytes: Long) {
    val muted = MaterialTheme.meow.mutedText
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = Formatters.bytes(bytes),
            style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
            color = muted,
        )
    }
}

/** e.g. "29 hours, 35 minutes, 38 seconds", in the UI's language; units still at zero are left out up front. */
@Composable
private fun uptime(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val seconds = (millis / 1_000).coerceAtLeast(0)
    return remember(seconds, locale) {
        val hours = seconds / 3_600
        val minutes = seconds % 3_600 / 60
        val measures = buildList {
            if (hours > 0) add(Measure(hours, MeasureUnit.HOUR))
            if (hours > 0 || minutes > 0) add(Measure(minutes, MeasureUnit.MINUTE))
            add(Measure(seconds % 60, MeasureUnit.SECOND))
        }
        MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).formatMeasures(*measures.toTypedArray())
    }
}
