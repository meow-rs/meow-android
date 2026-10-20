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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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

@Composable
fun LogbookScreen(
    state: LogbookUiState,
    contentPadding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.logbook_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("logbook_search"),
        )
        Spacer(Modifier.height(8.dp))

        if (!state.loaded) return@Column
        if (state.entries.isEmpty()) {
            Empty(
                error = state.error,
                search = state.search,
                kind = state.kind,
                modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
            )
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
            // A day header above the first entry of each day, keyed by that
            // entry: a clock set back can bring a day round twice.
            var day: LocalDate? = null
            for (entry in state.entries) {
                val millis = epochMillis(entry.time)
                val entryDay = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                if (entryDay != day) {
                    day = entryDay
                    item(key = "day-${entry.id}", contentType = "day") { DayHeader(millis) }
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

/** The top bar's event filter: every event, or one kind of it. */
@Composable
fun LogbookActions(kind: String?, onKindChange: (String?) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("logbook_filter")) {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = stringResource(R.string.logbook_filter),
                // Lit while an event is picked, so one event's entries do not pass for the whole logbook.
                tint = if (kind == null) LocalContentColor.current else MaterialTheme.meow.accent,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            FilterItem(stringResource(R.string.logbook_filter_all), Icons.Filled.Book, selected = kind == null) {
                menuOpen = false
                onKindChange(null)
            }
            for ((name, event) in LogbookKinds.all) {
                FilterItem(stringResource(event.label), event.icon, selected = kind == name) {
                    menuOpen = false
                    onKindChange(name)
                }
            }
        }
    }
}

@Composable
private fun FilterItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
        modifier = Modifier
            .semantics { this.selected = selected }
            .testTag("logbook_filter_item"),
    )
}

/** The name of [kind], or the kind itself when this build does not know it. */
@Composable
private fun kindName(kind: String): String = LogbookKinds.of(kind)?.let { stringResource(it.label) } ?: kind

@Composable
private fun Empty(error: String?, search: String, kind: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp).testTag("logbook_empty"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    error != null -> stringResource(R.string.logbook_read_failed, error)
                    search.isNotEmpty() -> stringResource(R.string.logbook_search_empty, search)
                    kind != null -> stringResource(R.string.logbook_filter_empty, kindName(kind))
                    else -> stringResource(R.string.logbook_empty)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.meow.mutedText,
                textAlign = TextAlign.Center,
            )
            if (error == null && search.isEmpty() && kind == null) {
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

/**
 * One entry, drawn as its kind of event and tinted by how it went; a tap
 * shows its times in full and the details the engine recorded.
 */
@Composable
private fun EntryCard(entry: LogbookEntry, epochMillis: Long) {
    val colors = MaterialTheme.meow
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val kind = LogbookKinds.of(entry.kind)
    val label = kindName(entry.kind)
    val tint = when (entry.level) {
        LogbookLevel.Info -> colors.accent
        LogbookLevel.Warning -> colors.warning
        LogbookLevel.Error -> colors.danger
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth().testTag("logbook_entry"),
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = kind?.icon ?: LogbookKinds.fallbackIcon(entry.level),
                // The label next to it already names the event.
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // "Dialer Proxy Switch · US Home": what happened, and to what.
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold)) { append(label) }
                            if (entry.subject.isNotEmpty()) append(" · ${entry.subject}")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("logbook_kind"),
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

private fun epochMillis(iso: String): Long =
    runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
