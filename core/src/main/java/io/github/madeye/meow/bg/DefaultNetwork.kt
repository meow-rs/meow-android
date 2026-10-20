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
import timber.log.Timber

/**
 * Reports the network the engine's sockets go out on each time it changes,
 * until [stop]: the system's default network, which for this app is the VPN
 * itself, so the best one that is not a VPN is tracked instead. A change of
 * the network's capabilities alone is not reported.
 *
 * The registration per API level is shadowsocks-android's
 * `DefaultNetworkListener`, whose quirks it works around.
 */
internal class DefaultNetwork(
    context: Context,
    private val onNetwork: (Network?, NetworkCapabilities?) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // Callbacks come one at a time, on the main looper (or, below API 26,
        // the system's connectivity thread).
        private var current: Network? = null

        // Below API 26 a network's capabilities need not follow its
        // onAvailable, so it is read there.
        override fun onAvailable(network: Network) {
            if (Build.VERSION.SDK_INT < 26) changed(network, connectivity.getNetworkCapabilities(network))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            changed(network, capabilities)
        }

        private fun changed(network: Network, capabilities: NetworkCapabilities?) {
            if (network == current) return
            current = network
            onNetwork(network, capabilities)
        }

        override fun onLost(network: Network) {
            // The network handed over from can be reported lost after the
            // one that took over: that one still carries the traffic.
            if (network != current) return
            current = null
            onNetwork(null, null)
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
            // runs on without hearing of network changes.
            Timber.w(e, "DefaultNetwork: could not track the default network")
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
            Timber.d(e, "DefaultNetwork: nothing to unregister")
        }
    }
}

/** The transports a logbook entry names a network by, in that order. */
internal fun NetworkCapabilities.transportNames(): List<String> = listOfNotNull(
    "Wi-Fi".takeIf { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
    "cellular".takeIf { hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
    "Ethernet".takeIf { hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
)

internal fun NetworkCapabilities.kindName(): String = transportNames().firstOrNull() ?: "network"

/** A network as logbook entries name it: `Wi-Fi 324`. */
internal fun Network.describe(capabilities: NetworkCapabilities?): String =
    "${capabilities?.kindName() ?: "network"} $this"
