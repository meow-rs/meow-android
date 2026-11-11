package io.github.madeye.meow.ui.screens.home

import android.graphics.drawable.Drawable
import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.madeye.meow.R
import io.github.madeye.meow.traffic.TrafficGroup
import io.github.madeye.meow.traffic.TrafficRow
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.screens.trafficdetails.AppIcon
import io.github.madeye.meow.ui.screens.trafficdetails.Avatar
import io.github.madeye.meow.ui.screens.trafficdetails.TrafficDetailsUiState
import io.github.madeye.meow.ui.screens.trafficdetails.ledgerTint
import io.github.madeye.meow.ui.screens.trafficdetails.rowNameAlign
import io.github.madeye.meow.ui.screens.trafficdetails.trafficRowName
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters
import java.text.NumberFormat

/** What the card can group today's traffic by, the first its default. */
val TOP_TRAFFIC_GROUPS: List<TrafficGroup> = listOf(TrafficGroup.App, TrafficGroup.Host, TrafficGroup.Policy)

/** The rows the card lists, largest first. */
const val TOP_TRAFFIC_ROWS = 5

/**
 * Home's last card: today's largest apps, hosts or policies from the
 * engine's traffic ledger, each with its share of the largest as a bar,
 * and how many there were in all. A tap on the card opens the Traffic page
 * in the card's grouping ([onOpen]); one on a row opens the ledger narrowed
 * to it ([onOpenRow]), and the picker only regroups the card. [state] is a
 * ledger read of today, grouped by one of [TOP_TRAFFIC_GROUPS].
 */
@Composable
fun TopTrafficCard(
    state: TrafficDetailsUiState,
    appIcon: suspend (packageName: String) -> Drawable?,
    onGroupChange: (TrafficGroup) -> Unit,
    onOpen: (TrafficGroup) -> Unit,
    onOpenRow: (TrafficGroup, TrafficRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.meow
    val report = state.report
    val rows = report?.rows.orEmpty()
    GlassCard(modifier = modifier.testTag("home_top_traffic"), onClick = { onOpen(state.by) }) {
        // The picker goes under the title when both do not fit on a line
        // (long labels, large text).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // The chevron marks the card as a way in; the tap is the card's.
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_top_traffic),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.mutedText,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.mutedText,
                )
            }
            GroupPicker(selected = state.by, onSelect = onGroupChange)
        }
        Spacer(Modifier.height(12.dp))
        when {
            report == null && state.error != null ->
                Hint(stringResource(R.string.traffic_details_error, state.error))
            rows.isEmpty() -> Hint(stringResource(R.string.traffic_details_empty))
            else -> {
                val largest = rows.first().total
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rows.take(TOP_TRAFFIC_ROWS).forEach { row ->
                        TopRow(
                            group = state.by,
                            row = row,
                            name = trafficRowName(state.by, row.name, state.appLabels),
                            largest = largest,
                            appIcon = appIcon,
                            // An empty name cannot narrow: the ledger reads
                            // an empty filter as no filter.
                            onClick = if (row.name.isNotEmpty()) {
                                { onOpenRow(state.by, row) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = colors.border, modifier = Modifier.padding(top = 12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.traffic_today),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedText,
                modifier = Modifier.weight(1f),
            )
            val plural = state.by.countPlural
            if (rows.isNotEmpty() && plural != null) {
                Text(
                    text = countLine(plural, rows.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.mutedText,
                    maxLines = 1,
                    modifier = Modifier.testTag("home_top_traffic_count"),
                )
            }
        }
    }
}

/**
 * A pill of the card's groupings, the selected one raised. Under the title
 * it can still be too wide (Russian at large text on a 360dp phone), so its
 * segments then narrow in proportion and their labels shrink to fit, as the
 * route mode's do, rather than the last one being cut off.
 */
@Composable
private fun GroupPicker(selected: TrafficGroup, onSelect: (TrafficGroup) -> Unit) {
    val colors = MaterialTheme.meow
    val labelStyle = MaterialTheme.typography.labelLarge
    FittingRow(
        modifier = Modifier
            .background(colors.canvas, CircleShape)
            .border(1.dp, colors.border.copy(alpha = 0.6f), CircleShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        TOP_TRAFFIC_GROUPS.forEach { group ->
            val isSelected = group == selected
            Box(
                modifier = Modifier
                    .then(if (isSelected) Modifier.shadow(1.dp, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.panel else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(group) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("home_top_traffic_by_${group.name}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(group.label),
                    style = labelStyle,
                    color = if (isSelected) colors.ink else colors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = labelStyle.fontSize),
                )
            }
        }
    }
}

/**
 * [content] side by side at its natural widths, or, when those do not fit,
 * at widths cut down in proportion.
 */
@Composable
private fun FittingRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val total = natural.sum()
        val scale = if (constraints.hasBoundedWidth && total > constraints.maxWidth) {
            constraints.maxWidth.toFloat() / total
        } else {
            1f
        }
        val placeables = measurables.mapIndexed { i, measurable ->
            measurable.measure(Constraints(maxWidth = (natural[i] * scale).toInt(), maxHeight = constraints.maxHeight))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(placeables.sumOf { it.width }, height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width
            }
        }
    }
}

@Composable
private fun TopRow(
    group: TrafficGroup,
    row: TrafficRow,
    name: String,
    largest: Long,
    appIcon: suspend (packageName: String) -> Drawable?,
    onClick: (() -> Unit)?,
) {
    val tint = ledgerTint(name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowIcon(group, row, name, appIcon)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = rowNameAlign(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = Formatters.bytes(row.total),
                    style = MaterialTheme.typography.bodyLarge.merge(MeowTextStyles.monoDigits),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            ShareBar(share(row.total, largest), tint)
        }
    }
}

private val ICON_SIZE = 40.dp
private val ICON_SHAPE = RoundedCornerShape(10.dp)

/** An app's icon, a globe for a bare address, otherwise the name's letter. */
@Composable
private fun RowIcon(
    group: TrafficGroup,
    row: TrafficRow,
    name: String,
    appIcon: suspend (packageName: String) -> Drawable?,
) {
    when {
        group == TrafficGroup.App && row.name.isNotEmpty() ->
            AppIcon(row.name, name, appIcon, size = ICON_SIZE, shape = ICON_SHAPE)
        group == TrafficGroup.Host && isAddress(row.name) -> {
            val muted = MaterialTheme.meow.mutedText
            Box(
                modifier = Modifier
                    .size(ICON_SIZE)
                    .background(muted.copy(alpha = 0.10f), ICON_SHAPE)
                    .border(1.dp, muted.copy(alpha = 0.25f), ICON_SHAPE),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Language, contentDescription = null, tint = muted, modifier = Modifier.size(22.dp))
            }
        }
        else -> Avatar(name, size = ICON_SIZE, shape = ICON_SHAPE)
    }
}

/** [fraction] of the width in [tint], over a faint track. */
@Composable
private fun ShareBar(fraction: Float, tint: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.meow.border.copy(alpha = 0.45f), CircleShape),
    ) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(tint, CircleShape))
        }
    }
}

