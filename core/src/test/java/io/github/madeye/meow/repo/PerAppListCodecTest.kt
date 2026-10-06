package io.github.madeye.meow.repo

import io.github.madeye.meow.repo.PerAppListCodec.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerAppListCodecTest {

    @Test
    fun `encode writes the store wire format`() {
        assertEquals(
            "bypass\ncom.a\ncom.b",
            PerAppListCodec.encode(PerAppMode.Bypass, setOf("com.a", "com.b")),
        )
        // No selection still exports the mode line — and must round-trip.
        assertEquals("proxy", PerAppListCodec.encode(PerAppMode.Proxy, emptySet()))
    }

    @Test
    fun `own wire format sets mode and packages`() {
        assertEquals(
            Parsed(PerAppMode.Proxy, setOf("com.a")),
            PerAppListCodec.parse("proxy\ncom.a"),
        )
        assertEquals(
            Parsed(PerAppMode.Bypass, setOf("com.a", "com.b")),
            PerAppListCodec.parse("bypass\ncom.a\ncom.b"),
        )
    }

    @Test
    fun `a mode line with no packages is still a valid list`() {
        // Reimporting a legit export of an empty selection must work.
        assertEquals(Parsed(PerAppMode.Bypass, emptySet()), PerAppListCodec.parse("bypass"))
    }

    @Test
    fun `v2rayNG boolean first line maps to bypass or proxy`() {
        assertEquals(
            Parsed(PerAppMode.Bypass, setOf("com.a")),
            PerAppListCodec.parse("true\ncom.a"),
        )
        assertEquals(
            Parsed(PerAppMode.Proxy, setOf("com.a")),
            PerAppListCodec.parse("false\ncom.a"),
        )
    }

    @Test
    fun `an unrecognised first line means a bare package list`() {
        // Not a mode this build knows, so nothing is consumed as a header —
        // every line is a package candidate and the mode stays untouched.
        assertEquals(
            Parsed(null, setOf("global", "com.a", "com.b")),
            PerAppListCodec.parse("global\ncom.a\ncom.b"),
        )
        assertEquals(
            Parsed(null, setOf("com.a", "com.b")),
            PerAppListCodec.parse("com.a\ncom.b"),
        )
    }

    @Test
    fun `comments blanks and padding are dropped`() {
        val parsed = PerAppListCodec.parse(
            "# copied from somewhere\n\n  proxy  \n  com.a  \n\n# trailing\n  com.b\n",
        )
        assertEquals(Parsed(PerAppMode.Proxy, setOf("com.a", "com.b")), parsed)
    }

    @Test
    fun `a leading comment does not hide the mode line`() {
        assertEquals(
            Parsed(PerAppMode.Bypass, setOf("com.a")),
            PerAppListCodec.parse("# my list\nbypass\ncom.a"),
        )
    }

    @Test
    fun `crlf endings parse the same`() {
        assertEquals(
            Parsed(PerAppMode.Proxy, setOf("com.a", "com.b")),
            PerAppListCodec.parse("proxy\r\ncom.a\r\ncom.b\r\n"),
        )
    }

    @Test
    fun `a leading utf-8 bom is stripped before parsing`() {
        // Without the strip, the first line is "\uFEFFbypass" — not a mode —
        // and the whole blob becomes a bare package list.
        assertEquals(
            Parsed(PerAppMode.Bypass, setOf("com.a")),
            PerAppListCodec.parse("\uFEFFbypass\ncom.a"),
        )
    }

    @Test
    fun `duplicate packages collapse`() {
        assertEquals(
            Parsed(null, setOf("com.a", "com.b")),
            PerAppListCodec.parse("com.a\ncom.b\ncom.a\n"),
        )
    }

    @Test
    fun `empty or comment-only text is invalid`() {
        assertNull(PerAppListCodec.parse(""))
        assertNull(PerAppListCodec.parse("   \n\t\n"))
        assertNull(PerAppListCodec.parse("# nothing but a comment\n\n"))
    }

    @Test
    fun `encode then parse round-trips`() {
        val text = PerAppListCodec.encode(PerAppMode.Bypass, setOf("com.a", "com.b"))
        assertEquals(Parsed(PerAppMode.Bypass, setOf("com.a", "com.b")), PerAppListCodec.parse(text))
    }
}
