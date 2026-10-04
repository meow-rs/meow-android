package io.github.madeye.meow.ui.screens.subscribe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.subscription.AutoUpdateSchedule
import io.github.madeye.meow.subscription.SubscriptionUserInfo
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.components.SectionHeader
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters

/**
 * The subscription list, as items under Home's cards: a section header, then
 * one card per profile, or an add prompt while there are none.
 */
fun LazyListScope.subscriptionItems(
    state: SubscribeUiState,
    onSelect: (Long) -> Unit,
    onEdit: (ProfileUi) -> Unit,
    onEditYaml: (Long) -> Unit,
    onExport: (ProfileUi) -> Unit,
    onRefresh: (Long) -> Unit,
    onShareQr: (ProfileUi) -> Unit,
    onDelete: (Long) -> Unit,
    onAddRequested: () -> Unit,
) {
    item {
        Spacer(Modifier.height(4.dp))
        SectionHeader(stringResource(R.string.subs_title))
    }
    if (state.profiles.isEmpty()) {
        item { EmptyState(onAdd = onAddRequested) }
    } else {
        items(state.profiles, key = { it.id }) { profile ->
            ProfileCard(
                profile = profile,
                onSelect = { onSelect(profile.id) },
                onEdit = { onEdit(profile) },
                onEditYaml = { onEditYaml(profile.id) },
                onExport = { onExport(profile) },
                onRefresh = { onRefresh(profile.id) },
                onShareQr = { onShareQr(profile) },
                onDelete = { onDelete(profile.id) },
            )
        }
    }
}

/**
 * Long operations (fetch, refresh-all, import) block interaction rather than
 * letting a second one start mid-flight.
 */
@Composable
fun SubscriptionBusyOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(modifier = Modifier.size(72.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    GlassCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.meow.mutedText.copy(alpha = 0.6f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.subs_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.meow.mutedText,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAdd) { Text(stringResource(R.string.subs_add)) }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileUi,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onEditYaml: () -> Unit,
    onExport: () -> Unit,
    onRefresh: () -> Unit,
    onShareQr: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val colors = MaterialTheme.meow

    GlassCard(onClick = onSelect) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (profile.selected) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (profile.selected) colors.accent else colors.mutedText.copy(alpha = 0.5f),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (profile.url.isNotEmpty()) {
                    Text(
                        text = profile.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (profile.userInfo.hasQuota || profile.userInfo.hasExpiry) {
                    PlanUsage(profile.userInfo)
                }
                if (profile.lastUpdated > 0) {
                    Text(
                        text = stringResource(
                            R.string.subs_last_updated,
                            Formatters.timestamp(profile.lastUpdated),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedText,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = colors.mutedText)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_select)) },
                        onClick = { menuOpen = false; onSelect() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    if (profile.hasYaml) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.subs_edit_yaml)) },
                            onClick = { menuOpen = false; onEditYaml() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.subs_export)) },
                            onClick = { menuOpen = false; onExport() },
                        )
                    }
                    if (profile.url.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_refresh)) },
                            onClick = { menuOpen = false; onRefresh() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.subs_share_qr)) },
                            onClick = { menuOpen = false; onShareQr() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete)) },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.subs_delete_confirm, profile.name)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * The provider's `subscription-userinfo` figures: used of total over a thin
 * bar, then the expiry date. Each part renders only when the provider reported
 * it, so a plain config URL keeps the compact card.
 */
@Composable
private fun PlanUsage(info: SubscriptionUserInfo) {
    val colors = MaterialTheme.meow
    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (info.hasQuota) {
            val exhausted = info.used >= info.total
            Text(
                text = stringResource(
                    R.string.subs_usage,
                    Formatters.bytes(info.used),
                    Formatters.bytes(info.total),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (exhausted) colors.danger else colors.mutedText,
            )
            UsageBar(
                fraction = info.usedFraction(),
                color = if (exhausted) colors.danger else colors.accent,
            )
        }
        // Blank when the provider sent a date java.time can't place; skip the
        // row rather than render "Expires " with nothing after it.
        val expiry = Formatters.date(info.expire)
        if (expiry.isNotEmpty()) {
            // Read at composition rather than ticking: the list recomposes on
            // every profile emission and tab revisit, which is fresh enough
            // for a day-granular date.
            val expired = info.isExpired(System.currentTimeMillis() / 1000)
            Text(
                text = stringResource(
                    if (expired) R.string.subs_expired else R.string.subs_expires,
                    expiry,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (expired) colors.danger else colors.mutedText,
                fontWeight = if (expired) FontWeight.SemiBold else null,
            )
        }
    }
}

/** Hand-drawn rather than LinearProgressIndicator, whose M3 gap and stop dot crowd a 4 dp bar. */
@Composable
private fun UsageBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(color.copy(alpha = 0.16f), CircleShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(color, CircleShape),
        )
    }
}

