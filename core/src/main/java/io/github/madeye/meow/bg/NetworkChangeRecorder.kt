package io.github.madeye.meow.bg

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.github.madeye.meow.core.MeowCore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * Records in the logbook (kind `network.change`) how the networks under the
 * VPN change while it runs: the networks up, the one the VPN declared with
 * [android.net.VpnService.setUnderlyingNetworks] and whether it is still up,
 * and what [ConnectivityManager.getActiveNetworkInfo] answers the apps on
 * the VPN. Android answers them with the declared network, and Chromium's
 * network stack (Cronet) goes offline when the answer is none: a VPN that
 * declared a Wi-Fi it then left had apps offline until it restarted.
 *
 * Diagnostics only: nothing the VPN does changes. A change is recorded once
 * the networks have been quiet for [SETTLE_MS], and only when it differs
 * from the last record. Network ids only: no SSID, no address.
 *
 * [start] and [stop] are called on the main thread, which owns all state.
 */
internal class NetworkChangeRecorder(context: Context) {
    companion object {
        /** A switch is a burst of callbacks: lost, available, validated. */
        private const val SETTLE_MS = 1_000L
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val networks = HashSet<Network>()
    /** The kind of every network seen, gone ones included: a lost declared network keeps its name. */
    private val kinds = HashMap<Network, String>()
    private var declared: Network? = null
    private var last: NetworkSnapshot? = null
    private var started = false

    private val settled = Runnable { record() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // On the system's connectivity thread.
        override fun onAvailable(network: Network) {
            handler.post { up(network) }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            handler.post { up(network) }
        }

        override fun onLost(network: Network) {
            handler.post { lost(network) }
        }
    }

    // Every network with internet access, the VPN's own included: its
    // capabilities follow the networks under it.
    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    /** Starts recording, with nothing declared until [declare]. */
    fun start() {
        started = try {
            connectivity.registerNetworkCallback(request, callback)
            true
        } catch (e: RuntimeException) {
            // Too many requests for this uid, or an OEM refusal.
            Timber.w(e, "NetworkChangeRecorder: could not listen to networks")
            false
        }
        // The networks at the start, once their first callbacks are in.
        if (started) changed()
    }

    /** The VPN handed [network] to `setUnderlyingNetworks`, null for nothing. Any thread. */
    fun declare(network: Network?) {
        handler.post {
            declared = network
            network?.let { connectivity.getNetworkCapabilities(it)?.let { capabilities -> kinds[it] = capabilities.kindName() } }
            changed()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(settled)
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            Timber.d(e, "NetworkChangeRecorder: nothing to unregister")
        }
    }

    private fun up(network: Network) {
        networks += network
        changed()
    }

    private fun lost(network: Network) {
        networks -= network
        changed()
    }

    private fun changed() {
        if (!started) return
        handler.removeCallbacks(settled)
        handler.postDelayed(settled, SETTLE_MS)
    }

    private fun record() {
        if (!started) return
        val snapshot = try {
            take()
        } catch (e: RuntimeException) {
            // A binder or OEM failure: the next change tries again.
            Timber.w(e, "NetworkChangeRecorder: could not read the networks")
            return
        }
        if (snapshot == last) return
        last = snapshot
        val message = snapshot.message
        Timber.i("NetworkChangeRecorder: $message")
        val fields = snapshot.fields(deviceIdle = power.isDeviceIdleMode)
        MeowCore.nativeRecordNetworkChange(snapshot.warning, message, fields.toString())
    }

    private fun take(): NetworkSnapshot {
        val up = ArrayList<NetworkSnapshot.Link>()
        var vpn: NetworkCapabilities? = null
        for (network in networks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpn = capabilities
                continue
            }
            val kind = capabilities.kindName()
            kinds[network] = kind
            up += NetworkSnapshot.Link(
                network.toString(),
                kind,
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }
        up.sortBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
        // This app goes through its own VPN unless the per-app list leaves it
        // out; then it is answered as an app off the VPN.
        val activeCapabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val onVpn = activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (onVpn) vpn = activeCapabilities
        val declared = declared
        return NetworkSnapshot(
            up = up,
            declared = declared?.let { NetworkSnapshot.Link(it.toString(), kinds[it] ?: "network") },
            declaredUp = declared?.let { connectivity.getNetworkCapabilities(it) } != null,
            onVpn = onVpn,
            active = activeNetworkInfo(),
            vpn = vpn?.vpnSummary(),
        )
    }

    /** What the apps on the VPN are told, Chromium's network stack among them. */
    @Suppress("DEPRECATION")
    private fun activeNetworkInfo(): NetworkSnapshot.Active? = connectivity.activeNetworkInfo?.let {
        NetworkSnapshot.Active(it.typeName, it.isConnected, it.detailedState?.name ?: "UNKNOWN")
    }

    /** The VPN's capabilities as the apps on it see them: Android copies them from the networks under it. */
    private fun NetworkCapabilities.vpnSummary(): String = (
        transportNames().ifEmpty { listOf("no underlying transport") } +
            (if (hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "not validated") +
            (if (hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "not metered" else "metered")
        ).joinToString()
}

/** One look at the networks, as [NetworkChangeRecorder] records it. */
internal data class NetworkSnapshot(
    /** The networks up other than VPNs, by id. */
    val up: List<Link>,
    /** What the VPN declared as its underlying network; null for nothing. */
    val declared: Link?,
    val declaredUp: Boolean,
    /** Whether this app goes through its own VPN, and so is answered as the apps on it are. */
    val onVpn: Boolean,
    /** What `getActiveNetworkInfo` answers this app; null for no network. */
    val active: Active?,
    /** The VPN network's capabilities; null when it is not seen. */
    val vpn: String?,
) {
    /** A network by its id (`Network.toString()`) and kind. */
    data class Link(val id: String, val kind: String, val validated: Boolean = true) {
        val name: String get() = "$kind $id"

        override fun toString() = if (validated) name else "$name (not validated)"
    }

    /** `NetworkInfo`'s type name (`WIFI`, `MOBILE`) and detailed state. */
    data class Active(val type: String, val connected: Boolean, val state: String)

    /**
     * The declared network is gone, or the apps on the VPN are told there is
     * no network while one is up: what makes them give up.
     */
    val warning: Boolean
        get() = (declared != null && !declaredUp) || (onVpn && active == null && up.isNotEmpty())

    /** One English sentence, as logbook messages are. */
    val message: String
        get() = buildString {
            append(
                when (up.size) {
                    0 -> "no network is up"
                    1 -> "${up[0]} is up"
                    else -> "${up.dropLast(1).joinToString()} and ${up.last()} are up"
                },
            )
            append("; the VPN declares ")
            append(
                when {
                    declared == null -> "no network"
                    declaredUp -> declared.name
                    else -> "${declared.name}, which is gone"
                },
            )
            append("; ")
            append(
                when {
                    !onVpn -> "this app is outside the VPN, so what the apps on it see is not known"
                    active == null -> "apps on the VPN see no network"
                    active.connected -> "apps on the VPN see ${typeName(active.type)}"
                    else -> "apps on the VPN see ${typeName(active.type)}, ${active.state.lowercase()}"
                },
            )
            append('.')
        }.replaceFirstChar { it.uppercaseChar() }

    /** The details the message leaves out; [deviceIdle] is Doze. */
    fun fields(deviceIdle: Boolean): JsonObject = buildJsonObject {
        put("active_network_info", active?.let { "${it.type} ${it.state}" } ?: "none")
        vpn?.let { put("vpn", it) }
        put("device_idle", deviceIdle)
    }

    private fun typeName(type: String) = when (type) {
        "WIFI" -> "Wi-Fi"
        "MOBILE" -> "cellular"
        "ETHERNET" -> "Ethernet"
        "VPN" -> "the VPN"
        else -> type
    }
}
