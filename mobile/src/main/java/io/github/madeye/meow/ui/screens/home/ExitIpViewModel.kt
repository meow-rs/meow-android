package io.github.madeye.meow.ui.screens.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.net.ExitIp
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
sealed interface ExitIpUiState {
    /** The setting is off, or not read yet: no card, and no requests. */
    data object Hidden : ExitIpUiState

    data object Checking : ExitIpUiState

    /**
     * [bypassesVpn]: the VPN is up but this app is not inside it (per-app
     * proxy left it out), so [exitIp] is the direct address, not the proxy's.
     */
    @Immutable
    data class Found(val exitIp: ExitIp, val bypassesVpn: Boolean) : ExitIpUiState

    data object Failed : ExitIpUiState
}

/**
 * Home's exit-IP card: which public address traffic currently leaves from.
 *
 * Kept apart from [HomeViewModel] because its triggers are its own: a check
 * runs when the tunnel comes up or goes down, after a node or route-mode
 * switch ([routeChanges]), and on tap. Triggers inside [SETTLE_MS] of each
 * other collapse into one check, and a new trigger cancels the check in
 * flight — its answer would describe the route being left.
 */
class ExitIpViewModel(
    private val lookup: suspend () -> ExitIp,
    vpnState: StateFlow<BaseService.State>,
    private val enabled: StateFlow<Boolean?>,
    /** Whether this app's own sockets currently go through the VPN. */
    private val routedViaVpn: () -> Boolean,
    /** Node and route-mode switches that reached the running engine. */
    routeChanges: Flow<Unit>,
) : ViewModel() {

    companion object {
        /**
         * Lets a switch take hold before probing it: across a VPN up/down the
         * platform needs a moment to move the app's default network, and a
         * burst of node taps should cost one lookup, not one each.
         */
        const val SETTLE_MS = 1_000L
    }

    private enum class Route { Direct, Tunnel }

    private val status = MutableStateFlow<ExitIpUiState>(ExitIpUiState.Checking)

    /** `null` mid-transition (connecting / stopping), when there is nothing stable to measure. */
    private var route: Route? = null
    private var check: Job? = null

    val uiState: StateFlow<ExitIpUiState> = combine(enabled, status) { on, current ->
        if (on == true) current else ExitIpUiState.Hidden
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExitIpUiState.Hidden)

    init {
        viewModelScope.launch {
            combine(enabled, vpnState) { on, state -> (on == true) to routeOf(state) }
                // Idle -> Stopped is still "direct": no second lookup.
                .distinctUntilChanged()
                .collect { (on, newRoute) ->
                    route = newRoute
                    when {
                        !on -> cancelCheck()
                        newRoute == null -> {
                            // Whatever is in flight describes the route being torn down.
                            cancelCheck()
                            status.value = ExitIpUiState.Checking
                        }
                        else -> schedule(SETTLE_MS)
                    }
                }
        }
        // Collected for as long as Home is on the back stack, so a node picked
        // on the Proxy Groups tab is already being re-checked on the way back.
        viewModelScope.launch {
            routeChanges.collect {
                if (enabled.value == true && route == Route.Tunnel) schedule(SETTLE_MS)
            }
        }
    }

    /** A tap on the card. */
    fun refresh() {
        if (enabled.value == true && route != null) schedule(0)
    }

    private fun schedule(delayMs: Long) {
        check?.cancel()
        status.value = ExitIpUiState.Checking
        val via = route
        check = viewModelScope.launch {
            delay(delayMs)
            status.value = try {
                val exitIp = lookup()
                ExitIpUiState.Found(exitIp, bypassesVpn = via == Route.Tunnel && !routedViaVpn())
            } catch (e: IOException) {
                Timber.w(e, "exit IP lookup failed")
                ExitIpUiState.Failed
            }
        }
    }

    private fun cancelCheck() {
        check?.cancel()
        check = null
    }

    private fun routeOf(state: BaseService.State): Route? = when (state) {
        BaseService.State.Connected -> Route.Tunnel
        BaseService.State.Idle, BaseService.State.Stopped -> Route.Direct
        BaseService.State.Connecting, BaseService.State.Stopping -> null
    }
}

/**
 * Whether this app's default network is the VPN. While connected, `false`
 * means per-app proxy leaves Meow out (an allow-list without it, or a
 * bypass-list with it), so its own lookups go direct. Unknown counts as
 * routed: a false alarm on the card is worse than a missing one.
 */
fun Context.defaultNetworkIsVpn(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return true) ?: return true
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}
