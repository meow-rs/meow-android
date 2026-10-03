package io.github.madeye.meow.ui.screens.perapp

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.repo.DomesticAppClassifier
import io.github.madeye.meow.repo.InstalledApp
import io.github.madeye.meow.repo.InstalledAppsRepository
import io.github.madeye.meow.repo.PerAppConfig
import io.github.madeye.meow.repo.PerAppMode
import io.github.madeye.meow.repo.PerAppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val scanningDomestic: Boolean = false,
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
    private val domesticApps: DomesticAppClassifier,
    private val analytics: Analytics,
) : ViewModel() {

    private val config = MutableStateFlow(PerAppConfig())
    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val query = MutableStateFlow("")
    private val showSystem = MutableStateFlow(false)
    private val scanningDomestic = MutableStateFlow(false)
    private var saving = false

    /**
     * Packages the user deselected while a domestic scan is in flight. The
     * scan merges by union, so without this a mid-scan uncheck of a matched
     * app would be silently reverted — and counted in the snackbar — when
     * the scan lands.
     */
    private val scanDeselected = mutableSetOf<String>()

    /** One-shot scan outcomes for the route to snackbar. */
    sealed interface DomesticScanEvent {
        /**
         * [matched] packages looked Chinese; [added] of them were newly
         * unioned in; [skippedUid] were refused by the shared-UID gate —
         * packages the user deselected mid-scan count as neither.
         */
        data class Finished(val matched: Int, val added: Int, val skippedUid: Int) :
            DomesticScanEvent
        data object Failed : DomesticScanEvent
    }

    private val domesticResult = MutableSharedFlow<DomesticScanEvent>(extraBufferCapacity = 1)
    val domesticScanResult: SharedFlow<DomesticScanEvent> = domesticResult

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
    }.combine(scanningDomestic) { state, scanning ->
        state.copy(scanningDomestic = scanning)
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
        if (scanningDomestic.value) {
            if (removing) scanDeselected += packageName else scanDeselected -= packageName
        }
        config.value = config.value.copy(
            packages = if (removing) current - packageName else current + packageName,
        )
    }

    /**
     * Applies to the currently filtered list only — and stays third-party:
     * "select all" means "every user app", so system apps keep their
     * per-row opt-in here. (The domestic scan is where matched system
     * apps get in — gated there on a private UID.)
     */
    fun onSelectAllVisible(visible: List<InstalledApp>) {
        val adding = visible.filter { !it.isSystem }.map { it.packageName }
        if (scanningDomestic.value) scanDeselected -= adding.toSet()
        config.value = config.value.copy(
            packages = config.value.packages + adding,
        )
    }

    fun onDeselectAllVisible(visible: List<InstalledApp>) {
        val removing = visible.map { it.packageName }.toSet()
        if (scanningDomestic.value) scanDeselected += removing
        config.value = config.value.copy(
            packages = config.value.packages - removing,
        )
    }

    /**
     * Union-selects every package that looks Chinese. The scan scope follows
     * the system-apps toggle (the SagerNet convention): off — user apps only;
     * on — everything. Auto-selection gates on the OEM-platform hazard:
     * components like com.miui.securitycenter sit on shared system UIDs, so
     * a system app is picked only when its UID is exclusively its own —
     * selecting a shared-UID member would silently route every sibling, an
     * outcome that stays unsafe even if the mode is later flipped. (User
     * apps sharing a UID are same-signature suites, benign by comparison.)
     * Like select-all this never removes a manual pick; in bypass mode it
     * exempts domestic apps from the tunnel, in proxy mode it extends the
     * tunnel to them. Scope snapshots at launch; the merge keeps every
     * manual change made mid-scan: additions ride the union, removals are
     * recorded in [scanDeselected] and subtracted back out of it.
     */
    fun onSelectDomestic() {
        if (scanningDomestic.value) return
        viewModelScope.launch {
            scanningDomestic.value = true
            scanDeselected.clear()
            try {
                // The `in config` clause mirrors visibleApps' escape rule so
                // hand-picked system apps still count toward `matched` even
                // when hidden.
                val scope = apps.value.filter {
                    !it.isSystem || showSystem.value || it.packageName in config.value.packages
                }
                val domestic = domesticApps.classify(scope.map { it.packageName })
                val byName = scope.associateBy { it.packageName }
                // classify() returns a subset of its input, so a byName miss
                // is impossible — the `!= false` just keeps even that
                // unreachable path failing safe (skipped, not selected).
                val (uidShared, uidPrivate) = domestic.partition { pkg ->
                    byName[pkg]?.let { it.isSystem && it.sharesUid } != false
                }
                // Mid-scan unchecks win over the union: a matched app the
                // user just deselected is neither re-added nor reported.
                val added = uidPrivate - config.value.packages - scanDeselected
                config.value = config.value.copy(
                    packages = config.value.packages + uidPrivate - scanDeselected,
                )
                // Already hand-picked shared-UID matches weren't "skipped" —
                // the user opted in — and neither were apps the user just
                // deselected, so the report counts only new refusals.
                val skipped = uidShared.count {
                    it !in config.value.packages && it !in scanDeselected
                }
                domesticResult.tryEmit(
                    DomesticScanEvent.Finished(domestic.size, added.size, skipped),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                domesticResult.tryEmit(DomesticScanEvent.Failed)
            } finally {
                scanningDomestic.value = false
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        // Checked synchronously: two taps within a frame would otherwise launch
        // two coroutines, both finishing with onSaved() → double popBackStack.
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                // A scan mid-flight would otherwise land after the persist and
                // be silently dropped; the route also disables the button, this
                // is the belt to its suspenders.
                scanningDomestic.first { !it }
                perApp.save(config.value)
                analytics.perAppProxySave(config.value.mode.key)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "per-app save failed")
            } finally {
                saving = false
            }
        }
    }

    /** Icons are fetched per row and memoised; loading all of them up front is visibly slow. */
    suspend fun icon(packageName: String): Drawable? =
        iconCache.getOrPut(packageName) { installedApps.icon(packageName) }
}
