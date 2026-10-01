package io.github.madeye.meow.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteModeTest {

    @Test
    fun `fromWire accepts the engine spellings case-insensitively`() {
        assertEquals(RouteMode.Rule, RouteMode.fromWire("rule"))
        assertEquals(RouteMode.Global, RouteMode.fromWire("Global"))
        assertEquals(RouteMode.Direct, RouteMode.fromWire("DIRECT\n"))
        assertNull(RouteMode.fromWire("script"))
        assertNull(RouteMode.fromWire(""))
    }

    @Test
    fun `configured reads the top-level mode`() {
        val yaml = """
            mixed-port: 7890
            mode: global
            proxies: []
        """.trimIndent()

        assertEquals(RouteMode.Global, RouteMode.configured(yaml))
    }

    @Test
    fun `configured strips quotes, comments and a leading BOM`() {
        assertEquals(RouteMode.Direct, RouteMode.configured("\uFEFFmode: direct\n"))
        assertEquals(RouteMode.Global, RouteMode.configured("mode: \"global\" # set by provider\n"))
        assertEquals(RouteMode.Direct, RouteMode.configured("mode: 'Direct'\n"))
    }

    @Test
    fun `configured ignores indented plugin mode keys`() {
        val yaml = """
            proxies:
              - name: ss
                plugin-opts:
                  mode: websocket
        """.trimIndent()

        assertEquals(RouteMode.Rule, RouteMode.configured(yaml))
    }

    @Test
    fun `configured falls back to rule like the engine`() {
        assertEquals(RouteMode.Rule, RouteMode.configured(""))
        assertEquals(RouteMode.Rule, RouteMode.configured("mode: script\n"))
    }
}
