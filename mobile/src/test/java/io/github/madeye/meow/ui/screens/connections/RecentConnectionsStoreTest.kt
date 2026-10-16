package io.github.madeye.meow.ui.screens.connections

import io.github.madeye.meow.api.Connection
import io.github.madeye.meow.api.ConnectionMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            rows = listOf(
                ConnectionRow(connection("a", host = "One.Example")),
                ConnectionRow(connection("b", host = "", destinationIp = "8.8.8.8")),
                ConnectionRow(connection("c", host = "other.test")),
                ConnectionRow(connection("d", host = "one.example"), lastSeenAtMillis = 1),
            ),
            query = "example",
        )

        assertEquals(listOf("a", "d"), state.visibleRows.map { it.connection.id })
        assertEquals(listOf("b"), state.copy(query = "8.8").visibleRows.map { it.connection.id })
        assertEquals(4, state.copy(query = "  ").visibleRows.size)
    }

    @Test
    fun `query matches the app by name or package once its name is known`() {
        val state = ConnectionsUiState(
            rows = listOf(
                ConnectionRow(connection("chrome", host = "a.test", process = "com.android.chrome")),
                ConnectionRow(connection("mail", host = "b.test", process = "com.example.mail")),
                ConnectionRow(connection("none", host = "c.test")),
            ),
            query = "chrome",
            appLabels = mapOf("com.android.chrome" to "Chrome", "com.example.mail" to "Inbox"),
        )

        assertEquals(listOf("chrome"), state.visibleRows.map { it.connection.id })
        assertEquals(listOf("mail"), state.copy(query = "inbox").visibleRows.map { it.connection.id })
        assertEquals(listOf("mail"), state.copy(query = "example.mail").visibleRows.map { it.connection.id })
        assertEquals("Chrome", state.appLabelOf(state.rows[0].connection))
        assertNull(state.appLabelOf(state.rows[2].connection))
        assertNull("not resolved yet", state.copy(appLabels = emptyMap()).appLabelOf(state.rows[0].connection))
    }

    @Test
    fun `open and closed connections share one list, newest start first`() {
        val lists = ConnectionLists(
            active = listOf(
                connection("open-old", start = "2026-10-16T02:00:00Z"),
                connection("open-new", start = "2026-10-16T02:05:00.5Z"),
            ),
            recent = listOf(
                RecentConnection(connection("closed-mid", start = "2026-10-16T02:03:00Z"), lastSeenAtMillis = 7),
                RecentConnection(connection("closed-newest", start = "2026-10-16T02:05:01Z"), lastSeenAtMillis = 9),
                RecentConnection(connection("closed-unparseable", start = ""), lastSeenAtMillis = 3),
            ),
        )

        val rows = lists.rows()

        assertEquals(
            listOf("closed-newest", "open-new", "closed-mid", "open-old", "closed-unparseable"),
            rows.map { it.connection.id },
        )
        assertEquals(listOf(9L, null, 7L, null, 3L), rows.map { it.lastSeenAtMillis })
        val state = ConnectionsUiState(rows = rows)
        assertTrue(state.hasActive && state.hasClosed)
        assertFalse(ConnectionsUiState(rows = rows.filter { it.lastSeenAtMillis != null }).hasActive)
        assertFalse(ConnectionsUiState(rows = rows.filter { it.lastSeenAtMillis == null }).hasClosed)
    }

    private fun connection(
        id: String,
        host: String = "",
        destinationIp: String = "",
        start: String = "",
        process: String = "",
    ) = Connection(
        id = id,
        start = start,
        metadata = ConnectionMeta(host = host, destinationIP = destinationIp, process = process),
    )
}
