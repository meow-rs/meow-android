package io.github.madeye.meow.traffic

import io.github.madeye.meow.core.MeowCore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How the engine's traffic ledger groups a report. */
enum class TrafficGroup(val wire: String) {
    Host("host"),
    App("app"),
    Policy("policy"),
    Interface("interface"),
}

/** One group of a [TrafficReport]: a host, an app, a policy or a network. */
@Serializable
data class TrafficRow(
    /**
     * A registrable domain or an IP address, an app's package name, a leaf
     * proxy, or a network label (see [TrafficNetwork]). Empty for a flow
     * with no host, an app the system did not name or a network the engine
     * was given no label for; [OTHER_IPS] for the bare addresses past a
     * day's cap.
     */
    val name: String,
    val upload: Long = 0,
    val download: Long = 0,
    val requests: Long = 0,
) {
    val total: Long get() = upload + download

    companion object {
        const val OTHER_IPS = "*"

        /**
         * The IP addresses a day keeps apart before the rest fold into
         * [OTHER_IPS], as `meow_common::traffic_ledger::MAX_IP_HOSTS`.
         */
        const val MAX_IP_HOSTS = 1024
    }
}

/** The traffic of a span of days, as `meow_common::traffic_ledger::Report`. */
@Serializable
data class TrafficReport(
    val from: String = "",
    val to: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    val requests: Long = 0,
    /** Largest first. */
    val rows: List<TrafficRow> = emptyList(),
    val error: String? = null,
) {
    val total: Long get() = upload + download
}

/** The labels the `:vpn` process gives the engine for the network it goes out on. */
object TrafficNetwork {
    const val WIFI = "Wi-Fi"
    const val CELLULAR = "Cellular"
    const val ETHERNET = "Ethernet"
    const val OTHER = "Other"
}

/**
 * Reads the engine's traffic ledger — every day's traffic by host, app,
 * policy and network, kept for 62 days — from the database in the engine home,
 * from this process: the engine writes it in `:vpn` about once a minute,
 * and it stays readable after the VPN stops.
 */
class TrafficLedgerReader(private val dir: () -> File) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * The local days [from] to [to] (`LocalDate.toEpochDay()`, both
     * included) grouped [by], keeping the rows that match the filters
     * given; throws [IOException] when the ledger cannot be read.
     */
    suspend fun read(
        from: Long,
        to: Long,
        by: TrafficGroup,
        host: String? = null,
        app: String? = null,
        policy: String? = null,
        network: String? = null,
    ): TrafficReport = withContext(Dispatchers.IO) {
        val answer = MeowCore.nativeReadTraffic(
            dir().absolutePath,
            from.toInt(),
            to.toInt(),
            by.wire,
            host.orEmpty(),
            app.orEmpty(),
            policy.orEmpty(),
            network.orEmpty(),
        )
            ?: throw IOException("no answer from the engine library")
        val report = try {
            json.decodeFromString(TrafficReport.serializer(), answer)
        } catch (e: SerializationException) {
            throw IOException(e.message, e)
        }
        report.error?.let { throw IOException(it) }
        report
    }

    /** Deletes everything the ledger holds; false when it could not. */
    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        MeowCore.nativeClearTraffic(dir().absolutePath)
    }
}
