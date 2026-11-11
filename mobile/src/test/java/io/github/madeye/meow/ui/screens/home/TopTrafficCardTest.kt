package io.github.madeye.meow.ui.screens.home

import io.github.madeye.meow.traffic.TrafficGroup
import io.github.madeye.meow.traffic.TrafficRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopTrafficCardTest {

    @Test
    fun `the largest row fills its bar and the others scale to it`() {
        assertEquals(1f, share(341, 341), 0f)
        assertEquals(0.5f, share(150, 300), 0.0001f)
    }

    @Test
    fun `a row with traffic keeps a sliver of bar`() {
        assertEquals(0.02f, share(1, 1_000_000_000), 0f)
    }

    @Test
    fun `no traffic draws no bar`() {
        assertEquals(0f, share(0, 300), 0f)
        assertEquals(0f, share(0, 0), 0f)
    }

    @Test
    fun `addresses and the folded rest are told from domains`() {
        assertTrue(isAddress("203.0.113.45"))
        assertTrue(isAddress("2001:db8::1"))
        assertTrue(isAddress(TrafficRow.OTHER_IPS))
        assertFalse(isAddress("example.com"))
        assertFalse(isAddress("123.example.com"))
        assertFalse(isAddress(""))
    }

    @Test
    fun `the card offers apps first, then hosts and policies`() {
        assertEquals(listOf(TrafficGroup.App, TrafficGroup.Host, TrafficGroup.Policy), TOP_TRAFFIC_GROUPS)
    }
}
