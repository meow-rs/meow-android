package io.github.madeye.meow.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [DataStore.showExitIp] as a flow, so Home's exit-IP card follows the
 * Settings switch the moment it moves. Only the UI process reads it, so —
 * unlike [RouteModeStore] — SharedPreferences' read-once caching is fine.
 *
 * `null` until the first read lands. That read can touch disk, so it runs off
 * the main thread, and until it does nothing may contact the lookup services.
 */
class ExitIpPreference(
    scope: CoroutineScope,
    private val read: () -> Boolean = { DataStore.showExitIp },
    private val write: (Boolean) -> Unit = { DataStore.showExitIp = it },
) {
    private val _enabled = MutableStateFlow<Boolean?>(null)
    val enabled: StateFlow<Boolean?> = _enabled.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val stored = read()
            // A set() that beat the first read is newer; keep it.
            _enabled.compareAndSet(null, stored)
        }
    }

    fun set(enabled: Boolean) {
        _enabled.value = enabled
        // SharedPreferences.apply() reaches disk asynchronously.
        write(enabled)
    }
}
