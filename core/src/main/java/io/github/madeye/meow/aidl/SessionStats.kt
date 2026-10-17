package io.github.madeye.meow.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Bytes one way and the other, as the engine counts them: app payload. Also
 * the shape of one proxy in the engine's `GET /statistics`.
 */
@Parcelize
@Serializable
data class ByteTotals(
    val upload: Long = 0L,
    val download: Long = 0L,
) : Parcelable {
    val total: Long get() = upload + download
}

/**
 * The running VPN session, for the Statistics screen: when its engine came up
 * and what it relayed over each kind of network since (see
 * [io.github.madeye.meow.bg.NetworkTrafficMeter]).
 */
@Parcelize
data class SessionStats(
    /** Epoch millis. */
    val startedAt: Long,
    val wifiEthernet: ByteTotals = ByteTotals(),
    val cellular: ByteTotals = ByteTotals(),
    /** Any other transport: Bluetooth or USB tethering, a satellite link. */
    val other: ByteTotals = ByteTotals(),
) : Parcelable
