package io.github.madeye.meow.ui.screens.trafficdetails

import io.github.madeye.meow.traffic.TrafficGroup
import io.github.madeye.meow.traffic.TrafficReport
import io.github.madeye.meow.traffic.TrafficRow
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficDetailsViewModelTest {

    private data class Read(val from: LocalDate, val to: LocalDate, val by: TrafficGroup, val filter: TrafficFilter)

    /** Every read, in order. */
    private val reads = mutableListOf<Read>()
    private var clears = 0

    /** What every read fails with, while set. */
    private var failure: IOException? = null

    /** The app labels known, and every package asked for. */
    private val labels = MutableStateFlow<Map<String, String>>(emptyMap())
    private val labelRequests = mutableListOf<String>()

    private val today = LocalDate.of(2026, 1, 15)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun read(from: Long, to: Long, by: TrafficGroup, filter: TrafficFilter): TrafficReport {
        reads += Read(LocalDate.ofEpochDay(from), LocalDate.ofEpochDay(to), by, filter)
        failure?.let { throw it }
        return TrafficReport(
            upload = 1,
            download = 2,
            requests = 3,
            rows = listOf(TrafficRow(name = "${by.name}-${reads.size}", upload = 1, download = 2, requests = 3)),
        )
    }

    private fun TestScope.viewModel(
        filter: TrafficFilter = TrafficFilter(),
        period: TrafficPeriod = TrafficPeriod.Today,
        by: TrafficGroup = TrafficGroup.Host,
    ): TrafficDetailsViewModel {
        val vm = TrafficDetailsViewModel(
            filter = filter,
            period = period,
            by = by,
            read = { from, to, group, f -> read(from, to, group, f) },
            clearLedger = { clears += 1; true },
            appLabels = labels,
            requestAppLabels = { labelRequests += it },
            today = { today },
        )
        // uiState is WhileSubscribed, as it is under the real screen.
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun `periods are calendar days, last month across the year`() {
        assertEquals(today.toEpochDay() to today.toEpochDay(), TrafficPeriod.Today.range(today))
        assertEquals(
            LocalDate.of(2026, 1, 1).toEpochDay() to today.toEpochDay(),
            TrafficPeriod.ThisMonth.range(today),
        )
        assertEquals(
            LocalDate.of(2025, 12, 1).toEpochDay() to LocalDate.of(2025, 12, 31).toEpochDay(),
            TrafficPeriod.LastMonth.range(today),
        )
        val march = LocalDate.of(2028, 3, 31)
        assertEquals(
            LocalDate.of(2028, 2, 1).toEpochDay() to LocalDate.of(2028, 2, 29).toEpochDay(),
            TrafficPeriod.LastMonth.range(march),
        )
    }

    @Test
    fun `reads on open, again on every poll and on a new period or grouping`() = runTest {
        val vm = viewModel()
        assertEquals(listOf(Read(today, today, TrafficGroup.Host, TrafficFilter())), reads)
        assertEquals("Host-1", vm.uiState.value.report?.rows?.single()?.name)

        advanceTimeBy(TrafficDetailsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(2, reads.size)

        vm.onPeriodChange(TrafficPeriod.LastMonth)
        runCurrent()
        assertEquals(
            Read(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31), TrafficGroup.Host, TrafficFilter()),
            reads.last(),
        )
        assertEquals(TrafficPeriod.LastMonth, vm.uiState.value.period)

        vm.onGroupChange(TrafficGroup.Policy)
        runCurrent()
        assertEquals(TrafficGroup.Policy, reads.last().by)
        assertEquals(TrafficGroup.Policy, vm.uiState.value.by)
    }

    @Test
    fun `a narrowed page groups only by what its filter leaves open`() = runTest {
        val filter = TrafficFilter(host = "example.com")
        val vm = viewModel(filter = filter, by = TrafficGroup.Host)

        assertEquals(
            listOf(TrafficGroup.App, TrafficGroup.Policy, TrafficGroup.Interface),
            vm.uiState.value.groups,
        )
        assertEquals("a pinned grouping falls back to the first open one", TrafficGroup.App, vm.uiState.value.by)
        assertEquals(Read(today, today, TrafficGroup.App, filter), reads.single())

        vm.onGroupChange(TrafficGroup.Host)
        runCurrent()
        assertEquals(TrafficGroup.App, vm.uiState.value.by)
        assertEquals(1, reads.size)

        val narrowed = filter.with(TrafficGroup.App, "org.example.browser").with(TrafficGroup.Policy, "node")
        assertEquals(TrafficFilter(host = "example.com", app = "org.example.browser", policy = "node"), narrowed)
        assertEquals(listOf(TrafficGroup.Interface), narrowed.groups)
    }

    @Test
    fun `apps are named as their labels resolve`() = runTest {
        val vm = viewModel(filter = TrafficFilter(app = "org.example.opened"), by = TrafficGroup.App)
        // Grouped by app it falls back to hosts; the page's own app is asked for.
        assertEquals(listOf("org.example.opened"), labelRequests)

        vm.onGroupChange(TrafficGroup.Policy)
        runCurrent()
        assertEquals(listOf("org.example.opened"), labelRequests)

        val all = viewModel(by = TrafficGroup.App)
        assertEquals(listOf("org.example.opened", "App-3"), labelRequests)
        labels.value = mapOf("App-3" to "Browser")
        runCurrent()
        assertEquals(mapOf("App-3" to "Browser"), all.uiState.value.appLabels)
    }

    @Test
    fun `a failed read shows its error, a failed poll keeps the last report`() = runTest {
        failure = IOException("locked")
        val vm = viewModel()
        assertNull(vm.uiState.value.report)
        assertEquals("locked", vm.uiState.value.error)

        failure = null
        advanceTimeBy(TrafficDetailsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals("Host-2", vm.uiState.value.report?.rows?.single()?.name)
        assertNull(vm.uiState.value.error)

        failure = IOException("gone")
        advanceTimeBy(TrafficDetailsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals("Host-2", vm.uiState.value.report?.rows?.single()?.name)
        assertEquals("gone", vm.uiState.value.error)
    }

    @Test
    fun `clearing deletes the ledger and reads it again`() = runTest {
        val vm = viewModel()
        assertEquals(1, reads.size)

        vm.clear()
        runCurrent()
        assertEquals(1, clears)
        assertEquals(2, reads.size)
    }
}
