package io.github.madeye.meow.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.RouteMode
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.preference.RouteModeStore
import io.github.madeye.meow.repo.ProfileRepository
import io.github.madeye.meow.vpn.VpnStateRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

@Immutable
data class ProxyNodeUi(
    val name: String,
    val type: String,
    val delayMs: Int?,
    val selected: Boolean,
)

@Immutable
data class ProxyGroupUi(
    val name: String,
    val type: String,
    val now: String,
    val nodes: List<ProxyNodeUi>,
)

@Immutable
data class HomeUiState(
    val state: BaseService.State = BaseService.State.Idle,
    val profileName: String = "",
    val hasProfile: Boolean = false,
    val traffic: TrafficStats = TrafficStats(),
    val groups: List<ProxyGroupUi> = emptyList(),
    val expandedGroup: String? = null,
    val testingGroup: String? = null,
    val routeMode: RouteMode = RouteMode.Rule,
) {
    val isConnected: Boolean get() = state == BaseService.State.Connected

    /** The switch is inert mid-transition, matching the old Flutter behaviour. */
    val isBusy: Boolean
        get() = state == BaseService.State.Connecting || state == BaseService.State.Stopping
}

class HomeViewModel(
    private val vpn: VpnStateRepository,
    private val profiles: ProfileRepository,
    private val api: MeowApi,
    private val analytics: Analytics,
    private val routeModes: RouteModeStore,
) : ViewModel() {

    private val groups = MutableStateFlow<List<ProxyGroupUi>>(emptyList())
    private val expanded = MutableStateFlow<String?>(null)
    private val testing = MutableStateFlow<String?>(null)

    /** The user's persisted pick; `null` until they make one. */
    private val savedMode = MutableStateFlow<RouteMode?>(null)

    /** What the running engine reports; `null` while disconnected. */
    private val engineMode = MutableStateFlow<RouteMode?>(null)
    private val modeSwitch = Mutex()

    /** The engine is the truth while it runs; otherwise, what the next start will use. */
    private val routeMode: Flow<RouteMode> = combine(
        engineMode,
        savedMode,
        // Mapped apart from uiState so the YAML scan doesn't rerun on every traffic tick.
        profiles.observeSelected()
            .map { it?.yamlContent }
            .distinctUntilChanged()
            .map { yaml -> yaml?.let(RouteMode::configured) ?: RouteMode.Rule },
    ) { engine, saved, configured -> engine ?: saved ?: configured }

    val uiState: StateFlow<HomeUiState> = combine(
        vpn.state,
        vpn.traffic,
        profiles.observeSelected(),
        groups,
        combine(expanded, testing, routeMode, ::LocalState),
    ) { state, traffic, profile, groupList, local ->
        HomeUiState(
            state = state,
            profileName = profile?.name.orEmpty(),
            hasProfile = profile != null,
            traffic = traffic,
            groups = groupList,
            expandedGroup = local.expandedGroup,
            testingGroup = local.testingGroup,
            routeMode = local.routeMode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private data class LocalState(
        val expandedGroup: String?,
        val testingGroup: String?,
        val routeMode: RouteMode,
    )

    init {
        viewModelScope.launch {
            savedMode.value = withContext(Dispatchers.IO) { routeModes.load() }
        }
        // Groups only exist while the engine is up, and they change when the
        // user switches profile — so reload on every state transition.
        viewModelScope.launch {
            vpn.state.collect { state ->
                if (state == BaseService.State.Connected) {
                    loadGroups()
                } else {
                    groups.value = emptyList()
                    engineMode.value = null
                }
            }
        }
    }

    /** The `:vpn` process may have been killed while backgrounded. */
    fun onResume() {
        vpn.refresh()
        if (vpn.state.value == BaseService.State.Connected) loadGroups()
    }

    fun onToggleExpanded(group: String) {
        expanded.value = if (expanded.value == group) null else group
    }

    fun onConnectRequested() = analytics.vpnConnect()

    fun onDisconnect(context: android.content.Context) {
        analytics.vpnDisconnect()
        vpn.requestStop(context)
    }

    fun onSelectNode(group: String, node: String) {
        viewModelScope.launch {
            try {
                api.selectProxy(group, node)
                analytics.proxyNodeSelect(node)
                // Persist the pick so it can be replayed on the next connect.
                profiles.getSelected()?.let { profiles.saveSelectedProxy(it.id, node) }
                loadGroups()
            } catch (e: MeowApiException) {
                // The engine is the source of truth; a failed select simply
                // leaves the previous node in place on the next refresh.
                loadGroups()
            }
        }
    }

    /**
     * Persists [mode] for every later engine start and, while connected,
     * switches the running engine too. Open connections are then closed: the
     * engine only re-routes new flows, so an app that stays connected (a
     * stream, a chat socket) would otherwise keep its old route.
     */
    fun onSelectRouteMode(mode: RouteMode) {
        viewModelScope.launch {
            // Serialized so rapid taps reach the engine in tap order; the
            // last one has to win.
            modeSwitch.withLock { switchRouteMode(mode) }
        }
    }

    private suspend fun switchRouteMode(mode: RouteMode) {
        val connected = vpn.state.value == BaseService.State.Connected
        if (connected) {
            val previous = engineMode.value
            engineMode.value = mode
            try {
                api.setMode(mode)
            } catch (e: MeowApiException) {
                Timber.w(e, "switching route mode failed")
                engineMode.value = previous
                loadGroups()
                return
            }
        }
        try {
            withContext(Dispatchers.IO) { routeModes.save(mode) }
            savedMode.value = mode
        } catch (e: IOException) {
            // The running engine already switched; only the replay is lost.
            Timber.w(e, "saving route mode failed")
        }
        analytics.routeModeSelect(mode.wire)
        if (connected) {
            try {
                api.closeAllConnections()
            } catch (e: MeowApiException) {
                Timber.w(e, "closing connections after a route mode switch failed")
            }
            // GLOBAL is only listed in global mode.
            loadGroups()
        }
    }

    fun onTestGroup(group: String) {
        viewModelScope.launch {
            testing.value = group
            try {
                api.testGroupDelay(group)
            } catch (e: MeowApiException) {
                // Individual probes can time out; the reload below still shows
                // whatever latencies did come back.
            } finally {
                testing.value = null
                loadGroups()
            }
        }
    }

    private fun loadGroups() {
        viewModelScope.launch {
            // Read first: the mode decides whether GLOBAL is listed.
            loadRouteMode()
            groups.value = try {
                val result = api.proxies()
                result.visibleGroups(engineMode.value).map { group ->
                    ProxyGroupUi(
                        name = group.name,
                        type = group.type,
                        now = group.now,
                        nodes = group.all.map { nodeName ->
                            val node = result.proxies[nodeName]
                            // Members can be groups themselves (every one of
                            // GLOBAL's is); those live in the other map.
                            val member = result.groups[nodeName]
                            ProxyNodeUi(
                                name = nodeName,
                                type = node?.type ?: member?.type.orEmpty(),
                                delayMs = (node?.latestDelay ?: member?.history?.lastOrNull()?.delay)
                                    ?.takeIf { it > 0 },
                                selected = nodeName == group.now,
                            )
                        },
                    )
                }
            } catch (e: MeowApiException) {
                // An empty group list and a failed fetch look identical on
                // screen, so the reason has to reach logcat or it is invisible.
                Timber.w(e, "loading proxy groups failed")
                emptyList()
            }
        }
    }

    private suspend fun loadRouteMode() {
        try {
            api.configs().routeMode?.let { engineMode.value = it }
        } catch (e: MeowApiException) {
            Timber.w(e, "loading route mode failed")
        }
    }
}
