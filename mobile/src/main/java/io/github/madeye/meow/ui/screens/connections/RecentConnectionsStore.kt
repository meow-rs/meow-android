package io.github.madeye.meow.ui.screens.connections

import io.github.madeye.meow.api.Connection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One connection that was present in an earlier poll and missing from a later one.
 *
 * [closedAtMillis] is when this store noticed it was gone, not an engine timestamp:
 * `/connections` does not report a close time.
 */
data class RecentConnection(
    val connection: Connection,
    val closedAtMillis: Long,
)

data class ConnectionLists(
    val active: List<Connection> = emptyList(),
    val recent: List<RecentConnection> = emptyList(),
)

/**
 * Process-scoped memory of connections that disappeared between polls.
 *
 * The engine's `/connections` snapshot only lists flows that are still open, so a
 * request that finishes is gone on the next poll. This store diffs each snapshot
 * against the previous one and keeps the vanished rows, newest first, up to
 * [CAPACITY]. It lives on [io.github.madeye.meow.AppGraph] rather than the
 * Connections screen's ViewModel so leaving the screen does not wipe the list.
 * Polling itself still runs only while that screen is open.
 *
 * A flow that starts and ends between two polls, or entirely while the screen is
 * closed, is never seen. Close time is when a poll first noticed the flow was
 * gone, which can be the next visit. Several flows that vanish in the same poll
 * are ordered by start time, newest first — the poll cannot tell which of them
 * closed last.
 *
 * A snapshot whose fetch started before [applyLocal] is dropped, so an in-flight
 * poll cannot undo a close the user just tapped.
 */
class RecentConnectionsStore(
    private val capacity: Int = CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacity > 0)
    }

    private val lock = Any()
    private val _lists = MutableStateFlow(ConnectionLists())
    val lists: StateFlow<ConnectionLists> = _lists.asStateFlow()

    /** Last successfully applied active set, in snapshot order. */
    private var activeById: Map<String, Connection> = emptyMap()

    /** Fetch-start time of the newest local edit. Older snapshots are stale. */
    private var lastMutationAt: Long = Long.MIN_VALUE

    internal fun currentTimeMillis(): Long = clock()

    /**
     * @param observedAt when the fetch that produced [activeNow] started. Defaults
     *   to "now", which is always applied.
     */
    fun onSnapshot(activeNow: List<Connection>, observedAt: Long = clock()) {
        synchronized(lock) {
            if (observedAt < lastMutationAt) return
            publish(activeNow)
        }
    }

    /**
     * Optimistic edit (the user closed a connection, or all of them). Beats any
     * snapshot whose fetch started earlier.
     */
    fun applyLocal(activeNow: List<Connection>) {
        synchronized(lock) {
            lastMutationAt = clock()
            publish(activeNow)
        }
    }

    fun clearRecent() {
        synchronized(lock) {
            val current = _lists.value
            if (current.recent.isEmpty()) return
            _lists.value = current.copy(recent = emptyList())
        }
    }

    private fun publish(activeNow: List<Connection>) {
        val nowIds = activeNow.mapTo(HashSet(activeNow.size)) { it.id }
        val closed = activeById.values.filter { it.id !in nowIds }
        val current = _lists.value
        var nextRecent = current.recent
        if (nextRecent.any { it.connection.id in nowIds }) {
            nextRecent = nextRecent.filterNot { it.connection.id in nowIds }
        }
        if (closed.isNotEmpty()) {
            val closedAt = clock()
            val stamped = closed
                .sortedByDescending { it.start }
                .map { RecentConnection(connection = it, closedAtMillis = closedAt) }
            nextRecent = (stamped + nextRecent).take(capacity)
        }
        activeById = activeNow.associateBy { it.id }
        if (nextRecent != current.recent || activeNow != current.active) {
            _lists.value = ConnectionLists(active = activeNow, recent = nextRecent)
        }
    }

    companion object {
        const val CAPACITY = 200
    }
}
