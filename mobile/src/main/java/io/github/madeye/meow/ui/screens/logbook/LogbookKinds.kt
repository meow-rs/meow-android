package io.github.madeye.meow.ui.screens.logbook

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.madeye.meow.R
import io.github.madeye.meow.logbook.LogbookLevel

/** How the page names and draws an event kind (`meow_common::logbook::kind`). */
data class LogbookKind(@StringRes val label: Int, val icon: ImageVector)

/**
 * The engine's event kinds. A kind is never renamed once released, so this
 * table only grows; a kind it lacks (a newer engine's) shows as its raw name
 * under its level's icon.
 */
object LogbookKinds {
    /** Every kind, in the filter menu's order: the engine, its config and sources, then groups and the network. */
    val all: Map<String, LogbookKind> = linkedMapOf(
        "engine.start" to LogbookKind(R.string.logbook_kind_engine_start, Icons.Filled.PlayCircle),
        "engine.stop" to LogbookKind(R.string.logbook_kind_engine_stop, Icons.Filled.StopCircle),
        "engine.unclean-exit" to LogbookKind(R.string.logbook_kind_engine_unclean_exit, Icons.Filled.Report),
        "config.reload" to LogbookKind(R.string.logbook_kind_config_reload, Icons.Filled.Sync),
        "config.change" to LogbookKind(R.string.logbook_kind_config_change, Icons.Filled.Tune),
        "subscription.update" to LogbookKind(R.string.logbook_kind_subscription_update, Icons.Filled.CloudSync),
        "proxy-provider.update" to LogbookKind(R.string.logbook_kind_proxy_provider_update, Icons.Filled.Hub),
        "rule-provider.update" to LogbookKind(R.string.logbook_kind_rule_provider_update, Icons.Filled.ViewList),
        "geodata.update" to LogbookKind(R.string.logbook_kind_geodata_update, Icons.Filled.Public),
        "group.health" to LogbookKind(R.string.logbook_kind_group_health, Icons.Filled.MonitorHeart),
        "group.switch" to LogbookKind(R.string.logbook_kind_group_switch, Icons.Filled.AltRoute),
        "network.interface" to LogbookKind(R.string.logbook_kind_network_interface, Icons.Filled.SettingsEthernet),
        "listener.start" to LogbookKind(R.string.logbook_kind_listener_start, Icons.Filled.Lan),
        "logbook.cleared" to LogbookKind(R.string.logbook_kind_logbook_cleared, Icons.Filled.DeleteSweep),
        "logbook.reset" to LogbookKind(R.string.logbook_kind_logbook_reset, Icons.Filled.RestartAlt),
    )

    fun of(kind: String): LogbookKind? = all[kind]

    /** The icon of a kind this table lacks. */
    fun fallbackIcon(level: LogbookLevel): ImageVector = when (level) {
        LogbookLevel.Info -> Icons.Filled.Info
        LogbookLevel.Warning -> Icons.Filled.Warning
        LogbookLevel.Error -> Icons.Filled.Error
    }
}
