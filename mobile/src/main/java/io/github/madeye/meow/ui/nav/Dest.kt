package io.github.madeye.meow.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import io.github.madeye.meow.R
import kotlinx.serialization.Serializable

/**
 * Navigation destinations.
 *
 * Four tabs, laid out as on meow-ios: Home (the VPN switch), which pushes
 * Subscriptions — and from there the YAML editor — and Rules; Proxy Groups,
 * which pushes a node's detail page; Utility, which pushes the monitoring screens (Connections / DNS / Traffic /
 * Logs), where a connection pushes its detail page, which pushes Rules at
 * the rule it matched; and Settings, which pushes Per-App Proxy.
 */
sealed interface Dest {
    @Serializable data object Home : Dest

    @Serializable data object ProxyGroups : Dest

    @Serializable data object Utility : Dest

    @Serializable data object Settings : Dest

    @Serializable data object Subscriptions : Dest

    @Serializable data object Traffic : Dest

    @Serializable data object PerAppProxy : Dest

    @Serializable data class YamlEditor(val profileId: Long) : Dest

    @Serializable data object Connections : Dest

    /** [id]: the engine's connection id. */
    @Serializable data class ConnectionDetail(val id: String) : Dest

    /** [focus]: a rule to scroll to and mark, by its place in the engine's list; -1 for none. */
    @Serializable data class Rules(val focus: Int = -1) : Dest

    @Serializable data object Logs : Dest

    @Serializable data object Dns : Dest

    @Serializable data object Statistics : Dest

    @Serializable data object Logbook : Dest

    /** [testUrl]: the health-check URL of the group the node was opened from, if it has one. */
    @Serializable data class ProxyDetail(val name: String, val testUrl: String? = null) : Dest
}

data class TabDestination(
    val dest: Dest,
    @StringRes val label: Int,
    val icon: ImageVector,
    val testTag: String,
)

val TABS = listOf(
    TabDestination(Dest.Home, R.string.tab_home, Icons.Filled.Home, "tab_home"),
    TabDestination(Dest.ProxyGroups, R.string.tab_proxy_groups, Icons.Filled.Layers, "tab_proxy_groups"),
    TabDestination(Dest.Utility, R.string.tab_utility, Icons.Filled.Handyman, "tab_utility"),
    TabDestination(Dest.Settings, R.string.tab_settings, Icons.Filled.Settings, "tab_settings"),
)
