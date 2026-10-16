package io.github.madeye.meow.ui.screens.connections

import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.api.Connection
import io.github.madeye.meow.api.ConnectionMeta
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.Rule
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
    ).also { viewModels += it }

    private suspend fun ConnectionDetailViewModel.await(until: (ConnectionDetailUiState) -> Boolean) =
        withTimeout(5_000) { uiState.first(until) }

    @Test
    fun `shows the open connection, then its Recent row once it closes`() = runBlocking {
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

    private fun conn(
        id: String,
        upload: Long = 0,
        rule: String = "DOMAIN-SUFFIX",
        payload: String = "example.com",
        chains: List<String> = listOf("Proxy"),
    ) = Connection(
        id = id,
        upload = upload,
        start = "2026-10-16T02:13:01Z",
        chains = chains,
        rule = rule,
        rulePayload = payload,
        metadata = ConnectionMeta(host = "$id.example"),
    )

    private companion object {
        val RULES = listOf(
            Rule(type = "DOMAIN-SUFFIX", payload = "example.com", proxy = "Proxy"),
            Rule(type = "RULE-SET", payload = "direct", proxy = "Proxy"),
            Rule(type = "RULE-SET", payload = "direct", proxy = "DIRECT"),
            Rule(type = "MATCH", payload = "", proxy = "Proxy"),
        )
    }
}
