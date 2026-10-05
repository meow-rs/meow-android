package io.github.madeye.meow.ui.screens.subscribe

import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.RuleProviderInfo
import io.github.madeye.meow.bg.BaseService.State
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleSetUpdaterTest {

    private val vpn = MutableStateFlow(State.Connected)

    /** What the engine lists; replaced to stand for an update or a reload. */
    private var providers = listOf(
        provider("ads", "HTTP", "2026-10-05T01:00:00Z"),
        provider("cn", "http", "2026-10-03T08:30:00Z"),
        provider("local", "File", "2026-09-01T00:00:00Z"),
        provider("inline", "Inline", ""),
    )
    private var listFails = false
    private var listings = 0

    /** Every update asked for, in order. */
    private val updated = mutableListOf<String>()

    /** What an update of a name does; succeeds at once by default. */
    private var answer: suspend (String) -> Unit = {}

    private fun provider(name: String, vehicle: String, updatedAt: String) =
        RuleProviderInfo(name = name, type = "Rule", vehicleType = vehicle, updatedAt = updatedAt, ruleCount = 10)

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun updater() = RuleSetUpdater(
        vpnState = vpn,
        listProviders = {
            listings++
            if (listFails) throw MeowApiException.Http("ruleProviders", 500)
            providers
        },
        updateProvider = { name ->
            updated += name
            answer(name)
        },
    )

    /** Collects [RuleSetUpdater.state] as the page would; the latest value is in the returned box. */
    private fun TestScope.watch(updater: RuleSetUpdater): MutableStateFlow<RuleSetsUi?> {
        val latest = MutableStateFlow<RuleSetsUi?>(null)
        backgroundScope.launch { updater.state.collect { latest.value = it } }
        runCurrent()
        return latest
    }

    // -------------------------------------------------------------------------
    // Detection
    // -------------------------------------------------------------------------

    @Test
    fun `a top-level rule-providers key declares rule sets`() {
        assertTrue(declaresRuleSets("mode: rule\nrule-providers:\n  ads:\n    type: http\n"))
        assertTrue(declaresRuleSets("rule-providers: {}"))
        assertTrue(declaresRuleSets("\uFEFFrule-providers:\n  ads: {}\n"))
        assertTrue(declaresRuleSets("mode: rule\r\nrule-providers:\r\n  ads: {}\r\n"))
    }

    @Test
    fun `nested, commented or similar keys do not`() {
        assertFalse(declaresRuleSets(""))
        assertFalse(declaresRuleSets("dns:\n  rule-providers:\n    ads: {}\n"))
        assertFalse(declaresRuleSets("# rule-providers:\nrules:\n  - MATCH,DIRECT\n"))
        assertFalse(declaresRuleSets("proxy-providers:\n  sub: {}\n"))
        assertFalse(declaresRuleSets("rules:\n  - RULE-SET,ads,REJECT\n"))
    }

    // -------------------------------------------------------------------------
    // Summary
    // -------------------------------------------------------------------------

    @Test
    fun `the summary counts HTTP sets only and keeps their oldest time`() {
        val summary = RuleSetSummary.of(providers)

        // The File set's older time does not count: the row never updates it.
        assertEquals(RuleSetSummary(count = 2, oldestUpdateMillis = millis("2026-10-03T08:30:00Z")), summary)
    }

    @Test
    fun `times that do not parse are skipped`() {
        val summary = RuleSetSummary.of(
            listOf(
                provider("a", "HTTP", ""),
                provider("b", "HTTP", "yesterday"),
                provider("c", "HTTP", "2026-10-04T00:00:00Z"),
            ),
        )
        assertEquals(RuleSetSummary(count = 3, oldestUpdateMillis = millis("2026-10-04T00:00:00Z")), summary)

        assertNull(RuleSetSummary.of(listOf(provider("a", "HTTP", ""))).oldestUpdateMillis)
        assertEquals(RuleSetSummary(0, null), RuleSetSummary.of(listOf(provider("x", "Inline", ""))))
    }

    // -------------------------------------------------------------------------
    // Listing
    // -------------------------------------------------------------------------

    @Test
    fun `lists the sets while connected and forgets them when not`() = runTest {
        val state = watch(updater())

        assertEquals(RuleSetsUi(connected = true, summary = RuleSetSummary.of(providers)), state.value)

        vpn.value = State.Stopping
        runCurrent()
        assertEquals(RuleSetsUi(connected = false), state.value)
        assertFalse(state.value!!.nothingToUpdate)

        // Coming back up lists again: another profile may be running now.
        providers = listOf(provider("other", "HTTP", ""))
        vpn.value = State.Connected
        runCurrent()
        assertEquals(RuleSetSummary(1, null), state.value!!.summary)
        assertEquals(2, listings)
    }

    @Test
    fun `nothing is listed while disconnected`() = runTest {
        vpn.value = State.Stopped
        val state = watch(updater())

        assertEquals(RuleSetsUi(connected = false), state.value)
        assertEquals(0, listings)
    }

    @Test
    fun `a failed listing leaves the row usable without a count`() = runTest {
        listFails = true
        val state = watch(updater())

        assertEquals(RuleSetsUi(connected = true, summary = null), state.value)
        assertFalse(state.value!!.nothingToUpdate)
    }

    @Test
    fun `no HTTP sets means nothing to update`() = runTest {
        providers = listOf(provider("local", "File", ""), provider("inline", "Inline", ""))
        val state = watch(updater())

        assertTrue(state.value!!.nothingToUpdate)
    }

    // -------------------------------------------------------------------------
    // Updating
    // -------------------------------------------------------------------------

    @Test
    fun `updates every HTTP set, re-lists them and reports the count`() = runTest {
        val updater = updater()
        val state = watch(updater)
        answer = { name ->
            providers = providers.map { if (it.name == name) it.copy(updatedAt = "2026-10-05T09:00:00Z") else it }
        }

        val event = updater.updateAll()

        assertEquals(SubscribeEvent.RuleSetsUpdated(2), event)
        assertEquals(setOf("ads", "cn"), updated.toSet())
        assertEquals(2, updated.size)
        runCurrent()
        assertEquals(
            RuleSetsUi(connected = true, summary = RuleSetSummary(2, millis("2026-10-05T09:00:00Z"))),
            state.value,
        )
    }

    @Test
    fun `a failed set is counted and the others still go ahead`() = runTest {
        val updater = updater()
        answer = { name -> if (name == "cn") throw MeowApiException.Http("updateRuleProvider", 503) }

        val event = updater.updateAll()

        assertEquals(SubscribeEvent.RuleSetsUpdateFailed(1), event)
        assertEquals(setOf("ads", "cn"), updated.toSet())
    }

    @Test
    fun `a listing failure on update throws and clears the running flag`() = runTest {
        val updater = updater()
        val state = watch(updater)
        listFails = true

        val thrown = runCatching { updater.updateAll() }.exceptionOrNull()

        assertTrue(thrown is IOException)

        runCurrent()
        assertFalse(state.value!!.updating)
        assertTrue(updated.isEmpty())
    }

    @Test
    fun `a second tap while updating is dropped`() = runTest {
        val updater = updater()
        val state = watch(updater)
        val gate = CompletableDeferred<Unit>()
        answer = { gate.await() }

        val first = async { updater.updateAll() }
        runCurrent()
        assertTrue(state.value!!.updating)

        assertNull(updater.updateAll())
        assertEquals(2, updated.size)

        gate.complete(Unit)
        assertEquals(SubscribeEvent.RuleSetsUpdated(2), first.await())
        runCurrent()
        assertFalse(state.value!!.updating)
    }

    @Test
    fun `at most four updates run at once`() = runTest {
        providers = (1..9).map { provider("set$it", "HTTP", "") }
        var running = 0
        var peak = 0
        answer = {
            running++
            peak = maxOf(peak, running)
            delay(1_000)
            running--
        }

        val event = updater().updateAll()

        assertEquals(SubscribeEvent.RuleSetsUpdated(9), event)
        assertEquals(9, updated.size)
        assertEquals(RuleSetUpdater.UPDATE_CONCURRENCY, peak)
    }
}