@Composable
private fun Hint(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.meow.mutedText,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
}

/** "274 hosts", the number set apart. */
@Composable
private fun countLine(@PluralsRes plural: Int, count: Int): AnnotatedString {
    val number = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(count)
    val text = pluralStringResource(plural, count, number)
    val at = text.indexOf(number)
    val ink = MaterialTheme.meow.ink
    return buildAnnotatedString {
        append(text)
        if (at >= 0) addStyle(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold), at, at + number.length)
    }
}

/**
 * [total]'s share of [largest], the first row's, for its bar; a row with
 * any traffic keeps a sliver, so it does not read as none.
 */
internal fun share(total: Long, largest: Long): Float = when {
    total <= 0 || largest <= 0 -> 0f
    else -> (total.toFloat() / largest).coerceIn(MIN_SHARE, 1f)
}

private const val MIN_SHARE = 0.02f

/** A ledger host that is an IP address, or the bare addresses past a day's cap, rather than a domain. */
internal fun isAddress(host: String): Boolean =
    host == TrafficRow.OTHER_IPS ||
        host.contains(':') ||
        (host.isNotEmpty() && host.all { it.isDigit() || it == '.' })

private val TrafficGroup.label: Int
    get() = when (this) {
        TrafficGroup.Host -> R.string.traffic_by_host
        TrafficGroup.App -> R.string.traffic_by_app
        TrafficGroup.Policy -> R.string.traffic_by_policy
        TrafficGroup.Interface -> R.string.traffic_by_network
    }

/** How the footer counts the rows of this grouping; null for one the card does not offer. */
private val TrafficGroup.countPlural: Int?
    get() = when (this) {
        TrafficGroup.Host -> R.plurals.traffic_count_hosts
        TrafficGroup.App -> R.plurals.traffic_count_apps
        TrafficGroup.Policy -> R.plurals.traffic_count_policies
        TrafficGroup.Interface -> null
    }
