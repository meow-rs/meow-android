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
 * Four tabs, laid out as on meow-ios: Home (the VPN switch over the
 * subscriptions), Proxy Groups, Utility — which pushes the monitoring screens
 * (Connections / DNS / Traffic / Logs) — and Settings. Rules is still pushed
 * from Settings.
 */
sealed interface Dest {
    @Serializable data object Home : Dest

    @Serializable data object ProxyGroups : Dest

    @Serializable data object Utility : Dest

    @Serializable data object Settings : Dest

    @Serializable data object Traffic : Dest

    @Serializable data object PerAppProxy : Dest

    @Serializable data class YamlEditor(val profileId: Long) : Dest

    @Serializable data object Connections : Dest

    @Serializable data object Rules : Dest

    @Serializable data object Logs : Dest

    @Serializable data object Dns : Dest
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
