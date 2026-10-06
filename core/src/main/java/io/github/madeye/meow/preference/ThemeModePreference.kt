package io.github.madeye.meow.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [DataStore.themeMode] as a flow, so `MainActivity`'s `MeowTheme` follows the
 * Settings pick the moment it moves. Only the UI process reads it, so —
 * unlike [RouteModeStore] — SharedPreferences' read-once caching is fine.
 *
 * `null` until the first read lands. That read can touch disk, so it runs off
 * the main thread, and until it does the app follows the system theme.
 */
class ThemeModePreference(
    scope: CoroutineScope,
    private val read: () -> ThemeMode = { ThemeMode.fromWire(DataStore.themeMode) },
    private val write: (ThemeMode) -> Unit = { DataStore.themeMode = it.toWire() },
) {
    private val _mode = MutableStateFlow<ThemeMode?>(null)
    val mode: StateFlow<ThemeMode?> = _mode.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val stored = read()
            // A set() that beat the first read is newer; keep it.
            _mode.compareAndSet(null, stored)
        }
    }

    fun set(mode: ThemeMode) {
        _mode.value = mode
        // SharedPreferences.apply() reaches disk asynchronously.
        write(mode)
    }
}
