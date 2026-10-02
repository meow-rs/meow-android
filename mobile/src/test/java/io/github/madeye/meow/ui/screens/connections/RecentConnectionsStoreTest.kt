package io.github.madeye.meow.ui.screens.connections

import io.github.madeye.meow.api.Connection
import io.github.madeye.meow.api.ConnectionMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentConnectionsStoreTest {

    private var now = 1_000L
    private val store = RecentConnectionsStore(capacity = 2, clock = { now })

    @Test
    fun `first snapshot records nothing as recent`() {
        store.onSnapshot(listOf(conn("a")))

        assertEquals(listOf("a"), store.lists.value.active.map { it.id })
        assertTrue(store.lists.value.recent.isEmpty())
    }

    @Test
    fun `a vanished connection keeps the bytes and time it was last seen with`() {
        store.onSnapshot(listOf(conn("a", upload = 1)))
        now = 2_000
        store.onSnapshot(listOf(conn("a", upload = 40, download = 9)))
        now = 3_500
        store.onSnapshot(emptyList())

        val recent = store.lists.value.recent
        assertEquals(1, recent.size)
        assertEquals(40, recent[0].connection.upload)
        assertEquals(9, recent[0].connection.download)
        assertEquals(2_000L, recent[0].lastSeenAtMillis)
        assertTrue(store.lists.value.active.isEmpty())
    }

    @Test
    fun `a gap between polls does not stretch the last seen time`() {
        // The screen is left with "a" open and revisited ten minutes later.
        store.onSnapshot(listOf(conn("a")))
        now = 601_000
        store.onSnapshot(emptyList())

        assertEquals(1_000L, store.lists.value.recent.single().lastSeenAtMillis)
    }

    @Test
    fun `newer closes are prepended and the oldest fall off the cap`() {
        store.onSnapshot(listOf(conn("a", start = "2026-10-02T00:00:01Z")))
        store.onSnapshot(emptyList())
        store.onSnapshot(
            listOf(
                conn("b", start = "2026-10-02T00:00:02Z"),
                conn("c", start = "2026-10-02T00:00:03Z"),
            ),
        )
        store.onSnapshot(emptyList())

        // c started last, so it leads the batch that closed together; a is past the cap.
        assertEquals(listOf("c", "b"), store.lists.value.recent.map { it.connection.id })
    }

    @Test
    fun `a connection that reappears is taken back out of recent`() {
        store.onSnapshot(listOf(conn("a", upload = 1), conn("b")))
        store.onSnapshot(listOf(conn("b")))
        assertEquals(listOf("a"), store.lists.value.recent.map { it.connection.id })

        store.onSnapshot(listOf(conn("a", upload = 8), conn("b")))

        assertTrue(store.lists.value.recent.isEmpty())
        assertEquals(8, store.lists.value.active.first { it.id == "a" }.upload)
    }

    @Test
    fun `a snapshot fetched before a local close cannot restore it`() {
        store.onSnapshot(listOf(conn("a"), conn("b")))
        val inFlight = store.snapshotTicket()
        store.closeLocal("a")
        store.onSnapshot(listOf(conn("a", upload = 99), conn("b")), inFlight)

        assertEquals(listOf("b"), store.lists.value.active.map { it.id })
        assertEquals(listOf("a"), store.lists.value.recent.map { it.connection.id })
        assertEquals(0, store.lists.value.recent[0].connection.upload)

        store.onSnapshot(listOf(conn("b")))
        assertEquals(listOf("a"), store.lists.value.recent.map { it.connection.id })
    }

    @Test
    fun `a wall clock that steps backwards does not freeze polling`() {
        now = 10_000
        store.onSnapshot(listOf(conn("a"), conn("b")))
        store.closeLocal("a")
        now = 1_000 // NTP or the user set the clock back
        store.onSnapshot(listOf(conn("b"), conn("c")))

        assertEquals(listOf("b", "c"), store.lists.value.active.map { it.id })
    }

    @Test
    fun `closing an unknown id does not discard an in-flight snapshot`() {
        val inFlight = store.snapshotTicket()
        store.closeLocal("gone")
        store.onSnapshot(listOf(conn("a")), inFlight)

        assertEquals(listOf("a"), store.lists.value.active.map { it.id })
    }

    @Test
    fun `a local close files the state from the last poll`() {
        store.onSnapshot(listOf(conn("a", upload = 3), conn("b")))
        now = 5_000
        store.closeLocal("a")
        now = 6_000
        store.onSnapshot(emptyList())

        // Neither row was observed after the 1_000 poll; the tap is not a sighting.
        assertEquals(
            listOf("b" to 1_000L, "a" to 1_000L),
            store.lists.value.recent.map { it.connection.id to it.lastSeenAtMillis },
        )
        assertEquals(3, store.lists.value.recent[1].connection.upload)
    }

    @Test
    fun `a fresh snapshot after a failed close puts the connection back`() {
        store.onSnapshot(listOf(conn("a")))
        store.closeAllLocal()
        now = 5_000
        store.onSnapshot(listOf(conn("a", upload = 4)))

        assertEquals(listOf("a"), store.lists.value.active.map { it.id })
        assertTrue(store.lists.value.recent.isEmpty())
    }

    @Test
    fun `closing twice does not duplicate the row`() {
        store.onSnapshot(listOf(conn("a")))
        store.closeAllLocal()
        store.closeAllLocal()
        store.closeLocal("a")

        assertEquals(1, store.lists.value.recent.size)
    }

    @Test
    fun `clear recent drops history and leaves the active set alone`() {
        store.onSnapshot(listOf(conn("a"), conn("b")))
        store.onSnapshot(listOf(conn("b")))
        store.clearRecent()

        assertTrue(store.lists.value.recent.isEmpty())
        assertEquals(listOf("b"), store.lists.value.active.map { it.id })

        store.onSnapshot(emptyList())
        assertEquals(listOf("b"), store.lists.value.recent.map { it.connection.id })
    }

    @Test
    fun `an unchanged snapshot does not emit`() {
        store.onSnapshot(listOf(conn("a", upload = 1)))
        val first = store.lists.value
        store.onSnapshot(listOf(conn("a", upload = 1)))

        assertTrue(first === store.lists.value)
    }

    private fun conn(
        id: String,
        upload: Long = 0,
        download: Long = 0,
        start: String = "2026-10-02T00:00:00Z",
    ) = Connection(
        id = id,
        upload = upload,
        download = download,
        start = start,
        metadata = ConnectionMeta(host = "$id.example"),
    )
}

class ConnectionsUiStateTest {

    @Test
    fun `query matches host or destination ip case insensitively`() {
        val state = ConnectionsUiState(
            connections = listOf(
                connection("a", host = "One.Example"),
                connection("b", host = "", destinationIp = "8.8.8.8"),
                connection("c", host = "other.test"),
            ),
            recent = listOf(
                RecentConnection(connection("d", host = "one.example"), lastSeenAtMillis = 1),
            ),
            query = "example",
        )

        assertEquals(listOf("a"), state.visibleConnections.map { it.id })
        assertEquals(listOf("d"), state.visibleRecent.map { it.connection.id })
        assertEquals(listOf("b"), state.copy(query = "8.8").visibleConnections.map { it.id })
        assertEquals(3, state.copy(query = "  ").visibleConnections.size)
    }

    private fun connection(id: String, host: String, destinationIp: String = "") = Connection(
        id = id,
        metadata = ConnectionMeta(host = host, destinationIP = destinationIp),
    )
}
