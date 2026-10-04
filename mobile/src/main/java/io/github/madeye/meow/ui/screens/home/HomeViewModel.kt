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
data class HomeUiState(
    val state: BaseService.State = BaseService.State.Idle,
    val profileName: String = "",
    val hasProfile: Boolean = false,
    val traffic: TrafficStats = TrafficStats(),
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
    private val routeChanges: RouteChanges,
) : ViewModel() {

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
        routeMode,
    ) { state, traffic, profile, mode ->
        HomeUiState(
            state = state,
            profileName = profile?.name.orEmpty(),
            hasProfile = profile != null,
            traffic = traffic,
            routeMode = mode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            savedMode.value = withContext(Dispatchers.IO) { routeModes.load() }
        }
        // The engine's mode is only there to read while it runs, and a
        // profile switch restarts it — so re-read on every state transition.
        viewModelScope.launch {
            vpn.state.collect { state ->
                if (state == BaseService.State.Connected) {
                    loadRouteMode()
                } else {
                    engineMode.value = null
                }
            }
        }
    }

    /** The `:vpn` process may have been killed while backgrounded. */
    fun onResume() {
        vpn.refresh()
        if (vpn.state.value == BaseService.State.Connected) {
            viewModelScope.launch { loadRouteMode() }
        }
    }

    fun onConnectRequested() = analytics.vpnConnect()

    fun onDisconnect(context: android.content.Context) {
        analytics.vpnDisconnect()
        vpn.requestStop(context)
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
            routeChanges.notifyChanged()
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
