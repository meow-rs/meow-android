package io.github.madeye.meow.ui.screens.proxies

import io.github.madeye.meow.api.MemberDelay
import io.github.madeye.meow.api.ProxyHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GroupTestProgressTest {

    @Test
    fun `a fresh run shows every member as testing`() {
        val progress = GroupTestProgress.start(listOf("a", "b", "c"))

        assertEquals(listOf(NodeDelay.Testing, NodeDelay.Testing, NodeDelay.Testing), progress.delays.values.toList())
        assertEquals(0, progress.completed)
        assertEquals(3, progress.total)
        assertEquals(0f, progress.fraction)
    }

    @Test
    fun `results fill in one member at a time, a zero as timed out`() {
        val progress = GroupTestProgress.start(listOf("a", "b", "c"))
            .withResult(MemberDelay("b", 120))
            .withResult(MemberDelay("c", 0))

        assertEquals(
            mapOf("a" to NodeDelay.Testing, "b" to NodeDelay.Measured(120), "c" to NodeDelay.TimedOut),
            progress.delays,
        )
        assertEquals(2, progress.completed)
        assertEquals(2f / 3, progress.fraction)
        // Rows stay in the group's order, not the order results land in.
        assertEquals(listOf("a", "b", "c"), progress.delays.keys.toList())
    }

    @Test
    fun `stray results are ignored`() {
        val progress = GroupTestProgress.start(listOf("a")).withResult(MemberDelay("a", 80))

        // Already reported, or not part of this run (e.g. left over from a superseded one).
        assertSame(progress, progress.withResult(MemberDelay("a", 0)))
        assertSame(progress, progress.withResult(MemberDelay("zz", 10)))
    }

    @Test
    fun `an empty group reports no progress rather than dividing by zero`() {
        assertEquals(0f, GroupTestProgress.start(emptyList()).fraction)
    }

    @Test
    fun `history tells untested from timed out`() {
        assertEquals(NodeDelay.Untested, NodeDelay.fromHistory(emptyList()))
        assertEquals(NodeDelay.TimedOut, NodeDelay.fromHistory(listOf(history(90), history(0))))
        assertEquals(NodeDelay.Measured(45), NodeDelay.fromHistory(listOf(history(0), history(45))))
    }

    @Test
    fun `a running test is laid over its own group only`() {
        val loaded = listOf(
            group("Proxy", node("a", NodeDelay.Measured(300)), node("b", NodeDelay.Untested), node("new")),
            group("Other", node("a", NodeDelay.Measured(300))),
        )
        val tests = mapOf(
            "Proxy" to GroupTestProgress.start(listOf("a", "b")).withResult(MemberDelay("a", 75)),
        )

        val shown = loaded.withTests(tests)

        assertEquals(0.5f, shown[0].testProgress)
        assertEquals(
            listOf(NodeDelay.Measured(75), NodeDelay.Testing, NodeDelay.Untested),
            shown[0].nodes.map { it.delay },
        )
        // A group with no test running is passed through untouched.
        assertSame(loaded[1], shown[1])
        assertNull(shown[1].testProgress)
    }

    @Test
    fun `no running tests leaves the loaded list as is`() {
        val loaded = listOf(group("Proxy", node("a")))

        assertSame(loaded, loaded.withTests(emptyMap()))
    }

    private fun history(delay: Int) = ProxyHistory(timeMillis = 0, delay = delay)

    private fun node(name: String, delay: NodeDelay = NodeDelay.Untested) =
        ProxyNodeUi(name = name, type = "Shadowsocks", delay = delay, selected = false)

    private fun group(name: String, vararg nodes: ProxyNodeUi) =
        ProxyGroupUi(name = name, type = "Selector", now = nodes.first().name, nodes = nodes.toList())
}
