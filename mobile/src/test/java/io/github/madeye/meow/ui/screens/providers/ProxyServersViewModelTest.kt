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
    fun `multiple nested additions keep each map and the following proxy intact`() {
        val yaml = """
            proxies:
              - name: Nested
                type: vless
                server: old.example
                port: 443
                uuid: id
                ws-opts:
                  path: /old
                reality-opts:
                  short-id: old
              - name: Other
                type: direct
        """.trimIndent()

        val updated = replaceProxyEntry(
            yaml,
            "Nested",
            mapOf(
                "ws-opts.headers.Host" to "edge.example",
                "reality-opts.public-key" to "new-key",
            ),
        )!!

        assertTrue(updated.contains("ws-opts:\n      path: /old\n      headers:\n        Host: edge.example"))
        assertTrue(updated.contains("reality-opts:\n      short-id: old\n      public-key: new-key"))
        assertTrue(updated.contains("- name: Other"))
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
    fun `hysteria2 port hopping is conditional and unknown credentials stay visible`() {
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
        assertEquals("Advanced", parsed.fields.first { it.key == "mport" }.section)
        assertTrue(parsed.fields.first { it.key == "port" }.required)
        assertTrue(parsed.fields.first { it.key == "new-secret-token" }.sensitive)
    }

    @Test
    fun `protocol capability changes keep invalidated values in advanced`() {
        val snell = parseProxyEntry(
            """
            proxies:
              - name: Snell v6
                type: snell
                server: edge.example
                port: 443
                psk: secret
                version: 6
                mode: unshaped
                obfs-opts:
                  mode: http
                  host: cover.example
            """.trimIndent(),
            "Snell v6",
        )!!
        assertTrue(snell.fields.any { it.key == "mode" && it.section == "Transport" })
        assertTrue(snell.fields.any { it.key == "obfs-opts.mode" && it.section == "Advanced" })

        val hy2 = parseProxyEntry(
            """
            proxies:
              - name: Gecko
                type: hysteria2
                server: edge.example
                ports: 443-445
                password: secret
                obfs: gecko
                obfs-password: obfs-secret
                obfs-min-packet-size: 64
                obfs-max-packet-size: 512
            """.trimIndent(),
            "Gecko",
        )!!
        assertTrue(hy2.fields.any { it.key == "port" && !it.required })
        assertTrue(hy2.fields.any { it.key == "obfs-min-packet-size" && it.section == "QUIC" })
        val salamander = adaptProxyFields(
            hy2.type,
            hy2.fields.map { field ->
                if (field.key == "obfs") field.copy(value = "salamander", present = true) else field
            },
        )
        assertTrue(salamander.none { it.key == "obfs-min-packet-size" && it.section == "QUIC" })
        assertTrue(salamander.any { it.key == "obfs-min-packet-size" && it.section == "Advanced" && !it.required })
    }

    @Test
    fun `plugin controls match the in-process transport capabilities`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: plugin
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: v2ray-plugin
            """.trimIndent(),
            "plugin",
        )!!
        val v2ray = parsed.fields.filter { it.key.startsWith("plugin-opts.") }
        assertTrue(v2ray.any { it.key == "plugin-opts.skip-cert-verify" })
        assertTrue(v2ray.any { it.key == "plugin-opts.headers.Host" })
        assertTrue(v2ray.none { it.key == "plugin-opts.mode" && it.options.contains("quic") })

        val gost = adaptProxyFields(
            parsed.type,
            parsed.fields.map { field ->
                if (field.key == "plugin") field.copy(value = "gost-plugin", present = true) else field
            },
        )
        assertTrue(gost.any { it.key == "plugin-opts.mode" && it.required })
        assertTrue(gost.any { it.key == "plugin-opts.headers.Host" })
        assertTrue(gost.none { it.key == "plugin-opts.mode" && it.options.contains("grpc") })
    }

    @Test
    fun `hysteria2 gecko exposes packet sizing controls and restls exposes required host`() {
        val gecko = parseProxyEntry(
            """
            proxies:
              - name: Gecko
                type: hysteria2
                server: edge.example
                port: 443
                password: secret
                obfs: gecko
                obfs-password: obfs-secret
            """.trimIndent(),
            "Gecko",
        )!!

        assertTrue(gecko.fields.any { it.key == "obfs" && it.options.contains("gecko") })
        assertTrue(gecko.fields.any { it.key == "obfs-min-packet-size" && it.section == "QUIC" })
        assertTrue(gecko.fields.any { it.key == "obfs-max-packet-size" && it.section == "QUIC" })
        assertEquals("512", gecko.fields.first { it.key == "obfs-min-packet-size" }.value)
        assertEquals("1200", gecko.fields.first { it.key == "obfs-max-packet-size" }.value)

        val restls = parseProxyEntry(
            """
            proxies:
              - name: Restls
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: restls
                plugin-opts:
                  host: cover.example
                  password: plugin-secret
                  version-hint: tls13
            """.trimIndent(),
            "Restls",
        )!!
        assertTrue(restls.fields.any { it.key == "plugin-opts.host" && it.required })
    }

    @Test
    fun `plugin aliases keep existing ech tls tunnel spelling`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: ECH
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: ech-tls-tunnel
                plugin-opts:
                  mode: client
                  sni: tunnel.example
                  path: /ws
                  ech_config: QUJD
                  fast_open: true
            """.trimIndent(),
            "ECH",
        )!!

        assertTrue(parsed.fields.any { it.key == "plugin-opts.ech_config" && it.present })
        assertTrue(parsed.fields.any { it.key == "plugin-opts.fast_open" && it.present })
        assertTrue(parsed.fields.none { it.key == "plugin-opts.ech-config" })
        assertTrue(parsed.fields.none { it.key == "plugin-opts.fast-open" })

        val updated = replaceProxyEntry(
            """
            proxies:
              - name: ECH
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: ech-tls-tunnel
                plugin-opts:
                  mode: client
                  sni: tunnel.example
                  path: /ws
                  ech_config: QUJD
                  fast_open: true
            """.trimIndent(),
            "ECH",
            mapOf("plugin-opts.ech_config" to "REVG"),
        )!!

        assertTrue(updated.contains("ech_config: REVG"))
        assertTrue(!updated.contains("ech-config:"))
        assertTrue(updated.contains("fast_open: true"))
    }

    @Test
    fun `gost plugin keeps its backend default mux enabled`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: Gost
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: gost-plugin
                plugin-opts:
                  mode: websocket
            """.trimIndent(),
            "Gost",
        )!!

        val mux = parsed.fields.first { it.key == "plugin-opts.mux" }
        assertEquals("true", mux.value)
        assertTrue(!mux.present)
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

    @Test
    fun `transport fields follow the selected network instead of showing every protocol`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: WebSocket node
                type: vless
                server: edge.example
                port: 443
                uuid: id
                network: ws
                ws-opts:
                  path: /ray
            """.trimIndent(),
            "WebSocket node",
        )!!

        assertTrue(parsed.fields.any { it.key == "ws-opts.path" })
        assertTrue(parsed.fields.none { it.key == "grpc-opts.grpc-service-name" })
        assertTrue(parsed.fields.none { it.key == "h2-opts.host" })

        val switched = adaptProxyFields(
            parsed.type,
            parsed.fields.map { field ->
                if (field.key == "network") field.copy(value = "grpc", present = true) else field
            },
        )
        assertTrue(switched.any { it.key == "grpc-opts.grpc-service-name" })
        // Existing transport values are retained in Advanced so changing the
        // selector never silently destroys a working node configuration.
        assertTrue(switched.any { it.key == "ws-opts.path" && it.section == "Advanced" })
        assertTrue(switched.none { it.key == "h2-opts.host" })
    }

    @Test
    fun `changing an empty plugin adds that plugins controls`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: Plugin node
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
            """.trimIndent(),
            "Plugin node",
        )!!

        assertTrue(parsed.fields.none { it.key == "plugin-opts.mode" })
        val selected = adaptProxyFields(
            parsed.type,
            parsed.fields.map { field ->
                if (field.key == "plugin") field.copy(value = "v2ray-plugin", present = true) else field
            },
        )
        assertTrue(selected.any { it.key == "plugin-opts.mode" })
        assertTrue(selected.any { it.key == "plugin-opts.path" })
    }

    @Test
    fun `optional fields can be removed from block and flow entries`() {
        val block = """
            proxies:
              - name: Block
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                udp: true
        """.trimIndent()
        val blockUpdated = replaceProxyEntry(
            block,
            "Block",
            mapOf("udp" to ""),
            mapOf("udp" to ProxyEditFieldKind.Boolean),
            mapOf("udp" to false),
            setOf("udp"),
        )!!
        assertTrue(!blockUpdated.contains("udp: true"))

        val flow = """
            proxies:
              - { name: Flow, type: ss, server: edge.example, port: 443, udp: true }
        """.trimIndent()
        val flowUpdated = replaceProxyEntry(
            flow,
            "Flow",
            mapOf("udp" to ""),
            mapOf("udp" to ProxyEditFieldKind.Boolean),
            mapOf("udp" to false),
            setOf("udp"),
        )!!
        assertTrue(!flowUpdated.contains("udp: true"))
    }

    @Test
    fun `unknown advanced fields removed from the form are removed from yaml`() {
        val yaml = """
            proxies:
              - name: Future
                type: vless
                server: edge.example
                port: 443
                uuid: id
                future-option: keep-me
                future-map:
                  key: keep-too
              - name: Other
                type: direct
        """.trimIndent()

        val parsed = parseProxyEntry(yaml, "Future")!!
        val advanced = parsed.fields.filter { it.section == "Advanced" }
        assertTrue(advanced.any { it.key == "future-option" })
        assertTrue(advanced.any { it.key == "future-map.key" })

        val updated = replaceProxyEntry(
            yaml,
            "Future",
            parsed.fields.filter { it.key != "future-option" && it.key != "future-map.key" }
                .associate { it.key to it.value },
            removed = setOf("future-option", "future-map.key"),
        )!!

        assertTrue(!updated.contains("future-option"))
        assertTrue(!updated.contains("key: keep-too"))
        assertTrue(updated.contains("- name: Other"))
    }

    @Test
    fun `plugin schema keeps restls unique and exposes supported ss plugins`() {
        val restls = parseProxyEntry(
            """
            proxies:
              - name: RESTLS
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: restls
                plugin-opts:
                  host: cover.example
                  password: plugin-secret
            """.trimIndent(),
            "RESTLS",
        )!!
        assertEquals(1, restls.fields.count { it.key == "plugin-opts.host" })

        val plugins = restls.fields.first { it.key == "plugin" }.options
        assertTrue(plugins.contains("ech-tls-tunnel"))

        val gost = adaptProxyFields(
            restls.type,
            restls.fields.map { field ->
                if (field.key == "plugin") field.copy(value = "gost-plugin", present = true) else field
            },
        )
        assertTrue(gost.any { it.key == "plugin-opts.headers.Host" })
    }

    @Test
    fun `capability toggles collapse child controls without dropping existing values`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: Tuned VLESS
                type: vless
                server: edge.example
                port: 443
                uuid: id
                tls: true
                skip-cert-verify: true
                smux:
                  enabled: true
                  protocol: yamux
            """.trimIndent(),
            "Tuned VLESS",
        )!!

        val collapsed = adaptProxyFields(
            parsed.type,
            parsed.fields.map { field ->
                when (field.key) {
                    "tls", "smux.enabled" -> field.copy(value = "false", present = true)
                    else -> field
                }
            },
        )

        assertTrue(collapsed.none { it.key == "skip-cert-verify" && it.section == "TLS" })
        assertTrue(collapsed.any { it.key == "skip-cert-verify" && it.section == "Advanced" })
        assertTrue(collapsed.none { it.key == "smux.protocol" && it.section == "Multiplexing" })
        assertTrue(collapsed.any { it.key == "smux.protocol" && it.section == "Advanced" })
    }

    @Test
    fun `plugin controls expose only the backend supported mode`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: V2Ray plugin
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: v2ray-plugin
                plugin-opts:
                  mode: websocket
                  path: /ray
            """.trimIndent(),
            "V2Ray plugin",
        )!!

        val mode = parsed.fields.first { it.key == "plugin-opts.mode" }
        assertTrue(mode.options.contains("websocket"))
        assertTrue(!mode.options.contains("quic"))
        assertTrue(!mode.options.contains("grpc"))
        assertTrue(parsed.fields.any { it.key == "plugin-opts.path" && it.section == "Plugin options" })
    }

    @Test
    fun `kcptun schema retains every backend option`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: KCP
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: kcptun
            """.trimIndent(),
            "KCP",
        )!!
        val keys = parsed.fields.map { it.key }.toSet()
        assertTrue(keys.containsAll(setOf(
            "plugin-opts.scavengettl",
            "plugin-opts.ratelimit",
            "plugin-opts.acknodelay",
            "plugin-opts.nodelay",
            "plugin-opts.interval",
            "plugin-opts.resend",
            "plugin-opts.nc",
            "plugin-opts.sockbuf",
            "plugin-opts.smuxbuf",
            "plugin-opts.framesize",
            "plugin-opts.streambuf",
        )))
        val mode = parsed.fields.first { it.key == "plugin-opts.mode" }
        assertTrue(mode.options.contains("manual"))
    }

    @Test
    fun `socks5 does not render duplicate udp controls`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: SOCKS
                type: socks5
                server: edge.example
                port: 1080
            """.trimIndent(),
            "SOCKS",
        )!!

        assertEquals(1, parsed.fields.count { it.key == "udp" })
    }

    @Test
    fun `enabling mux then editing a missing child keeps one mux map`() {
        val yaml = """
            proxies:
              - name: Mux node
                type: vless
                server: edge.example
                port: 443
                uuid: id
                smux:
                  enabled: false
              - name: Other
                type: direct
        """.trimIndent()
        val parsed = parseProxyEntry(yaml, "Mux node")!!
        val enabled = adaptProxyFields(
            parsed.type,
            parsed.fields.map { field ->
                if (field.key == "smux.enabled") field.copy(value = "true", present = true) else field
            },
        )
        val edited = enabled.map { field ->
            if (field.key == "smux.protocol") field.copy(value = "yamux", present = true) else field
        }

        val updated = replaceProxyEntry(
            yaml,
            "Mux node",
            edited.associate { it.key to it.value },
            edited.associate { it.key to it.kind },
            edited.associate { it.key to it.present },
        )!!

        assertEquals(1, updated.lines().count { it.trim() == "smux:" })
        assertTrue(updated.contains("smux:\n      enabled: true\n      protocol: yamux"))
        assertTrue(updated.contains("- name: Other"))
    }

    @Test
    fun `removing the final child removes its empty parent map`() {
        val yaml = """
            proxies:
              - name: Future
                type: vless
                server: edge.example
                port: 443
                uuid: id
                future-map:
                  key: keep-too
              - name: Other
                type: direct
        """.trimIndent()
        val parsed = parseProxyEntry(yaml, "Future")!!
        val kept = parsed.fields.filterNot { it.key == "future-map.key" }
        val updated = replaceProxyEntry(
            yaml,
            "Future",
            kept.associate { it.key to it.value },
            removed = setOf("future-map.key"),
        )!!

        assertTrue(!updated.contains("future-map:"))
        assertTrue(!updated.contains("key: keep-too"))
        assertTrue(updated.contains("- name: Other"))
    }

    @Test
    fun `inline map removal and sibling update are applied atomically`() {
        val yaml = """
            proxies:
              - name: Inline plugin
                type: ss
                server: edge.example
                port: 443
                cipher: aes-256-gcm
                password: secret
                plugin: v2ray-plugin
                plugin-opts: { host: old.example, path: /ray }
        """.trimIndent()

        val updated = replaceProxyEntry(
            yaml,
            "Inline plugin",
            mapOf("plugin-opts.host" to "new.example", "plugin-opts.path" to ""),
            presence = mapOf("plugin-opts.host" to true, "plugin-opts.path" to false),
            removed = setOf("plugin-opts.path"),
        )!!

        assertTrue(updated.contains("plugin-opts: { host: new.example }"))
        assertTrue(!updated.contains("path: /ray"))
    }

    @Test
    fun `trusttunnel credentials are marked required`() {
        val parsed = parseProxyEntry(
            """
            proxies:
              - name: Trust
                type: trusttunnel
                server: vpn.example
                port: 443
                username: user
                password: secret
            """.trimIndent(),
            "Trust",
        )!!

        assertTrue(parsed.fields.first { it.key == "username" }.required)
        assertTrue(parsed.fields.first { it.key == "password" }.required)
    }

    @Test
    fun `supported open ended headers have their own editable section`() {
        val http = parseProxyEntry(
            """
            proxies:
              - name: HTTP
                type: http
                server: proxy.example
                port: 8080
                headers:
                  X-Trace: enabled
            """.trimIndent(),
            "HTTP",
        )!!
        assertEquals("Headers", http.fields.first { it.key == "headers.X-Trace" }.section)

        val ws = parseProxyEntry(
            """
            proxies:
              - name: WS
                type: vless
                server: edge.example
                port: 443
                uuid: id
                network: ws
                ws-opts:
                  headers:
                    X-Trace: enabled
            """.trimIndent(),
            "WS",
        )!!
        assertEquals("WebSocket headers", ws.fields.first { it.key == "ws-opts.headers.X-Trace" }.section)
    }
}
