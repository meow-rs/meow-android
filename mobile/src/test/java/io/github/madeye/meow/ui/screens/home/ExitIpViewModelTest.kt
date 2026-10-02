package io.github.madeye.meow.ui.screens.home

import io.github.madeye.meow.bg.BaseService.State
import io.github.madeye.meow.net.ExitIp
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExitIpViewModelTest {

    private val vpn = MutableStateFlow(State.Stopped)
    private val enabled = MutableStateFlow<Boolean?>(true)
    private var routedViaVpn = true

    private var lookups = 0
    private var cancelled = 0

    /** What the n-th (1-based) lookup does; by default answers 198.51.100.n. */
    private var answer: suspend (Int) -> ExitIp = { n -> exit("198.51.100.$n") }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun exit(ip: String) = ExitIp(ip, "JP", "Japan", 2516, "KDDI")

    private fun TestScope.viewModel(): ExitIpViewModel {
        val vm = ExitIpViewModel(
            lookup = {
                val n = ++lookups
                try {
                    answer(n)
                } catch (e: CancellationException) {
                    cancelled++
                    throw e
                }
            },
            vpnState = vpn,
            enabled = enabled,
            routedViaVpn = { routedViaVpn },
        )
        // uiState is WhileSubscribed, as it is under the real screen.
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    private fun TestScope.settle() {
        advanceTimeBy(ExitIpViewModel.SETTLE_MS)
        runCurrent()
    }

    @Test
    fun `nothing is looked up while the setting is off`() = runTest {
        enabled.value = false
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(0, lookups)
        assertEquals(ExitIpUiState.Hidden, vm.uiState.value)
    }

    @Test
    fun `nothing is looked up before the setting has been read`() = runTest {
        enabled.value = null
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(0, lookups)
        assertEquals(ExitIpUiState.Hidden, vm.uiState.value)
    }

    @Test
    fun `disconnected, the direct exit is looked up once things settle`() = runTest {
        val vm = viewModel()

        assertEquals(ExitIpUiState.Checking, vm.uiState.value)
        advanceTimeBy(ExitIpViewModel.SETTLE_MS - 1)
        runCurrent()
        assertEquals(0, lookups)

        settle()

        assertEquals(1, lookups)
        assertEquals(ExitIpUiState.Found(exit("198.51.100.1"), bypassesVpn = false), vm.uiState.value)
    }

    @Test
    fun `Idle then Stopped is one direct route, so one lookup`() = runTest {
        vpn.value = State.Idle
        viewModel()
        runCurrent()
        vpn.value = State.Stopped

        advanceUntilIdle()

        assertEquals(1, lookups)
    }

    @Test
    fun `connecting cancels the direct lookup in flight and re-checks once connected`() = runTest {
        answer = { n -> if (n == 1) awaitCancellation() else exit("198.51.100.$n") }
        val vm = viewModel()
        settle()
        assertEquals(1, lookups)

        vpn.value = State.Connecting
        runCurrent()
        assertEquals(1, cancelled)
        assertEquals(ExitIpUiState.Checking, vm.uiState.value)

        vpn.value = State.Connected
        settle()

        assertEquals(2, lookups)
        assertEquals(ExitIpUiState.Found(exit("198.51.100.2"), bypassesVpn = false), vm.uiState.value)
    }

    @Test
    fun `disconnecting re-checks to show the direct exit again`() = runTest {
        vpn.value = State.Connected
        val vm = viewModel()
        settle()

        vpn.value = State.Stopping
        runCurrent()
        vpn.value = State.Stopped
        settle()

        assertEquals(2, lookups)
        assertEquals("198.51.100.2", (vm.uiState.value as ExitIpUiState.Found).exitIp.ip)
    }

    @Test
    fun `a burst of node switches costs one lookup`() = runTest {
        vpn.value = State.Connected
        val vm = viewModel()
        settle()
        assertEquals(1, lookups)

        repeat(3) {
            vm.onRouteChanged()
            advanceTimeBy(ExitIpViewModel.SETTLE_MS / 2)
            runCurrent()
        }
        assertEquals(ExitIpUiState.Checking, vm.uiState.value)
        settle()

        assertEquals(2, lookups)
    }

    @Test
    fun `a route switch while disconnected is not a trigger`() = runTest {
        val vm = viewModel()
        settle()

        vm.onRouteChanged()
        advanceUntilIdle()

        assertEquals(1, lookups)
    }

    @Test
    fun `a failure shows Failed and a tap retries at once`() = runTest {
        answer = { n -> if (n == 1) throw IOException("offline") else exit("198.51.100.$n") }
        val vm = viewModel()
        settle()
        assertEquals(ExitIpUiState.Failed, vm.uiState.value)

        vm.refresh()
        runCurrent()

        assertEquals(2, lookups)
        assertEquals("198.51.100.2", (vm.uiState.value as ExitIpUiState.Found).exitIp.ip)
    }

    @Test
    fun `connected but outside the VPN is flagged, disconnected never is`() = runTest {
        routedViaVpn = false
        vpn.value = State.Connected
        val vm = viewModel()
        settle()
        assertEquals(true, (vm.uiState.value as ExitIpUiState.Found).bypassesVpn)

        vpn.value = State.Stopped
        settle()

        assertEquals(false, (vm.uiState.value as ExitIpUiState.Found).bypassesVpn)
    }

    @Test
    fun `switching the setting off cancels the lookup and hides the card`() = runTest {
        answer = { awaitCancellation() }
        val vm = viewModel()
        settle()
        assertEquals(1, lookups)

        enabled.value = false
        runCurrent()

        assertEquals(1, cancelled)
        assertEquals(ExitIpUiState.Hidden, vm.uiState.value)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, lookups)
    }

    @Test
    fun `switching the setting back on checks again`() = runTest {
        enabled.value = false
        val vm = viewModel()
        advanceUntilIdle()

        enabled.value = true
        settle()

        assertEquals(1, lookups)
        assertEquals("198.51.100.1", (vm.uiState.value as ExitIpUiState.Found).exitIp.ip)
    }
}
