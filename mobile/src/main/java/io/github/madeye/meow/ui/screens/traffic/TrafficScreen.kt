package io.github.madeye.meow.ui.screens.traffic

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
            // The cards stand as tall as the taller one.
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UsageCard(
                    label = stringResource(R.string.traffic_today),
                    totals = state.today,
                    onClick = { onPeriodChange(TrafficPeriod.Today) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                UsageCard(
                    label = stringResource(R.string.traffic_this_month),
                    totals = state.thisMonth,
                    onClick = { onPeriodChange(TrafficPeriod.ThisMonth) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
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
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        // A line each, downloads first as on the rows below, so a value
        // never breaks between its number and its unit.
        Direction(Icons.Filled.ArrowDownward, MaterialTheme.meow.download, totals.rx)
        Direction(Icons.Filled.ArrowUpward, MaterialTheme.meow.upload, totals.tx)
    }
}

@Composable
private fun Direction(icon: ImageVector, tint: Color, bytes: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text = Formatters.bytes(bytes),
            style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
            color = MaterialTheme.meow.mutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
