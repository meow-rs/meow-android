package io.github.madeye.meow.ui.screens.subscribe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.subscription.AutoUpdateSchedule
import io.github.madeye.meow.subscription.SubscriptionUserInfo
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.components.NavRow
import io.github.madeye.meow.ui.components.SectionHeader
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.Formatters

/**
 * The Subscriptions page, pushed from Home's app bar and laid out like
 * Surge's profile sheet: the profiles, where a tap picks the one the VPN
 * runs; every action on the picked one; then the ways to add another. All of
 * them are rows, so nothing hides behind a menu.
 */
@Composable
fun SubscriptionsScreen(
    state: SubscribeUiState,
    contentPadding: PaddingValues,
    onSelect: (Long) -> Unit,
    onEdit: (ProfileUi) -> Unit,
    onEditYaml: (Long) -> Unit,
    onDuplicate: (id: Long, name: String) -> Unit,
    onRefresh: (Long) -> Unit,
    onUpdateRuleSets: () -> Unit,
    onExport: (ProfileUi) -> Unit,
    onShareQr: (ProfileUi) -> Unit,
    onDelete: (Long) -> Unit,
    onAddFromUrl: () -> Unit,
    onScanQr: () -> Unit,
    onImportFromFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.profiles.firstOrNull { it.selected }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            )
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(stringResource(R.string.subs_title))
        ProfileList(profiles = state.profiles, onSelect = onSelect)

        // The actions follow the check in the list above, so they never need
        // to say which profile they act on. Nothing picked, nothing to act on.
        if (selected != null) {
            val nameOfCopy = copyName(stringResource(R.string.subs_copy_name, selected.name))
            Spacer(Modifier.height(8.dp))
            SectionHeader(stringResource(R.string.common_edit))
            ProfileActions(
                profile = selected,
                ruleSets = state.ruleSets,
                onEdit = { onEdit(selected) },
                onEditYaml = { onEditYaml(selected.id) },
                onDuplicate = { onDuplicate(selected.id, nameOfCopy) },
                onRefresh = { onRefresh(selected.id) },
                onUpdateRuleSets = onUpdateRuleSets,
                onExport = { onExport(selected) },
                onShareQr = { onShareQr(selected) },
                onDelete = { onDelete(selected.id) },
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader(stringResource(R.string.common_add))
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            NavRow(
                title = stringResource(R.string.subs_add_from_url),
                icon = Icons.Filled.Link,
                onClick = onAddFromUrl,
            )
            HorizontalDivider(color = MaterialTheme.meow.border)
            NavRow(
                title = stringResource(R.string.subs_scan_qr),
                icon = Icons.Filled.QrCodeScanner,
                onClick = onScanQr,
            )
            HorizontalDivider(color = MaterialTheme.meow.border)
            NavRow(
                title = stringResource(R.string.subs_import_from_file),
                icon = Icons.Filled.FileOpen,
                onClick = onImportFromFile,
            )
        }
    }
}

/**
 * Long operations (fetch, refresh, import) block interaction rather than
 * letting a second one start mid-flight.
 */
@Composable
fun SubscriptionBusyOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // No card padding: the indicator centres in the full 72dp, not in
        // the shrunken content area.
        GlassCard(
            modifier = Modifier.size(72.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** One card, one row per profile; only one can be picked, so they read as radio buttons. */
@Composable
private fun ProfileList(profiles: List<ProfileUi>, onSelect: (Long) -> Unit) {
    GlassCard(
        modifier = Modifier.selectableGroup(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.subs_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.meow.mutedText,
                )
            }
        }
        profiles.forEachIndexed { index, profile ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.meow.border)
            key(profile.id) {
                ProfileRow(profile = profile, onSelect = { onSelect(profile.id) })
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: ProfileUi, onSelect: () -> Unit) {
    val colors = MaterialTheme.meow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = profile.selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        // The row's selectable carries the state for accessibility services.
        if (profile.selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent)
        }
    }
}

/**
 * Every action on [profile], the selected one, each a row of its own. A row
 * only shows when it can work: a file import has no URL to refresh from or
 * share, and a profile without YAML has nothing to open, copy or export.
 *
 * A subscription's config is read-only, as on Surge: every download replaces
 * it, so its YAML row only views it, and editing starts from a copy, which is
 * a local profile.
 *
 * "Update rule sets" shows for a config with `rule-providers:` and works on
 * the running engine; see [RuleSetsRow].
 */
