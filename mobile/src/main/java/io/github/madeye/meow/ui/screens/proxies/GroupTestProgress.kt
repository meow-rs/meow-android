package io.github.madeye.meow.ui.screens.proxies

import androidx.compose.runtime.Immutable
import io.github.madeye.meow.api.MemberDelay
import io.github.madeye.meow.api.ProxyHistory

/** What a node row's latency badge shows. */
@Immutable
sealed interface NodeDelay {
    /** Never probed: the engine holds no history for it. */
    data object Untested : NodeDelay

    /** Queued or in flight in a running group test. */
    data object Testing : NodeDelay

    data class Measured(val ms: Int) : NodeDelay

    /** The last probe timed out or failed; the engine records both as 0. */
    data object TimedOut : NodeDelay

    companion object {
        fun of(delayMs: Int): NodeDelay = if (delayMs > 0) Measured(delayMs) else TimedOut

        /**
         * A failed probe still appends a (zero) entry, so only an empty
         * history means "never tested".
         */
        fun fromHistory(history: List<ProxyHistory>): NodeDelay =
            history.lastOrNull()?.let { of(it.delay) } ?: Untested
    }
}

/**
 * One group's running latency test: every member starts out [NodeDelay.Testing]
 * and is replaced by its result as it lands.
 */
data class GroupTestProgress(val delays: Map<String, NodeDelay>) {
    val total: Int get() = delays.size
    val completed: Int get() = delays.values.count { it != NodeDelay.Testing }

    /** Share of members with a result, for a determinate indicator. */
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total

    /**
     * Records [result]. A name outside this run, or one already reported, is
     * ignored, so a stray late result can never overwrite a fresh one.
     */
    fun withResult(result: MemberDelay): GroupTestProgress =
        if (delays[result.name] != NodeDelay.Testing) {
            this
        } else {
            copy(delays = delays + (result.name to NodeDelay.of(result.delayMs)))
        }

    companion object {
        fun start(members: List<String>): GroupTestProgress =
            GroupTestProgress(members.associateWith { NodeDelay.Testing })
    }
}

/**
 * Lays running tests over the groups last loaded from `/proxies`. Kept apart
 * from the loaded list so a reload mid-test (resume, a node pick) cannot wipe
 * the spinners or results already in.
 */
internal fun List<ProxyGroupUi>.withTests(
    tests: Map<String, GroupTestProgress>,
): List<ProxyGroupUi> {
    if (tests.isEmpty()) return this
    return map { group ->
        val progress = tests[group.name] ?: return@map group
        group.copy(
            testProgress = progress.fraction,
            // A member added by a reload mid-test keeps its loaded state.
            nodes = group.nodes.map { node ->
                progress.delays[node.name]?.let { node.copy(delay = it) } ?: node
            },
        )
    }
}
