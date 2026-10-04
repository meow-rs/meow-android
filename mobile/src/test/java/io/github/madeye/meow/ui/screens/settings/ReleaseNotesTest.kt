package io.github.madeye.meow.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun `Markdown becomes plain text`() {
        val notes = plainReleaseNotes(
            "## Meow 1.0.8\r\n\r\n### Fixes\r\n" +
                "- **AnyTLS** dials use the `real` resolver\r\n" +
                "  * nested item\r\n" +
                "- See [the PR](https://github.com/meow-rs/meow-android/pull/99)\r\n",
        )

        assertEquals(
            "Meow 1.0.8\n\nFixes\n• AnyTLS dials use the real resolver\n  • nested item\n• See the PR",
            notes,
        )
    }

    @Test
    fun `single emphasis marks and inline hashes are left alone`() {
        val notes = plainReleaseNotes("Fixes #97: a * b, snake_case_name")

        assertEquals("Fixes #97: a * b, snake_case_name", notes)
    }

    @Test
    fun `long notes are cut at a line break`() {
        val line = "x".repeat(69)
        val notes = plainReleaseNotes(List(100) { line }.joinToString("\n"))

        val whole = MAX_NOTES_CHARS / (line.length + 1)
        assertEquals(List(whole) { line }.joinToString("\n") + "\n…", notes)
    }

    @Test
    fun `no notes stay empty`() {
        assertEquals("", plainReleaseNotes("  \n"))
    }
}