@Composable
private fun ProfileActions(
    profile: ProfileUi,
    ruleSets: RuleSetsUi,
    onEdit: () -> Unit,
    onEditYaml: () -> Unit,
    onDuplicate: () -> Unit,
    onRefresh: () -> Unit,
    onUpdateRuleSets: () -> Unit,
    onExport: () -> Unit,
    onShareQr: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val border = MaterialTheme.meow.border

    GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        NavRow(
            title = stringResource(R.string.subs_edit),
            icon = Icons.Filled.Edit,
            onClick = onEdit,
        )
        if (profile.hasYaml) {
            val subscription = profile.url.isNotEmpty()
            HorizontalDivider(color = border)
            // Both open the editor, which decides for itself whether the
            // config is read-only.
            NavRow(
                title = stringResource(
                    if (subscription) R.string.subs_view_yaml else R.string.subs_edit_yaml,
                ),
                icon = if (subscription) Icons.Filled.Visibility else Icons.Filled.EditNote,
                onClick = onEditYaml,
            )
            HorizontalDivider(color = border)
            NavRow(
                title = stringResource(R.string.subs_duplicate),
                icon = Icons.Filled.ContentCopy,
                onClick = onDuplicate,
            )
        }
        if (profile.url.isNotEmpty()) {
            HorizontalDivider(color = border)
            NavRow(
                title = stringResource(R.string.common_refresh),
                icon = Icons.Filled.Refresh,
                onClick = onRefresh,
            )
        }
        if (profile.declaresRuleSets && !ruleSets.nothingToUpdate) {
            HorizontalDivider(color = border)
            RuleSetsRow(state = ruleSets, onClick = onUpdateRuleSets)
        }
        if (profile.hasYaml) {
            HorizontalDivider(color = border)
            NavRow(
                title = stringResource(R.string.subs_export),
                icon = Icons.Filled.SaveAlt,
                onClick = onExport,
            )
        }
        if (profile.url.isNotEmpty()) {
            HorizontalDivider(color = border)
            NavRow(
                title = stringResource(R.string.subs_share_qr),
                icon = Icons.Filled.QrCode2,
                onClick = onShareQr,
            )
        }
        HorizontalDivider(color = border)
        NavRow(
            title = stringResource(R.string.common_delete),
            icon = Icons.Filled.Delete,
            onClick = { confirmDelete = true },
            destructive = true,
        )
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
 * "Update rule sets". Disabled while the VPN is down, as Home's Rules row is:
 * rule sets are the running engine's, and only it can download them. While
 * up, the subtitle counts the HTTP rule sets and gives the oldest of their
 * update times, so every set is at least that fresh; a listing that failed
 * leaves the row usable without it. During an update the row is disabled
 * and shows its progress in place of the chevron.
 */
@Composable
private fun RuleSetsRow(state: RuleSetsUi, onClick: () -> Unit) {
    val summary = state.summary
    val subtitle = when {
        !state.connected -> stringResource(R.string.settings_engine_offline)
        summary == null -> null
        summary.oldestUpdateMillis == null ->
            pluralStringResource(R.plurals.subs_rule_sets_count, summary.count, summary.count)
        else -> pluralStringResource(
            R.plurals.subs_rule_sets_count_updated,
            summary.count,
            summary.count,
            Formatters.timestamp(summary.oldestUpdateMillis),
        )
    }
    NavRow(
        title = stringResource(R.string.subs_update_rule_sets),
        icon = Icons.Filled.CloudDownload,
        onClick = onClick,
        enabled = state.connected && !state.updating,
        subtitle = subtitle,
        trailing = if (state.updating) {
            { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
        } else {
            null
        },
    )
}

/**
 * The provider's `subscription-userinfo` figures: used of total over a thin
 * bar, then the expiry date. Each part renders only when the provider reported
 * it, so a plain config URL keeps the compact row.
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
            // every profile emission and page visit, which is fresh enough
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

/**
 * Add/edit dialog. [initial] non-null means edit; a local profile's edit
 * shows the name alone.
 */
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
    // A local profile (a file import or a copy) only gets a new name here. A
    // URL would turn it into a subscription, whose next download would
    // overwrite its config, edits included.
    val local = initial != null && initial.url.isEmpty()

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
                if (!local) {
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
                }
                // Without a URL there is nothing to refresh from, so no
                // schedule: never for a local profile, and not while a
                // subscription's URL is cleared.
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
                // A local profile has no schedule fields and sends its stored
                // schedule back as it was, so only the name has to be valid.
                enabled = if (local) {
                    name.isNotBlank()
                } else {
                    url.isNotBlank() && (intervalHours != null || !autoUpdate)
                },
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
