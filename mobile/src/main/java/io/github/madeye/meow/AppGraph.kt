package io.github.madeye.meow

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.net.ExitIpLookup
import io.github.madeye.meow.preference.ExitIpPreference
import io.github.madeye.meow.preference.RouteModeStore
import io.github.madeye.meow.repo.ConfigValidator
import io.github.madeye.meow.repo.InstalledAppsRepository
import io.github.madeye.meow.repo.PerAppRepository
import io.github.madeye.meow.repo.ProfileRepository
import io.github.madeye.meow.repo.TrafficHistoryRepository
import io.github.madeye.meow.ui.screens.connections.ConnectionsViewModel
import io.github.madeye.meow.ui.screens.connections.RecentConnectionsStore
import io.github.madeye.meow.ui.screens.dns.DnsViewModel
import io.github.madeye.meow.ui.screens.home.ExitIpViewModel
import io.github.madeye.meow.ui.screens.home.HomeViewModel
import io.github.madeye.meow.ui.screens.home.RouteChanges
import io.github.madeye.meow.ui.screens.home.defaultNetworkIsVpn
import io.github.madeye.meow.ui.screens.logs.LogsViewModel
import io.github.madeye.meow.ui.screens.perapp.PerAppProxyViewModel
import io.github.madeye.meow.ui.screens.proxies.ProxyGroupsViewModel
import io.github.madeye.meow.ui.screens.rules.RulesViewModel
import io.github.madeye.meow.ui.screens.settings.SettingsViewModel
import io.github.madeye.meow.ui.screens.subscribe.SubscribeViewModel
import io.github.madeye.meow.ui.screens.traffic.TrafficViewModel
import io.github.madeye.meow.ui.screens.yaml.YamlEditorViewModel
import io.github.madeye.meow.ui.util.AppVersions
import io.github.madeye.meow.vpn.ConfigReloader
import io.github.madeye.meow.vpn.SpeedSampleStore
import io.github.madeye.meow.vpn.VpnStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-written dependency graph.
 *
 * Nine ViewModels over a handful of singletons does not justify Hilt's
 * annotation processing round; this is ~40 lines and reads top to bottom.
 */
object AppGraph {

    private lateinit var appContext: Context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val api: MeowApi by lazy { MeowApi() }
    val vpn: VpnStateRepository by lazy { VpnStateRepository() }
    val speedSamples: SpeedSampleStore by lazy { SpeedSampleStore(vpn, scope) }

    /** Survives leaving the Connections screen; see [RecentConnectionsStore]. */
    val recentConnections: RecentConnectionsStore by lazy { RecentConnectionsStore() }
    val profiles: ProfileRepository by lazy { ProfileRepository() }
    val trafficHistory: TrafficHistoryRepository by lazy { TrafficHistoryRepository() }
    val perApp: PerAppRepository by lazy { PerAppRepository() }
    val installedApps: InstalledAppsRepository by lazy { InstalledAppsRepository(appContext) }
    val configValidator: ConfigValidator by lazy { ConfigValidator() }
    val analytics: Analytics by lazy { Analytics(appContext) }
    val appVersions: AppVersions by lazy { AppVersions(appContext) }
    val routeModes: RouteModeStore get() = RouteModeStore.default
    val configReloader: ConfigReloader get() = ConfigReloader.default

    /** Settings flips it; Home's exit-IP card follows. */
    val showExitIp: ExitIpPreference by lazy { ExitIpPreference(scope) }
    val exitIpLookup: ExitIpLookup by lazy { ExitIpLookup() }

    /** Node and route-mode switches, for the exit-IP card; see [RouteChanges]. */
    val routeChanges: RouteChanges by lazy { RouteChanges() }

    fun init(context: Context) {
        appContext = context.applicationContext
        // Touching this here starts the speed-sample collector, so the chart has
        // history even if the user never opens the Traffic screen before connecting.
        speedSamples
    }

    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            when (modelClass) {
                HomeViewModel::class.java ->
                    HomeViewModel(vpn, profiles, api, analytics, routeModes, routeChanges)

                ExitIpViewModel::class.java -> ExitIpViewModel(
                    lookup = exitIpLookup::lookup,
                    vpnState = vpn.state,
                    enabled = showExitIp.enabled,
                    routedViaVpn = appContext::defaultNetworkIsVpn,
                    routeChanges = routeChanges.events,
                )

                ProxyGroupsViewModel::class.java ->
                    ProxyGroupsViewModel(vpn, profiles, api, analytics, routeChanges)

                SubscribeViewModel::class.java ->
                    SubscribeViewModel(profiles, configValidator, analytics)

                TrafficViewModel::class.java ->
                    TrafficViewModel(vpn, trafficHistory, speedSamples)

                SettingsViewModel::class.java ->
                    SettingsViewModel(appVersions, vpn, showExitIp)

                PerAppProxyViewModel::class.java ->
                    PerAppProxyViewModel(perApp, installedApps, analytics)

                YamlEditorViewModel::class.java ->
                    YamlEditorViewModel(extras.createSavedStateHandle(), profiles, configValidator, analytics)

                ConnectionsViewModel::class.java -> ConnectionsViewModel(api, recentConnections)
                RulesViewModel::class.java -> RulesViewModel(api)
                DnsViewModel::class.java -> DnsViewModel(fetch = { search -> api.dnsResults(search) })
                LogsViewModel::class.java -> LogsViewModel(api)

                else -> error("unknown ViewModel: ${modelClass.name}")
            } as T
    }
}
