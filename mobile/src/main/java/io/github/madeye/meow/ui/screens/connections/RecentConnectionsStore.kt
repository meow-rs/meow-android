package io.github.madeye.meow.ui.screens.connections

import io.github.madeye.meow.api.Connection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One connection that was present in an earlier poll and missing from a later one.
 *
 * The row is the last snapshot that still listed the flow: [connection]'s byte
 * counts and [lastSeenAtMillis] both date from that poll. `/connections` does not
 * report a close time, so the flow closed somewhere after [lastSeenAtMillis].
 */
data class RecentConnection(
    val connection: Connection,
    val lastSeenAtMillis: Long,
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
 * closed, is never seen. A flow that was open when the screen was left and gone
 * on the next visit is filed with the state it was last seen in, so the gap does
 * not stretch its duration. Several flows that vanish in the same poll are
 * ordered by start time, newest first — the poll cannot tell which closed last.
 *
 * A snapshot fetched across a local close ([closeLocal], [closeAllLocal]) is
 * dropped, so an in-flight poll cannot undo a close the user just tapped. This is
 * ordered by a counter rather than the wall clock, which can step backwards.
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

    /** Last active set, in snapshot order. */
    private var activeById: Map<String, Connection> = emptyMap()

    /** When a poll last observed [activeById]; local closes do not refresh it. */
    private var activeSeenAt: Long = 0

    /** Bumped by every local close; a snapshot taken under an older value is stale. */
    private var localEdits: Long = 0

    /** Take before fetching a snapshot and hand back to [onSnapshot]. */
    fun snapshotTicket(): Long = synchronized(lock) { localEdits }

    fun onSnapshot(activeNow: List<Connection>, ticket: Long = snapshotTicket()) {
        synchronized(lock) {
            if (ticket != localEdits) return
            val seenAt = clock()
            // Vanished rows are stamped with the previous poll's time, so publish first.
            publish(activeNow)
            activeSeenAt = seenAt
        }
    }

    /** Optimistic close of one flow; beats any snapshot already in flight. */
    fun closeLocal(id: String) {
        synchronized(lock) {
            if (id !in activeById) return
            localEdits++
            publish(activeById.values.filter { it.id != id })
        }
    }

    /** Optimistic close of every flow; beats any snapshot already in flight. */
    fun closeAllLocal() {
        synchronized(lock) {
            localEdits++
            publish(emptyList())
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
            val stamped = closed
                .sortedByDescending { it.start }
                .map { RecentConnection(connection = it, lastSeenAtMillis = activeSeenAt) }
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
