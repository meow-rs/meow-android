package io.github.madeye.meow.api

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

/**
 * Client for the embedded engine's Clash-compatible controller API.
 *
 * The engine runs in-process (in `:vpn`) and binds its listener on
 * `127.0.0.1:9090` — see `MeowInstance.start`. [baseUrl] is a constructor
 * parameter rather than a hardcoded constant purely so tests can point it at a
 * `MockWebServer`, and so the iOS-style hardening (random port + minted secret)
 * stays a one-line change later.
 *
 * Ported from `flutter_module/lib/services/meow_api.dart`.
 */
class MeowApi(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = MeowJson,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:9090"

        /**
         * Probe target for the delay endpoints: HTTPS to Cloudflare's anycast
         * 204, the same URL sing-box and clash clients use. Plain HTTP to a
         * Google anycast address lets a transit-path hijack or captive portal
         * answer the probe in the endpoint's place.
         */
        const val DELAY_TEST_URL = "https://cp.cloudflare.com/generate_204"

        /**
         * Delay probes in flight at once. Each can hold its call open for the
         * full probe timeout, so a large group is worked through in waves.
         */
        const val PROBE_CONCURRENCY = 8

        /**
         * Retries for a probe that never got a dial out (HTTP 503). The wait
         * doubles from 500 ms, so a transient answer costs at most 3.5 s.
         */
        private const val DELAY_PROBE_RETRIES = 3

        /** Same page size as meow-ios's DNS screen (and the engine's default). */
        const val DNS_RESULTS_LIMIT = 256

        private val JSON_MEDIA = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Never route loopback through a system proxy — installing proxies
            // is this app's entire job, and honouring one here would loop.
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            // Keeps the /logs socket from being reaped while idle.
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Delay probes get their own dispatcher. OkHttp runs at most five calls per
     * host by default, so sharing [client]'s would cap a group test below
     * [PROBE_CONCURRENCY] and queue every other API call behind probes that
     * can each take seconds to time out.
     */
    private val probeDispatcher by lazy {
        Dispatcher().apply { maxRequestsPerHost = PROBE_CONCURRENCY }
    }

    /**
     * [updateRuleProvider]'s client. The engine answers that call only after
     * downloading the provider, and gives the download 15 s to connect and
     * 60 s to read, so [client]'s 10 s read timeout would abandon an ordinary
     * refresh of a large list while the engine is still working on it. Built
     * once from [client], so it shares its connection pool and dispatcher.
     */
    private val providerUpdateClient by lazy {
        client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    }

    // -------------------------------------------------------------------------
    // Proxies
    // -------------------------------------------------------------------------

    /** `/proxies`, plus the profile's group order from [proxyGroupOrder]. */
    suspend fun proxies(): ProxiesResult {
        val body = get("/proxies", operation = "proxies")
        val result = decode("proxies") { ProxiesResult.parse(json.parseToJsonElement(body).jsonObject) }
        return result.copy(groupOrder = proxyGroupOrder())
    }

    /**
     * Group names in `proxy-groups:` order. `/proxies` is a map and has lost
     * that order; `/api/proxy-groups` lists the engine's parsed copy of the
     * profile instead. Re-read on every call rather than cached: a config
     * reload changes it without telling this client, and it is one loopback
     * round trip.
     *
     * Best effort: the order is cosmetic, so a failure here sorts the groups
     * by name instead of failing a group list that is otherwise complete.
     */
    private suspend fun proxyGroupOrder(): List<String> =
        try {
            val body = get("/api/proxy-groups", operation = "proxyGroups")
            decode("proxyGroups") {
                json.decodeFromString(ListSerializer(ConfiguredGroup.serializer()), body)
                    .map { it.name }
                    .filter { it.isNotEmpty() }
            }
        } catch (e: IOException) {
            // IOException rather than MeowApiException: a body that breaks off
            // mid-read throws a bare one. Logged because the only symptom,
            // groups sorted by name, looks deliberate on screen.
            Timber.w(e, "loading proxy group order failed; sorting groups by name")
            emptyList()
        }

    suspend fun selectProxy(group: String, name: String) {
        val payload = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name))),
        )
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("proxies").addPathSegment(group).build())
            .put(payload.toRequestBody(JSON_MEDIA))
            .build()
        execute(request, "selectProxy", okCodes = setOf(200, 204))
    }

    /**
     * Returns an url-test or fallback group to automatic selection, dropping a
     * member pinned through [selectProxy]. Selectors have nothing to unpin and
     * answer 400.
     */
    suspend fun unfixProxy(group: String) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("proxies").addPathSegment(group).build())
            .delete()
            .build()
        execute(request, "unfixProxy", okCodes = setOf(200, 204))
    }

    suspend fun testProxyDelay(
        name: String,
        url: String = DELAY_TEST_URL,
        timeoutMs: Int = 5_000,
    ): Int {
        val requestUrl = baseUrl.newBuilder()
            .addPathSegment("proxies").addPathSegment(name).addPathSegment("delay")
            .addQueryParameter("url", url)
            .addQueryParameter("timeout", timeoutMs.toString())
            .build()
        // The engine answers only once the probe ends, so the read has to
        // outlast the probe's own timeout.
        val scoped = client.newBuilder()
            .dispatcher(probeDispatcher)
            .readTimeout(timeoutMs + 5_000L, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(requestUrl).build()
        // A 503 is UrlTestError::Transport — the probe never got a dial out,
        // routine just after a connect while the bypass path is still
        // settling or on a DNS blip, so it retries on the same 500 ms
        // doubling ramp as the log stream. Any other status fails as before.
        var attempt = 0
        while (true) {
            try {
                val body = execute(request, "testProxyDelay", client = scoped)
                return decode("testProxyDelay") {
                    json.parseToJsonElement(body).jsonObject["delay"]?.jsonPrimitive?.intOrNull ?: 0
                }
            } catch (e: MeowApiException.Http) {
                if (e.code != 503 || attempt == DELAY_PROBE_RETRIES) throw e
                delay(500L shl attempt++)
            }
        }
    }

    /**
     * Probes every member of a group. The default timeout is far longer than the
     * client's read timeout, so this call gets its own timeout budget.
     */
    suspend fun testGroupDelay(
        group: String,
        url: String = DELAY_TEST_URL,
        timeoutMs: Int = 60_000,
    ): Map<String, Int> {
        val requestUrl = baseUrl.newBuilder()
            .addPathSegment("group").addPathSegment(group).addPathSegment("delay")
            .addQueryParameter("url", url)
            .addQueryParameter("timeout", timeoutMs.toString())
            .build()
        val scoped = client.newBuilder()
            .callTimeout(timeoutMs + 5_000L, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs + 5_000L, TimeUnit.MILLISECONDS)
            .build()
        val body = execute(Request.Builder().url(requestUrl).build(), "testGroupDelay", client = scoped)
        // Success is a flat name -> delay map; failures come back as
        // {"message": "..."}, so non-numeric values are dropped rather than
        // blowing up the whole result.
        return decode("testGroupDelay") {
            json.parseToJsonElement(body).jsonObject.mapNotNull { (key, value) ->
                value.jsonPrimitive.intOrNull?.let { key to it }
            }.toMap()
        }
    }

    // -------------------------------------------------------------------------
    // Rules / connections / configs
    // -------------------------------------------------------------------------

    suspend fun rules(): List<Rule> {
        val body = get("/rules", operation = "rules")
        return decode("rules") { json.decodeFromString(RulesResponse.serializer(), body).rules }
    }

    /**
     * The profile's `rule-providers:`, sorted by name, case-insensitively.
     * The engine keeps them in a map and sends that map as is, so the order
     * the config declares them in is gone by the time it arrives; sorting
     * keeps the list from reshuffling between refreshes. A value without a
     * name of its own takes its map key.
     */
    suspend fun ruleProviders(): List<RuleProviderInfo> {
        val body = get("/providers/rules", operation = "ruleProviders")
        return decode("ruleProviders") {
            json.decodeFromString(RuleProvidersResponse.serializer(), body).providers
                .map { (key, provider) -> if (provider.name.isBlank()) provider.copy(name = key) else provider }
                .sortedWith(
                    compareBy<RuleProviderInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                        // Names differing only in case must not swap places between refreshes.
                        .thenBy { it.name },
                )
        }
    }

    /**
     * Re-downloads an HTTP rule provider; the engine re-reads a File one and
     * has nothing to do for an inline one. It answers only once that is done:
     * 204, 404 for a name it does not know, 503 when the download failed. Both
     * failures surface as [MeowApiException.Http].
     */
    suspend fun updateRuleProvider(name: String) {
        val request = Request.Builder()
            .url(
                baseUrl.newBuilder()
                    .addPathSegment("providers").addPathSegment("rules").addPathSegment(name)
                    .build(),
            )
            .put(ByteArray(0).toRequestBody())
            .build()
        execute(request, "updateRuleProvider", okCodes = setOf(200, 204), client = providerUpdateClient)
    }

    suspend fun connections(): ConnectionsSnapshot {
        val body = get("/connections", operation = "connections")
        return decode("connections") {
            json.decodeFromString(ConnectionsSnapshot.serializer(), body)
        }
    }

    suspend fun closeConnection(id: String) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("connections").addPathSegment(id).build())
            .delete()
            .build()
        execute(request, "closeConnection", okCodes = setOf(200, 204))
    }

    suspend fun closeAllConnections() {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("connections").build())
            .delete()
            .build()
        execute(request, "closeAllConnections", okCodes = setOf(200, 204))
    }

    suspend fun configs(): RuntimeConfig {
        val body = get("/configs", operation = "configs")
        return decode("configs") { json.decodeFromString(RuntimeConfig.serializer(), body) }
    }

    suspend fun patchConfigs(patch: JsonObject) {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("configs").build())
            .patch(json.encodeToString(JsonObject.serializer(), patch).toRequestBody(JSON_MEDIA))
            .build()
        execute(request, "patchConfigs", okCodes = setOf(200, 204))
    }

    /** Switches routing for new flows; open connections keep their route. */
    suspend fun setMode(mode: RouteMode) {
        patchConfigs(JsonObject(mapOf("mode" to kotlinx.serialization.json.JsonPrimitive(mode.wire))))
    }

    // -------------------------------------------------------------------------
    // DNS
    // -------------------------------------------------------------------------

    /**
     * The engine's DNS cache. [search] is matched by the engine, against the
     * name, the answer IPs and the upstream, case-insensitively; [limit] caps
     * the entries returned (the engine clamps it to 1024).
     */
    suspend fun dnsResults(search: String? = null, limit: Int = DNS_RESULTS_LIMIT): List<DnsResult> {
        val url = baseUrl.newBuilder()
            .addPathSegment("dns").addPathSegment("results")
            .addQueryParameter("limit", limit.toString())
            .apply { if (!search.isNullOrBlank()) addQueryParameter("search", search.trim()) }
            .build()
        val body = execute(Request.Builder().url(url).build(), "dnsResults")
        return decode("dnsResults") {
            json.decodeFromString(ListSerializer(DnsResult.serializer()), body)
        }
    }

    // -------------------------------------------------------------------------
    // Streams
    // -------------------------------------------------------------------------

    /**
     * Live engine logs.
     *
     * This replaces the old `getLogs` JNI call, which could never have worked
     * from the UI process: the Rust ring buffer it drains is a per-process
     * `static`, and the engine lives in `:vpn`. The WebSocket crosses the
     * process boundary over loopback, so it sees the real log stream.
     */
    fun logs(level: String = "info"): Flow<LogEntry> {
        val url = baseUrl.newBuilder()
            .scheme(if (baseUrl.isHttps) "https" else "http")
            .addPathSegment("logs")
            .addQueryParameter("level", level)
            .build()
        return streamJsonLines(url) { json.decodeFromString(LogEntry.serializer(), it) }
    }

    private fun <T> streamJsonLines(url: HttpUrl, parse: (String) -> T): Flow<T> {
        // Reset the backoff whenever a connection actually delivers something,
        // so a long-lived stream that drops doesn't inherit the previous ramp.
        val attemptOffset = java.util.concurrent.atomic.AtomicInteger(0)
        return callbackFlow {
            val request = Request.Builder().url(url).build()
            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        attemptOffset.set(0)
                        // A single malformed frame must not tear down the stream.
                        val parsed = try {
                            parse(text)
                        } catch (e: Exception) {
                            return
                        }
                        trySend(parsed)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        close(t)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // OkHttp does not answer a peer close frame for us; without
                        // this the socket lingers and onClosed never arrives.
                        webSocket.close(code, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        // A clean close still means "reconnect": the engine
                        // drops the socket on restart, and the stream is
                        // supposed to outlive that. Completing normally here
                        // would end the flow for good, since retryWhen below
                        // only sees exceptions.
                        close(StreamClosed(code, reason))
                    }
                },
            )
            awaitClose { socket.cancel() }
        }.retryWhen { cause, _ ->
            if (cause is kotlinx.coroutines.CancellationException) return@retryWhen false
            val attempt = attemptOffset.getAndIncrement().coerceAtMost(6)
            // 500ms doubling to a 30s ceiling — same ramp as the Dart client.
            delay((500L shl attempt).coerceAtMost(30_000L))
            true
        }
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    private suspend fun get(path: String, operation: String): String {
        val url = baseUrl.newBuilder()
            .addPathSegments(path.trimStart('/'))
            .build()
        return execute(Request.Builder().url(url).build(), operation)
    }

    private suspend fun execute(
        request: Request,
        operation: String,
        okCodes: Set<Int> = setOf(200),
        client: OkHttpClient = this.client,
    ): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw MeowApiException.Unreachable(e)
        }
        response.use {
            if (it.code !in okCodes) throw MeowApiException.Http(operation, it.code)
            it.body?.string().orEmpty()
        }
    }

    private inline fun <T> decode(operation: String, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            throw MeowApiException.Decode(operation, e)
        }
}

/**
 * Lenient by design: the engine is the source of truth and adds fields between
 * releases, so unknown keys must never fail a response the UI could have used.
 */
val MeowJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

/** Signals that the peer hung up, so the stream re-dials rather than ending. */
private class StreamClosed(code: Int, reason: String) :
    IOException("websocket closed ($code${if (reason.isEmpty()) "" else ": $reason"})")

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // A response delivered after cancellation would otherwise leak
            // its body and connection.
            if (cont.isActive) cont.resume(response) else response.close()
        }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
