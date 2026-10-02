package io.github.madeye.meow.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedMeterTest {

    private val meter = SpeedMeter()

    @Test
    fun `the first read is only a baseline`() {
        // Cumulative totals from before the ticker started are not a speed.
        assertNull(meter.sample(tx = 50_000_000, rx = 90_000_000, nowMillis = 1_000))
    }

    @Test
    fun `speed is the counter delta per second`() {
        meter.sample(tx = 1_000, rx = 10_000, nowMillis = 1_000)

        assertEquals(Speed(up = 512, down = 4_096), meter.sample(tx = 1_512, rx = 14_096, nowMillis = 2_000))
    }

    @Test
    fun `a late tick is averaged over the time it actually covered`() {
        meter.sample(tx = 0, rx = 0, nowMillis = 1_000)

        assertEquals(Speed(up = 1_000, down = 2_000), meter.sample(tx = 4_000, rx = 8_000, nowMillis = 5_000))
    }

    @Test
    fun `each read is measured from the one before it`() {
        meter.sample(tx = 0, rx = 0, nowMillis = 0)
        meter.sample(tx = 10_000, rx = 0, nowMillis = 1_000)

        assertEquals(Speed(up = 0, down = 300), meter.sample(tx = 10_000, rx = 300, nowMillis = 2_000))
    }

    @Test
    fun `counters that restarted read as idle, not negative`() {
        meter.sample(tx = 80_000, rx = 80_000, nowMillis = 1_000)

        assertEquals(Speed.IDLE, meter.sample(tx = 100, rx = 0, nowMillis = 2_000))
        // ...and the restarted counters are the new baseline.
        assertEquals(Speed(up = 900, down = 0), meter.sample(tx = 1_000, rx = 0, nowMillis = 3_000))
    }

    @Test
    fun `a clock that did not move keeps the old baseline`() {
        meter.sample(tx = 0, rx = 0, nowMillis = 1_000)

        assertNull(meter.sample(tx = 500, rx = 500, nowMillis = 1_000))
        assertEquals(Speed(up = 1_000, down = 1_000), meter.sample(tx = 1_000, rx = 1_000, nowMillis = 2_000))
    }

    @Test
    fun `rates use the same unit ladder as the Home screen`() {
        assertEquals("0 B/s", Speed.rate(0))
        assertEquals("1023 B/s", Speed.rate(1_023))
        assertEquals("1.0 KB/s", Speed.rate(1_024))
        assertEquals("1.5 KB/s", Speed.rate(1_536))
        assertEquals("99.9 KB/s", Speed.rate(102_297))
        assertEquals("100 KB/s", Speed.rate(102_400))
        assertEquals("1.2 MB/s", Speed.rate(1_258_291))
        assertEquals("2.0 GB/s", Speed.rate(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `notification text puts upload first`() {
        assertEquals("↑ 0 B/s  ↓ 0 B/s", Speed.IDLE.text)
        assertEquals("↑ 1.5 KB/s  ↓ 340 KB/s", Speed(up = 1_536, down = 348_160).text)
    }
}
