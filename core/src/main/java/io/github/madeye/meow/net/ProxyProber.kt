package io.github.madeye.meow.net

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl

/**
 * Carries probes to the `:vpn` process, where the engine and its proxies
 * live (`IMeowService.probeProxy`).
 */
interface ProxyProbeTransport {
    /**
     * Runs one probe and returns its JSON answer, blocking until it ends.
     * An empty [proxy] is the engine's routing rather than one proxy.
     * Throws [IOException] when `:vpn` cannot be reached.
     */
    fun probe(id: Long, proxy: String, kind: String, arg: String): String

    /** Ends probe [id] early; a no-op once it has ended. */
    fun cancel(id: Long)
}

/** A probe that ran and measured nothing. [code] says why, as the engine side reports it. */
class ProxyProbeException(message: String, val code: String) : IOException(message) {
    companion object {
        const val TIMEOUT = "timeout"
        const val UDP_UNSUPPORTED = "udp-unsupported"
        const val ENGINE_STOPPED = "engine-stopped"
    }
}

/** How a NAT treats one internal endpoint's traffic (RFC 4787 terms). */
enum class NatBehavior(val wire: String) {
    EndpointIndependent("endpoint-independent"),
    AddressDependent("address-dependent"),
    AddressAndPortDependent("address-and-port-dependent"),

    /** Depends on the destination, but servers without RFC 5780 cannot tell how. */
    Dependent("dependent"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): NatBehavior = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

/** The NAT a proxy's UDP relay presents, as STUN servers saw it. */
data class NatType(
    val mapping: NatBehavior,
    val filtering: NatBehavior,
    /** The relay's public address, `ip:port`. */
    val mappedAddress: String?,
    val server: String?,
    /**
     * The relay does not report where replies come from, so no test could
     * tell its filtering (or mapping) apart.
     */
    val sourceHidden: Boolean,
)

/** [bytes] moved in [millis]. */
data class Throughput(val bytes: Long, val millis: Long) {
    val bitsPerSecond: Double get() = bytes * 8_000.0 / millis.coerceAtLeast(1)
}

data class ProbeHttpResponse(val status: Int, val body: String)

/**
 * Diagnostics through one named proxy of the running engine, for the proxy
 * detail page. Each call dials that proxy directly — rules and group
 * selections play no part — and measures inside `:vpn`, so the numbers are
 * the proxy's own rather than the tunnel's. [routedNatType] is the one
 * exception, for Home.
 *
 * Cancelling a call ends its probe in `:vpn` as well, rather than leaving it
 * to run out its budget there.
 */
class ProxyProber(private val transport: ProxyProbeTransport) {

    private companion object {
        /**
         * `:vpn` keys running probes by id and outlives this process, so ids
         * start at random: a restarted UI must not reuse the id of a probe
         * its predecessor left running.
         */
        val ids = AtomicLong(Random.nextLong(Long.MAX_VALUE / 2))
    }

    /** A DNS query's round trip over the proxy's UDP relay, dial included. */
    suspend fun udpLatency(proxy: String): Long = run(proxy, "udp-latency").long("ms")

    /** A GET of [url] through [proxy]; any status is an answer. */
    suspend fun get(proxy: String, url: HttpUrl): ProbeHttpResponse {
        val answer = run(proxy, "http-get", url.toString())
        return ProbeHttpResponse(status = answer.int("status"), body = answer.string("body").orEmpty())
    }

    suspend fun natType(proxy: String): NatType {
        val answer = run(proxy, "nat")
        return NatType(
            mapping = NatBehavior.fromWire(answer.string("mapping")),
            filtering = NatBehavior.fromWire(answer.string("filtering")),
            mappedAddress = answer.string("mapped"),
            server = answer.string("server"),
            sourceHidden = answer.string("note") == "source-hidden",
        )
    }

    /**
     * The NAT that UDP to the internet meets now: the test goes wherever the
     * engine's mode, rules and group selections send an app's UDP to its
     * STUN server, so the answer follows a node switch.
     */
    suspend fun routedNatType(): NatType = natType(proxy = "")

    suspend fun download(proxy: String): Throughput = run(proxy, "download").throughput()

    suspend fun upload(proxy: String): Throughput = run(proxy, "upload").throughput()

    /** Fetches exit-IP sources through [proxy] instead of this process's sockets. */
    fun exitIpFetcher(proxy: String) = ExitIpFetcher { url ->
        val response = get(proxy, url)
        // Rate limits come back as 403 (ip.sb) or 429 (ipinfo.io).
        if (response.status !in 200..299) throw IOException("${url.host} returned HTTP ${response.status}")
        response.body
    }

    private suspend fun run(proxy: String, kind: String, arg: String = ""): JsonObject {
        val id = ids.incrementAndGet()
        val text = coroutineScope {
            val call = async(Dispatchers.IO) { transport.probe(id, proxy, kind, arg) }
            try {
                call.await()
            } catch (e: CancellationException) {
                // The blocked call returns once `:vpn` has ended the probe;
                // this scope waits for it, so nothing outlives the caller.
                transport.cancel(id)
                throw e
            }
        }
        val answer = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw IOException("unreadable $kind answer", e)
        }
        answer.string("error")?.let { message ->
            throw ProxyProbeException(message, answer.string("code") ?: "failed")
        }
        return answer
    }

    private fun JsonObject.throughput() = Throughput(bytes = long("bytes"), millis = long("ms"))

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: throw IOException("probe answer has no $key")

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: throw IOException("probe answer has no $key")
}
