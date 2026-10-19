package io.github.madeye.meow.ui.screens.logbook

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.logbook.LogbookEntry
import io.github.madeye.meow.logbook.LogbookLevel
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.components.SectionHeader
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonPrimitive

/** The filters, lowest level shown: everything, warnings and errors, errors. */
private val FILTERS = listOf(null, LogbookLevel.Warning, LogbookLevel.Error)

@Composable
fun LogbookScreen(
    state: LogbookUiState,
    contentPadding: PaddingValues,
    onLevelChange: (LogbookLevel?) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("logbook_filter"),
        ) {
            FILTERS.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = state.level == level,
                    onClick = { onLevelChange(level) },
                    shape = SegmentedButtonDefaults.itemShape(index, FILTERS.size),
                ) {
                    Text(
                        stringResource(
                            when (level) {
                                null -> R.string.logbook_level_all
                                LogbookLevel.Warning -> R.string.logbook_level_warning
                                else -> R.string.logbook_level_error
                            },
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (!state.loaded) return@Column
        if (state.entries.isEmpty()) {
            Empty(state.error, Modifier.padding(bottom = contentPadding.calculateBottomPadding()))
            return@Column
        }

        val zone = remember { ZoneId.systemDefault() }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("logbook_list"),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A day header above the first entry of each day.
            var day: LocalDate? = null
            for (entry in state.entries) {
                val millis = epochMillis(entry.time)
                val entryDay = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                if (entryDay != day) {
                    day = entryDay
                    item(key = "day-$entryDay", contentType = "day") { DayHeader(millis) }
                }
                item(key = entry.id, contentType = "entry") { EntryCard(entry, millis) }
            }
            if (state.more) {
                item(key = "more", contentType = "more") {
                    LaunchedEffect(state.entries.size) { onLoadMore() }
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(error: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp).testTag("logbook_empty"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (error == null) {
                    stringResource(R.string.logbook_empty)
                } else {
                    stringResource(R.string.logbook_read_failed, error)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.meow.mutedText,
                textAlign = TextAlign.Center,
            )
            if (error == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.logbook_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.meow.mutedText,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** e.g. "Sun, Oct 19", in the UI's language. */
@Composable
private fun DayHeader(epochMillis: Long) {
    val context = LocalContext.current
    val label = remember(epochMillis) {
        DateUtils.formatDateTime(
            context,
            epochMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
        )
    }
    SectionHeader(label, Modifier.padding(top = 8.dp))
}

/** One entry; a tap shows its times in full and the details the engine recorded. */
@Composable
private fun EntryCard(entry: LogbookEntry, epochMillis: Long) {
    val colors = MaterialTheme.meow
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val (icon, tint) = levelIcon(entry.level)

    GlassCard(
        modifier = Modifier.fillMaxWidth().testTag("logbook_entry"),
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = entry.level.wire, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // "group.switch · Auto": what kind of event, and what about.
                    Text(
                        text = listOf(entry.kind, entry.subject).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = buildString {
                            if (entry.count > 1) append("×${entry.count}  ")
                            append(Formatters.clockTime(epochMillis))
                        },
                        style = MaterialTheme.typography.labelMedium.merge(MeowTextStyles.monoDigits),
                        color = colors.mutedText,
                    )
                }
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(visible = expanded) {
                    Details(entry)
                }
            }
        }
    }
}

@Composable
private fun Details(entry: LogbookEntry) {
    val muted = MaterialTheme.meow.mutedText
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${stringResource(R.string.logbook_first)}  ${Formatters.dateTime(entry.time)}", style = style, color = muted)
        entry.last?.let { last ->
            Text("${stringResource(R.string.logbook_last)}  ${Formatters.dateTime(last)}", style = style, color = muted)
        }
        for ((key, value) in entry.fields) {
            val text = (value as? JsonPrimitive)?.content ?: value.toString()
            Text("$key: $text", style = style, color = muted)
        }
    }
}

@Composable
private fun levelIcon(level: LogbookLevel): Pair<ImageVector, Color> {
    val colors = MaterialTheme.meow
    return when (level) {
        LogbookLevel.Info -> Icons.Filled.Info to colors.accent
        LogbookLevel.Warning -> Icons.Filled.Warning to colors.warning
        LogbookLevel.Error -> Icons.Filled.Error to colors.danger
    }
}

private fun epochMillis(iso: String): Long =
    runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
