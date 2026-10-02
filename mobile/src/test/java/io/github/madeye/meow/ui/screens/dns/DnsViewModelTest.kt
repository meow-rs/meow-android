package io.github.madeye.meow.ui.screens.dns

import io.github.madeye.meow.api.DnsResult
import io.github.madeye.meow.api.MeowApiException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DnsViewModelTest {

    /** The `search` of every request, in order. */
    private val searches = mutableListOf<String?>()

    /** What a request answers; by default one entry named after the search. */
    private var answer: suspend (String?) -> List<DnsResult> = { search -> listOf(entry(search ?: "all")) }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entry(name: String) = DnsResult(name = name, ips = listOf("192.0.2.1"), fromServer = "8.8.8.8", ttl = 60)

    private var collector: Job? = null

    private fun TestScope.viewModel(): DnsViewModel {
        val vm = DnsViewModel(fetch = { search ->
            searches += search
            answer(search)
        })
        // uiState is WhileSubscribed, as it is under the real screen.
        collector = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun `polls at once and then on the interval`() = runTest {
        val vm = viewModel()

        assertEquals(listOf<String?>(null), searches)
        assertTrue(vm.uiState.value.loaded)
        assertEquals(listOf("all"), vm.uiState.value.results.map { it.name })

        advanceTimeBy(DnsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf<String?>(null, null), searches)
    }

    @Test
    fun `is not loaded until the first answer`() = runTest {
        val gate = CompletableDeferred<List<DnsResult>>()
        answer = { gate.await() }
        val vm = viewModel()

        assertFalse(vm.uiState.value.loaded)

        gate.complete(emptyList())
        runCurrent()
        assertTrue(vm.uiState.value.loaded)
    }

    @Test
    fun `typing costs one trimmed request after a pause`() = runTest {
        val vm = viewModel()
        searches.clear()

        for (text in listOf("g", "go", "goo ")) {
            vm.onQueryChange(text)
            runCurrent()
            advanceTimeBy(DnsViewModel.DEBOUNCE_MS / 2)
        }
        assertEquals(emptyList<String?>(), searches)
        assertEquals("goo ", vm.uiState.value.query)

        advanceTimeBy(DnsViewModel.DEBOUNCE_MS)
        runCurrent()
        assertEquals(listOf<String?>("goo"), searches)
        assertEquals(listOf("goo"), vm.uiState.value.results.map { it.name })

        // The new query keeps polling.
        advanceTimeBy(DnsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf<String?>("goo", "goo"), searches)
    }

    @Test
    fun `clearing the query asks for everything without waiting`() = runTest {
        val vm = viewModel()
        vm.onQueryChange("google")
        advanceTimeBy(DnsViewModel.DEBOUNCE_MS + 1)
        runCurrent()
        searches.clear()

        vm.onQueryChange("")
        runCurrent()

        assertEquals(listOf<String?>(null), searches)
    }

    @Test
    fun `an answer for a superseded query never lands`() = runTest {
        val slow = CompletableDeferred<List<DnsResult>>()
        answer = { search -> if (search == "old") slow.await() else listOf(entry(search ?: "all")) }
        val vm = viewModel()

        vm.onQueryChange("old")
        advanceTimeBy(DnsViewModel.DEBOUNCE_MS + 1)
        runCurrent()
        vm.onQueryChange("new")
        advanceTimeBy(DnsViewModel.DEBOUNCE_MS + 1)
        runCurrent()
        slow.complete(listOf(entry("old")))
        runCurrent()

        assertEquals(listOf("new"), vm.uiState.value.results.map { it.name })
    }

    @Test
    fun `a failure keeps the last results and a success clears it`() = runTest {
        val vm = viewModel()

        answer = { throw MeowApiException.Http("dnsResults", 500) }
        advanceTimeBy(DnsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(DnsError.Failed, vm.uiState.value.error)
        assertEquals(listOf("all"), vm.uiState.value.results.map { it.name })

        answer = { throw MeowApiException.Unreachable(IOException("refused")) }
        advanceTimeBy(DnsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(DnsError.Offline, vm.uiState.value.error)

        answer = { listOf(entry("back")) }
        advanceTimeBy(DnsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertNull(vm.uiState.value.error)
        assertEquals(listOf("back"), vm.uiState.value.results.map { it.name })
    }

    @Test
    fun `stops polling once the screen is gone`() = runTest {
        viewModel()
        collector!!.cancel()
        // WhileSubscribed keeps upstream alive for 5 s, which spans two polls.
        advanceTimeBy(5_000)
        runCurrent()
        val afterGrace = searches.size

        advanceTimeBy(DnsViewModel.POLL_INTERVAL_MS * 5)
        runCurrent()

        assertEquals(afterGrace, searches.size)
    }
}
