package io.github.madeye.meow.ui.screens.perapp

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.repo.InstalledApp
import io.github.madeye.meow.repo.InstalledAppsRepository
import io.github.madeye.meow.repo.PerAppConfig
import io.github.madeye.meow.repo.PerAppListCodec
import io.github.madeye.meow.repo.PerAppMode
import io.github.madeye.meow.repo.PerAppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class PerAppUiState(
    val loading: Boolean = true,
    val mode: PerAppMode = PerAppMode.Proxy,
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val showSystemApps: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
) {
    /** Filtering happens here so the list and the list-scoped bulk actions share one scope. */
    val visibleApps: List<InstalledApp>
        get() = apps.filter { app ->
            (showSystemApps || !app.isSystem || app.packageName in selected) &&
                (
                    query.isBlank() ||
                        app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                    )
        }
}

class PerAppProxyViewModel(
    private val perApp: PerAppRepository,
    private val installedApps: InstalledAppsRepository,
    private val analytics: Analytics,
) : ViewModel() {

    private val config = MutableStateFlow(PerAppConfig())
    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val query = MutableStateFlow("")
    private val showSystem = MutableStateFlow(false)
    private var saving = false

    /** One-shot outcomes for the route to snackbar. */
    sealed interface PerAppEvent {
        /** The persist threw; the route stays open so the user can retry. */
        data object SaveFailed : PerAppEvent

        /**
         * A clipboard import landed: [applied] packages staged, [skipped]
         * named packages the device does not have installed.
         */
        data class Imported(val applied: Int, val skipped: Int) : PerAppEvent

        /** The clipboard held nothing parseable as an app list. */
        data object ImportInvalid : PerAppEvent
    }

    private val _events = MutableSharedFlow<PerAppEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PerAppEvent> = _events.asSharedFlow()

    private val iconCache = mutableMapOf<String, Drawable?>()

    val uiState: StateFlow<PerAppUiState> = combine(
        config,
        apps,
        loading,
        query,
        showSystem,
    ) { current, appList, isLoading, search, system ->
        PerAppUiState(
            loading = isLoading,
            mode = current.mode,
            selected = current.packages,
            query = search,
            showSystemApps = system,
            apps = appList,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PerAppUiState())

    init {
        viewModelScope.launch {
            try {
                config.value = perApp.load()
                apps.value = installedApps.load()
                // Packages uninstalled since the last save have no row to
                // toggle and deselect-all can't reach them — prune so they
                // don't inflate the count; the cleaned set lands on the next
                // save. The baseline is every package PM knows, not the
                // picker list: disabled and archived apps are transient, not
                // uninstalled. An empty baseline means the enumeration
                // failed, not that everything vanished.
                val installed = installedApps.installedPackageNames()
                if (installed.isNotEmpty()) {
                    config.value = config.value.copy(
                        packages = config.value.packages intersect installed,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever survives shows as an empty picker; the alternative
                // is an uncaught-coroutine crash on the main thread.
                Timber.w(e, "per-app init failed")
            } finally {
                loading.value = false
            }
        }
    }

    fun onQueryChange(value: String) { query.value = value }

    fun onToggleSystemApps() { showSystem.value = !showSystem.value }

    fun onModeChange(mode: PerAppMode) {
        config.value = config.value.copy(mode = mode)
    }

    fun onToggleApp(packageName: String) {
        val current = config.value.packages
        val removing = packageName in current
        config.value = config.value.copy(
            packages = if (removing) current - packageName else current + packageName,
        )
    }

    /**
     * Applies to the currently filtered list only — and stays third-party:
     * "select all" means "every user app", so system apps keep their
     * per-row opt-in here.
     */
    fun onSelectAllVisible(visible: List<InstalledApp>) {
        val adding = visible.filter { !it.isSystem }.map { it.packageName }
        config.value = config.value.copy(
            packages = config.value.packages + adding,
        )
    }

    fun onDeselectAllVisible(visible: List<InstalledApp>) {
        val removing = visible.map { it.packageName }.toSet()
        config.value = config.value.copy(
            packages = config.value.packages - removing,
        )
    }

    /** The working selection in the share/import wire format. */
    fun exportText(): String =
        PerAppListCodec.encode(config.value.mode, config.value.packages)

    /**
     * Stages [text] into the working config — deliberately NOT persisted;
     * the ✓ save remains the commit point, so a bad paste is still
     * reversible. Packages are kept only when PackageManager knows them
     * (exact match, never substring); a pasted mode line replaces the mode,
     * a bare list keeps it.
     */
    fun importText(text: String?) {
        val parsed = text?.let(PerAppListCodec::parse)
        if (parsed == null) {
            _events.tryEmit(PerAppEvent.ImportInvalid)
            return
        }
        viewModelScope.launch {
            val installed = installedApps.installedPackageNames()
            // An empty census means enumeration failed, not that nothing is
            // installed — the init prune reads it the same way. Apply the
            // list verbatim rather than silently dropping all of it.
            val applied = if (installed.isEmpty()) {
                parsed.packages
            } else {
                parsed.packages intersect installed
            }
            config.value = config.value.copy(
                mode = parsed.mode ?: config.value.mode,
                packages = applied,
            )
            _events.emit(
                PerAppEvent.Imported(applied.size, parsed.packages.size - applied.size),
            )
        }
    }

    fun save(onSaved: () -> Unit) {
        // Checked synchronously: two taps within a frame would otherwise launch
        // two coroutines, both finishing with onSaved() → double popBackStack.
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                perApp.save(config.value)
                // Analytics is telemetry, not part of the persist: by the time
                // it runs the selection is already durable, so a failure here
                // must NOT surface as "save failed". Keep the order persist →
                // analytics → onSaved() so analytics still completes before
                // onSaved() pops the route and the ViewModel is destroyed.
                runCatching { analytics.perAppProxySave(config.value.mode.key) }
                    .onFailure {
                        // runCatching swallows Throwable, which includes the
                        // CancellationException structured cancellation relies
                        // on — rethrow it so cancellation stays observable.
                        // The analytics call is non-suspend today; this guard
                        // is for if that ever changes.
                        if (it is CancellationException) throw it
                        Timber.w(it, "per-app save analytics failed")
                    }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "per-app save failed")
                // Without this the ✓ looks dead: onSaved is skipped, so the
                // route stays open and nothing tells the user why. SaveFailed
                // means the authoritative config file could not be replaced.
                _events.tryEmit(PerAppEvent.SaveFailed)
            } finally {
                saving = false
            }
        }
    }

    /** Icons are fetched per row and memoised; loading all of them up front is visibly slow. */
    suspend fun icon(packageName: String): Drawable? =
        iconCache.getOrPut(packageName) { installedApps.icon(packageName) }
}
