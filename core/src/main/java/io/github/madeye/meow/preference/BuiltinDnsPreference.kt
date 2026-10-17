package io.github.madeye.meow.preference

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * [BuiltinDnsStore] as a flow, for Settings' switch. `null` until the first
 * read lands.
 *
 * The file is touched off the main thread, one access at a time in call
 * order, so after a burst of flips the last position is the one on disk,
 * and the one [applyLive] puts a running VPN on.
 */
class BuiltinDnsPreference(
    private val scope: CoroutineScope,
    private val store: BuiltinDnsStore = BuiltinDnsStore.default,
    /**
     * Puts a running VPN on a position, blocking until it is: null then or
     * when none runs, else why it kept the DNS it had.
     */
    private val applyLive: (Boolean) -> String? = { null },
) {
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val _enabled = MutableStateFlow<Boolean?>(null)
    val enabled: StateFlow<Boolean?> = _enabled.asStateFlow()

    private val _applyFailures = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Why a flip did not reach the running VPN, once per flip. */
    val applyFailures: SharedFlow<String> = _applyFailures.asSharedFlow()

    init {
        scope.launch(io) {
            val stored = store.load()
            // A set() that beat the first read is newer; keep it.
            _enabled.compareAndSet(null, stored)
        }
    }

    fun set(enabled: Boolean) {
        _enabled.value = enabled
        scope.launch(io) {
            try {
                store.save(enabled)
            } catch (e: IOException) {
                Timber.w(e, "saving the built-in DNS switch failed")
            }
            // After the save: a VPN start that misses this reads the store.
            applyLive(enabled)?.let { reason ->
                Timber.w("applying the built-in DNS switch failed: %s", reason)
                _applyFailures.tryEmit(reason)
            }
        }
    }
}
