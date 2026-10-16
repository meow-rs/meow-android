package io.github.madeye.meow.ui.screens.connections

import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.api.Connection
import io.github.madeye.meow.api.ConnectionMeta
import io.github.madeye.meow.api.DnsResult
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.ProxiesResult
import io.github.madeye.meow.api.Proxy
import io.github.madeye.meow.api.ProxyGroup
import io.github.madeye.meow.api.Rule
import io.github.madeye.meow.net.IpGeo
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionDetailViewModelTest {

    private var now = 1_000L
    private val store = RecentConnectionsStore(clock = { now })
    private val ruleLookups = AtomicInteger()
    private var rules: suspend () -> List<Rule> = { RULES }
    private val closed = CopyOnWriteArrayList<String>()
    private val polled = AtomicInteger()
    private val geoLookups = CopyOnWriteArrayList<String>()
    private val labelLookups = CopyOnWriteArrayList<String>()
    private val viewModels = mutableListOf<ConnectionDetailViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        runBlocking {
            withTimeout(5_000) { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        }
        Dispatchers.resetMain()
    }

    private fun viewModel(id: String) = ConnectionDetailViewModel(
        id = id,
        store = store,
        polling = flowOf(Unit).onStart { polled.incrementAndGet() },
        rules = {
            ruleLookups.incrementAndGet()
            rules()
        },
        closeConnection = { closed += it },
        proxies = { PROXIES },
        dnsCache = { host -> listOf(DnsResult(name = host, ips = listOf("9.9.9.9"))) },
        ipGeo = { ip ->
            geoLookups += ip
            IpGeo(country = "CN", asn = 136958, org = "China Unicom Guangdong IP network")
        },
        appLabel = { pkg ->
            labelLookups += pkg
            "Label of $pkg"
        },
    ).also { viewModels += it }

    private suspend fun ConnectionDetailViewModel.await(until: (ConnectionDetailUiState) -> Boolean) =
        withTimeout(5_000) { uiState.first(until) }

    @Test
    fun `shows the open connection, then its closed row once it closes`() = runBlocking {
        store.onSnapshot(listOf(conn("a", upload = 10), conn("b")))
        val vm = viewModel("a")

        val open = vm.await { it.connection != null }
        assertTrue(open.active)
        assertEquals(10L, open.connection?.upload)
        assertTrue("showing the page polls", polled.get() > 0)

        now = 2_000
        store.onSnapshot(listOf(conn("a", upload = 25), conn("b")))
        assertEquals(25L, vm.await { it.connection?.upload == 25L }.connection?.upload)

        now = 3_000
        store.onSnapshot(listOf(conn("b")))
        val gone = vm.await { !it.active }
        assertEquals("a", gone.connection?.id)
        assertEquals("closed after the last poll that listed it", 2_000L, gone.lastSeenAtMillis)
    }

    @Test
    fun `a connection in neither list is gone`() = runBlocking {
        store.onSnapshot(listOf(conn("b")))
        val state = viewModel("a").await { true }
        assertNull(state.connection)
        assertFalse(state.active)
    }

    @Test
    fun `finds the rule by type, payload and the policy it chose`() = runBlocking {
        store.onSnapshot(listOf(conn("a", rule = "RULE-SET", payload = "direct", chains = listOf("DIRECT"))))
        val state = viewModel("a").await { it.ruleIndex != null }

        // Not index 1: same rule set, but a different policy.
        assertEquals(2, state.ruleIndex)
        assertEquals(1, ruleLookups.get())
    }

    @Test
    fun `the rule is looked up once the connection first shows up`() = runBlocking {
        val vm = viewModel("a")
        assertEquals(0, ruleLookups.get())

        store.onSnapshot(listOf(conn("a", rule = "MATCH", payload = "", chains = listOf("Proxy"))))
        assertEquals(3, vm.await { it.ruleIndex != null }.ruleIndex)
        store.onSnapshot(listOf(conn("a", rule = "MATCH", payload = "", chains = listOf("Proxy"), upload = 5)))
        vm.await { it.connection?.upload == 5L }
        assertEquals(1, ruleLookups.get())
    }

    @Test
    fun `routes no listed rule chose are not looked up`() = runBlocking {
        store.onSnapshot(
            listOf(
                conn("final", rule = "Final", payload = "", chains = listOf("DIRECT")),
                conn("global", rule = "Global", payload = "", chains = listOf("GLOBAL")),
            ),
        )
        val final = viewModel("final").await { it.connection != null }
        val global = viewModel("global").await { it.connection != null }

        assertEquals(UnlistedRoute.Final, UnlistedRoute.of(final.connection!!))
        assertEquals(UnlistedRoute.Global, UnlistedRoute.of(global.connection!!))
        assertNull(final.ruleIndex)
        assertEquals(0, ruleLookups.get())
    }

    @Test
    fun `no index when the rule is gone from the list or the lookup fails`() = runBlocking {
        store.onSnapshot(
            listOf(
                conn("reloaded", rule = "DOMAIN", payload = "removed.example", chains = listOf("Proxy")),
                conn("failed", rule = "RULE-SET", payload = "direct", chains = listOf("DIRECT")),
            ),
        )
        val reloaded = viewModel("reloaded")
        withTimeout(5_000) { while (ruleLookups.get() < 1) delay(10) }
        assertNull(reloaded.await { it.connection != null }.ruleIndex)

        rules = { throw MeowApiException.Unreachable(IOException("VPN stopped")) }
        val failed = viewModel("failed")
        withTimeout(5_000) { while (ruleLookups.get() < 2) delay(10) }
        assertNull(failed.await { it.connection != null }.ruleIndex)
    }

    @Test
    fun `close ends the connection here and in the engine`() = runBlocking {
        store.onSnapshot(listOf(conn("a")))
        val vm = viewModel("a")
        vm.await { it.active }

        vm.close()

        assertFalse(vm.await { !it.active }.active)
        assertEquals(listOf("a"), closed.toList())
    }

    @Test
    fun `the remote ip arrives with where it is registered`() = runBlocking {
        store.onSnapshot(listOf(conn("a", destinationIp = "157.148.41.176")))
        val state = viewModel("a").await { (it.remote as? RemoteIp.Known)?.geo != null }

        assertEquals(
            RemoteIp.Known(
                ip = "157.148.41.176",
                geo = IpGeo(country = "CN", asn = 136958, org = "China Unicom Guangdong IP network"),
            ),
            state.remote,
        )
        assertEquals(listOf("157.148.41.176"), geoLookups.toList())
    }

    @Test
    fun `the app that opened the connection arrives with its name`() = runBlocking {
        store.onSnapshot(listOf(conn("a", process = "com.example.app")))
        val state = viewModel("a").await { it.appLabel != null }

        assertEquals("Label of com.example.app", state.appLabel)
        assertEquals(listOf("com.example.app"), labelLookups.toList())
    }

    @Test
    fun `no app name is looked up when the engine did not say`() = runBlocking {
        store.onSnapshot(listOf(conn("a")))
        val state = viewModel("a").await { it.remote != null }

        assertNull(state.appLabel)
        assertTrue(labelLookups.isEmpty())
    }

    @Test
    fun `an address the proxy server resolves is not looked up`() = runBlocking {
        store.onSnapshot(listOf(conn("a", chains = listOf("Proxy"))))
        val state = viewModel("a").await { it.remote != null }

        assertEquals(RemoteIp.ResolvedByProxy, state.remote)
        assertTrue(geoLookups.isEmpty())
    }

    @Test
    fun `the engine's destination ip wins over any lookup`() = runBlocking {
        val remote = remoteIpOf(
            conn("a", chains = listOf("Proxy"), destinationIp = "1.2.3.4"),
            proxies = { error("not consulted") },
            dnsCache = { error("not consulted") },
        )
        assertEquals(RemoteIp.Known("1.2.3.4"), remote)
    }

    @Test
    fun `a host dialed DIRECT through a group takes its address from the DNS cache`() = runBlocking {
        val cache = listOf(
            // The engine's search matches substrings too.
            DnsResult(name = "cdn.a.example", ips = listOf("1.1.1.1")),
            DnsResult(name = "A.example.", ips = listOf("9.9.9.9", "9.9.9.8")),
        )
        val remote = remoteIpOf(conn("a", chains = listOf("Domestic")), { PROXIES }, { cache })
        assertEquals(RemoteIp.Known("9.9.9.9", fromDnsCache = true), remote)

        // Expired from the cache: nothing to show.
        assertNull(remoteIpOf(conn("a", chains = listOf("DIRECT")), { PROXIES }, { emptyList() }))
    }

    @Test
    fun `nothing to say for a rejected flow, a bare ip flow or a stopped engine`() = runBlocking {
        val cache: suspend (String) -> List<DnsResult> = { error("not consulted") }
        assertNull(remoteIpOf(conn("a", chains = listOf("REJECT")), { PROXIES }, cache))
        assertNull(remoteIpOf(conn("a", chains = listOf("Unknown")), { PROXIES }, cache))
        assertNull(remoteIpOf(conn("a").copy(metadata = ConnectionMeta()), { PROXIES }, cache))
        assertNull(
            remoteIpOf(
                conn("a"),
                { throw MeowApiException.Unreachable(IOException("VPN stopped")) },
                cache,
            ),
        )
    }

    @Test
    fun `a policy is followed through its groups' picks to a leaf`() {
        assertEquals("Trojan", PROXIES.leafType("Proxy"))
        assertEquals("Direct", PROXIES.leafType("Domestic"))
        assertEquals("Direct", PROXIES.leafType("DIRECT"))
        assertEquals("LoadBalance", PROXIES.leafType("Balanced"))
        assertNull(PROXIES.leafType("Unknown"))
        val loop = ProxiesResult(
            groups = mapOf("A" to group("A", now = "B"), "B" to group("B", now = "A")),
            proxies = emptyMap(),
        )
        assertNull(loop.leafType("A"))
    }

    private fun conn(
        id: String,
        upload: Long = 0,
        rule: String = "DOMAIN-SUFFIX",
        payload: String = "example.com",
        chains: List<String> = listOf("Proxy"),
        destinationIp: String = "",
        process: String = "",
    ) = Connection(
        id = id,
        upload = upload,
        start = "2026-10-16T02:13:01Z",
        chains = chains,
        rule = rule,
        rulePayload = payload,
        metadata = ConnectionMeta(
            host = "$id.example",
            destinationIP = destinationIp,
            sourceIP = "172.19.0.1",
            process = process,
        ),
    )

    private companion object {
        val RULES = listOf(
            Rule(type = "DOMAIN-SUFFIX", payload = "example.com", proxy = "Proxy"),
            Rule(type = "RULE-SET", payload = "direct", proxy = "Proxy"),
            Rule(type = "RULE-SET", payload = "direct", proxy = "DIRECT"),
            Rule(type = "MATCH", payload = "", proxy = "Proxy"),
        )

        fun group(name: String, now: String, type: String = "Selector") =
            ProxyGroup(name = name, type = type, now = now, all = listOf(now), history = emptyList())

        val PROXIES = ProxiesResult(
            groups = mapOf(
                "Proxy" to group("Proxy", now = "Auto"),
                "Auto" to group("Auto", now = "HK", type = "URLTest"),
                "Domestic" to group("Domestic", now = "DIRECT"),
                "Balanced" to group("Balanced", now = "", type = "LoadBalance"),
            ),
            proxies = listOf("HK" to "Trojan", "DIRECT" to "Direct", "REJECT" to "Reject")
                .associate { (name, type) -> name to Proxy(name = name, type = type, history = emptyList()) },
        )
    }
}
