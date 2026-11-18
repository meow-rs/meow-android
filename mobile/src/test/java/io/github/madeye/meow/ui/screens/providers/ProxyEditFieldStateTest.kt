package io.github.madeye.meow.ui.screens.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyEditFieldStateTest {
    private val profiles = listOf(
        """
            proxies:
              - { name: Local, type: snell, server: test.example, port: 443, psk: test-secret, udp: true, obfs-opts: { mode: http, host: edge.example }, future-option: old-value, dialer-proxy: DIRECT }
        """.trimIndent(),
        """
            proxies:
              - name: Local
                type: snell
                server: test.example
                port: 443
                psk: test-secret
                udp: true
                obfs-opts:
                  mode: http
                  host: edge.example
                future-option: old-value
                dialer-proxy: DIRECT
        """.trimIndent(),
    )

    @Test
    fun `clearing optional text removes saved keys and keeps the dialer`() {
        profiles.forEach { yaml ->
            var state = initialState(yaml)
            state = updateProxyEditField(state, "obfs-opts.host", "")
            state = updateProxyEditField(state, "future-option", "")
            assertTrue(state.dirty)
            // The Advanced control remains editable after erasing its last character.
            assertEquals("", state.fields.single { it.key == "future-option" }.value)
            val saved = save(yaml, state)
            assertFalse(saved.contains("host:"))
            assertFalse(saved.contains("future-option:"))
            val reopened = parseProxyEntry(saved, "Local")!!.fields
            assertEquals("DIRECT", reopened.last().value)
            assertEquals("http", reopened.single { it.key == "obfs-opts.mode" }.value)
        }
    }

    @Test
    fun `typing into a cleared Advanced field saves its replacement`() {
        profiles.forEach { yaml ->
            var state = updateProxyEditField(initialState(yaml), "future-option", "")
            state = updateProxyEditField(state, "future-option", "replacement")
            val reopened = parseProxyEntry(save(yaml, state), "Local")!!.fields
            assertEquals("replacement", reopened.single { it.key == "future-option" }.value)
        }
    }

    @Test
    fun `turning a switch off persists false without a delete action`() {
        profiles.forEach { yaml ->
            val state = updateProxyEditField(initialState(yaml), "udp", "false")
            val reopened = parseProxyEntry(save(yaml, state), "Local")!!.fields
            val udp = reopened.single { it.key == "udp" }
            assertTrue(udp.present)
            assertEquals("false", udp.value)
            assertEquals("DIRECT", reopened.last().value)
        }
    }

    @Test
    fun `clearing required text keeps the field for validation`() {
        profiles.forEach { yaml ->
            val state = updateProxyEditField(initialState(yaml), "psk", "")
            val field = state.fields.single { it.key == "psk" }
            assertTrue(field.required)
            assertTrue(field.present)
            assertFalse(state.removedKeys.contains("psk"))
            assertEquals("", field.value)
        }
    }

    @Test
    fun `toggling UDP preserves the original PSK and untouched YAML`() {
        profiles.forEach { fixture ->
            val yaml = fixture.replace("test-secret", "'mock''key'")
            val state = updateProxyEditField(initialState(yaml), "udp", "false")
            assertEquals("mock'key", state.fields.single { it.key == "psk" }.value)

            val saved = save(yaml, state)
            assertTrue(saved.contains("psk: 'mock''key'"))
            val reopened = parseProxyEntry(saved, "Local")!!.fields
            assertEquals("mock'key", reopened.single { it.key == "psk" }.value)
            assertEquals("false", reopened.single { it.key == "udp" }.value)
        }
    }

    @Test
    fun `saving a block list neighbor leaves the list and its comment untouched`() {
        val yaml = """
            proxies:
              - name: Local
                type: trojan
                server: edge.example
                port: 443 # keep this comment
                password: 'mock''key'
                alpn:
                  - h2
                  - http/1.1
                udp: true
        """.trimIndent()
        val state = updateProxyEditField(initialState(yaml), "udp", "false")

        assertEquals(yaml.replace("udp: true", "udp: false"), save(yaml, state))
    }

    @Test
    fun `indentless block nodes support keys before name and before the next proxy`() {
        val yaml = """
            proxies:
            - type: snell
              udp: true
              name: Local
              server: edge.example
              port: 443
              psk: example
              dialer-proxy: Relay
            - server: relay.example
              name: Relay
              type: socks5
              port: 1080
            proxy-groups:
            - type: select
              name: Select relay
              proxies: [Relay, DIRECT]
            rules: [MATCH,DIRECT]
        """.trimIndent()
        val state = initialState(yaml)
        assertEquals("snell", state.type)
        val dialer = state.fields.single { it.key == "dialer-proxy" }
        assertEquals("Relay", dialer.value)
        assertTrue(dialer.options.containsAll(listOf("Relay", "Select relay")))

        val saved = save(yaml, updateProxyEditField(state, "udp", "false"))
        assertEquals(yaml.replace("udp: true", "udp: false"), saved)

        val updatedRelay = replaceProxyEntry(saved, "Relay", mapOf("server" to "new.example"))
        assertEquals(saved.replace("- server: relay.example", "- server: new.example"), updatedRelay)
    }

    private fun initialState(yaml: String): ProxyEditUiState {
        val parsed = parseProxyEntry(yaml, "Local")!!
        return ProxyEditUiState(
            name = "Local",
            type = parsed.type,
            fields = parsed.fields,
            initialFields = parsed.fields,
            loading = false,
        )
    }

    private fun save(yaml: String, state: ProxyEditUiState): String = applyProxyEdit(yaml, state)!!
}
