package io.github.madeye.meow.ui.screens.providers

import io.github.madeye.meow.api.ProxiesResult
import io.github.madeye.meow.api.Proxy
import io.github.madeye.meow.api.ProxyHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyServersViewModelTest {

    @Test
    fun `local proxy fields can be parsed and replaced without touching other entries`() {
        val yaml = """
            proxies:
              - name: Local SS
                type: ss
                server: old.example
                port: 443
                password: old-secret
              - name: Other
                type: direct
            rules:
              - MATCH,Local SS
        """.trimIndent()

        val parsed = parseProxyEntry(yaml, "Local SS")
        assertEquals("ss", parsed?.type)
        assertEquals("old.example", parsed?.fields?.first { it.key == "server" }?.value)

        val updated = replaceProxyEntry(
            yaml,
            "Local SS",
            parsed!!.fields.associate { field ->
                field.key to if (field.key == "server") "new.example" else field.value
            },
        )
        assertTrue(updated!!.contains("server: new.example"))
        assertTrue(updated.contains("- name: Other"))
        assertTrue(updated.contains("MATCH,Local SS"))
    }

    @Test
    fun `vless editor exposes protocol transport and reality fields`() {
        val yaml = """
            proxies:
              - name: Reality node
                type: vless
                server: old.example
                port: 443
                uuid: 00000000-0000-0000-0000-000000000000
                network: tcp
                tls: true
                reality-opts:
                  public-key: old-key
                  short-id: 1234
        """.trimIndent()

        val parsed = parseProxyEntry(yaml, "Reality node")!!

        assertEquals("vless", parsed.type)
        assertEquals("old.example", parsed.fields.first { it.key == "server" }.value)
        assertEquals("true", parsed.fields.first { it.key == "tls" }.value)
        assertEquals("old-key", parsed.fields.first { it.key == "reality-opts.public-key" }.value)
        assertEquals("REALITY public key", parsed.fields.first { it.key == "reality-opts.public-key" }.label)
    }

    @Test
    fun `nested protocol field replacement preserves the rest of the profile`() {
        val yaml = """
            proxies:
              - name: Reality node
                type: vless
                server: old.example
                port: 443
                uuid: 00000000-0000-0000-0000-000000000000
                reality-opts:
                  public-key: old-key
                  short-id: 1234
              - name: Other
                type: direct
        """.trimIndent()

        val updated = replaceProxyEntry(
            yaml,
            "Reality node",
            mapOf("reality-opts.public-key" to "new-key"),
        )!!

        assertTrue(updated.contains("public-key: new-key"))
        assertTrue(updated.contains("short-id: 1234"))
        assertTrue(updated.contains("- name: Other"))
    }

    @Test
    fun `missing descendants are inserted as nested yaml maps`() {
        val yaml = """
            proxies:
              - name: WebSocket
                type: vless
                server: old.example
                port: 443
                uuid: id
                ws-opts:
                  path: /old
        """.trimIndent()

        val updated = replaceProxyEntry(
            yaml,
            "WebSocket",
            mapOf("ws-opts.headers.Host" to "edge.example"),
        )!!

        assertTrue(updated.contains("ws-opts:\n      path: /old\n      headers:\n        Host: edge.example"))
        assertTrue(!updated.contains("headers.Host:"))
    }

    @Test
    fun `flow style proxy names and scalar fields can be edited`() {
        val yaml = """
            proxies:
              - { name: Flow SS, type: ss, server: old.example, port: 443, cipher: aes-256-gcm, password: old }
        """.trimIndent()

        val parsed = parseProxyEntry(yaml, "Flow SS")!!
        val updated = replaceProxyEntry(yaml, "Flow SS", mapOf("server" to "new.example"))!!

        assertEquals("ss", parsed.type)
        assertTrue(updated.contains("server: new.example"))
    }

    @Test
    fun `flow style existing values can be cleared`() {
        val yaml = """
            proxies:
              - { name: Flow SS, type: ss, server: old.example, port: 443, password: old }
        """.trimIndent()

        val updated = replaceProxyEntry(yaml, "Flow SS", mapOf("password" to ""))!!

        assertTrue(updated.contains("password: \"\""))
    }

    @Test
    fun `editor has a dedicated form for every engine proxy type`() {
        val fixtures = listOf(
            "direct" to "connect-timeout: 7\n                dns: [1.1.1.1]",
            "ss" to "cipher: aes-256-gcm\n                password: secret",
            "trojan" to "password: secret\n                sni: edge.example",
            "vless" to "uuid: 00000000-0000-0000-0000-000000000000\n                network: ws",
            "vmess" to "uuid: 00000000-0000-0000-0000-000000000000\n                cipher: auto",
            "hysteria2" to "password: secret\n                ports: 443-445",
            "anytls" to "password: secret",
            "snell" to "psk: secret\n                version: 4",
            "http" to "username: alice\n                password: secret",
            "socks5" to "username: alice\n                password: secret",
            "trusttunnel" to "username: alice\n                password: secret",
        )

        fixtures.forEach { (type, options) ->
            val parsed = parseProxyEntry(
                """
                proxies:
                    - name: $type node
                      type: $type
                      server: edge.example
                      port: 443
                      $options
                """.trimIndent(),
                "$type node",
            )

            assertEquals(type, parsed?.type)
            val entry = parsed ?: error("$type entry did not parse")
            if (type != "direct") {
                assertTrue("$type should expose server", entry.fields.any { it.key == "server" })
            }
            val section = if (type == "direct") "Direct" else "Endpoint"
            assertTrue("$type should expose its endpoint section", entry.fields.any { it.section == section })
        }
    }

    @Test
    fun `untouched optional defaults are not written but edited nested options are added`() {
        val yaml = """
            proxies:
              - name: VLESS
                type: vless
                server: old.example
                port: 443
                uuid: 00000000-0000-0000-0000-000000000000
        """.trimIndent()
        val parsed = parseProxyEntry(yaml, "VLESS")!!
        val fields = parsed.fields.map { field ->
            if (field.key == "reality-opts.public-key") field.copy(value = "new-key", present = true) else field
        }
        val updated = replaceProxyEntry(
            yaml,
            "VLESS",
            fields.associate { it.key to it.value },
            fields.associate { it.key to it.kind },
            fields.associate { it.key to it.present },
        )!!

        assertTrue(updated.contains("reality-opts:"))
        assertTrue(updated.contains("public-key: new-key"))
        assertTrue("untouched ECH defaults must not be serialized", !updated.contains("ech-opts:"))
    }

    @Test
    fun `proxy list indentation and inline nested maps round trip`() {
        val yaml = """
            proxies:
                - { name: Inline, type: vless, server: old.example, port: 443, uuid: id, ws-opts: { path: /old, headers: { Host: old.example } } }
            rules:
              - MATCH,Inline
        """.trimIndent()

        val parsed = parseProxyEntry(yaml, "Inline")!!
        assertEquals("/old", parsed.fields.first { it.key == "ws-opts.path" }.value)
        val updated = replaceProxyEntry(yaml, "Inline", mapOf("ws-opts.path" to "/new"))!!

        assertTrue(updated.contains("ws-opts: { path: /new"))
        assertTrue(updated.contains("Host: old.example"))
        assertTrue(updated.contains("MATCH,Inline"))
    }

    @Test
    fun `legacy mux alias keeps its namespace and plugin options are protocol specific`() {
        val yaml = """
            proxies:
              - name: Legacy SS
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: shadow-tls
                plugin-opts:
                  host: cover.example
                  version: 3
                mux:
                  enabled: true
                  protocol: h2mux
        """.trimIndent()

        val parsed = parseProxyEntry(yaml, "Legacy SS")!!
        assertTrue(parsed.fields.any { it.key == "mux.enabled" && it.section == "Multiplexing" })
        assertTrue(parsed.fields.none { it.key == "smux.enabled" })
        assertTrue(parsed.fields.any { it.key == "plugin-opts.host" && it.section == "Plugin options" })
        assertTrue(parsed.fields.any { it.key == "plugin-opts.password" && it.sensitive })

        val updated = replaceProxyEntry(
            yaml,
            "Legacy SS",
            mapOf("mux.enabled" to "false", "plugin-opts.host" to "new-cover.example"),
        )!!
        assertTrue(updated.contains("enabled: false"))
        assertTrue(updated.contains("host: new-cover.example"))
        assertTrue(!updated.contains("smux:"))
    }

    @Test
    fun `scalar plugin options stay scalar when edited`() {
        val yaml = """
            proxies:
              - name: Scalar SS
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: shadow-tls
                plugin-opts: host=cover.example;version=3
        """.trimIndent()

        val parsed = parseProxyEntry(yaml, "Scalar SS")!!
        assertTrue(parsed.fields.any { it.key == "plugin-opts" })
        assertTrue(parsed.fields.none { it.key == "plugin-opts.host" })
        val updated = replaceProxyEntry(yaml, "Scalar SS", mapOf("plugin-opts" to "host=new.example;version=3"))!!
        assertTrue(updated.contains("plugin-opts: 'host=new.example;version=3'"))
        assertTrue(!updated.contains("plugin-opts:\n"))
    }

    @Test
    fun `hysteria2 multiplex port and unknown credentials get mature controls`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: Hysteria
                type: hysteria2
                server: edge.example
                port: 443
                mport: 35000-39000
                password: secret
                new-secret-token: hidden
            """.trimIndent(),
            "Hysteria",
        )!!

        assertEquals("35000-39000", parsed.fields.first { it.key == "mport" }.value)
        assertEquals("Endpoint", parsed.fields.first { it.key == "mport" }.section)
        assertTrue(parsed.fields.first { it.key == "new-secret-token" }.sensitive)
    }

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

    @Test
    fun `server search matches names and protocol while retaining provider grouping`() {
        val cards = listOf(
            ProxyServerProviderUi(
                id = "airport",
                name = "airport",
                vehicleType = "HTTP",
                testUrl = "",
                expectedStatus = "",
                nodes = listOf(
                    ProxyServerNodeUi("Tokyo", "Vless", null, false),
                    ProxyServerNodeUi("Singapore", "Hysteria2", null, false),
                ),
            ),
            ProxyServerProviderUi(
                id = "local",
                name = "local",
                vehicleType = "",
                testUrl = "",
                expectedStatus = "",
                nodes = listOf(ProxyServerNodeUi("Direct", "Direct", null, false)),
            ),
        )

        val filtered = filterProxyServerProviders(cards, "hysteria")

        assertEquals(listOf("airport"), filtered.map { it.id })
        assertEquals(listOf("Singapore"), filtered.single().nodes.map { it.name })
    }

    @Test
    fun `http2 host list is rendered as an array when edited`() {
        val yaml = """
            proxies:
              - name: H2
                type: vless
                server: edge.example
                port: 443
                uuid: id
                network: h2
                h2-opts:
                  host: [old.example]
                  path: /
        """.trimIndent()

        val parsed = parseProxyEntry(yaml, "H2")!!
        val host = parsed.fields.first { it.key == "h2-opts.host" }
        assertEquals(ProxyEditFieldKind.List, host.kind)
        val updated = replaceProxyEntry(
            yaml,
            "H2",
            mapOf("h2-opts.host" to "one.example, two.example"),
            mapOf("h2-opts.host" to host.kind),
        )!!

        assertTrue(updated.contains("host: [one.example, two.example]"))
    }
}
