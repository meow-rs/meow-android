package io.github.madeye.meow

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards against translation drift.
 *
 * `stringResource` silently falls back to the default locale, so a key that
 * exists only in `values/` looks fine in development and quietly ships English
 * text to everyone else. meow-ios has the equivalent check
 * (`LocalizableParityTests`); this is the Android half.
 *
 * Every `values-*` directory with a strings.xml is checked, in `:mobile` and in
 * `:core` (whose strings the VPN notification shows), so a new locale is
 * covered the day it lands.
 *
 * Plain JVM test — no Robolectric, no device. Gradle runs JVM tests with the
 * module directory as the working directory.
 */
class StringsParityTest {

    private val catalogs: List<Catalog> = RES_DIRS.flatMap { res ->
        val english = parse(File(res, "values/strings.xml"))
        val translated = res.listFiles().orEmpty()
            .filter { it.name.startsWith("values-") && File(it, "strings.xml").exists() }
            .sortedBy { it.name }
        assertTrue("no translations found under ${res.absolutePath}", translated.isNotEmpty())
        translated.map { dir ->
            Catalog(
                label = "${res.path}/${dir.name}",
                qualifier = dir.name.removePrefix("values-"),
                english = english,
                translated = parse(File(dir, "strings.xml")),
            )
        }
    }

    @Test
    fun `every locale defines the same string keys`() {
        val drift = catalogs.associate { catalog ->
            catalog.label to Drift(
                missing = catalog.english.strings.keys - catalog.translated.strings.keys,
                extra = catalog.translated.strings.keys - catalog.english.strings.keys,
            )
        }.filterValues { !it.isEmpty() }
        assertEquals("string keys out of sync with values/", emptyMap<String, Drift>(), drift)
    }

    @Test
    fun `every locale defines the same plurals`() {
        val drift = catalogs.associate { catalog ->
            catalog.label to Drift(
                missing = catalog.english.plurals - catalog.translated.plurals,
                extra = catalog.translated.plurals - catalog.english.plurals,
            )
        }.filterValues { !it.isEmpty() }
        assertEquals("plurals out of sync with values/", emptyMap<String, Drift>(), drift)
    }

    @Test
    fun `no translated value is blank`() {
        val blank = catalogs.flatMap { catalog ->
            catalog.translated.strings.filterValues { it.isBlank() }.keys.map { "${catalog.label}: $it" }
        }
        assertEquals("blank translations", emptyList<String>(), blank)
    }

    @Test
    fun `format specifiers match across locales`() {
        val mismatched = catalogs.flatMap { catalog ->
            catalog.translated.strings.keys.intersect(catalog.english.strings.keys).filter { key ->
                specifiers(catalog.english.strings.getValue(key)) != specifiers(catalog.translated.strings.getValue(key))
            }.map { "${catalog.label}: $it" }
        }
        assertEquals("these keys have different format arguments than values/", emptyList<String>(), mismatched)
    }

    @Test
    fun `multi-argument strings use positional specifiers`() {
        // Bare %s cannot be reordered by a translator, and Android throws if a
        // resource mixes positional and non-positional forms.
        val all = catalogs.map { it.english }.distinct() + catalogs.map { it.translated }
        val offenders = all.flatMap { resources ->
            resources.strings.filterValues { value ->
                val bare = Regex("%[sd]").findAll(value).count()
                bare > 0 && specifiers(value).isNotEmpty()
            }.keys
        }
        assertTrue("mixed positional and bare specifiers in $offenders", offenders.isEmpty())
    }

    @Test
    fun `every translated locale is packaged`() {
        // localeFilters strips any locale it does not list, translations included.
        val gradle = File("build.gradle.kts").readText()
        val list = checkNotNull(Regex("""localeFilters \+= listOf\(([^)]*)\)""").find(gradle)) {
            "localeFilters not found in build.gradle.kts"
        }.groupValues[1]
        val filters = Regex("\"([^\"]+)\"").findAll(list).map { it.groupValues[1] }.toSet()
        val unpackaged = catalogs.map { it.qualifier }.toSet() - filters
        assertEquals("translated but missing from localeFilters", emptySet<String>(), unpackaged)
    }

