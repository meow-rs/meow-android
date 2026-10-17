package io.github.madeye.meow.bg

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.madeye.meow.aidl.ByteTotals
import io.github.madeye.meow.aidl.SessionStats
import timber.log.Timber

/**
 * Splits one engine run's byte totals by the kind of network they left the
 * device on, for the Statistics screen. The engine keeps only totals, so when
 * the default network changes, what they gained since the last change is
 * charged to the network that was in use meanwhile; a read charges the gain so
 * far to the current one.
 *
 * Bytes counted while no network is known stay unclaimed and go to the next
 * one: they can only be the tail of a hand-over.
 */
internal class NetworkTrafficMeter(private val startedAt: Long) {

    enum class Kind { WifiEthernet, Cellular, Other }

    private val upload = LongArray(Kind.entries.size)
    private val download = LongArray(Kind.entries.size)
    private var current: Kind? = null
    private var chargedTx = 0L
    private var chargedRx = 0L

    /** The default network is now [kind], or none; [tx] and [rx] are the engine's totals at the change. */
    @Synchronized
    fun onNetwork(kind: Kind?, tx: Long, rx: Long) {
        charge(tx, rx)
        current = kind
    }

    /** The session so far; [tx] and [rx] are the engine's totals now. */
    @Synchronized
    fun read(tx: Long, rx: Long): SessionStats {
        charge(tx, rx)
        fun totals(kind: Kind) = ByteTotals(upload[kind.ordinal], download[kind.ordinal])
        return SessionStats(
            startedAt = startedAt,
            wifiEthernet = totals(Kind.WifiEthernet),
            cellular = totals(Kind.Cellular),
            other = totals(Kind.Other),
        )
    }

    private fun charge(tx: Long, rx: Long) {
        val kind = current ?: return
        // The totals only grow while the engine runs, and a meter lives no
        // longer than its run; a read racing the stop sees them back at zero.
        upload[kind.ordinal] += (tx - chargedTx).coerceAtLeast(0)
        download[kind.ordinal] += (rx - chargedRx).coerceAtLeast(0)
        chargedTx = tx
        chargedRx = rx
    }
}

internal fun NetworkCapabilities.trafficKind(): NetworkTrafficMeter.Kind = when {
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTrafficMeter.Kind.WifiEthernet
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTrafficMeter.Kind.Cellular
    else -> NetworkTrafficMeter.Kind.Other
}

/**
 * Reports the kind of the network the engine's sockets go out on as it
 * changes, until [stop]: the system's default network, which for this app
 * is the VPN itself, so the best one that is not a VPN is tracked instead.
 *
 * The registration per API level is shadowsocks-android's
 * `DefaultNetworkListener`, whose quirks it works around. This app's
 * `net.DefaultNetworkListener` hears every network, not just the best one, so
 * it cannot tell which of Wi-Fi and a mobile data link kept up beside it
 * carries the traffic.
 */
internal class DefaultNetworkKind(
    context: Context,
    private val onKind: (NetworkTrafficMeter.Kind?) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // Callbacks come one at a time, on the main looper (or, below API 26,
        // the system's connectivity thread).
        private var current: Network? = null

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            current = network
            onKind(capabilities.trafficKind())
        }

        override fun onLost(network: Network) {
            // The network handed over from can be reported lost after the
            // one that took over: that one still carries the traffic.
            if (network != current) return
            current = null
            onKind(null)
        }
    }

    // INTERNET and NOT_RESTRICTED on top of the builder's NOT_VPN.
    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()

    fun start() {
        val handler = Handler(Looper.getMainLooper())
        try {
            when {
                Build.VERSION.SDK_INT >= 31 -> registerBestMatching(handler)
                // A request, not a listen: it follows the network that wins.
                Build.VERSION.SDK_INT >= 28 -> connectivity.requestNetwork(request, callback, handler)
                Build.VERSION.SDK_INT >= 26 -> connectivity.registerDefaultNetworkCallback(callback, handler)
                else -> connectivity.registerDefaultNetworkCallback(callback)
            }
        } catch (e: RuntimeException) {
            // Too many requests for this uid, or an OEM refusal: the session
            // runs on, with its traffic left unsplit.
            Timber.w(e, "DefaultNetworkKind: could not track the default network")
        }
    }

    @TargetApi(31)
    private fun registerBestMatching(handler: Handler) =
        connectivity.registerBestMatchingNetworkCallback(request, callback, handler)

    fun stop() {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Never registered: start() was refused.
            Timber.d(e, "DefaultNetworkKind: nothing to unregister")
        }
    }
}
