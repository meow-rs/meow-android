package io.github.madeye.meow.ui.screens.subscribe

import java.net.MalformedURLException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A `clash://install-config?url=<encoded>&name=<optional>` link — the de-facto
 * "add this subscription" link behind panels' "Import to Clash" buttons.
 * `clashmeta://` is the same link from Clash Meta-branded panels.
 *
 * Parsed by hand rather than through `android.net.Uri` so it runs as a plain
 * JVM test. The link can come from any web page, so this only decides what may
 * be *offered*; nothing is fetched until the user confirms.
 */
sealed interface InstallConfigLink {

    /** [url] is an absolute http(s) URL; [name] is never blank. */
    data class Valid(val url: String, val name: String) : InstallConfigLink

    data class Invalid(val reason: Reason) : InstallConfigLink

    enum class Reason {
        /** Not a `clash://install-config` link at all. */
        UNSUPPORTED_LINK,

        /** No `url` parameter, or an empty one. */
        MISSING_URL,

        /** `url` is not an absolute http(s) URL: `javascript:`, `file:`, `content:`, hostless, malformed. */
        UNSUPPORTED_URL,
    }

    companion object {
        private val SCHEMES = setOf("clash", "clashmeta")
        private const val HOST = "install-config"
        private val URL_PROTOCOLS = setOf("http", "https")

        /** Long enough for any real subscription name, short enough to stay one card line. */
        private const val MAX_NAME_LENGTH = 64

        private val FORMAT_CHARS = Regex("\\p{Cf}")
        private val BREAKS_AND_CONTROLS = Regex("[\\s\\p{Cc}]+")

        fun parse(link: String): InstallConfigLink {
            val colon = link.indexOf(':')
            if (colon <= 0 || link.substring(0, colon).lowercase() !in SCHEMES) {
                return Invalid(Reason.UNSUPPORTED_LINK)
            }
            val hierarchical = link.substring(colon + 1)
            if (!hierarchical.startsWith("//")) return Invalid(Reason.UNSUPPORTED_LINK)

            // The fragment is not part of the query; a properly encoded url
            // parameter carries its own '#' as %23.
            val withoutFragment = hierarchical.substring(2).substringBefore('#')
            val hostAndPath = withoutFragment.substringBefore('?')
            val authority = hostAndPath.substringBefore('/')
            val path = hostAndPath.substring(authority.length)
            if (!authority.equals(HOST, ignoreCase = true) || (path != "" && path != "/")) {
                return Invalid(Reason.UNSUPPORTED_LINK)
            }

            val params = withoutFragment.substringAfter('?', missingDelimiterValue = "")
                .split('&')
                .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }
            val rawUrl = params.firstOrNull { it.first == "url" }?.second
            if (rawUrl.isNullOrEmpty()) return Invalid(Reason.MISSING_URL)

            val url = decodeUrl(rawUrl) ?: return Invalid(Reason.UNSUPPORTED_URL)
            if (url.isEmpty()) return Invalid(Reason.MISSING_URL)
            val urlHost = httpHostOf(url) ?: return Invalid(Reason.UNSUPPORTED_URL)

            // Without a name the manual flow would store the whole URL as one;
            // the host is what a user would type and reads better in the card.
            val name = params.firstOrNull { it.first == "name" }?.second?.let(::decodeName)
            return Valid(url = url, name = name ?: urlHost)
        }

        /**
         * The link that offers [url] under [name]: what a shared QR code holds,
         * so a phone's camera app opens it straight in meow (or another Clash
         * client), and [parse] reads it back unchanged.
         */
        fun of(url: String, name: String): String =
            "clash://$HOST?url=${encode(url)}&name=${encode(name)}"

        /**
         * What a scanned QR code offers. Panels encode either an install-config
         * link or the bare subscription URL; a bare URL is offered under its
         * host, like a link without a name.
         */
        fun parseScanned(text: String): InstallConfigLink {
            val trimmed = text.trim()
            val scheme = trimmed.substringBefore(':', missingDelimiterValue = "")
            if (scheme.lowercase() !in URL_PROTOCOLS) return parse(trimmed)
            val host = httpHostOf(trimmed) ?: return Invalid(Reason.UNSUPPORTED_URL)
            return Valid(url = trimmed, name = host)
        }

        /** encodeURIComponent's output, which every link parser reads: URLEncoder writes a space as '+'. */
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /**
         * Percent-decodes the url parameter, keeping '+' literal: a URL cannot
         * contain a space, so a '+' here is a plus the link generator didn't
         * escape, never form-encoding. Some generators encode the URL twice,
         * which leaves an `https%3A…` that no single decode turns into a URL.
         */
        private fun decodeUrl(raw: String): String? {
            val once = percentDecode(raw.replace("+", "%2B")) ?: return null
            val twice = if (once.startsWith("http%3a", ignoreCase = true) ||
                once.startsWith("https%3a", ignoreCase = true)
            ) {
                percentDecode(once.replace("+", "%2B")) ?: return null
            } else {
                once
            }
            return twice.trim()
        }

        /**
         * The host of [url] if it is an absolute http(s) URL, else null.
         *
         * `java.net.URL` is what `SubscriptionService` fetches with, so anything
         * accepted here is fetchable there. Whitespace and control characters
         * are rejected up front: URL would accept them, and a line break would
         * let a page lay out a fake second URL in the confirmation dialog.
         */
        private fun httpHostOf(url: String): String? {
            if (url.any { it.isWhitespace() || it.isISOControl() }) return null
            val parsed = try {
                URL(url)
            } catch (_: MalformedURLException) {
                return null
            }
            if (parsed.protocol !in URL_PROTOCOLS) return null
            return parsed.host.takeUnless { it.isNullOrEmpty() }
        }

        /**
         * Form-decodes the name ('+' is a space, as Python's urlencode emits)
         * and flattens it into a one-line label: line breaks and control
         * characters become a single space, and format characters such as
         * bidi overrides — which make a name read differently from what is
         * stored — are dropped.
         */
        private fun decodeName(raw: String): String? {
            val decoded = percentDecode(raw) ?: return null
            val clean = decoded
                .replace(FORMAT_CHARS, "")
                .replace(BREAKS_AND_CONTROLS, " ")
                .trim()
                .take(MAX_NAME_LENGTH)
                // take() counts UTF-16 units; don't leave half a surrogate pair.
                .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
                .trim()
            return clean.ifEmpty { null }
        }

        /** Null on a malformed escape such as `%zz` or a trailing `%`. */
        private fun percentDecode(value: String): String? = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
