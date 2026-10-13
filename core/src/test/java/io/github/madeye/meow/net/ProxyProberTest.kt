package io.github.madeye.meow.net

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyProberTest {

    private data class Call(val id: Long, val proxy: String, val kind: String, val arg: String)

    /** Answers each probe with whatever [answer] returns for its kind and arg. */
    private class FakeTransport(val answer: (kind: String, arg: String) -> String) : ProxyProbeTransport {
        val calls = CopyOnWriteArrayList<Call>()
        val cancelled = CopyOnWriteArrayList<Long>()

        override fun probe(id: Long, proxy: String, kind: String, arg: String): String {
            calls += Call(id, proxy, kind, arg)
            return answer(kind, arg)
        }

        override fun cancel(id: Long) {
            cancelled += id
        }
    }

    private fun prober(answer: (kind: String, arg: String) -> String) = FakeTransport(answer).let { it to ProxyProber(it) }

    @Test
    fun `reads each kind of answer`() = runBlocking {
        val (transport, prober) = prober { kind, _ ->
            when (kind) {
                "udp-latency" -> """{"ms":42}"""
                "download" -> """{"bytes":10000000,"ms":8000}"""
                "upload" -> """{"bytes":2500000,"ms":4000}"""
                "nat" -> """{"mapping":"endpoint-independent","filtering":"address-dependent",""" +
                    """"mapped":"203.0.113.7:41000","server":"stun.example:3478","note":null}"""
                else -> error(kind)
            }
        }
        assertEquals(42L, prober.udpLatency("hk"))
        assertEquals(10_000_000.0, prober.download("hk").bitsPerSecond, 0.001)
        assertEquals(Throughput(bytes = 2_500_000, millis = 4_000), prober.upload("hk"))
        assertEquals(
            NatType(
                mapping = NatBehavior.EndpointIndependent,
                filtering = NatBehavior.AddressDependent,
                mappedAddress = "203.0.113.7:41000",
                server = "stun.example:3478",
                sourceHidden = false,
            ),
            prober.natType("hk"),
        )
        assertEquals(listOf("udp-latency", "download", "upload", "nat"), transport.calls.map { it.kind })
        assertTrue(transport.calls.all { it.proxy == "hk" })
        assertEquals("every probe gets its own id", 4, transport.calls.map { it.id }.distinct().size)
    }

    @Test
    fun `a relay that hides reply sources is reported as such`() = runBlocking {
        val (_, prober) = prober { _, _ ->
            """{"mapping":"unknown","filtering":"unknown","mapped":"203.0.113.7:41000","server":"s","note":"source-hidden"}"""
        }
        val nat = prober.natType("hk")
        assertTrue(nat.sourceHidden)
        assertEquals(NatBehavior.Unknown, nat.mapping)
    }

    @Test
    fun `an unknown behavior reads as unknown`() {
        assertEquals(NatBehavior.Unknown, NatBehavior.fromWire("somewhere-in-between"))
        assertEquals(NatBehavior.Unknown, NatBehavior.fromWire(null))
    }

    @Test
    fun `an error answer throws with its code`() {
        val (_, prober) = prober { _, _ -> """{"error":"the proxy does not relay UDP","code":"udp-unsupported"}""" }
        val e = assertThrows(ProxyProbeException::class.java) { runBlocking { prober.udpLatency("http-only") } }
        assertEquals(ProxyProbeException.UDP_UNSUPPORTED, e.code)
        assertEquals("the proxy does not relay UDP", e.message)
    }

    @Test
    fun `an unreadable answer is an IOException`() {
        val (_, prober) = prober { _, _ -> "not json" }
        assertThrows(IOException::class.java) { runBlocking { prober.download("hk") } }
        val (_, missing) = prober { _, _ -> """{"bytes":1}""" }
        assertThrows(IOException::class.java) { runBlocking { missing.download("hk") } }
    }

    @Test
    fun `cancelling the caller ends the probe in vpn`() = runBlocking {
        val released = CountDownLatch(1)
        val started = CompletableDeferred<Unit>()
        val transport = object : ProxyProbeTransport {
            val cancelled = CopyOnWriteArrayList<Long>()
            var probed = -1L

            override fun probe(id: Long, proxy: String, kind: String, arg: String): String {
                probed = id
                started.complete(Unit)
                // Blocks as the binder call does, until `:vpn` ends the probe.
                assertTrue(released.await(5, TimeUnit.SECONDS))
                return """{"error":"cancelled","code":"cancelled"}"""
            }

            override fun cancel(id: Long) {
                cancelled += id
                released.countDown()
            }
        }
        val job = launch(Dispatchers.Default) {
            runCatching { ProxyProber(transport).download("hk") }
        }
        withTimeout(5_000) { started.await() }
        withTimeout(5_000) { job.cancelAndJoin() }
        assertEquals(listOf(transport.probed), transport.cancelled)
    }

    @Test
    fun `the exit IP fetcher passes the URL and refuses non-2xx answers`() = runBlocking {
        val (transport, prober) = prober { _, arg ->
            if (arg.contains("limited")) {
                """{"status":429,"body":"slow down"}"""
            } else {
                """{"status":200,"body":"{\"ip\":\"198.51.100.4\",\"country_code\":\"jp\"}"}"""
            }
        }
        val lookup = ExitIpLookup(
            sources = listOf(
                ExitIpSource.ipSb("https://limited.example/geoip".toHttpUrl()),
                ExitIpSource.ipSb("https://ok.example/geoip".toHttpUrl()),
            ),
            fetcher = prober.exitIpFetcher("jp"),
        )
        val exitIp = lookup.lookup()
        assertEquals("198.51.100.4", exitIp.ip)
        assertEquals("JP", exitIp.countryCode)
        assertEquals(
            listOf("https://limited.example/geoip", "https://ok.example/geoip"),
            transport.calls.map { it.arg },
        )
        assertTrue(transport.calls.all { it.kind == "http-get" && it.proxy == "jp" })

        val refused = assertThrows(IOException::class.java) {
            runBlocking { prober.exitIpFetcher("jp").fetch("https://limited.example/".toHttpUrl()) }
        }
        assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains("HTTP 429"))
        assertTrue("nothing was cancelled", transport.cancelled.isEmpty())
    }
}
