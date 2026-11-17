package io.github.madeye.meow.ui.screens.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyEditDialerTest {
    private val flowYaml = """
        proxies:
          - { name: Snell node, type: snell, server: snell.example, port: 443, psk: test-secret, version: 3, obfs-opts: { mode: http, host: example.org }, dialer-proxy: Relay A }
          - { name: Relay A, type: direct }
          - name: Relay B
            type: direct
        proxy-groups:
          - name: Relay Group
            type: select
            proxies: [Relay A, Relay B]
    """.trimIndent()

    @Test
    fun `existing flow dialer initializes a final routing dropdown`() {
        val fields = parseProxyEntry(flowYaml, "Snell node")!!.fields
        val dialer = fields.last()
        assertEquals("dialer-proxy", dialer.key)
        assertEquals("Routing", dialer.section)
        assertEquals(ProxyEditFieldKind.Select, dialer.kind)
        assertEquals("Relay A", dialer.value)
        assertTrue(dialer.present)
        assertEquals(listOf("", "DIRECT", "Relay A", "Relay B", "Relay Group"), dialer.options)
        assertFalse(dialer.options.contains("Snell node"))
    }

    @Test
    fun `switching dialer to a group saves and reopens with the selection`() {
        val fields = parseProxyEntry(flowYaml, "Snell node")!!.fields
        val changed = adaptProxyFields("snell", fields.map {
            if (it.key == "dialer-proxy") it.copy(value = "Relay Group", present = true) else it
        })
        val reopened = parseProxyEntry(save(flowYaml, changed), "Snell node")!!.fields
        assertEquals("Relay Group", reopened.last().value)
        assertEquals(fields.last().options, reopened.last().options)
        assertEquals("example.org", reopened.single { it.key == "obfs-opts.host" }.value)
    }

    @Test
    fun `editing snell version retains existing dialer through schema rebuild and save`() {
        val fields = parseProxyEntry(flowYaml, "Snell node")!!.fields
        val changed = adaptProxyFields("snell", fields.map {
            if (it.key == "version") it.copy(value = "6", present = true) else it
        })
        assertEquals(fields.last(), changed.last())
        val reopened = parseProxyEntry(save(flowYaml, changed), "Snell node")!!.fields
        assertEquals("6", reopened.single { it.key == "version" }.value)
        assertEquals("Relay A", reopened.last().value)
    }

    @Test
    fun `a configured dialer missing from local targets remains visible and unchanged`() {
        val yaml = flowYaml.replace("dialer-proxy: Relay A", "dialer-proxy: Provider node")
        val fields = parseProxyEntry(yaml, "Snell node")!!.fields
        assertEquals("Provider node", fields.last().value)
        assertTrue(fields.last().options.contains("Provider node"))
        val reopened = parseProxyEntry(save(yaml, fields), "Snell node")!!.fields
        assertEquals("Provider node", reopened.last().value)
    }

    @Test
    fun `none clears an existing dialer in both block and flow profiles`() {
        val blockYaml = """
            proxies:
              - name: Snell node
                type: snell
                server: snell.example
                port: 443
                psk: test-secret
                version: 3
                dialer-proxy: 'Relay A'
                future-option: preserved
              - name: Relay A
                type: direct
        """.trimIndent()
        listOf(flowYaml, blockYaml).forEach { yaml ->
            val fields = parseProxyEntry(yaml, "Snell node")!!.fields
            assertEquals("Relay A", fields.last().value)
            val changed = adaptProxyFields("snell", fields.map {
                if (it.key == "dialer-proxy") it.copy(value = "", present = false) else it
            })
            val updated = save(yaml, changed)
            assertFalse(updated.contains("dialer-proxy:"))
            val reopened = parseProxyEntry(updated, "Snell node")!!.fields.last()
            assertEquals("", reopened.value)
            assertFalse(reopened.present)
            assertEquals("Routing", reopened.section)
        }
    }

    @Test
    fun `target discovery handles groups with name after type and ignores nested members`() {
        val yaml = """
            proxies:
            - { name: 'Relay #1', type: direct }
            proxy-groups:
            - type: select
              name: 'Group #1'
              proxies:
                - Relay #1
            - { name: 'Group 2', type: select, proxies: ['Relay #1'] }
            rules:
              - MATCH,Group 2
        """.trimIndent()
        assertEquals(listOf("Relay #1", "Group #1", "Group 2"), proxyTargetNamesFromYaml(yaml))
    }

    private fun save(yaml: String, fields: List<ProxyEditField>): String = replaceProxyEntry(
        yaml = yaml,
        name = "Snell node",
        fields = fields.associate { it.key to it.value },
        kinds = fields.associate { it.key to it.kind },
        presence = fields.associate { it.key to it.present },
        removed = fields.filter { !it.present }.mapTo(mutableSetOf()) { it.key },
    )!!
}
