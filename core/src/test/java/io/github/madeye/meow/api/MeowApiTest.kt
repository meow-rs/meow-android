package io.github.madeye.meow.api

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeowApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MeowApi

    @Before
    fun setUp() {
        server = MockWebServer()
        // proxies() follows up with a group-order request that most tests
        // don't queue; answer it with a 404 at once rather than stalling
        // until the client's read timeout.
        server.dispatcher = QueueDispatcher().apply { setFailFast(true) }
        server.start()
        api = MeowApi(baseUrl = server.url("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    /**
     * Answers `/proxies` and `/api/proxy-groups` by path, so a test holds
     * whichever order [MeowApi.proxies] fetches them in.
     */
    private fun serveProxies(proxies: String, proxyGroups: MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/proxies" -> MockResponse().setBody(proxies)
                    "/api/proxy-groups" -> proxyGroups
                    else -> MockResponse().setResponseCode(404)
                }
        }
    }

    /** `GET /api/proxy-groups` as the engine renders it: an array in `proxy-groups:` order. */
    private fun configuredGroups(vararg names: String) = MockResponse().setBody(
        names.joinToString(prefix = "[", postfix = "]") { name ->
            """{"name": "$name", "type": "select", "proxies": ["HK 01"], "now": "HK 01", "url": null, "interval": null, "tolerance": null}"""
        },
    )

    // -------------------------------------------------------------------------
    // /proxies — the heterogeneous map, discriminated by `type` value
    // -------------------------------------------------------------------------

    @Test
    fun `proxies splits groups from leaf nodes`() = runTest {
        enqueue(
            """
            {"proxies": {
              "Proxy":     {"type": "Selector", "now": "Tokyo 01", "all": ["Tokyo 01", "SG 02"]},
              "Tokyo 01":  {"type": "Shadowsocks", "history": [{"time": "2026-08-17T10:00:00Z", "delay": 76}]},
              "SG 02":     {"type": "Trojan", "history": []}
            }}
            """.trimIndent(),
        )

        val result = api.proxies()

        assertEquals(setOf("Proxy"), result.groups.keys)
        assertEquals(setOf("Tokyo 01", "SG 02"), result.proxies.keys)
        assertEquals("Tokyo 01", result.groups.getValue("Proxy").now)
        assertEquals(listOf("Tokyo 01", "SG 02"), result.groups.getValue("Proxy").all)
        assertEquals(76, result.proxies.getValue("Tokyo 01").latestDelay)
        assertEquals(0, result.proxies.getValue("SG 02").latestDelay)
    }

    @Test
    fun `selectableGroups hides GLOBAL and sorts case-insensitively`() = runTest {
        enqueue(
            """
            {"proxies": {
              "zeta":   {"type": "Selector", "now": "a", "all": []},
              "Alpha":  {"type": "URLTest",  "now": "b", "all": []},
              "GLOBAL": {"type": "Selector", "now": "c", "all": []}
            }}
            """.trimIndent(),
        )

        val names = api.proxies().selectableGroups.map { it.name }

        assertEquals(listOf("Alpha", "zeta"), names)
    }

    @Test
    fun `visibleGroups lists GLOBAL first only in global mode`() = runTest {
        enqueue(
            """
            {"proxies": {
              "Proxy":  {"type": "Selector", "now": "a", "all": []},
              "GLOBAL": {"type": "Selector", "now": "Proxy", "all": ["Proxy", "DIRECT"]}
            }}
            """.trimIndent(),
        )

        val result = api.proxies()

        assertEquals(listOf("GLOBAL", "Proxy"), result.visibleGroups(RouteMode.Global).map { it.name })
        assertEquals(listOf("Proxy"), result.visibleGroups(RouteMode.Rule).map { it.name })
        assertEquals(listOf("Proxy"), result.visibleGroups(RouteMode.Direct).map { it.name })
        // Mode not known yet (e.g. /configs failed): never guess global.
        assertEquals(listOf("Proxy"), result.visibleGroups(null).map { it.name })
    }

    // -------------------------------------------------------------------------
    // Group order — /api/proxy-groups carries the profile's `proxy-groups:` order
    // -------------------------------------------------------------------------

    @Test
    fun `groups follow the profile order from proxy-groups`() = runTest {
        serveProxies(GROUPED_PROXIES, configuredGroups("Proxy", "Streaming", "Auto"))

        val result = api.proxies()

        assertEquals(listOf("Proxy", "Streaming", "Auto"), result.selectableGroups.map { it.name })
        assertEquals(
            setOf("/proxies", "/api/proxy-groups"),
            List(server.requestCount) { server.takeRequest().requestUrl!!.encodedPath }.toSet(),
        )
    }

    @Test
    fun `groups the profile does not declare follow by name`() = runTest {
        // "zeta" and "Auto" are live but undeclared; "Gone" is declared but no
        // longer live (a reload landed between the two fetches).
        serveProxies(
            """
            {"proxies": {
              "zeta":      {"type": "Selector", "now": "HK 01", "all": ["HK 01"]},
              "Auto":      {"type": "URLTest",  "now": "HK 01", "all": ["HK 01"]},
              "Proxy":     {"type": "Selector", "now": "Auto",  "all": ["Auto", "HK 01"]},
              "Streaming": {"type": "Selector", "now": "Proxy", "all": ["Proxy"]},
              "HK 01":     {"type": "Shadowsocks"}
            }}
            """.trimIndent(),
            configuredGroups("Gone", "Streaming", "Proxy"),
        )

        val names = api.proxies().selectableGroups.map { it.name }

        assertEquals(listOf("Streaming", "Proxy", "Auto", "zeta"), names)
    }

    @Test
    fun `GLOBAL leads in global mode and hides otherwise whatever the profile order`() = runTest {
        // A profile may declare GLOBAL itself, anywhere in its list.
        serveProxies(GROUPED_PROXIES, configuredGroups("Streaming", "GLOBAL", "Proxy", "Auto"))

        val result = api.proxies()

        assertEquals(
            listOf("GLOBAL", "Streaming", "Proxy", "Auto"),
            result.visibleGroups(RouteMode.Global).map { it.name },
        )
        for (mode in listOf(RouteMode.Rule, RouteMode.Direct, null)) {
            assertEquals(
                "mode $mode",
                listOf("Streaming", "Proxy", "Auto"),
                result.visibleGroups(mode).map { it.name },
            )
        }
    }

    @Test
    fun `groups fall back to name order when proxy-groups fails`() = runTest {
        val failures = mapOf(
            "404" to MockResponse().setResponseCode(404),
            "500" to MockResponse().setResponseCode(500),
            "dropped connection" to MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            // Not wrapped as Unreachable: the body read itself throws.
            "truncated body" to configuredGroups("Proxy", "Streaming", "Auto")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        for ((label, failure) in failures) {
            serveProxies(GROUPED_PROXIES, failure)

            val result = api.proxies()

            assertEquals(label, listOf("Auto", "Proxy", "Streaming"), result.selectableGroups.map { it.name })
            assertEquals(label, setOf("HK 01", "SG 02", "DIRECT"), result.proxies.keys)
        }
    }

    @Test
    fun `groups fall back to name order on an unexpected proxy-groups body`() = runTest {
        val bodies = listOf(
            "not json",
            """{"message": "Resource not found"}""",
            """["Proxy", "Auto"]""",
            """[{"type": "select"}, {"name": null}]""",
            "[]",
        )
        for (body in bodies) {
            serveProxies(GROUPED_PROXIES, MockResponse().setBody(body))

            val names = api.proxies().selectableGroups.map { it.name }

            assertEquals(body, listOf("Auto", "Proxy", "Streaming"), names)
        }
    }

    @Test
    fun `names differing only in case keep one order`() {
        fun group(name: String) = ProxyGroup(name, "Selector", now = "", all = emptyList(), history = emptyList())
        val one = ProxiesResult(mapOf("b" to group("b"), "B" to group("B")), emptyMap())
        val other = ProxiesResult(mapOf("B" to group("B"), "b" to group("b")), emptyMap())

        assertEquals(listOf("B", "b"), one.selectableGroups.map { it.name })
        assertEquals(one.selectableGroups, other.selectableGroups)
    }

    @Test
    fun `proxy history accepts both Go string time and Rust SystemTime`() = runTest {
        enqueue(
            """
            {"proxies": {
              "go":   {"type": "Shadowsocks", "history": [{"time": "2026-08-17T10:00:00Z", "delay": 10}]},
              "rust": {"type": "Shadowsocks", "history": [{"time": {"secs_since_epoch": 1786960800, "nanos_since_epoch": 0}, "delay": 20}]}
            }}
            """.trimIndent(),
        )

        val result = api.proxies()

        // 2026-08-17T10:00:00Z in both encodings.
        assertEquals(1786960800000L, result.proxies.getValue("go").history.single().timeMillis)
        assertEquals(1786960800000L, result.proxies.getValue("rust").history.single().timeMillis)
    }

    // -------------------------------------------------------------------------
    // Delay probes
    // -------------------------------------------------------------------------

    @Test
    fun `proxies reads an url-test group's testUrl and leaves a selector's null`() = runTest {
        enqueue(
            """
            {"proxies": {
              "Auto":  {"type": "URLTest", "now": "a", "all": ["a"], "testUrl": "https://cp.cloudflare.com/"},
              "Proxy": {"type": "Selector", "now": "a", "all": ["a"]}
            }}
            """.trimIndent(),
        )

        val groups = api.proxies().groups

        assertEquals("https://cp.cloudflare.com/", groups.getValue("Auto").testUrl)
        assertNull(groups.getValue("Proxy").testUrl)
    }

    @Test
    fun `testProxyDelay sends url and timeout and reads the delay`() = runTest {
        enqueue("""{"delay": 142}""")

        val delay = api.testProxyDelay("Tokyo 01", timeoutMs = 5000)

        assertEquals(142, delay)
        val request = server.takeRequest()
        assertEquals("/proxies/Tokyo%2001/delay", request.requestUrl!!.encodedPath)
        assertEquals(MeowApi.DELAY_TEST_URL, request.requestUrl!!.queryParameter("url"))
        assertEquals("5000", request.requestUrl!!.queryParameter("timeout"))
    }

    @Test
    fun `testGroupDelay drops non-numeric entries from an error body`() = runTest {
        enqueue("""{"message": "group not found"}""")

        assertTrue(api.testGroupDelay("Nope").isEmpty())
    }

    @Test
    fun `testGroupDelay returns the name to delay map`() = runTest {
        enqueue("""{"Tokyo 01": 76, "SG 02": 121}""")

        assertEquals(mapOf("Tokyo 01" to 76, "SG 02" to 121), api.testGroupDelay("Proxy"))
    }

    // -------------------------------------------------------------------------
    // Mutations
    // -------------------------------------------------------------------------

    @Test
    fun `selectProxy PUTs the node name and accepts 204`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.selectProxy("Proxy", "Tokyo 01")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/proxies/Proxy", request.requestUrl!!.encodedPath)
        assertEquals("""{"name":"Tokyo 01"}""", request.body.readUtf8())
    }

    @Test
    fun `unfixProxy DELETEs the group and accepts 204`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.unfixProxy("Auto Select")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/proxies/Auto%20Select", request.requestUrl!!.encodedPath)
    }

    @Test
    fun `setMode PATCHes configs with the wire value`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.setMode(RouteMode.Global)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/configs", request.requestUrl!!.encodedPath)
        assertEquals("""{"mode":"global"}""", request.body.readUtf8())
    }

    @Test
    fun `setMode surfaces an engine rejection`() = runTest {
        enqueue("""{"message": "Body invalid"}""", code = 400)

        assertThrows(MeowApiException.Http::class.java) {
            kotlinx.coroutines.runBlocking { api.setMode(RouteMode.Direct) }
        }
    }

    @Test
    fun `closeConnection DELETEs the id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.closeConnection("abc-123")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/connections/abc-123", request.requestUrl!!.encodedPath)
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    @Test
    fun `rules unwraps the rules array`() = runTest {
        enqueue("""{"rules": [{"type": "DOMAIN", "payload": "example.com", "proxy": "DIRECT"}]}""")

        val rules = api.rules()

        assertEquals(1, rules.size)
        assertEquals("DOMAIN", rules[0].type)
        assertEquals("DIRECT", rules[0].proxy)
    }

    @Test
    fun `configs maps kebab-case engine keys`() = runTest {
        enqueue("""{"mode": "global", "allow-lan": true, "log-level": "debug", "mixed-port": 7891}""")

        val config = api.configs()

        assertEquals("global", config.mode)
        assertEquals(RouteMode.Global, config.routeMode)
        assertTrue(config.allowLan)
        assertEquals("debug", config.logLevel)
        assertEquals(7891, config.mixedPort)
    }

    @Test
    fun `unknown fields do not break decoding`() = runTest {
        enqueue("""{"downloadTotal": 5, "uploadTotal": 3, "connections": [], "somethingNew": 1}""")

        val snapshot = api.connections()

        assertEquals(5L, snapshot.downloadTotal)
        assertEquals(3L, snapshot.uploadTotal)
    }

    // -------------------------------------------------------------------------
    // Errors
    // -------------------------------------------------------------------------

    @Test
    fun `non-success status raises Http with the operation name`() = runTest {
        enqueue("nope", code = 404)

        val error = assertThrows(MeowApiException.Http::class.java) {
            kotlinx.coroutines.runBlocking { api.rules() }
        }
        assertEquals("rules", error.operation)
        assertEquals(404, error.code)
    }

    @Test
    fun `a dead engine raises Unreachable rather than a raw IOException`() = runTest {
        val deadPort = server.port
        server.shutdown()
        val offline = MeowApi(baseUrl = "http://127.0.0.1:$deadPort".toHttpUrl())

        assertThrows(MeowApiException.Unreachable::class.java) {
            kotlinx.coroutines.runBlocking { offline.rules() }
        }
    }

    @Test
    fun `malformed json raises Decode`() = runTest {
        enqueue("this is not json")

        assertThrows(MeowApiException.Decode::class.java) {
            kotlinx.coroutines.runBlocking { api.proxies() }
        }
    }

    @Test
    fun `empty history yields a null-safe latest delay`() = runTest {
        enqueue("""{"proxies": {"a": {"type": "Shadowsocks"}}}""")

        val proxy = api.proxies().proxies.getValue("a")

        assertEquals(0, proxy.latestDelay)
        assertNull(proxy.history.firstOrNull())
    }

    private companion object {
        /** `/proxies` for a profile declaring Proxy, Streaming, Auto, plus the auto-created GLOBAL. */
        val GROUPED_PROXIES = """
            {"proxies": {
              "Auto":      {"type": "URLTest",  "now": "HK 01", "all": ["HK 01", "SG 02"]},
              "GLOBAL":    {"type": "Selector", "now": "Proxy", "all": ["Auto", "DIRECT", "Proxy", "Streaming"]},
              "Proxy":     {"type": "Selector", "now": "Auto",  "all": ["Auto", "HK 01", "SG 02"]},
              "Streaming": {"type": "Selector", "now": "Proxy", "all": ["Proxy", "DIRECT"]},
              "HK 01":     {"type": "Shadowsocks"},
              "SG 02":     {"type": "Trojan"},
              "DIRECT":    {"type": "Direct"}
            }}
        """.trimIndent()
    }
}
