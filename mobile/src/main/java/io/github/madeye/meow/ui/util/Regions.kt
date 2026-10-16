package io.github.madeye.meow.ui.util

import java.util.IllformedLocaleException
import java.util.Locale

/** The two regional-indicator symbols that render as [code]'s flag; null unless it is two letters A–Z. */
fun regionFlag(code: String): String? {
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
    return buildString { code.forEach { appendCodePoint(0x1F1E6 + (it - 'A')) } }
}

/** [code] (ISO 3166-1 alpha-2) named in [locale]; null for a code the platform does not know. */
fun regionName(code: String, locale: Locale): String? {
    val name = try {
        Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
    } catch (e: IllformedLocaleException) {
        ""
    }
    // An unknown region comes back as the code itself.
    return name.takeIf { it.isNotEmpty() && it != code }
}
