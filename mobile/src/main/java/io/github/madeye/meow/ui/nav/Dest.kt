package io.github.madeye.meow.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import io.github.madeye.meow.R
import kotlinx.serialization.Serializable

/**
 * Navigation destinations.
 *
 * Four tabs. The third, Utility, follows meow-ios's Utility tab: it pushes
 * the monitoring screens (Traffic / Connections / Logs). Rules is still
 * pushed from Settings.
 */
sealed interface Dest {
    @Serializable data object Home : Dest

    @Serializable data object Subscribe : Dest

    @Serializable data object Utility : Dest

    @Serializable data object Settings : Dest

    @Serializable data object Traffic : Dest

    @Serializable data object PerAppProxy : Dest

    @Serializable data class YamlEditor(val profileId: Long) : Dest

    @Serializable data object Connections : Dest

    @Serializable data object Rules : Dest

    @Serializable data object Logs : Dest
}

data class TabDestination(
    val dest: Dest,
    @StringRes val label: Int,
    val icon: ImageVector,
    val testTag: String,
)

val TABS = listOf(
    TabDestination(Dest.Home, R.string.tab_home, Icons.Filled.Home, "tab_home"),
    TabDestination(Dest.Subscribe, R.string.tab_subscribe, Icons.Filled.Dns, "tab_subscribe"),
    TabDestination(Dest.Utility, R.string.tab_utility, Icons.Filled.Handyman, "tab_utility"),
    TabDestination(Dest.Settings, R.string.tab_settings, Icons.Filled.Settings, "tab_settings"),
)
