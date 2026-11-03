package io.github.madeye.meow.ui.screens.trafficdetails

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.madeye.meow.R
import io.github.madeye.meow.traffic.TrafficGroup
import io.github.madeye.meow.traffic.TrafficNetwork
import io.github.madeye.meow.traffic.TrafficReport
import io.github.madeye.meow.traffic.TrafficRow
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters
import java.util.Locale

/**
 * The traffic ledger narrowed to a row picked on the Traffic page, or on
 * a page like this one: how much each host, app, policy or network under
 * it carried. A row narrows it further.
 */
@Composable
fun TrafficDetailsScreen(
    state: TrafficDetailsUiState,
    appIcon: suspend (packageName: String) -> Drawable?,
    contentPadding: PaddingValues,
    onPeriodChange: (TrafficPeriod) -> Unit,
    onGroupChange: (TrafficGroup) -> Unit,
    onOpen: (TrafficGroup, TrafficRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("traffic_details"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        trafficDetails(state, appIcon, onPeriodChange, onGroupChange, onOpen)
    }
}

/**
 * The traffic ledger's items: period and grouping pickers, the period's
 * sums, and its rows, largest first, an app's with its [appIcon]. The
 * Traffic page shows them under its data usage; a row opens a
 * [TrafficDetailsScreen] narrowed to it.
 */
fun LazyListScope.trafficDetails(
    state: TrafficDetailsUiState,
    appIcon: suspend (packageName: String) -> Drawable?,
    onPeriodChange: (TrafficPeriod) -> Unit,
    onGroupChange: (TrafficGroup) -> Unit,
    onOpen: (TrafficGroup, TrafficRow) -> Unit,
) {
    val report = state.report
    val rows = report?.rows.orEmpty()
    item(key = "ledger_period") {
        Choices(
            options = TrafficPeriod.entries,
            selected = state.period,
            label = { period -> stringResource(period.label) },
            onSelect = onPeriodChange,
        )
    }
    if (state.groups.size > 1) {
        item(key = "ledger_group") {
            Spacer(Modifier.height(8.dp))
            Choices(
                options = state.groups,
                selected = state.by,
                label = { group -> stringResource(group.label) },
                onSelect = onGroupChange,
            )
        }
    }
    item(key = "ledger_summary") {
        Summary(report)
    }
    when {
        report == null && state.error != null -> item(key = "ledger_error") {
            Hint(stringResource(R.string.traffic_details_error, state.error))
        }
        report == null -> Unit
        rows.isEmpty() -> item(key = "ledger_empty") { Hint(stringResource(R.string.traffic_details_empty)) }
        else -> itemsIndexed(rows, key = { _, row -> "ledger_row:${row.name}" }) { index, row ->
            LedgerRow(
                group = state.by,
                row = row,
                name = trafficRowName(state.by, row.name, state.appLabels),
                appIcon = appIcon,
                first = index == 0,
                last = index == rows.lastIndex,
                // A row narrows the page to it, which needs a grouping left
                // to show it by. An empty name cannot narrow: the ledger
                // reads an empty filter as no filter.
                onClick = if (state.groups.size > 1 && row.name.isNotEmpty()) {
                    { onOpen(state.by, row) }
                } else {
                    null
                },
            )
        }
    }
    item(key = "ledger_footer") {
        Text(
            text = stringResource(R.string.traffic_details_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.meow.mutedText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp),
        )
    }
}

/** The top bar's clear action, with its confirmation. */
@Composable
fun TrafficDetailsActions(onClear: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { confirming = true }, modifier = Modifier.testTag("traffic_details_clear")) {
        Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.traffic_details_clear))
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.traffic_details_clear)) },
            text = { Text(stringResource(R.string.traffic_details_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onClear()
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.meow.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * How a row of [group] named [name] reads: the ledger's empty and catch-all
 * names spelled out, an app by its label in [appLabels] once known.
 */
@Composable
fun trafficRowName(group: TrafficGroup, name: String, appLabels: Map<String, String> = emptyMap()): String = when (group) {
    TrafficGroup.Host -> when (name) {
        "" -> stringResource(R.string.traffic_details_unknown)
        TrafficRow.OTHER_IPS -> stringResource(R.string.traffic_details_other_ips)
        else -> name
    }
    TrafficGroup.App -> if (name.isEmpty()) stringResource(R.string.traffic_details_unknown) else appLabels[name] ?: name
    TrafficGroup.Policy -> name.ifEmpty { stringResource(R.string.traffic_details_unknown) }
    TrafficGroup.Interface -> when (name) {
        TrafficNetwork.WIFI -> stringResource(R.string.traffic_network_wifi)
        TrafficNetwork.CELLULAR -> stringResource(R.string.statistics_cellular)
        TrafficNetwork.ETHERNET -> stringResource(R.string.traffic_network_ethernet)
        "", TrafficNetwork.OTHER -> stringResource(R.string.statistics_other_network)
        else -> name
    }
}

private val TrafficPeriod.label: Int
    get() = when (this) {
        TrafficPeriod.Today -> R.string.traffic_today
        TrafficPeriod.ThisMonth -> R.string.traffic_this_month
        TrafficPeriod.LastMonth -> R.string.traffic_last_month
    }

private val TrafficGroup.label: Int
    get() = when (this) {
        TrafficGroup.Host -> R.string.traffic_by_host
        TrafficGroup.App -> R.string.traffic_by_app
        TrafficGroup.Policy -> R.string.traffic_by_policy
        TrafficGroup.Interface -> R.string.traffic_by_network
    }

@Composable
private fun <T> Choices(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The period's sums, one line over its rows. */
@Composable
private fun Summary(report: TrafficReport?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Formatters.bytes(report?.total ?: 0),
            style = MaterialTheme.typography.titleMedium.merge(MeowTextStyles.monoDigits),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(12.dp))
        Direction(Icons.Filled.ArrowDownward, MaterialTheme.meow.download, report?.download ?: 0)
        Spacer(Modifier.size(8.dp))
        Direction(Icons.Filled.ArrowUpward, MaterialTheme.meow.upload, report?.upload ?: 0)
        Spacer(Modifier.weight(1f))
        val requests = report?.requests ?: 0
        Text(
            text = pluralStringResource(R.plurals.traffic_requests, requests.toQuantity(), requests),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.meow.mutedText,
        )
    }
}

@Composable
private fun Direction(icon: ImageVector, tint: Color, bytes: Long) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
    Text(
        text = Formatters.bytes(bytes),
        style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
        color = MaterialTheme.meow.mutedText,
    )
}

@Composable
private fun LedgerRow(
    group: TrafficGroup,
    row: TrafficRow,
    name: String,
    appIcon: suspend (packageName: String) -> Drawable?,
    first: Boolean,
    last: Boolean,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.meow
    val radius = 14.dp
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )
    Column(modifier = Modifier.fillMaxWidth().background(colors.panel, shape)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (group == TrafficGroup.App && row.name.isNotEmpty()) {
                AppIcon(row.name, name, appIcon)
            } else {
                Avatar(name)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.traffic_requests, row.requests.toQuantity(), row.requests),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Formatters.bytes(row.total),
                    style = MaterialTheme.typography.bodyMedium.merge(MeowTextStyles.monoDigits),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "↓ ${Formatters.bytes(row.download)}  ↑ ${Formatters.bytes(row.upload)}",
                    style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
                    color = colors.mutedText,
                )
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.mutedText,
                )
            }
        }
        if (!last) HorizontalDivider(color = colors.border, modifier = Modifier.padding(start = 56.dp))
    }
}

/** The app's icon; its [label]'s [Avatar] while it loads, or when the app is gone. */
@Composable
private fun AppIcon(packageName: String, label: String, appIcon: suspend (packageName: String) -> Drawable?) {
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = appIcon(packageName)?.let { drawable ->
            runCatching { drawable.toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(32.dp))
    } else {
        Avatar(label)
    }
}

private const val ICON_PX = 96

/** A letter on a tinted disc, the tint picked by the name so a row keeps its colour. */
@Composable
private fun Avatar(name: String) {
    val colors = MaterialTheme.meow
    val palette = listOf(colors.accent, colors.ginger, colors.connected, colors.warning, colors.danger)
    val tint = palette[Math.floorMod(name.hashCode(), palette.size)]
    val letter = name.firstOrNull { it.isLetterOrDigit() }?.toString()?.uppercase(Locale.getDefault()) ?: "?"
    Box(
        modifier = Modifier.size(32.dp).background(tint.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = letter, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun Hint(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.meow.mutedText,
            textAlign = TextAlign.Center,
        )
    }
}

/** A request count as a plural quantity; past `Int.MAX_VALUE` every language says "other". */
private fun Long.toQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
