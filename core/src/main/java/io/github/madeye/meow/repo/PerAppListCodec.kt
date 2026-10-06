package io.github.madeye.meow.repo

/**
 * Text form of the per-app selection, for share-export and clipboard import.
 *
 * Export is the `PerAppConfigStore` wire format verbatim — the mode key on the
 * first line, then one package name per line. The parser is deliberately more
 * tolerant, so a list copied out of another client still stages: lines are
 * trimmed, blanks and `#` comments are dropped, and the v2rayNG/SagerNet
 * first-line convention (`true`/`false` for "bypass these") is recognised.
 * A text with no recognised first line is a bare package list and keeps the
 * current mode.
 */
object PerAppListCodec {

    /** A parsed list: [mode] is null when the text named no mode. */
    data class Parsed(val mode: PerAppMode?, val packages: Set<String>)

    /** The store's wire format: `mode.key` on line one, then a package per line. */
    fun encode(mode: PerAppMode, packages: Collection<String>): String =
        (listOf(mode.key) + packages).joinToString("\n")

    /**
     * Parses [text] into an optional mode override plus the package names it
     * lists, or null when nothing usable remains. Package lines are
     * deduplicated but otherwise unvalidated — exact-match filtering against
     * the installed-package census is the caller's job.
     */
    fun parse(text: String): Parsed? {
        // A BOM'd blob would otherwise treat the mode line as a package.
        val lines = text.removePrefix("\uFEFF").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
        if (lines.isEmpty()) return null
        val (mode, packages) = when (lines.first()) {
            PerAppMode.Proxy.key -> PerAppMode.Proxy to lines.drop(1)
            PerAppMode.Bypass.key -> PerAppMode.Bypass to lines.drop(1)
            // v2rayNG/SagerNet write a boolean first line: bypass-mode on/off.
            "true" -> PerAppMode.Bypass to lines.drop(1)
            "false" -> PerAppMode.Proxy to lines.drop(1)
            else -> null to lines
        }
        return Parsed(mode, packages.toSet())
    }
}
