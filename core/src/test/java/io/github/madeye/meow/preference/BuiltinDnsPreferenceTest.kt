package io.github.madeye.meow.preference

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinDnsPreferenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun store() = BuiltinDnsStore(File(tmp.root, "builtin_dns"))

    @Test
    fun `a flip reaches the VPN after it is saved`() {
        val store = store()
        // Each flip applied, with what the store held then.
        val applied = LinkedBlockingQueue<Pair<Boolean, Boolean>>()
        val pref = BuiltinDnsPreference(scope, store) { enabled ->
            applied.put(enabled to store.load())
            null
        }

        pref.set(false)

        // So a VPN start the flip did not reach reads it from the store.
        assertEquals(false to false, applied.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun `a burst of flips is applied in order`() {
        val applied = LinkedBlockingQueue<Boolean>()
        val pref = BuiltinDnsPreference(scope, store()) { enabled ->
            applied.put(enabled)
            null
        }

        pref.set(false)
        pref.set(true)
        pref.set(false)

        val order = List(3) { applied.poll(10, TimeUnit.SECONDS) }
        assertEquals(listOf(false, true, false), order)
    }

    @Test
    fun `a flip the VPN refused is reported`() = runBlocking {
        val pref = BuiltinDnsPreference(scope, store()) { "rule-set: missing" }
        val failure = async(start = CoroutineStart.UNDISPATCHED) { pref.applyFailures.first() }

        pref.set(false)

        assertEquals("rule-set: missing", withTimeout(10_000) { failure.await() })
    }
}
