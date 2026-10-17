package io.github.madeye.meow.bg

import io.github.madeye.meow.aidl.ByteTotals
import io.github.madeye.meow.aidl.SessionStats
import io.github.madeye.meow.bg.NetworkTrafficMeter.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTrafficMeterTest {

    private val meter = NetworkTrafficMeter(startedAt = 1_000)

    @Test
    fun `traffic is charged to the network in use while it was counted`() {
        meter.onNetwork(Kind.WifiEthernet, tx = 0, rx = 0)
        meter.onNetwork(Kind.Cellular, tx = 100, rx = 1_000)

        assertEquals(
            SessionStats(
                startedAt = 1_000,
                wifiEthernet = ByteTotals(upload = 100, download = 1_000),
                cellular = ByteTotals(upload = 20, download = 300),
            ),
            meter.read(tx = 120, rx = 1_300),
        )
    }

    @Test
    fun `a read charges the gain so far to the current network`() {
        meter.onNetwork(Kind.Cellular, tx = 0, rx = 0)

        assertEquals(ByteTotals(upload = 5, download = 50), meter.read(tx = 5, rx = 50).cellular)
        assertEquals(ByteTotals(upload = 7, download = 80), meter.read(tx = 7, rx = 80).cellular)
    }

    @Test
    fun `bytes counted with no network go to the next one`() {
        // Before the first callback, and between a loss and the next network.
        meter.onNetwork(Kind.WifiEthernet, tx = 10, rx = 10)
        meter.onNetwork(null, tx = 30, rx = 30)
        meter.read(tx = 35, rx = 40)
        meter.onNetwork(Kind.Cellular, tx = 40, rx = 50)

        val stats = meter.read(tx = 40, rx = 50)
        assertEquals(ByteTotals(upload = 30, download = 30), stats.wifiEthernet)
        assertEquals(ByteTotals(upload = 10, download = 20), stats.cellular)
        assertEquals(ByteTotals(), stats.other)
    }

    @Test
    fun `totals back at zero charge nothing`() {
        // A read racing the engine's stop.
        meter.onNetwork(Kind.Other, tx = 0, rx = 0)
        meter.read(tx = 64, rx = 128)

        assertEquals(ByteTotals(upload = 64, download = 128), meter.read(tx = 0, rx = 0).other)
    }
}
