package io.github.madeye.meow.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.BuildConfig
import io.github.madeye.meow.R
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.components.NavRow
import io.github.madeye.meow.ui.components.SectionHeader
import io.github.madeye.meow.ui.theme.MeowTextStyles
import io.github.madeye.meow.ui.theme.meow

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onPerAppProxy: () -> Unit,
    onShowExitIpChange: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unavailable = stringResource(R.string.settings_version_unavailable)

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
        SectionHeader(stringResource(R.string.settings_general))
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            InfoRow(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_version),
                value = state.appVersion.ifEmpty { unavailable },
            )
            HorizontalDivider(color = MaterialTheme.meow.border)
            InfoRow(
                icon = Icons.Filled.Memory,
                title = stringResource(R.string.settings_engine),
                value = state.engineVersion.ifEmpty { unavailable },
            )
            HorizontalDivider(color = MaterialTheme.meow.border)
            // The subtitle names where a tap goes: the GitHub check sends the
            // user's address to GitHub.
            NavRow(
                title = stringResource(R.string.settings_check_updates),
                icon = Icons.Filled.SystemUpdate,
                onClick = onCheckForUpdates,
                subtitle = stringResource(
                    if (BuildConfig.PLAY_STORE) R.string.settings_updates_play else R.string.settings_updates_github,
                ),
                trailing = if (state.checkingForUpdate) {
                    { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader(stringResource(R.string.settings_network))
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            NavRow(
                title = stringResource(R.string.perapp_title),
                icon = Icons.Filled.Apps,
                onClick = onPerAppProxy,
                subtitle = stringResource(R.string.perapp_desc),
            )
            HorizontalDivider(color = MaterialTheme.meow.border)
            InfoRow(
                icon = Icons.Filled.Dns,
                title = stringResource(R.string.settings_dns_server),
                value = stringResource(R.string.settings_dns_builtin),
            )
            HorizontalDivider(color = MaterialTheme.meow.border)
            // The subtitle names the services: every check sends them the
            // user's address.
            SwitchRow(
                icon = Icons.Filled.Public,
                title = stringResource(R.string.settings_show_exit_ip),
                subtitle = stringResource(R.string.settings_show_exit_ip_desc),
                checked = state.showExitIp,
                onCheckedChange = onShowExitIpChange,
                modifier = Modifier.testTag("settings_show_exit_ip"),
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader(stringResource(R.string.settings_about))
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            InfoRow(
                icon = Icons.Filled.Code,
                title = stringResource(R.string.settings_source_code),
                value = stringResource(R.string.settings_source_url),
            )
        }
    }
}

/** [NavRow]'s rhythm with a switch in place of the chevron; the whole row toggles. */
@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.meow
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(colors.accent.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
        }
        // Null: the row's toggleable owns the click and the semantics.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Non-interactive counterpart to [NavRow]: same rhythm, no chevron, no dimming. */
@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
) {
    val colors = MaterialTheme.meow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(colors.accent.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.merge(MeowTextStyles.monoDigits),
            color = colors.mutedText,
            textAlign = TextAlign.End,
        )
    }
}
