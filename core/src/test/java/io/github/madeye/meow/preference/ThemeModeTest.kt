package io.github.madeye.meow.preference

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `toWire round-trips through fromWire for every mode`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromWire(mode.toWire()))
        }
    }

    @Test
    fun `fromWire accepts the stored spellings case-insensitively`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromWire("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromWire("Light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromWire("DARK\n"))
    }

    @Test
    fun `fromWire falls back to system on anything else`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromWire("auto"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromWire(""))
    }
}
