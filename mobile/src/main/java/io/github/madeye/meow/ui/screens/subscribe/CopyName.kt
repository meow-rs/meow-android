package io.github.madeye.meow.ui.screens.subscribe

/**
 * The name a copy of a profile is stored under, given `subs_copy_name`
 * formatted with the original's name.
 *
 * The right-to-left translations wrap that argument in bidi isolates
 * (U+2068…U+2069), so a Latin name cannot reorder the sentence around it.
 * They only lay out that one string: the result is stored data, which other
 * sentences isolate themselves and which ends up in export file names, so
 * they are dropped. Trimmed like a name saved from the edit dialog, so a
 * blank original leaves no stray space.
 */
fun copyName(formatted: String): String =
    formatted.filterNot { it == FIRST_STRONG_ISOLATE || it == POP_DIRECTIONAL_ISOLATE }.trim()

private const val FIRST_STRONG_ISOLATE = '\u2068'
private const val POP_DIRECTIONAL_ISOLATE = '\u2069'
