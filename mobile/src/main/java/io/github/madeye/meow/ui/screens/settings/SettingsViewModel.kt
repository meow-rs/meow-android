package io.github.madeye.meow.ui.screens.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.preference.ExitIpPreference
import io.github.madeye.meow.ui.util.AppVersions
import io.github.madeye.meow.update.AppRelease
import io.github.madeye.meow.update.isNewerVersion
import io.github.madeye.meow.vpn.VpnStateRepository
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class SettingsUiState(
    val appVersion: String = "",
    val engineVersion: String = "",
    /** The engine-backed screens can only load while the tunnel is up. */
    val engineOnline: Boolean = false,
    val showExitIp: Boolean = false,
    /** An update check is waiting on GitHub. */
    val checkingForUpdate: Boolean = false,
    /** A newer release the last check found, until the user answers its dialog. */
    val update: AppRelease? = null,
)

/**
 * The snackbar outcomes of an update check. A newer release is state instead
 * ([SettingsUiState.update]), so its dialog survives rotation.
 */
sealed interface SettingsEvent {
    data class UpToDate(val version: String) : SettingsEvent
    data class UpdateCheckFailed(val reason: String) : SettingsEvent
}

class SettingsViewModel(
    private val versions: AppVersions,
    vpn: VpnStateRepository,
    private val exitIp: ExitIpPreference,
    /**
     * The newest GitHub release. Null in the Play build, which may only
     * update through Play: the route opens the store listing instead.
     */
    private val latestRelease: (suspend () -> AppRelease)?,
    private val installedVersion: String,
) : ViewModel() {

    private val versionPair = MutableStateFlow("" to "")
    private val checking = MutableStateFlow(false)
    private val update = MutableStateFlow<AppRelease?>(null)

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        versionPair,
        vpn.state.map { it == BaseService.State.Connected },
        exitIp.enabled,
        checking,
        update,
    ) { (app, engine), online, showExitIp, isChecking, newer ->
        SettingsUiState(
            appVersion = app,
            engineVersion = engine,
            engineOnline = online,
            showExitIp = showExitIp == true,
            checkingForUpdate = isChecking,
            update = newer,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { versionPair.value = versions.read() }
    }

    fun onShowExitIpChange(show: Boolean) = exitIp.set(show)

    /** A tap on "Check for updates"; ignored while a check is already running. */
    fun checkForUpdates() {
        val fetch = latestRelease ?: return
        if (checking.value) return
        checking.value = true
        viewModelScope.launch {
            try {
                val release = fetch()
                if (isNewerVersion(release.version, installedVersion)) {
                    update.value = release
                } else {
                    _events.tryEmit(SettingsEvent.UpToDate(installedVersion))
                }
            } catch (e: IOException) {
                Timber.w(e, "update check failed")
                _events.tryEmit(SettingsEvent.UpdateCheckFailed(e.message ?: e::class.java.simpleName))
            } finally {
                checking.value = false
            }
        }
    }

    fun onUpdateDialogClosed() {
        update.value = null
    }
}
