package io.github.madeye.meow.subscription

import io.github.madeye.meow.database.ClashProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdateScheduleTest {

    private val hour = 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    private fun subscription(
        lastUpdated: Long = now - 25 * hour,
        intervalHours: Int = 24,
        autoUpdate: Boolean = true,
        url: String = "https://example.com/sub.yaml",
        yaml: String = "proxies: []\n",
        backup: String = yaml,
    ) = ClashProfile(
        name = "sub",
        url = url,
        yamlContent = yaml,
        yamlBackup = backup,
        lastUpdated = lastUpdated,
        autoUpdate = autoUpdate,
        updateIntervalHours = intervalHours,
    )

    @Test
    fun `new subscriptions default to a daily auto-update`() {
        val profile = ClashProfile(url = "https://example.com/sub.yaml")

        assertTrue(profile.autoUpdate)
        assertEquals(24, profile.updateIntervalHours)
    }

    @Test
    fun `due once the interval has fully elapsed`() {
        assertTrue(AutoUpdateSchedule.isDue(subscription(lastUpdated = now - 24 * hour), now))
        assertFalse(AutoUpdateSchedule.isDue(subscription(lastUpdated = now - 24 * hour + 1), now))
    }

    @Test
    fun `the interval is per profile`() {
        val fetched = now - 7 * hour

        assertTrue(AutoUpdateSchedule.isDue(subscription(lastUpdated = fetched, intervalHours = 6), now))
        assertFalse(AutoUpdateSchedule.isDue(subscription(lastUpdated = fetched, intervalHours = 12), now))
    }

    @Test
    fun `a profile that was never fetched is due`() {
        assertTrue(AutoUpdateSchedule.isDue(subscription(lastUpdated = 0), now))
    }

    @Test
    fun `switched off is never due`() {
        assertFalse(AutoUpdateSchedule.isDue(subscription(lastUpdated = 0, autoUpdate = false), now))
    }

    @Test
    fun `a file import has no URL to refresh from`() {
        assertFalse(AutoUpdateSchedule.isDue(subscription(lastUpdated = 0, url = ""), now))
    }

    @Test
    fun `hand-edited YAML is never overwritten in the background`() {
        val edited = subscription(lastUpdated = 0, yaml = "edited: true\n", backup = "fetched: true\n")

        assertTrue(AutoUpdateSchedule.hasLocalEdits(edited))
        assertFalse(AutoUpdateSchedule.isDue(edited, now))
    }

    @Test
    fun `a timestamp from the future counts as due`() {
        // The clock was set back after the last fetch; waiting for it to
        // catch up would stall updates for as long as the skew.
        val skewed = subscription(lastUpdated = now + 3 * 24 * hour)

        assertEquals(now, AutoUpdateSchedule.dueAt(skewed.lastUpdated, skewed.updateIntervalHours, now))
        assertTrue(AutoUpdateSchedule.isDue(skewed, now))
    }

    @Test
    fun `out-of-range stored intervals are clamped`() {
        val fetched = now - 2 * hour

        // 0 would otherwise refetch on every worker run.
        assertEquals(fetched + hour, AutoUpdateSchedule.dueAt(fetched, 0, now))
        assertEquals(fetched + hour, AutoUpdateSchedule.dueAt(fetched, -5, now))
        assertEquals(
            fetched + AutoUpdateSchedule.MAX_INTERVAL_HOURS * hour,
            AutoUpdateSchedule.dueAt(fetched, Int.MAX_VALUE, now),
        )
    }

    @Test
    fun `interval field accepts whole hours in range`() {
        assertEquals(24, AutoUpdateSchedule.parseIntervalHours("24"))
        assertEquals(6, AutoUpdateSchedule.parseIntervalHours(" 6 "))
        assertEquals(1, AutoUpdateSchedule.parseIntervalHours("1"))
        assertEquals(720, AutoUpdateSchedule.parseIntervalHours("720"))
    }

    @Test
    fun `interval field rejects everything else`() {
        for (text in listOf("", " ", "0", "-1", "721", "1.5", "abc", "99999999999")) {
            assertNull("\"$text\"", AutoUpdateSchedule.parseIntervalHours(text))
        }
    }
}
