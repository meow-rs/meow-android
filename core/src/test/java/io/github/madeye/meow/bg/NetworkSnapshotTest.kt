package io.github.madeye.meow.bg

import io.github.madeye.meow.bg.NetworkSnapshot.Active
import io.github.madeye.meow.bg.NetworkSnapshot.Link
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSnapshotTest {

    private val wifi = Link("101", "Wi-Fi")
    private val cellular = Link("100", "cellular")

    private fun snapshot(
        up: List<Link> = listOf(cellular, wifi),
        declared: Link? = wifi,
        declaredUp: Boolean = true,
        onVpn: Boolean = true,
        active: Active? = Active("WIFI", connected = true, state = "CONNECTED"),
        vpn: String? = "Wi-Fi, validated, not metered",
    ) = NetworkSnapshot(up, declared, declaredUp, onVpn, active, vpn)

    @Test
    fun `a declared network that is up is info`() {
        val snapshot = snapshot()

        assertEquals(
            "Cellular 100 and Wi-Fi 101 are up; the VPN declares Wi-Fi 101; apps on the VPN see Wi-Fi.",
            snapshot.message,
        )
        assertFalse(snapshot.warning)
    }

    @Test
    fun `a declared network that is gone is a warning`() {
        // The switch to another Wi-Fi: Android answers the apps with the
        // network the VPN declared, which no longer exists.
        val snapshot = snapshot(
            up = listOf(cellular, Link("105", "Wi-Fi", validated = false)),
            declaredUp = false,
            active = null,
            vpn = "no underlying transport, not validated, metered",
        )

        assertEquals(
            "Cellular 100 and Wi-Fi 105 (not validated) are up; the VPN declares Wi-Fi 101, " +
                "which is gone; apps on the VPN see no network.",
            snapshot.message,
        )
        assertTrue(snapshot.warning)
        assertEquals(
            buildJsonObject {
                put("active_network_info", JsonPrimitive("none"))
                put("vpn", JsonPrimitive("no underlying transport, not validated, metered"))
                put("device_idle", JsonPrimitive(true))
            },
            snapshot.fields(deviceIdle = true),
        )
    }

    @Test
    fun `no network seen with one up is a warning even with nothing declared`() {
        val snapshot = snapshot(up = listOf(wifi), declared = null, declaredUp = false, active = null)

        assertEquals(
            "Wi-Fi 101 is up; the VPN declares no network; apps on the VPN see no network.",
            snapshot.message,
        )
        assertTrue(snapshot.warning)
    }

    @Test
    fun `no network at all is info`() {
        val snapshot = snapshot(up = emptyList(), declared = null, declaredUp = false, active = null, vpn = null)

        assertEquals(
            "No network is up; the VPN declares no network; apps on the VPN see no network.",
            snapshot.message,
        )
        assertFalse(snapshot.warning)
        assertEquals(
            buildJsonObject {
                put("active_network_info", JsonPrimitive("none"))
                put("device_idle", JsonPrimitive(false))
            },
            snapshot.fields(deviceIdle = false),
        )
    }

    @Test
    fun `a network that is not connected names its state`() {
        val snapshot = snapshot(active = Active("MOBILE", connected = false, state = "BLOCKED"))

        assertEquals(
            "Cellular 100 and Wi-Fi 101 are up; the VPN declares Wi-Fi 101; apps on the VPN see cellular, blocked.",
            snapshot.message,
        )
        assertFalse(snapshot.warning)
    }

    @Test
    fun `an app left off its own VPN cannot tell what the apps on it see`() {
        val snapshot = snapshot(up = listOf(cellular, wifi, Link("102", "Ethernet")), onVpn = false, active = null)

        assertEquals(
            "Cellular 100, Wi-Fi 101 and Ethernet 102 are up; the VPN declares Wi-Fi 101; " +
                "this app is outside the VPN, so what the apps on it see is not known.",
            snapshot.message,
        )
        assertFalse(snapshot.warning)
    }
}
