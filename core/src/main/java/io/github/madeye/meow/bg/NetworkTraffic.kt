package io.github.madeye.meow.bg

import android.net.NetworkCapabilities
import io.github.madeye.meow.aidl.ByteTotals
import io.github.madeye.meow.aidl.SessionStats

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
