package io.github.madeye.meow.net

import io.github.madeye.meow.api.await
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** The public address this device's traffic leaves from, as one geo-IP service sees it. */
data class ExitIp(
    val ip: String,
    /** ISO 3166-1 alpha-2, upper-case; null when the service did not give a usable one. */
    val countryCode: String?,
    /** The service's own country name — English only, so only a fallback for display. */
    val country: String?,
    val asn: Int?,
    /** The ISP, or failing that the AS organisation. */
    val org: String?,
)

/**
 * A keyless geo-IP endpoint that describes the caller's own address, plus the
 * parser for its answer. Each service has its own JSON shape and its own way
 * of saying "no", so every source carries its own parser.
 */
class ExitIpSource(val url: HttpUrl, internal val parse: (JsonObject) -> ExitIp) {
    companion object {
        fun ipSb(url: HttpUrl = "https://api.ip.sb/geoip".toHttpUrl()) = ExitIpSource(url, ::parseIpSb)
        fun ipwhoIs(url: HttpUrl = "https://ipwho.is/".toHttpUrl()) = ExitIpSource(url, ::parseIpwhoIs)
        fun ipinfo(url: HttpUrl = "https://ipinfo.io/json".toHttpUrl()) = ExitIpSource(url, ::parseIpinfo)
    }
}

/**
 * Asks public geo-IP services which address this device's traffic leaves from.
 *
 * Runs in the UI process, whose sockets go through the TUN like any other
 * app's (VpnService does not exclude the app's own uid), so while connected
 * the answer is the proxy's exit; while disconnected it is the direct one.
 * The exception is a per-app allow-list without Meow on it (or a bypass-list
 * with it): then even a connected lookup goes direct, and the caller has to
 * say so.
 *
 * Sources are tried in order rather than raced: a race would send every check
 * to every service, and each one sees the user's address. A source that
 * refuses (rate limit, challenge page) falls through at once; one that hangs
 * gets [ATTEMPT_TIMEOUT_MS], and the whole lookup gives up after
 * [TOTAL_TIMEOUT_MS] — a hang usually means the route itself is down, and
 * then every source would hang the same way.
 */
class ExitIpLookup(
    private val sources: List<ExitIpSource> = listOf(
        ExitIpSource.ipSb(),
        ExitIpSource.ipwhoIs(),
        ExitIpSource.ipinfo(),
    ),
    private val client: OkHttpClient = defaultClient(),
    private val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
) {
    companion object {
        const val ATTEMPT_TIMEOUT_MS = 5_000L
        const val TOTAL_TIMEOUT_MS = 8_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Measure the path apps' sockets take — the tunnel — not a Wi-Fi
            // HTTP proxy that only proxy-aware clients would honour.
            .proxy(Proxy.NO_PROXY)
            // No keep-alive: a pooled connection keeps the route it was opened
            // on, and the engine (v0.21.2) does not tear down live relays on
            // DELETE /connections, so after a node or mode switch a reused
            // connection would report the old exit. Every check dials fresh.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .callTimeout(ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Throws [IOException] when no source answered usably in time. */
    suspend fun lookup(): ExitIp = withContext(Dispatchers.IO) {
        val failures = mutableListOf<IOException>()
        val found = withTimeoutOrNull(totalTimeoutMs) {
            sources.firstNotNullOfOrNull { source ->
                try {
                    fetch(source)
                } catch (e: IOException) {
                    Timber.d(e, "exit IP: %s failed", source.url.host)
                    failures += e
                    null
                }
            }
        }
        if (found != null) return@withContext found
        val what = if (failures.size < sources.size) "exit IP lookup timed out" else "no exit IP source answered"
        // In the message rather than as suppressed exceptions: coroutine
        // stack-trace recovery rebuilds the exception and drops those.
        val reasons = failures.joinToString("; ") { it.message.orEmpty() }
        throw IOException(if (reasons.isEmpty()) what else "$what ($reasons)")
    }

    private suspend fun fetch(source: ExitIpSource): ExitIp {
        val request = Request.Builder()
            .url(source.url)
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(request).await().use { response ->
            // Rate limits come back as 403 (ip.sb) or 429 (ipinfo.io).
            if (!response.isSuccessful) throw IOException("${source.url.host} returned HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        return try {
            source.parse(Json.parseToJsonElement(body).jsonObject)
        } catch (e: IllegalArgumentException) {
            // Not JSON at all (a Cloudflare challenge page), or not an object.
            throw IOException("${source.url.host}: unreadable answer", e)
        }
    }
}

// -----------------------------------------------------------------------------
// Parsers — one per source, fed the decoded body of a 2xx answer.
// -----------------------------------------------------------------------------

/** `{"ip": "…", "country_code": "FI", "country": "Finland", "asn": 24940, "isp": "…", …}` */
internal fun parseIpSb(json: JsonObject): ExitIp = ExitIp(
    ip = json.requireIp("api.ip.sb"),
    countryCode = countryCode(json.string("country_code")),
    country = json.string("country"),
    asn = json.int("asn"),
    org = json.string("isp") ?: json.string("asn_organization") ?: json.string("organization"),
)

/**
 * `{"ip": "…", "success": true, "country_code": "FI", "connection": {"asn": …, "isp": …}, …}`.
 * Refusals (quota, reserved range) are `"success": false` with a `message`,
 * often on HTTP 200.
 */
internal fun parseIpwhoIs(json: JsonObject): ExitIp {
    if (json.boolean("success") == false) {
        throw IOException("ipwho.is refused: ${json.string("message") ?: "no reason given"}")
    }
    val connection = json["connection"] as? JsonObject
    return ExitIp(
        ip = json.requireIp("ipwho.is"),
        countryCode = countryCode(json.string("country_code")),
        country = json.string("country"),
        asn = connection?.int("asn"),
        org = connection?.string("isp") ?: connection?.string("org"),
    )
}

/**
 * `{"ip": "…", "country": "FI", "org": "AS24940 Hetzner Online GmbH", …}` —
 * the country is only a code, and the ASN is folded into `org`.
 */
internal fun parseIpinfo(json: JsonObject): ExitIp {
    // A private source address: the request never reached the internet as us.
    if (json.boolean("bogon") == true) throw IOException("ipinfo.io: bogon address")
    val org = json.string("org")
    val match = org?.let { AS_PREFIX.matchEntire(it) }
    return ExitIp(
        ip = json.requireIp("ipinfo.io"),
        countryCode = countryCode(json.string("country")),
        country = null,
        asn = match?.groupValues?.get(1)?.toIntOrNull(),
        org = if (match != null) match.groupValues[2].ifBlank { null } else org,
    )
}

private val AS_PREFIX = Regex("""AS(\d+)\s*(.*)""")

private fun countryCode(raw: String?): String? =
    raw?.uppercase()?.takeIf { code -> code.length == 2 && code.all { it in 'A'..'Z' } }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.requireIp(service: String): String =
    string("ip") ?: throw IOException("$service: answer has no ip")
