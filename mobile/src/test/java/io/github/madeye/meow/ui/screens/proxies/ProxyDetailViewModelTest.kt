package io.github.madeye.meow.ui.screens.proxies

import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.ProxyInfo
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.net.NatBehavior
import io.github.madeye.meow.net.NatType
import io.github.madeye.meow.net.ProxyProbeTransport
import io.github.madeye.meow.net.ProxyProber
import io.github.madeye.meow.net.Throughput
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

/**
 * On real threads rather than virtual time: the prober hands each probe to
 * [Dispatchers.IO], as it does over the binder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProxyDetailViewModelTest {

    private data class Call(val id: Long, val kind: String, val arg: String)

    private class FakeTransport : ProxyProbeTransport {
        val calls = CopyOnWriteArrayList<Call>()
        val cancelled = CopyOnWriteArrayList<Long>()

        /** Opens on the first cancel, as `:vpn` ends a probe on one. */
        val anyCancel = CountDownLatch(1)

        /** Answers by kind; a missing kind fails the probe. */
        val answers = ConcurrentHashMap<String, (id: Long, arg: String) -> String>()

        override fun probe(id: Long, proxy: String, kind: String, arg: String): String {
            calls += Call(id, kind, arg)
            val answer = answers[kind] ?: return """{"error":"no answer for $kind","code":"failed"}"""
            return answer(id, arg)
        }

        override fun cancel(id: Long) {
            cancelled += id
            anyCancel.countDown()
        }
    }

    private val transport = FakeTransport()
    private val vpnState = MutableStateFlow(BaseService.State.Connected)
    private val described = CopyOnWriteArrayList<String>()
    private val tcpTests = CopyOnWriteArrayList<Pair<String, String>>()
    private var tcpDelay: suspend () -> Int = { 120 }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    /** Cleared at the end as the screen would clear them, so no probe outlives the test's Main. */
    private val viewModels = mutableListOf<ProxyDetailViewModel>()

    @After
    fun tearDown() {
        runBlocking {
            withTimeout(5_000) { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        }
        Dispatchers.resetMain()
    }

    private fun track(vm: ProxyDetailViewModel) = vm.also { viewModels += it }

    private fun viewModel(testUrl: String? = "https://cp.cloudflare.com/") = track(ProxyDetailViewModel(
        name = "Tokyo 01",
        testUrl = testUrl,
        vpnState = vpnState,
        describe = { name ->
            described += name
            ProxyInfo(name = name, type = "Snell", udp = true, history = emptyList())
        },
        tcpDelay = { name, url ->
            tcpTests += name to url
            tcpDelay()
        },
        prober = ProxyProber(transport),
    ))

    private suspend fun ProxyDetailViewModel.await(probe: ProxyProbe, until: (ProbeState) -> Boolean): ProbeState =
        withTimeout(5_000) { uiState.first { until(it.probe(probe)) }.probe(probe) }

    private suspend fun ProxyDetailViewModel.finished(probe: ProxyProbe): ProbeState =
        await(probe) { it is ProbeState.Done || it is ProbeState.Failed }

    @Test
    fun `describes the proxy once connected`() = runBlocking {
        vpnState.value = BaseService.State.Stopped
        val vm = viewModel()
        assertNull(vm.uiState.value.type)
        assertTrue(described.isEmpty())

        vpnState.value = BaseService.State.Connected
        val state = withTimeout(5_000) { vm.uiState.first { it.type != null } }

        assertEquals("Snell", state.type)
        assertEquals(true, state.udp)
        assertEquals(listOf("Tokyo 01"), described.toList())
    }

    @Test
    fun `the TCP test probes the group's URL and falls back to the default`() = runBlocking {
        val vm = viewModel()
        vm.test(ProxyProbe.TcpLatency)
        assertEquals(ProbeState.Done(ProbeResult.Latency(120)), vm.finished(ProxyProbe.TcpLatency))

        val selectorVm = viewModel(testUrl = null)
        selectorVm.test(ProxyProbe.TcpLatency)
        selectorVm.finished(ProxyProbe.TcpLatency)

        assertEquals(
            listOf("Tokyo 01" to "https://cp.cloudflare.com/", "Tokyo 01" to MeowApi.DELAY_TEST_URL),
            tcpTests.toList(),
        )
    }

    @Test
    fun `a TCP test that gets no answer is a timeout`() = runBlocking {
        tcpDelay = { 0 }
        val vm = viewModel()
        vm.test(ProxyProbe.TcpLatency)
        assertEquals(ProbeState.Failed(ProbeFailure.TimedOut), vm.finished(ProxyProbe.TcpLatency))

        tcpDelay = { throw MeowApiException.Http("testProxyDelay", 504) }
        vm.test(ProxyProbe.TcpLatency)
        assertEquals(ProbeState.Failed(ProbeFailure.TimedOut), vm.finished(ProxyProbe.TcpLatency))
    }

    @Test
    fun `probes report their measurements`() = runBlocking {
        transport.answers["udp-latency"] = { _, _ -> """{"ms":35}""" }
        transport.answers["download"] = { _, _ -> """{"bytes":50000000,"ms":4000}""" }
        transport.answers["nat"] = { _, _ ->
            """{"mapping":"endpoint-independent","filtering":"endpoint-independent","mapped":"198.51.100.9:5000","server":"s","note":null}"""
        }
        transport.answers["http-get"] = { _, _ ->
            """{"status":200,"body":"{\"ip\":\"198.51.100.9\",\"country_code\":\"JP\",\"asn\":2516,\"isp\":\"KDDI\"}"}"""
        }
        val vm = viewModel()

        vm.test(ProxyProbe.UdpLatency)
        assertEquals(ProbeState.Done(ProbeResult.Latency(35)), vm.finished(ProxyProbe.UdpLatency))

        vm.test(ProxyProbe.Download)
        assertEquals(
            ProbeState.Done(ProbeResult.Bandwidth(Throughput(bytes = 50_000_000, millis = 4_000))),
            vm.finished(ProxyProbe.Download),
        )

        vm.test(ProxyProbe.Nat)
        val nat = (vm.finished(ProxyProbe.Nat) as ProbeState.Done).result as ProbeResult.Nat
        assertEquals(NatClass.FullCone, nat.type.natClass())
        assertEquals("198.51.100.9:5000", nat.type.mappedAddress)

        vm.test(ProxyProbe.ExitIp)
        val exit = (vm.finished(ProxyProbe.ExitIp) as ProbeState.Done).result as ProbeResult.Exit
        assertEquals("198.51.100.9", exit.exitIp.ip)
        assertEquals("JP", exit.exitIp.countryCode)
        // The first source answered; the lookup went through the node, once.
        assertEquals(1, transport.calls.count { it.kind == "http-get" })
    }

    @Test
    fun `failures are told apart`() = runBlocking {
        transport.answers["udp-latency"] = { _, _ -> """{"error":"the proxy does not relay UDP","code":"udp-unsupported"}""" }
        transport.answers["nat"] = { _, _ -> """{"error":"no reply","code":"timeout"}""" }
        transport.answers["upload"] = { _, _ -> """{"error":"HTTP 403","code":"failed"}""" }
        val vm = viewModel()

        vm.test(ProxyProbe.UdpLatency)
        assertEquals(ProbeState.Failed(ProbeFailure.UdpUnsupported), vm.finished(ProxyProbe.UdpLatency))
        vm.test(ProxyProbe.Nat)
        assertEquals(ProbeState.Failed(ProbeFailure.TimedOut), vm.finished(ProxyProbe.Nat))
        vm.test(ProxyProbe.Upload)
        assertEquals(ProbeState.Failed(ProbeFailure.Failed, "HTTP 403"), vm.finished(ProxyProbe.Upload))
    }

    @Test
    fun `a binder failure is a failed test, not a crash`() = runBlocking {
        val vm = track(ProxyDetailViewModel(
            name = "Tokyo 01",
            testUrl = null,
            vpnState = vpnState,
            describe = { throw IOException("engine unreachable") },
            tcpDelay = { _, _ -> 1 },
            prober = ProxyProber(object : ProxyProbeTransport {
                override fun probe(id: Long, proxy: String, kind: String, arg: String): String =
                    throw IOException("the VPN service is not bound")

                override fun cancel(id: Long) = Unit
            }),
        ))
        vm.test(ProxyProbe.Download)
        assertEquals(
            ProbeState.Failed(ProbeFailure.Failed, "the VPN service is not bound"),
            vm.finished(ProxyProbe.Download),
        )
        assertNull("describing failed, so the header stays blank", vm.uiState.value.type)
    }

    @Test
    fun `tests wait for the VPN`() = runBlocking {
        vpnState.value = BaseService.State.Stopped
        val vm = viewModel()
        vm.test(ProxyProbe.UdpLatency)
        vm.test(ProxyProbe.TcpLatency)
        assertEquals(ProbeState.Idle, vm.uiState.value.probe(ProxyProbe.UdpLatency))
        assertTrue(transport.calls.isEmpty() && tcpTests.isEmpty())
    }

    @Test
    fun `the VPN going down ends running tests in vpn too`() = runBlocking {
        transport.answers["udp-latency"] = { _, _ ->
            transport.anyCancel.await(5, TimeUnit.SECONDS)
            """{"error":"cancelled","code":"cancelled"}"""
        }
        val vm = viewModel()
        vm.test(ProxyProbe.UdpLatency)
        withTimeout(5_000) { while (transport.calls.isEmpty()) delay(10) }

        vpnState.value = BaseService.State.Stopping

        assertEquals(ProbeState.Idle, vm.await(ProxyProbe.UdpLatency) { it == ProbeState.Idle })
        withTimeout(5_000) { while (transport.cancelled.isEmpty()) delay(10) }
        assertEquals(listOf(transport.calls.single().id), transport.cancelled.toList())
        assertFalse(vm.uiState.value.connected)
    }

    @Test
    fun `a second tap on a running test is ignored`() = runBlocking {
        val released = CountDownLatch(1)
        transport.answers["download"] = { _, _ ->
            released.await(5, TimeUnit.SECONDS)
            """{"bytes":1000,"ms":1000}"""
        }
        val vm = viewModel()
        vm.test(ProxyProbe.Download)
        vm.test(ProxyProbe.Download)
        released.countDown()
        vm.finished(ProxyProbe.Download)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `download and upload take turns`() = runBlocking {
        val downloadReleased = CountDownLatch(1)
        transport.answers["download"] = { _, _ ->
            downloadReleased.await(5, TimeUnit.SECONDS)
            """{"bytes":1000,"ms":1000}"""
        }
        transport.answers["upload"] = { _, _ -> """{"bytes":500,"ms":1000}""" }
        val vm = viewModel()

        vm.test(ProxyProbe.Download)
        withTimeout(5_000) { while (transport.calls.isEmpty()) delay(10) }
        vm.test(ProxyProbe.Upload)
        delay(200)
        assertEquals("upload waits for the download", listOf("download"), transport.calls.map { it.kind })
        assertEquals(ProbeState.Running, vm.uiState.value.probe(ProxyProbe.Upload))

        downloadReleased.countDown()
        vm.finished(ProxyProbe.Upload)
        assertEquals(listOf("download", "upload"), transport.calls.map { it.kind })
    }

    @Test
    fun `NAT behaviours map to the classic names`() {
        fun nat(mapping: NatBehavior, filtering: NatBehavior) =
            NatType(mapping, filtering, mappedAddress = null, server = null, sourceHidden = false).natClass()

        val ei = NatBehavior.EndpointIndependent
        assertEquals(NatClass.FullCone, nat(ei, ei))
        assertEquals(NatClass.RestrictedCone, nat(ei, NatBehavior.AddressDependent))
        assertEquals(NatClass.PortRestrictedCone, nat(ei, NatBehavior.AddressAndPortDependent))
        assertEquals(NatClass.Cone, nat(ei, NatBehavior.Unknown))
        assertEquals(NatClass.Symmetric, nat(NatBehavior.AddressAndPortDependent, NatBehavior.AddressAndPortDependent))
        assertEquals(NatClass.Symmetric, nat(NatBehavior.Dependent, NatBehavior.Unknown))
        assertEquals(NatClass.Unknown, nat(NatBehavior.Unknown, NatBehavior.Unknown))
    }
}