/** Add/edit dialog. [initial] non-null means edit. */
@Composable
fun SubscriptionDialog(
    initial: ProfileUi?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, autoUpdate: Boolean, intervalHours: Int) -> Unit,
    clipboardText: () -> String?,
    onClipboardEmpty: () -> Unit,
) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var url by rememberSaveable(initial?.id) { mutableStateOf(initial?.url.orEmpty()) }
    val storedHours = initial?.updateIntervalHours ?: AutoUpdateSchedule.DEFAULT_INTERVAL_HOURS
    var autoUpdate by rememberSaveable(initial?.id) { mutableStateOf(initial?.autoUpdate ?: true) }
    var intervalText by rememberSaveable(initial?.id) { mutableStateOf(storedHours.toString()) }
    val intervalHours = AutoUpdateSchedule.parseIntervalHours(intervalText)
    val now = remember { System.currentTimeMillis() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (initial == null) R.string.subs_add else R.string.subs_edit),
            )
        },
        text = {
            // Scrolls so the schedule fields stay reachable above the keyboard.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.subs_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.subs_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val text = clipboardText()
                                if (text.isNullOrBlank()) onClipboardEmpty() else url = text
                            },
                        ) {
                            Icon(
                                Icons.Filled.ContentPaste,
                                contentDescription = stringResource(R.string.subs_paste),
                            )
                        }
                    },
                )
                // A file import has no URL to refresh from, so editing one
                // shows no schedule until a URL is entered.
                if (initial == null || url.isNotBlank()) {
                    // Saving a changed URL re-downloads, which restarts the
                    // schedule, so the preview only holds for the same URL.
                    val hint = initial?.takeIf { it.url == url.trim() }
                        ?.let { scheduleHint(it, intervalHours, now) }
                    Spacer(Modifier.height(12.dp))
                    AutoUpdateFields(
                        autoUpdate = autoUpdate,
                        onAutoUpdateChange = { autoUpdate = it },
                        intervalText = intervalText,
                        onIntervalChange = { intervalText = it },
                        intervalValid = intervalHours != null,
                        hint = hint,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name.trim(),
                        url.trim(),
                        autoUpdate,
                        // Switched off with a half-typed interval: keep the stored one.
                        intervalHours ?: storedHours,
                    )
                },
                enabled = url.isNotBlank() && (intervalHours != null || !autoUpdate),
            ) {
                Text(stringResource(if (initial == null) R.string.common_add else R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun AutoUpdateFields(
    autoUpdate: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    intervalText: String,
    onIntervalChange: (String) -> Unit,
    intervalValid: Boolean,
    hint: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = autoUpdate, role = Role.Switch, onValueChange = onAutoUpdateChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.subs_auto_update),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // The whole row is the toggle target; a clickable switch inside it
        // would be a second, smaller one.
        Switch(checked = autoUpdate, onCheckedChange = null)
    }
    if (autoUpdate) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = intervalText,
            onValueChange = { text -> onIntervalChange(text.filter(Char::isDigit)) },
            label = { Text(stringResource(R.string.subs_update_interval)) },
            singleLine = true,
            isError = !intervalValid,
            supportingText = when {
                !intervalValid -> {
                    {
                        Text(
                            stringResource(
                                R.string.subs_update_interval_range,
                                AutoUpdateSchedule.MIN_INTERVAL_HOURS,
                                AutoUpdateSchedule.MAX_INTERVAL_HOURS,
                            ),
                        )
                    }
                }
                hint != null -> {
                    { Text(hint) }
                }
                else -> null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * When the worker will pick this profile up with [intervalHours]. The worker
 * runs hourly and Doze can defer it, hence "after" rather than "at".
 */
@Composable
private fun scheduleHint(profile: ProfileUi, intervalHours: Int?, now: Long): String? {
    if (intervalHours == null) return null
    if (profile.hasLocalEdits) return stringResource(R.string.subs_auto_update_paused)
    val dueAt = AutoUpdateSchedule.dueAt(profile.lastUpdated, intervalHours, now)
    return if (dueAt <= now) {
        stringResource(R.string.subs_next_update_due)
    } else {
        stringResource(R.string.subs_next_update, Formatters.timestamp(dueAt))
    }
}
