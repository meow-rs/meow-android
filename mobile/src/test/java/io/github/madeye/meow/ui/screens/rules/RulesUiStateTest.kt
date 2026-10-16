package io.github.madeye.meow.ui.screens.rules

import io.github.madeye.meow.api.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesUiStateTest {

    @Test
    fun `filtered rules keep their place in the engine's list`() {
        val state = RulesUiState(
            rules = listOf(
                Rule(type = "DOMAIN", payload = "a.example", proxy = "Proxy"),
                Rule(type = "GEOIP", payload = "CN", proxy = "DIRECT"),
                Rule(type = "MATCH", payload = "", proxy = "Proxy"),
            ),
            query = "direct",
        )
        assertEquals(listOf(1), state.visible.map { it.index })
        assertEquals(listOf(0, 1, 2), state.copy(query = "").visible.map { it.index })
    }
}
