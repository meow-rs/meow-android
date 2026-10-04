package io.github.madeye.meow.vpn

import io.github.madeye.meow.bg.ActiveConfig
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigReloaderTest {

    private val a = ActiveConfig(1, "proxies: [a]")
    private val b = ActiveConfig(2, "proxies: [b]")
    private val c = ActiveConfig(3, "proxies: [c]")

    /** Stands in for the selected row in Room. */
    private var selected: ActiveConfig? = a
    private var readsFail = false
    private var sent = 0

    /** What the engine says about any YAML; null accepts it. */
    private var engineError: String? = null
    private var validatorFails = false
    private var validateMs = 0L
    private val validated = mutableListOf<String>()

    private fun TestScope.reloader() = ConfigReloader(
        selected = { if (readsFail) throw IllegalStateException("database closed") else selected },
        validate = { yaml ->
            validated += yaml
            delay(validateMs)
            if (validatorFails) throw UnsatisfiedLinkError("libmeow_core.so not loaded")
            engineError
        },
        sendReload = { sent++ },
        scope = this,
        settleMs = SETTLE,
    )

    @Test
    fun `switching profile reloads once the change settles`() = runTest {
        val reloader = reloader()
        val announced = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reloader.reloads.toList(announced) }

        reloader.applying { selected = b }
        advanceTimeBy(SETTLE - 1)
        runCurrent()
        assertEquals(0, sent)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, sent)
        assertEquals(1, announced.size)
    }

    @Test
    fun `new YAML for the selected profile reloads`() = runTest {
        reloader().applying { selected = a.copy(yaml = "proxies: [a2]") }
        advanceUntilIdle()

        assertEquals(1, sent)
    }

    @Test
    fun `a write that leaves the selection alone sends nothing`() = runTest {
        // A non-selected profile refreshed, or a rename.
        reloader().applying { }
        advanceUntilIdle()

        assertEquals(0, sent)
    }

    @Test
    fun `a refresh returning the same bytes sends nothing`() = runTest {
        reloader().applying { selected = ActiveConfig(1, "proxies: [a]") }
        advanceUntilIdle()

        assertEquals(0, sent)
        assertTrue(validated.isEmpty())
    }

    @Test
    fun `deleting the selected profile sends nothing`() = runTest {
        reloader().applying { selected = null }
        advanceUntilIdle()

        assertEquals(0, sent)
    }

    @Test
    fun `rapid switches settle into one reload`() = runTest {
        val reloader = reloader()
        reloader.applying { selected = b }
        advanceTimeBy(SETTLE / 2)
        reloader.applying { selected = c }
        advanceTimeBy(SETTLE / 2)
        reloader.applying { selected = a.copy(yaml = "proxies: [a2]") }
        advanceUntilIdle()

        assertEquals(1, sent)
        // Each change restarts the window.
        assertEquals(2 * (SETTLE / 2) + SETTLE, currentTime)
    }

    @Test
    fun `switching away and back within the window sends nothing`() = runTest {
        val reloader = reloader()
        reloader.applying { selected = b }
        advanceTimeBy(SETTLE / 2)
        reloader.applying { selected = a }
        advanceUntilIdle()

        assertEquals(0, sent)
    }

    @Test
    fun `changes further apart than the window reload separately`() = runTest {
        val reloader = reloader()
        reloader.applying { selected = b }
        advanceUntilIdle()
        reloader.applying { selected = c }
        advanceUntilIdle()

        assertEquals(2, sent)
    }

    @Test
    fun `a batch reloads once for all its writes`() = runTest {
        // Several writes to the selected profile inside one applying block.
        reloader().applying {
            selected = a.copy(yaml = "proxies: [a2]")
            selected = a.copy(yaml = "proxies: [a3]")
        }
        advanceUntilIdle()

        assertEquals(1, sent)
    }

    @Test
    fun `a write that commits and then fails still reloads`() = runTest {
        // The block saved the selected profile, then a later step threw.
        val result = runCatching {
            reloader().applying<Unit> {
                selected = b
                throw IOException("a later fetch failed")
            }
        }
        advanceUntilIdle()

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, sent)
    }

    @Test
    fun `a failed read after the write reloads nothing`() = runTest {
        reloader().applying {
            selected = b
            readsFail = true
        }
        advanceUntilIdle()

        assertEquals(0, sent)
    }

    @Test
    fun `a failed read once settled reloads nothing`() = runTest {
        reloader().applying { selected = b }
        readsFail = true
        advanceUntilIdle()

        assertEquals(0, sent)
    }

    @Test
    fun `a valid update is checked by the engine and then reloads`() = runTest {
        val reloader = reloader()
        val rejected = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reloader.rejected.toList(rejected) }

        reloader.applying { selected = b }
        advanceUntilIdle()

        assertEquals(listOf(b.yaml), validated)
        assertEquals(1, sent)
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `an update the engine rejects keeps the running config`() = runTest {
        val reloader = reloader()
        val announced = mutableListOf<Unit>()
        val rejected = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reloader.reloads.toList(announced) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reloader.rejected.toList(rejected) }
        // A subscription that came back as an HTML error page, say.
        engineError = "invalid config: expected a mapping"

        reloader.applying { selected = a.copy(yaml = "<html>502 Bad Gateway</html>") }
        advanceUntilIdle()

        assertEquals(0, sent)
        assertTrue(announced.isEmpty())
        assertEquals(listOf("invalid config: expected a mapping"), rejected)
    }

    @Test
    fun `a validator that throws keeps the running config`() = runTest {
        val reloader = reloader()
        val rejected = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reloader.rejected.toList(rejected) }
        validatorFails = true

        reloader.applying { selected = b }
        advanceUntilIdle()

        assertEquals(0, sent)
        // Unknown is not an engine error, so there is nothing to report.
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `fixing a rejected update reloads`() = runTest {
        val reloader = reloader()
        engineError = "invalid config"
        reloader.applying { selected = a.copy(yaml = "proxies: [") }
        advanceUntilIdle()

        engineError = null
        reloader.applying { selected = a.copy(yaml = "proxies: [a2]") }
        advanceUntilIdle()

        assertEquals(1, sent)
    }

    @Test
    fun `a change during validation checks only the newest YAML`() = runTest {
        val reloader = reloader()
        validateMs = SETTLE
        reloader.applying { selected = b }
        advanceTimeBy(SETTLE + SETTLE / 2)
        reloader.applying { selected = c }
        advanceUntilIdle()

        assertEquals(listOf(b.yaml, c.yaml), validated)
        assertEquals(1, sent)
    }

    @Test
    fun `switching back during validation sends nothing`() = runTest {
        val reloader = reloader()
        validateMs = SETTLE
        reloader.applying { selected = b }
        advanceTimeBy(SETTLE + SETTLE / 2)
        reloader.applying { selected = a }
        advanceUntilIdle()

        // Still compared against the burst's start, which is what runs.
        assertEquals(listOf(b.yaml), validated)
        assertEquals(0, sent)
    }

    private companion object {
        const val SETTLE = 500L
    }
}
