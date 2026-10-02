package io.github.madeye.meow.ui.screens.subscribe

import io.github.madeye.meow.ui.screens.subscribe.InstallConfigLink.Invalid
import io.github.madeye.meow.ui.screens.subscribe.InstallConfigLink.Reason
import io.github.madeye.meow.ui.screens.subscribe.InstallConfigLink.Valid
import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallConfigLinkTest {

    private val sub = "https://sub.example.com/api/v1/client/subscribe?token=abc&flag=meta"

    @Test
    fun `encoded url and name are decoded`() {
        assertEquals(
            Valid(url = sub, name = "My Sub"),
            parse("clash://install-config?url=${enc(sub)}&name=My%20Sub"),
        )
    }

    @Test
    fun `clashmeta is the same link`() {
        assertEquals(
            Valid(url = sub, name = "x"),
            parse("clashmeta://install-config?url=${enc(sub)}&name=x"),
        )
    }

    @Test
    fun `scheme and host match case-insensitively and allow a trailing slash`() {
        assertEquals(Valid(sub, "x"), parse("CLASH://Install-Config?url=${enc(sub)}&name=x"))
        assertEquals(Valid(sub, "x"), parse("clash://install-config/?url=${enc(sub)}&name=x"))
    }

    @Test
    fun `double-encoded url is decoded twice`() {
        assertEquals(
            Valid(url = sub, name = "sub.example.com"),
            parse("clash://install-config?url=${enc(enc(sub))}"),
        )
        // An inner layer that left '+' raw: still a plus, not a space.
        assertEquals(
            Valid(url = "https://example.com/s?k=a+b", name = "example.com"),
            parse("clash://install-config?url=${enc("https%3A%2F%2Fexample.com%2Fs%3Fk%3Da+b")}"),
        )
    }

    @Test
    fun `a url that itself carries an encoded url keeps its own escapes`() {
        // subconverter-style: the inner URL's escapes must survive the decode.
        val converter = "https://api.example.net/sub?target=clash&url=https%3A%2F%2Fa.example%2Fs"
        assertEquals(
            Valid(url = converter, name = "api.example.net"),
            parse("clash://install-config?url=${enc(converter)}"),
        )
    }

    @Test
    fun `an unencoded url with a single query parameter still works`() {
        assertEquals(
            Valid(url = "https://example.com/sub?token=abc", name = "example.com"),
            parse("clash://install-config?url=https://example.com/sub?token=abc"),
        )
    }

    @Test
    fun `plus is literal in url but a space in name`() {
        assertEquals(
            Valid(url = "https://example.com/s?k=a+b", name = "Work Sub"),
            parse("clash://install-config?url=https://example.com/s?k=a+b&name=Work+Sub"),
        )
    }

    @Test
    fun `extra parameters and the fragment are ignored`() {
        assertEquals(
            Valid(url = sub, name = "x"),
            parse("clash://install-config?foo=1&url=${enc(sub)}&name=x&interval=24#frag"),
        )
    }

    @Test
    fun `the first url parameter wins`() {
        assertEquals(
            Valid(url = "https://a.example/s", name = "a.example"),
            parse("clash://install-config?url=https%3A%2F%2Fa.example%2Fs&url=https%3A%2F%2Fb.example%2Fs"),
        )
    }

    @Test
    fun `plain http and ports are accepted`() {
        assertEquals(
            Valid(url = "http://10.0.2.2:8080/config.yaml", name = "10.0.2.2"),
            parse("clash://install-config?url=${enc("http://10.0.2.2:8080/config.yaml")}"),
        )
    }

    @Test
    fun `missing or blank name falls back to the url host`() {
        val host = Valid(url = sub, name = "sub.example.com")
        assertEquals(host, parse("clash://install-config?url=${enc(sub)}"))
        assertEquals(host, parse("clash://install-config?url=${enc(sub)}&name="))
        assertEquals(host, parse("clash://install-config?url=${enc(sub)}&name=%20%20"))
        assertEquals(host, parse("clash://install-config?url=${enc(sub)}&name"))
        // A malformed escape loses the name, not the link.
        assertEquals(host, parse("clash://install-config?url=${enc(sub)}&name=%zz"))
    }

    @Test
    fun `name drops control and bidi characters and is capped`() {
        assertEquals(
            Valid(url = sub, name = "Evil gpj.exe"),
            parse("clash://install-config?url=${enc(sub)}&name=${enc("Evil\n\u202Egpj.exe\u0000")}"),
        )
        val long = parse("clash://install-config?url=${enc(sub)}&name=${"a".repeat(200)}")
        assertEquals("a".repeat(64), (long as Valid).name)
    }

    @Test
    fun `capping a name never splits a surrogate pair`() {
        // 63 ASCII + an emoji (2 UTF-16 units) puts the cut between the halves.
        val name = "a".repeat(63) + "😀"
        val parsed = parse("clash://install-config?url=${enc(sub)}&name=${enc(name)}") as Valid
        assertEquals("a".repeat(63), parsed.name)
    }

    @Test
    fun `missing url is rejected`() {
        assertEquals(Invalid(Reason.MISSING_URL), parse("clash://install-config"))
        assertEquals(Invalid(Reason.MISSING_URL), parse("clash://install-config?"))
        assertEquals(Invalid(Reason.MISSING_URL), parse("clash://install-config?name=x"))
        assertEquals(Invalid(Reason.MISSING_URL), parse("clash://install-config?url=&name=x"))
        assertEquals(Invalid(Reason.MISSING_URL), parse("clash://install-config?url"))
        assertEquals(Invalid(Reason.MISSING_URL), parse("clash://install-config?url=%20"))
        assertEquals(Invalid(Reason.MISSING_URL), parse("clash://install-config?URL=${enc(sub)}"))
    }

    @Test
    fun `non-http schemes are rejected`() {
        listOf(
            "javascript:alert(1)",
            "file:///data/data/io.github.madeye.meow/databases/meow.db",
            "content://io.github.madeye.meow.provider/config",
            "ftp://example.com/config.yaml",
            "data:text/plain,proxies:",
            "intent://example.com#Intent;scheme=https;end",
            "clash://install-config?url=https%3A%2F%2Fexample.com",
        ).forEach { target ->
            assertEquals(target, Invalid(Reason.UNSUPPORTED_URL), parse("clash://install-config?url=${enc(target)}"))
        }
    }

    @Test
    fun `double-encoded non-http schemes are rejected too`() {
        assertEquals(
            Invalid(Reason.UNSUPPORTED_URL),
            parse("clash://install-config?url=${enc(enc("javascript:alert(1)"))}"),
        )
    }

    @Test
    fun `relative, hostless and malformed urls are rejected`() {
        listOf(
            "/sub",
            "//example.com/sub",
            "example.com/sub",
            "https:example.com/sub",
            "https:///sub",
            "https://",
            "https://example.com:port/sub",
        ).forEach { target ->
            assertEquals(target, Invalid(Reason.UNSUPPORTED_URL), parse("clash://install-config?url=${enc(target)}"))
        }
        assertEquals(Invalid(Reason.UNSUPPORTED_URL), parse("clash://install-config?url=https%3A%2F%2Fexample.com%2"))
        assertEquals(Invalid(Reason.UNSUPPORTED_URL), parse("clash://install-config?url=%zz"))
    }

    @Test
    fun `whitespace or control characters inside the url are rejected`() {
        // A line break would let a page lay out a fake second URL in the dialog.
        listOf(
            "https://evil.example/s\nhttps://trusted.example/s",
            "https://example.com/a b",
            "https://example.com/\u0000",
        ).forEach { target ->
            assertEquals(target, Invalid(Reason.UNSUPPORTED_URL), parse("clash://install-config?url=${enc(target)}"))
        }
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            Valid(url = sub, name = "sub.example.com"),
            parse("clash://install-config?url=${enc("  $sub \n")}"),
        )
    }

    @Test
    fun `other schemes, hosts and paths are not install links`() {
        listOf(
            "meow://install-config?url=${enc(sub)}",
            "https://install-config?url=${enc(sub)}",
            "clash:install-config?url=${enc(sub)}",
            "clash://install-proxy?url=${enc(sub)}",
            "clash://install-config.evil.example?url=${enc(sub)}",
            "clash://install-config/extra?url=${enc(sub)}",
            "clash://?url=${enc(sub)}",
            "install-config?url=${enc(sub)}",
            "",
        ).forEach { link ->
            assertEquals(link, Invalid(Reason.UNSUPPORTED_LINK), parse(link))
        }
    }

    private fun parse(link: String) = InstallConfigLink.parse(link)

    /** Mirrors JS encodeURIComponent closely enough: URLEncoder only differs on spaces. */
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
