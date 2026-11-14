package io.github.madeye.meow.ui.screens.providers

import io.github.madeye.meow.api.ProxiesResult
import io.github.madeye.meow.api.Proxy
import io.github.madeye.meow.api.ProxyHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyServersViewModelTest {

    @Test
    fun `inline proxies become a visible profile card while builtins are hidden`() {
        val result = ProxiesResult(
            groups = emptyMap(),
            proxies = mapOf(
                "DIRECT" to Proxy("DIRECT", "Direct", emptyList()),
                "REJECT" to Proxy("REJECT", "Reject", emptyList()),
                "Tokyo 01" to Proxy(
                    name = "Tokyo 01",
                    type = "Vmess",
                    history = listOf(ProxyHistory(timeMillis = 1, delay = 42)),
                ),
            ),
        )

        val cards = proxyServerProviders(result, emptyList())

        assertEquals(1, cards.size)
        assertEquals(ProxyServerKind.Inline, cards.single().kind)
        assertEquals(listOf("Tokyo 01"), cards.single().nodes.map { it.name })
        assertEquals(42, cards.single().nodes.single().delayMs)
    }

    @Test
    fun `provider and inline cards are both retained`() {
        val provider = io.github.madeye.meow.api.ProxyProvider(
            name = "airport",
            vehicleType = "HTTP",
            nodes = emptyList(),
            testUrl = "",
            expectedStatus = "",
            updatedAt = null,
            usage = null,
        )

        val cards = proxyServerProviders(
            inline = ProxiesResult(emptyMap(), mapOf("inline" to Proxy("inline", "Shadowsocks", emptyList()))),
            providers = listOf(provider),
        )

        assertEquals(listOf(ProxyServerKind.Provider, ProxyServerKind.Inline), cards.map { it.kind })
        assertTrue(cards.last().nodes.any { it.name == "inline" })
    }
}
