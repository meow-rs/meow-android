package io.github.madeye.meow.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fixtures under `resources/exit-ip/` are real answers recorded from each
 * service (2026-10), with the caller's address swapped for 203.0.113.7
 * (TEST-NET-3). The IPv6 and refusal fixtures are verbatim.
 */
class ExitIpLookupTest {

    private lateinit var server: MockWebServer

    /** Path -> answer; anything unrouted is a 404, like a wrong endpoint. */
    private val routes = mutableMapOf<String, MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.path] ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/exit-ip/$name")) { "missing fixture $name" }.readText()

    private fun answer(path: String, body: String, code: Int = 200) {
        routes[path] = MockResponse().setResponseCode(code).setBody(body)
    }

    private val ipSb get() = ExitIpSource.ipSb(server.url("/ipsb"))
    private val ipwhoIs get() = ExitIpSource.ipwhoIs(server.url("/ipwho"))
    private val ipinfo get() = ExitIpSource.ipinfo(server.url("/ipinfo"))

    private fun lookup(
        vararg sources: ExitIpSource,
        attemptTimeoutMs: Long = ExitIpLookup.ATTEMPT_TIMEOUT_MS,
        totalTimeoutMs: Long = ExitIpLookup.TOTAL_TIMEOUT_MS,
    ) = ExitIpLookup(
        sources = sources.toList(),
        client = ExitIpLookup.defaultClient().newBuilder()
            .callTimeout(attemptTimeoutMs, TimeUnit.MILLISECONDS)
            .build(),
        totalTimeoutMs = totalTimeoutMs,
    )

    private fun requestedPaths(): List<String?> =
        List(server.requestCount) { server.takeRequest().path }

    // -------------------------------------------------------------------------
    // One parser per source
    // -------------------------------------------------------------------------

    @Test
    fun `ip_sb answer`() = runTest {
        answer("/ipsb", fixture("ip-sb.json"))

        val exit = lookup(ipSb).lookup()

        assertEquals(ExitIp("203.0.113.7", "FI", "Finland", 24940, "Hetzner Online GmbH"), exit)
    }

    @Test
    fun `ip_sb IPv6 answer without region fields`() = runTest {
        answer("/ipsb", fixture("ip-sb-ipv6.json"))

        val exit = lookup(ipSb).lookup()

        assertEquals(ExitIp("2001:4860:4860::8888", "US", "United States", 15169, "Google"), exit)
    }

    @Test
    fun `ipwho_is answer reads the nested connection block`() = runTest {
        answer("/ipwho", fixture("ipwho-is.json"))

        val exit = lookup(ipwhoIs).lookup()

        assertEquals(ExitIp("203.0.113.7", "FI", "Finland", 24940, "Hetzner Online GmbH"), exit)
    }

    @Test
    fun `ipinfo answer splits the ASN out of org and has no country name`() = runTest {
        answer("/ipinfo", fixture("ipinfo.json"))

        val exit = lookup(ipinfo).lookup()

        assertEquals(ExitIp("203.0.113.7", "FI", null, 24940, "Hetzner Online GmbH"), exit)
    }

    @Test
    fun `ipinfo org without an AS prefix is kept whole`() {
        val exit = parseIpinfo(Json.parseToJsonElement("""{"ip": "192.0.2.1", "org": "Some ISP"}""").jsonObject)

        assertNull(exit.asn)
        assertEquals("Some ISP", exit.org)
        assertNull(exit.countryCode)
    }

    @Test
    fun `a malformed country code is dropped rather than shown`() {
        val exit = parseIpSb(Json.parseToJsonElement("""{"ip": "192.0.2.1", "country_code": "EU1"}""").jsonObject)

        assertNull(exit.countryCode)
    }

    // -------------------------------------------------------------------------
    // Refusals fall through to the next source
    // -------------------------------------------------------------------------

    @Test
    fun `an ip_sb rate limit falls through to ipwho_is`() = runTest {
        answer("/ipsb", fixture("ip-sb-rate-limited.json"), code = 403)
        answer("/ipwho", fixture("ipwho-is.json"))

        val exit = lookup(ipSb, ipwhoIs, ipinfo).lookup()

        assertEquals("203.0.113.7", exit.ip)
        // Stops at the first answer: ipinfo.io is never contacted.
        assertEquals(listOf("/ipsb", "/ipwho"), requestedPaths())
    }

    @Test
    fun `an ipwho_is refusal on HTTP 200 falls through to ipinfo`() = runTest {
        answer("/ipwho", fixture("ipwho-is-reserved.json"))
        answer("/ipinfo", fixture("ipinfo.json"))

        val exit = lookup(ipwhoIs, ipinfo).lookup()

        assertEquals("203.0.113.7", exit.ip)
        assertEquals(listOf("/ipwho", "/ipinfo"), requestedPaths())
    }

    @Test
    fun `a challenge page instead of JSON falls through`() = runTest {
        answer("/ipsb", "<!DOCTYPE html><html><head><title>Just a moment...</title></head></html>")
        answer("/ipinfo", fixture("ipinfo.json"))

        assertEquals("203.0.113.7", lookup(ipSb, ipinfo).lookup().ip)
    }

    @Test
    fun `every source refusing is an IOException naming each failure`() = runTest {
        answer("/ipsb", fixture("ip-sb-rate-limited.json"), code = 403)
        answer("/ipwho", fixture("ipwho-is-reserved.json"))
        answer("/ipinfo", fixture("ipinfo-bogon.json"))

        val error = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { lookup(ipSb, ipwhoIs, ipinfo).lookup() }
        }

        assertEquals(
            "no exit IP source answered (${server.url("/").host} returned HTTP 403; " +
                "ipwho.is refused: Reserved range; ipinfo.io: bogon address)",
            error.message,
        )
    }

    // -------------------------------------------------------------------------
    // Timeouts and connection reuse
    // -------------------------------------------------------------------------

    @Test
    fun `a source that never answers is abandoned for the next`() = runTest {
        routes["/ipsb"] = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        answer("/ipwho", fixture("ipwho-is.json"))

        val exit = lookup(ipSb, ipwhoIs, attemptTimeoutMs = 300).lookup()

        assertEquals("203.0.113.7", exit.ip)
    }

    @Test
    fun `the whole lookup gives up at its deadline`() = runTest {
        routes["/ipsb"] = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        routes["/ipwho"] = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)

        val started = System.nanoTime()
        val error = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                lookup(ipSb, ipwhoIs, attemptTimeoutMs = 5_000, totalTimeoutMs = 300).lookup()
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("timed-out lookup took ${elapsedMs}ms", elapsedMs < 4_000)
        assertEquals("exit IP lookup timed out", error.message)
    }

    @Test
    fun `every lookup dials a fresh connection`() = runTest {
        answer("/ipsb", fixture("ip-sb.json"))
        val lookup = lookup(ipSb)

        lookup.lookup()
        lookup.lookup()

        // sequenceNumber counts requests per connection: a reused (and
        // possibly stale-route) connection would make the second one 1.
        assertEquals(0, server.takeRequest().sequenceNumber)
        assertEquals(0, server.takeRequest().sequenceNumber)
    }
}
