package io.github.madeye.meow.preference

/** The app's light/dark choice, stored as the [DataStore.themeMode] string. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    fun toWire(): String = name.lowercase()

    companion object {
        /** Unrecognised values — written by another build — fall back to [SYSTEM]. */
        fun fromWire(value: String): ThemeMode =
            entries.firstOrNull { it.toWire().equals(value.trim(), ignoreCase = true) } ?: SYSTEM
    }
}
