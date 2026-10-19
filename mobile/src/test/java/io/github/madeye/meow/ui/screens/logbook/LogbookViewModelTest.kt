package io.github.madeye.meow.ui.screens.logbook

import io.github.madeye.meow.logbook.LogbookEntry
import io.github.madeye.meow.logbook.LogbookLevel
import io.github.madeye.meow.logbook.LogbookPage
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogbookViewModelTest {

    private data class Read(val before: Long, val since: Long, val limit: Int, val kind: String? = null)

    /** Every read, in order. */
    private val reads = mutableListOf<Read>()

    /** The logbook, oldest first, as the engine wrote it. */
    private val book = mutableListOf<LogbookEntry>()
    private var rev = 0L

    /** What every read fails with, while set. */
    private var failure: IOException? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun write(
        message: String,
        kind: String = "config.reload",
        subject: String = "",
        level: LogbookLevel = LogbookLevel.Info,
    ) {
        rev += 1
        book += LogbookEntry(
            id = book.size + 1L,
            rev = rev,
            time = "2026-10-19T08:00:00.000Z",
            kind = kind,
            level = level,
            subject = subject,
            message = message,
        )
    }

    /** `logbook::read`'s semantics, over [book]. */
    private fun read(before: Long, since: Long, limit: Int, kind: String?): LogbookPage {
        reads += Read(before, since, limit, kind)
        failure?.let { throw it }
        val matching = book.reversed()
            .filter { before == 0L || it.id < before }
            .filter { since == 0L || it.rev > since }
            .filter { kind == null || it.kind == kind || it.kind.startsWith("$kind.") }
        val size = if (limit == 0) 100 else limit
        return LogbookPage(entries = matching.take(size), rev = rev, more = matching.size > size)
    }

    private fun TestScope.viewModel(): LogbookViewModel {
        val vm = LogbookViewModel(
            read = ::read,
            kindLabel = { kind -> if (kind == "group.switch") "分组切换" else null },
            compute = EmptyCoroutineContext,
        )
        // uiState is WhileSubscribed, as it is under the real screen.
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    private val LogbookViewModel.messages get() = uiState.value.entries.map { it.message }

    @Test
    fun `reads the newest page on open, then again only once something was written`() = runTest {
        write("a")
        write("b")
        val vm = viewModel()

        assertTrue(vm.uiState.value.loaded)
        assertEquals(listOf("b", "a"), vm.messages)
        assertEquals(listOf(Read(0, 0, LogbookViewModel.PAGE_SIZE)), reads)

        reads.clear()
        advanceTimeBy(LogbookViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals("nothing new: one probe", listOf(Read(0, 2, 1)), reads)

        reads.clear()
        write("c")
        advanceTimeBy(LogbookViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf(Read(0, 2, 1), Read(0, 0, LogbookViewModel.PAGE_SIZE)), reads)
        assertEquals(listOf("c", "b", "a"), vm.messages)
    }

    @Test
    fun `an empty logbook is loaded, not failed`() = runTest {
        val vm = viewModel()

        assertTrue(vm.uiState.value.loaded)
        assertTrue(vm.uiState.value.entries.isEmpty())
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `pages back, and a later poll keeps every page shown`() = runTest {
        repeat(150) { write("e$it") }
        val vm = viewModel()
        assertEquals(100, vm.uiState.value.entries.size)
        assertTrue(vm.uiState.value.more)

        vm.loadMore()
        runCurrent()
        assertEquals(Read(51, 0, LogbookViewModel.PAGE_SIZE), reads.last())
        assertEquals(150, vm.uiState.value.entries.size)
        assertFalse(vm.uiState.value.more)

        write("new")
        advanceTimeBy(LogbookViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(Read(0, 0, 150), reads.last())
        assertEquals("new", vm.messages.first())
        assertEquals(150, vm.uiState.value.entries.size)
        assertTrue(vm.uiState.value.more)
    }

    @Test
    fun `an unreadable logbook says why, and the next poll recovers`() = runTest {
        failure = IOException("database is locked")
        val vm = viewModel()
        assertTrue(vm.uiState.value.loaded)
        assertEquals("database is locked", vm.uiState.value.error)

        failure = null
        write("back")
        advanceTimeBy(LogbookViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertNull(vm.uiState.value.error)
        assertEquals(listOf("back"), vm.messages)
    }

    @Test
    fun `a search reads the whole logbook once and matches every word, the event name too`() = runTest {
        repeat(1_200) { write("filler $it") }
        write("now on HK 02", kind = "group.switch", subject = "Auto")
        write("now on JP 01", kind = "group.switch", subject = "Manual")
        write("reloaded")
        val vm = viewModel()
        reads.clear()

        vm.onQueryChange("分组切换 auto")
        runCurrent()
        assertEquals("shown as typed", "分组切换 auto", vm.uiState.value.query)
        assertTrue("waits for a pause in typing", reads.isEmpty())

        advanceTimeBy(LogbookViewModel.DEBOUNCE_MS)
        runCurrent()
        val max = LogbookViewModel.MAX_PAGE
        assertEquals(listOf(Read(0, 0, max), Read(204, 0, max)), reads)
        assertEquals(listOf("now on HK 02"), vm.messages)
        assertEquals("分组切换 auto", vm.uiState.value.search)
        assertFalse("every match is shown", vm.uiState.value.more)

        reads.clear()
        advanceTimeBy(LogbookViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals("nothing new: one probe", listOf(Read(0, 1203, 1)), reads)

        reads.clear()
        write("now on HK 03", kind = "group.switch", subject = "Auto")
        advanceTimeBy(LogbookViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf(Read(0, 1203, 1), Read(0, 0, max), Read(205, 0, max)), reads)
        assertEquals(listOf("now on HK 03", "now on HK 02"), vm.messages)
    }

    @Test
    fun `typing reads the logbook once, after the pause`() = runTest {
        write("reloaded")
        val vm = viewModel()
        reads.clear()

        for (typed in listOf("r", "re", "rel")) {
            vm.onQueryChange(typed)
            advanceTimeBy(LogbookViewModel.DEBOUNCE_MS / 2)
            runCurrent()
        }
        advanceTimeBy(LogbookViewModel.DEBOUNCE_MS)
        runCurrent()

        assertEquals(listOf(Read(0, 0, LogbookViewModel.MAX_PAGE)), reads)
        assertEquals(listOf("reloaded"), vm.messages)
    }

    @Test
    fun `a search without a match says so, and clearing it shows the newest entries`() = runTest {
        repeat(150) { write("e$it") }
        val vm = viewModel()

        vm.onQueryChange("  nothing  ")
        advanceTimeBy(LogbookViewModel.DEBOUNCE_MS)
        runCurrent()
        assertTrue(vm.uiState.value.loaded)
        assertTrue(vm.uiState.value.entries.isEmpty())
        assertEquals("nothing", vm.uiState.value.search)

        reads.clear()
        vm.onQueryChange("")
        runCurrent()
        assertEquals(listOf(Read(0, 0, LogbookViewModel.PAGE_SIZE)), reads)
        assertEquals("", vm.uiState.value.search)
        assertEquals(100, vm.uiState.value.entries.size)
        assertTrue(vm.uiState.value.more)
    }

    @Test
    fun `picking an event reads only that event at once, and pages back within it`() = runTest {
        repeat(150) { write("s$it", kind = "group.switch", subject = "Auto") }
        write("reloaded")
        val vm = viewModel()
        reads.clear()

        vm.onKindChange("group.switch")
        runCurrent()
        assertEquals("group.switch", vm.uiState.value.kind)
        assertEquals(listOf(Read(0, 0, LogbookViewModel.PAGE_SIZE, "group.switch")), reads)
        assertEquals(100, vm.uiState.value.entries.size)
        assertTrue(vm.uiState.value.entries.all { it.kind == "group.switch" })
        assertTrue(vm.uiState.value.more)

        vm.loadMore()
        runCurrent()
        assertEquals(Read(51, 0, LogbookViewModel.PAGE_SIZE, "group.switch"), reads.last())
        assertEquals(150, vm.uiState.value.entries.size)
        assertFalse(vm.uiState.value.more)

        // The probe watches the whole logbook; the re-read stays on the event.
        reads.clear()
        write("again")
        advanceTimeBy(LogbookViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf(Read(0, 151, 1), Read(0, 0, 150, "group.switch")), reads)
        assertEquals(150, vm.uiState.value.entries.size)

        vm.onKindChange(null)
        runCurrent()
        assertEquals(Read(0, 0, LogbookViewModel.PAGE_SIZE), reads.last())
        assertEquals("again", vm.messages.first())
        assertNull(vm.uiState.value.kind)
    }

    @Test
    fun `an event and a search narrow each other, from one read of the logbook`() = runTest {
        write("now on HK 02", kind = "group.switch", subject = "Auto")
        write("Auto is down", kind = "group.health", subject = "Auto")
        write("reloaded")
        val vm = viewModel()

        vm.onQueryChange("auto")
        advanceTimeBy(LogbookViewModel.DEBOUNCE_MS)
        runCurrent()
        assertEquals(listOf("Auto is down", "now on HK 02"), vm.messages)

        reads.clear()
        vm.onKindChange("group.switch")
        runCurrent()
        assertEquals("no pause, and the copy read for the search is reused", listOf(Read(0, 3, 1)), reads)
        assertEquals(listOf("now on HK 02"), vm.messages)
        assertEquals("auto", vm.uiState.value.search)
    }
}
