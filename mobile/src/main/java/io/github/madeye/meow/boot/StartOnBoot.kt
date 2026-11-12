package io.github.madeye.meow.boot

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings' "Start on boot" switch, stored as [BootReceiver]'s component
 * state rather than as a preference: the manifest declares the receiver
 * disabled and the switch enables it. What Settings shows is then exactly
 * what the system delivers at boot, and with the switch off a boot never
 * wakes the app. Android keeps the state with the package, across updates.
 */
object StartOnBoot {
    private fun receiver(context: Context) = ComponentName(context, BootReceiver::class.java)

    /** Off until the switch first turns it on (the manifest's default). */
    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(receiver(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            receiver(context),
            // DISABLED, not DEFAULT: off stays off whatever a later manifest
            // declares.
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            // The user is in Settings; without it Android kills the app.
            PackageManager.DONT_KILL_APP,
        )
    }
}

/**
 * [StartOnBoot] as a flow for Settings. Only Settings writes the state, so
 * one read per process stays current. Like `ThemeModePreference` the read is
 * synchronous, one PackageManager call, so the switch opens in its real
 * position instead of sliding into it.
 */
class StartOnBootPreference(
    read: () -> Boolean,
    private val write: (Boolean) -> Unit,
) {
    private val _enabled = MutableStateFlow(read())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(enabled: Boolean) {
        write(enabled)
        _enabled.value = enabled
    }
}