    @Test
    fun `rtl locales isolate string arguments`() {
        // Arguments are mostly LTR: byte counts, timestamps, hosts, English
        // error text. Left bare in an RTL sentence, the bidi algorithm splits
        // "1.2 GB" into "1.2" and "GB" around neighbouring text, and a Latin
        // first argument flips the whole line to LTR. FSI…PDI keeps each one a
        // single unit.
        val bare = rtlCatalogs.flatMap { catalog ->
            catalog.translated.strings.filterValues { value ->
                Regex("%\\d+\\\$s").findAll(value).any { !value.contains("\\u2068${it.value}\\u2069") }
            }.keys.map { "${catalog.label}: $it" }
        }
        assertEquals("%s arguments not wrapped in \\u2068…\\u2069", emptyList<String>(), bare)
    }

    @Test
    fun `rtl sentences open right-to-left`() {
        // MeowTypography lays each paragraph out in the direction of its first
        // strong letter, skipping isolated arguments. A Persian sentence that
        // opens with a Latin word ("TTL …") would read left to right, so it
        // starts with \u200F (RLM). Strings with no Persian in them ("%1$d ms")
        // are values and are meant to read left to right.
        val flipped = rtlCatalogs.flatMap { catalog ->
            catalog.translated.strings.filterValues { value ->
                val letters = value.replace(NOT_PROSE, "").filter(Char::isLetter)
                !value.startsWith("\\u200F") && letters.any(::isRtl) && !isRtl(letters.first())
            }.keys.map { "${catalog.label}: $it" }
        }
        assertEquals("RTL strings that open with a left-to-right letter", emptyList<String>(), flipped)
    }

    private val rtlCatalogs: List<Catalog>
        get() = catalogs.filter { it.qualifier.substringBefore('-') in RTL_LANGUAGES }

    private fun isRtl(char: Char): Boolean = Character.getDirectionality(char) in RTL_DIRECTIONALITIES

    private fun specifiers(value: String): Set<String> =
        Regex("%(\\d+)\\$[sd]").findAll(value).map { it.value }.toSet()

    private fun parse(file: File): Resources {
        assertTrue("missing resource file: ${file.absolutePath}", file.exists())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

        val strings = document.getElementsByTagName("string").let { nodes ->
            (0 until nodes.length).associate { index ->
                val element = nodes.item(index) as Element
                element.getAttribute("name") to element.textContent
            }
        }
        val plurals = document.getElementsByTagName("plurals").let { nodes ->
            (0 until nodes.length).map { index ->
                (nodes.item(index) as Element).getAttribute("name")
            }.toSet()
        }
        return Resources(strings, plurals)
    }

    private data class Resources(val strings: Map<String, String>, val plurals: Set<String>)

    private class Catalog(
        val label: String,
        val qualifier: String,
        val english: Resources,
        val translated: Resources,
    )

    private data class Drift(val missing: Set<String>, val extra: Set<String>) {
        fun isEmpty() = missing.isEmpty() && extra.isEmpty()
    }

    private companion object {
        val RES_DIRS = listOf(File("src/main/res"), File("../core/src/main/res"))

        // Language subtags written right-to-left; "iw" is Android's legacy Hebrew code.
        val RTL_LANGUAGES = setOf("ar", "fa", "he", "iw", "ur")

        val RTL_DIRECTIONALITIES = setOf(
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
        )

        // Isolated arguments, \uXXXX and \n escapes, and format specifiers:
        // none of them decide a paragraph's direction.
        val NOT_PROSE = Regex("""\\u2068.*?\\u2069|\\u\p{XDigit}{4}|\\.|%\d+\$[sd]""")
    }
}
