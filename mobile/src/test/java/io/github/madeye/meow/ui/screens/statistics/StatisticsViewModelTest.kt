package io.github.madeye.meow.ui.screens.statistics

import io.github.madeye.meow.aidl.ByteTotals
import io.github.madeye.meow.aidl.SessionStats
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {

    private var session: SessionStats? = SessionStats(
        startedAt = 1_000,
        wifiEthernet = ByteTotals(upload = 10, download = 100),
        cellular = ByteTotals(upload = 1, download = 2),
    )

    private var traffic: suspend () -> Map<String, ByteTotals>? = {
        mapOf(
            "daniel" to ByteTotals(upload = 1, download = 1),
            "DIRECT" to ByteTotals(upload = 2, download = 2),
            "REJECT" to ByteTotals(),
            "daniel-TW" to ByteTotals(upload = 3, download = 3),
        )
    }

    private var now = 61_000L

    /** How many times the engine was asked for its per-proxy traffic. */
    private var trafficRequests = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): StatisticsViewModel {
        val vm = StatisticsViewModel(
            session = { session },
            proxyTraffic = {
                trafficRequests++
                traffic()
            },
            clock = { now },
        )
        // uiState is WhileSubscribed, as it is under the real screen.
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun `shows the session and the proxies that carried traffic, by name`() = runTest {
        val state = viewModel().uiState.value

        assertTrue(state.loaded)
        assertEquals(session, state.session)
        assertEquals(61_000L, state.now)
        assertEquals(listOf("DIRECT", "daniel", "daniel-TW"), state.policies!!.map { it.name })
        assertEquals(ByteTotals(upload = 3, download = 3), state.policies!!.last().totals)
    }

    @Test
    fun `polls every second`() = runTest {
        val vm = viewModel()

        now = 62_000L
        advanceTimeBy(StatisticsViewModel.POLL_INTERVAL_MS)
        runCurrent()

        assertEquals(62_000L, vm.uiState.value.now)
        assertEquals(2, trafficRequests)
    }

    @Test
    fun `a failed request keeps the proxies last read`() = runTest {
        val vm = viewModel()

        traffic = { throw IOException("connection reset") }
        advanceTimeBy(StatisticsViewModel.POLL_INTERVAL_MS)
        runCurrent()

        assertEquals(listOf("DIRECT", "daniel", "daniel-TW"), vm.uiState.value.policies!!.map { it.name })
    }

    @Test
    fun `an engine without per-proxy traffic still shows the session`() = runTest {
        traffic = { null }
        val state = viewModel().uiState.value

        assertEquals(session, state.session)
        assertNull(state.policies)
    }

    @Test
    fun `with the VPN down there is no session and the engine is not asked`() = runTest {
        session = null
        val state = viewModel().uiState.value

        assertTrue(state.loaded)
        assertNull(state.session)
        assertNull(state.policies)
        assertEquals(0, trafficRequests)
    }
}
