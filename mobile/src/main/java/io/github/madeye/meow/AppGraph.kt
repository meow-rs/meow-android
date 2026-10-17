package io.github.madeye.meow

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation.toRoute
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.bg.MeowInstance
import io.github.madeye.meow.net.ExitIpLookup
import io.github.madeye.meow.net.IpGeoLookup
import io.github.madeye.meow.net.ProxyProber
import io.github.madeye.meow.preference.ApiSecretStore
import io.github.madeye.meow.preference.BuiltinDnsPreference
import io.github.madeye.meow.preference.ExitIpPreference
import io.github.madeye.meow.preference.RouteModeStore
import io.github.madeye.meow.preference.ThemeModePreference
import io.github.madeye.meow.repo.ConfigValidator
import io.github.madeye.meow.repo.InstalledAppsRepository
import io.github.madeye.meow.repo.PerAppRepository
import io.github.madeye.meow.repo.ProfileRepository
import io.github.madeye.meow.repo.TrafficHistoryRepository
import io.github.madeye.meow.ui.screens.connections.AppLabels
import io.github.madeye.meow.ui.screens.connections.ConnectionDetailViewModel
import io.github.madeye.meow.ui.screens.connections.ConnectionsViewModel
import io.github.madeye.meow.ui.screens.connections.RecentConnectionsStore
import io.github.madeye.meow.ui.screens.connections.connectionsPolling
import io.github.madeye.meow.ui.screens.dns.DnsViewModel
import io.github.madeye.meow.ui.screens.home.ExitIpViewModel
import io.github.madeye.meow.ui.screens.home.HomeViewModel
import io.github.madeye.meow.ui.screens.home.RouteChanges
import io.github.madeye.meow.ui.screens.home.defaultNetworkIsVpn
import io.github.madeye.meow.ui.screens.logs.LogsViewModel
import io.github.madeye.meow.ui.screens.perapp.PerAppProxyViewModel
import io.github.madeye.meow.ui.nav.Dest
import io.github.madeye.meow.ui.screens.proxies.ProxyDetailViewModel
import io.github.madeye.meow.ui.screens.proxies.ProxyGroupsViewModel
import io.github.madeye.meow.ui.screens.rules.RulesViewModel
import io.github.madeye.meow.ui.screens.settings.SettingsViewModel
import io.github.madeye.meow.ui.screens.subscribe.RuleSetUpdater
import io.github.madeye.meow.ui.screens.subscribe.SubscribeViewModel
import io.github.madeye.meow.ui.screens.traffic.TrafficViewModel
import io.github.madeye.meow.ui.screens.yaml.YamlEditorViewModel
import io.github.madeye.meow.ui.util.AppVersions
import io.github.madeye.meow.update.GitHubReleases
import io.github.madeye.meow.vpn.ConfigReloader
import io.github.madeye.meow.vpn.SpeedSampleStore
import io.github.madeye.meow.vpn.VpnStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-written dependency graph.
 *
 * A dozen ViewModels over a handful of singletons does not justify Hilt's
 * annotation processing round; this reads top to bottom.
 */
object AppGraph {

    private lateinit var appContext: Context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val api: MeowApi by lazy { MeowApi(secret = ApiSecretStore.default::load) }
    val vpn: VpnStateRepository by lazy { VpnStateRepository() }
    val speedSamples: SpeedSampleStore by lazy { SpeedSampleStore(vpn, scope) }

    /** Survives leaving the Connections screen; see [RecentConnectionsStore]. */
    val recentConnections: RecentConnectionsStore by lazy { RecentConnectionsStore() }
    val profiles: ProfileRepository by lazy { ProfileRepository() }
    val trafficHistory: TrafficHistoryRepository by lazy { TrafficHistoryRepository() }
    val perApp: PerAppRepository by lazy { PerAppRepository() }
    val installedApps: InstalledAppsRepository by lazy { InstalledAppsRepository(appContext) }

