package io.github.madeye.meow.ui.screens.proxies

import io.github.madeye.meow.ui.screens.providers.proxyDialersFromYaml
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyGroupsDialerTest {

    @Test
    fun `reads dialer proxy values from block and flow nodes`() {
        val yaml = """
            proxies:
              - name: direct-node
                type: trojan
                server: direct.example
                port: 443
              - { name: chained-node, type: snell, server: chain.example, port: 443, dialer-proxy: Relay }
              - name: empty-node
                type: http
                server: empty.example
                port: 80
                dialer-proxy: ""
            proxy-groups:
              - name: Relay
                type: select
                proxies: [direct-node]
        """.trimIndent()

        assertEquals(
            mapOf("chained-node" to "Relay"),
            proxyDialersFromYaml(yaml),
        )
    }

    @Test
    fun `keeps quoted and whitespace padded dialer names`() {
        val yaml = """
            proxies:
              - name: node
                type: socks5
                server: node.example
                port: 1080
                dialer-proxy: ' Relay '
        """.trimIndent()

        assertEquals(mapOf("node" to "Relay"), proxyDialersFromYaml(yaml))
    }
}
