package io.github.madeye.meow.ui.screens.utility

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.madeye.meow.R
import io.github.madeye.meow.ui.components.GlassCard
import io.github.madeye.meow.ui.components.NavRow
import io.github.madeye.meow.ui.theme.meow

/**
 * The Utility tab, modelled on meow-ios's: one card of entry points to the
 * monitoring screens.
 *
 * Traffic reads the app's own history, so it is always open. Connections and
 * Logs read the engine's controller API, so — as when they lived in Settings —
 * they are only reachable while it is listening.
 */
@Composable
fun UtilityScreen(
    engineOnline: Boolean,
    contentPadding: PaddingValues,
    onTraffic: () -> Unit,
    onConnections: () -> Unit,
    onLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offlineHint = stringResource(R.string.settings_engine_offline)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            )
            .padding(horizontal = 16.dp),
    ) {
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            NavRow(
                title = stringResource(R.string.traffic_title),
                icon = Icons.AutoMirrored.Filled.ShowChart,
                onClick = onTraffic,
                modifier = Modifier.testTag("utility_traffic"),
            )
            HorizontalDivider(color = MaterialTheme.meow.border)
            NavRow(
                title = stringResource(R.string.connections_title),
                icon = Icons.Filled.SwapHoriz,
                onClick = onConnections,
                modifier = Modifier.testTag("utility_connections"),
                enabled = engineOnline,
                subtitle = offlineHint.takeUnless { engineOnline },
            )
            HorizontalDivider(color = MaterialTheme.meow.border)
            NavRow(
                title = stringResource(R.string.logs_title),
                icon = Icons.AutoMirrored.Filled.Article,
                onClick = onLogs,
                modifier = Modifier.testTag("utility_logs"),
                enabled = engineOnline,
                subtitle = offlineHint.takeUnless { engineOnline },
            )
        }
    }
}
