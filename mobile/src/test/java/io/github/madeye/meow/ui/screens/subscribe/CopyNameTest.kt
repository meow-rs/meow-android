package io.github.madeye.meow.ui.screens.subscribe

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyNameTest {

    @Test
    fun `a name without isolates is kept`() {
        assertEquals("Work copy", copyName("Work copy"))
        assertEquals("Bản sao của Work", copyName("Bản sao của Work"))
    }

    @Test
    fun `rtl isolates around the name are dropped`() {
        // values-fa and values-ar, formatted with "Work".
        assertEquals("Work (رونوشت)", copyName("\u2068Work\u2069 (رونوشت)"))
        assertEquals("Work (نسخة)", copyName("\u2068Work\u2069 (نسخة)"))
    }

    @Test
    fun `a blank original leaves no stray space`() {
        assertEquals("copy", copyName(" copy"))
        assertEquals("(نسخة)", copyName("\u2068\u2069 (نسخة)"))
    }
}
