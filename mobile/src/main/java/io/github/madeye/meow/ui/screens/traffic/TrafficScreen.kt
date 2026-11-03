package io.github.madeye.meow.ui.screens.traffic

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.repo.TrafficTotals
import io.github.madeye.meow.traffic.TrafficGroup
import io.github.madeye.meow.traffic.TrafficRow
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.components.SectionHeader
import io.github.madeye.meow.ui.screens.trafficdetails.TrafficDetailsUiState
import io.github.madeye.meow.ui.screens.trafficdetails.TrafficPeriod
import io.github.madeye.meow.ui.screens.trafficdetails.trafficDetails
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters
import java.util.Locale

/**
 * Data usage today and this month, then the engine's traffic ledger for a
 * period by host, app, policy or network, where a row opens the ledger
 * narrowed to it. A usage card picks its period below.
 */
@Composable
fun TrafficScreen(
    state: TrafficUiState,
    details: TrafficDetailsUiState,
    contentPadding: PaddingValues,
    onPeriodChange: (TrafficPeriod) -> Unit,
    onGroupChange: (TrafficGroup) -> Unit,
    onOpen: (TrafficGroup, TrafficRow) -> Unit,
    modifier: Modifier = Modifier,
    appIcon: suspend (packageName: String) -> Drawable? = { null },
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("traffic"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        item(key = "usage") {
            SectionHeader(stringResource(R.string.traffic_data_usage))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UsageCard(
                    label = stringResource(R.string.traffic_today),
                    totals = state.today,
                    onClick = { onPeriodChange(TrafficPeriod.Today) },
                    modifier = Modifier.weight(1f),
                )
                UsageCard(
                    label = stringResource(R.string.traffic_this_month),
                    totals = state.thisMonth,
                    onClick = { onPeriodChange(TrafficPeriod.ThisMonth) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.traffic_details_title))
            Spacer(Modifier.height(12.dp))
        }
        trafficDetails(details, appIcon, onPeriodChange, onGroupChange, onOpen)
    }
}

@Composable
private fun UsageCard(label: String, totals: TrafficTotals, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, onClick = onClick) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.meow.mutedText,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = Formatters.bytes(totals.total),
            style = MaterialTheme.typography.titleMedium.merge(MeowTextStyles.monoDigits),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint = MaterialTheme.meow.upload,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = Formatters.bytes(totals.tx),
                style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                color = MaterialTheme.meow.mutedText,
            )
            Spacer(Modifier.size(8.dp))
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.meow.download,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = Formatters.bytes(totals.rx),
                style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                color = MaterialTheme.meow.mutedText,
            )
        }
    }
}
