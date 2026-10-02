package io.github.madeye.meow.tile

import android.service.quicksettings.Tile
import io.github.madeye.meow.R
import io.github.madeye.meow.bg.BaseService.State
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnTileTest {

    @Test
    fun `the tile is on from the moment a start is under way`() {
        assertEquals(Tile.STATE_ACTIVE, tileLook(State.Connecting).tileState)
        assertEquals(Tile.STATE_ACTIVE, tileLook(State.Connected).tileState)
    }

    @Test
    fun `the tile is off as soon as a stop begins`() {
        for (state in listOf(State.Stopping, State.Stopped, State.Idle)) {
            assertEquals("$state", Tile.STATE_INACTIVE, tileLook(state).tileState)
        }
    }

    @Test
    fun `the status line tells a transition from where it settles`() {
        // An off tile that is still stopping must not read as already stopped.
        assertEquals(R.string.home_disconnecting, tileLook(State.Stopping).subtitle)
        assertEquals(R.string.home_connecting, tileLook(State.Connecting).subtitle)
        assertEquals(State.entries.size, State.entries.map { tileLook(it).subtitle }.toSet().size)
    }

    @Test
    fun `tapping a connected or connecting tile stops the vpn`() {
        assertEquals(TileTap.Stop, tileTap(State.Connected))
        assertEquals(TileTap.Stop, tileTap(State.Connecting))
    }

    @Test
    fun `a tap while stopping is dropped rather than lost in the service`() {
        assertEquals(TileTap.Ignore, tileTap(State.Stopping))
    }

    @Test
    fun `tapping a stopped tile starts the vpn`() {
        assertEquals(TileTap.Start, tileTap(State.Stopped))
        // Idle is what a binder reports for a service that is gone.
        assertEquals(TileTap.Start, tileTap(State.Idle))
    }

    @Test
    fun `a start with consent and a profile happens in place`() {
        assertEquals(StartRoute.Direct, startRoute(hasProfile = true, hasConsent = true))
    }

    @Test
    fun `a start without consent goes through the consent activity`() {
        assertEquals(StartRoute.Consent, startRoute(hasProfile = true, hasConsent = false))
    }

    @Test
    fun `a start without a profile opens the app, consent or not`() {
        assertEquals(StartRoute.OpenApp, startRoute(hasProfile = false, hasConsent = true))
        assertEquals(StartRoute.OpenApp, startRoute(hasProfile = false, hasConsent = false))
    }
}