    /** Names of the apps behind connections, shared by the list and the detail page. */
    val appLabels: AppLabels by lazy { AppLabels(scope, installedApps::label) }
    val configValidator: ConfigValidator by lazy { ConfigValidator() }
    val analytics: Analytics by lazy { Analytics(appContext) }
    val appVersions: AppVersions by lazy { AppVersions(appContext) }
    val routeModes: RouteModeStore get() = RouteModeStore.default
    val configReloader: ConfigReloader get() = ConfigReloader.default

    /** Settings flips it; Home's exit-IP card follows. */
    val showExitIp: ExitIpPreference by lazy { ExitIpPreference(scope) }
    val builtinDns: BuiltinDnsPreference by lazy {
        BuiltinDnsPreference(scope) { vpn.applyBuiltinDns(it) }
    }
    val exitIpLookup: ExitIpLookup by lazy { ExitIpLookup() }
    val ipGeoLookup: IpGeoLookup by lazy { IpGeoLookup { MeowInstance.homeDir(appContext) } }

    /** A proxy detail page's tests, run in `:vpn` over the activity's binding. */
    val proxyProber: ProxyProber by lazy { ProxyProber(vpn) }

    /** Node and route-mode switches, for the exit-IP card; see [RouteChanges]. */
    val routeChanges: RouteChanges by lazy { RouteChanges() }

    /** Settings picks it; each Activity applies it, see `applyThemeMode`. */
    val themeMode: ThemeModePreference by lazy { ThemeModePreference() }

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

                ProxyDetailViewModel::class.java -> {
                    val route = extras.createSavedStateHandle().toRoute<Dest.ProxyDetail>()
                    ProxyDetailViewModel(
                        name = route.name,
                        testUrl = route.testUrl,
                        vpnState = vpn.state,
                        describe = api::proxy,
                        tcpDelay = { name, url -> api.testProxyDelay(name, url) },
                        prober = proxyProber,
                    )
                }

                SubscribeViewModel::class.java -> SubscribeViewModel(
                    profiles,
                    configValidator,
                    analytics,
                    RuleSetUpdater(
                        vpnState = vpn.state,
                        listProviders = api::ruleProviders,
                        updateProvider = api::updateRuleProvider,
                    ),
                )

                TrafficViewModel::class.java ->
                    TrafficViewModel(vpn, trafficHistory, speedSamples)

                SettingsViewModel::class.java -> SettingsViewModel(
                    appVersions,
                    showExitIp,
                    builtinDns,
                    themeMode,
                    // Null in the Play build, which may only update through
                    // Play. Built here, not in a lazy property: that delegate
                    // is created with AppGraph, so R8 would keep the client.
                    latestRelease = if (BuildConfig.PLAY_STORE) {
                        null
                    } else {
                        GitHubReleases(userAgent = "meow-android/${BuildConfig.VERSION_NAME}")::latest
                    },
                    installedVersion = BuildConfig.VERSION_NAME,
                )

                PerAppProxyViewModel::class.java ->
                    PerAppProxyViewModel(perApp, installedApps, analytics)

                YamlEditorViewModel::class.java ->
                    YamlEditorViewModel(extras.createSavedStateHandle(), profiles, configValidator, analytics)

                ConnectionsViewModel::class.java -> ConnectionsViewModel(api, recentConnections, appLabels)
                RulesViewModel::class.java ->
                    RulesViewModel(api, focus = extras.createSavedStateHandle().toRoute<Dest.Rules>().focus)

                ConnectionDetailViewModel::class.java -> ConnectionDetailViewModel(
                    id = extras.createSavedStateHandle().toRoute<Dest.ConnectionDetail>().id,
                    store = recentConnections,
                    polling = connectionsPolling(api, recentConnections),
                    rules = api::rules,
                    closeConnection = api::closeConnection,
                    proxies = api::proxies,
                    dnsCache = { host -> api.dnsResults(search = host) },
                    ipGeo = ipGeoLookup::lookup,
                    appLabel = appLabels::labelOf,
                    connectionNotes = api::connectionNotes,
                )
                DnsViewModel::class.java -> DnsViewModel(fetch = { search -> api.dnsResults(search) })
                LogsViewModel::class.java -> LogsViewModel(api)

                else -> error("unknown ViewModel: ${modelClass.name}")
            } as T
    }
}
