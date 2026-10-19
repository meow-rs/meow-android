package io.github.madeye.meow.ui.screens.logbook

import io.github.madeye.meow.logbook.LogbookEntry
import io.github.madeye.meow.logbook.LogbookLevel
import io.github.madeye.meow.logbook.LogbookPage
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogbookViewModelTest {

    private data class Read(val before: Long, val since: Long, val limit: Int, val level: LogbookLevel?)

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

    private fun write(message: String, level: LogbookLevel = LogbookLevel.Info) {
        rev += 1
        book += LogbookEntry(
            id = book.size + 1L,
            rev = rev,
            time = "2026-10-19T08:00:00.000Z",
            kind = "config.reload",
            level = level,
            message = message,
        )
    }

    /** `logbook::read`'s semantics, over [book]. */
    private fun read(before: Long, since: Long, limit: Int, level: LogbookLevel?): LogbookPage {
        reads += Read(before, since, limit, level)
        failure?.let { throw it }
        val matching = book.reversed()
            .filter { before == 0L || it.id < before }
            .filter { since == 0L || it.rev > since }
            .filter { level == null || it.level >= level }
        val size = if (limit == 0) 100 else limit
        return LogbookPage(entries = matching.take(size), rev = rev, more = matching.size > size)
    }

    private fun TestScope.viewModel(): LogbookViewModel {
        val vm = LogbookViewModel(read = ::read)
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
        assertEquals(listOf(Read(0, 0, LogbookViewModel.PAGE_SIZE, null)), reads)

        reads.clear()
        advanceTimeBy(LogbookViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals("nothing new: one probe", listOf(Read(0, 2, 1, null)), reads)

        reads.clear()
        write("c")
        advanceTimeBy(LogbookViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf(Read(0, 2, 1, null), Read(0, 0, LogbookViewModel.PAGE_SIZE, null)), reads)
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
    fun `a filter change reads that level at once`() = runTest {
        write("fine")
        write("broken", LogbookLevel.Error)
        write("odd", LogbookLevel.Warning)
        val vm = viewModel()

        vm.onLevelChange(LogbookLevel.Warning)
        runCurrent()

        assertEquals(LogbookLevel.Warning, vm.uiState.value.level)

        assertEquals(Read(0, 0, LogbookViewModel.PAGE_SIZE, LogbookLevel.Warning), reads.last())
        assertEquals(listOf("odd", "broken"), vm.messages)
    }

    @Test
    fun `pages back, and a later poll keeps every page shown`() = runTest {
        repeat(150) { write("e$it") }
        val vm = viewModel()
        assertEquals(100, vm.uiState.value.entries.size)
        assertTrue(vm.uiState.value.more)

        vm.loadMore()
        runCurrent()
        assertEquals(Read(51, 0, LogbookViewModel.PAGE_SIZE, null), reads.last())
        assertEquals(150, vm.uiState.value.entries.size)
        assertFalse(vm.uiState.value.more)

        write("new")
        advanceTimeBy(LogbookViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(Read(0, 0, 150, null), reads.last())
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
}
