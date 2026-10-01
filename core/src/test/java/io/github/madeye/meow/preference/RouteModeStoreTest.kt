package io.github.madeye.meow.preference

import io.github.madeye.meow.api.RouteMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouteModeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storeFile(): File = File(tmp.root, "route_mode")

    @Test
    fun `nothing saved means follow the profile`() {
        assertNull(RouteModeStore(storeFile()).load())
    }

    @Test
    fun `a save is visible to a separate instance`() {
        // Stands in for the UI process writing and `:vpn` reading.
        RouteModeStore(storeFile()).save(RouteMode.Direct)

        assertEquals(RouteMode.Direct, RouteModeStore(storeFile()).load())
    }

    @Test
    fun `a later save replaces the earlier one`() {
        val store = RouteModeStore(storeFile())
        store.save(RouteMode.Global)
        store.save(RouteMode.Rule)

        assertEquals(RouteMode.Rule, store.load())
        assertFalse(File(tmp.root, "route_mode.tmp").exists())
    }

    @Test
    fun `an unreadable value is ignored`() {
        storeFile().writeText("script")

        assertNull(RouteModeStore(storeFile()).load())
    }
}
