package io.github.madeye.meow.ui.screens.proxies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.analytics.Analytics
import io.github.madeye.meow.api.GroupDelayTester
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.RouteMode
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.repo.ProfileRepository
import io.github.madeye.meow.ui.screens.home.RouteChanges
import io.github.madeye.meow.vpn.VpnStateRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class ProxyNodeUi(
    val name: String,
    val type: String,
    val delay: NodeDelay,
    val selected: Boolean,
)

@Immutable
data class ProxyGroupUi(
    val name: String,
    val type: String,
    val now: String,
    val nodes: List<ProxyNodeUi>,
    /** The group's own health-check URL, which its latency test probes with. */
    val testUrl: String? = null,
    /** Share of members tested while a latency test runs; `null` otherwise. */
    val testProgress: Float? = null,
)

@Immutable
data class ProxyGroupsUiState(
    val state: BaseService.State = BaseService.State.Idle,
    val hasProfile: Boolean = false,
    val groups: List<ProxyGroupUi> = emptyList(),
    val expandedGroup: String? = null,
)

/**
 * The Proxy Groups tab: the running engine's groups, node picks and latency
 * tests. Groups only exist while the engine is up, so everything here is read
 * from `/proxies` rather than from the profile.
 */
class ProxyGroupsViewModel(
    private val vpn: VpnStateRepository,
    private val profiles: ProfileRepository,
    private val api: MeowApi,
    private val analytics: Analytics,
    private val routeChanges: RouteChanges,
) : ViewModel() {

    private val groups = MutableStateFlow<List<ProxyGroupUi>>(emptyList())
    private val expanded = MutableStateFlow<String?>(null)

    /** Running latency tests by group, laid over [groups] until each ends. */
    private val groupTests = MutableStateFlow<Map<String, GroupTestProgress>>(emptyMap())

    /** One job per group under test. Only touched on the main thread. */
    private val testJobs = HashMap<String, Job>()
    private val delayTester = GroupDelayTester(api)

    val uiState: StateFlow<ProxyGroupsUiState> = combine(
        vpn.state,
        profiles.observeSelected().map { it != null },
        combine(groups, groupTests) { list, tests -> list.withTests(tests) },
        expanded,
    ) { state, hasProfile, groupList, expandedGroup ->
        ProxyGroupsUiState(
            state = state,
            hasProfile = hasProfile,
            groups = groupList,
            expandedGroup = expandedGroup,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProxyGroupsUiState())

    init {
        // They change when the user switches profile, so reload on every
        // state transition.
        viewModelScope.launch {
            vpn.state.collect { state ->
                if (state == BaseService.State.Connected) loadGroups() else groups.value = emptyList()
            }
        }
    }

    /**
     * Every time the tab shows, not only on app resume: a route-mode switch on
     * Home decides whether GLOBAL is listed, and the `:vpn` process may have
     * been killed while backgrounded.
     */
    fun onResume() {
        vpn.refresh()
        if (vpn.state.value == BaseService.State.Connected) loadGroups()
    }

    fun onToggleExpanded(group: String) {
        expanded.value = if (expanded.value == group) null else group
    }

    fun onSelectNode(group: String, node: String) {
        viewModelScope.launch {
            try {
                api.selectProxy(group, node)
                routeChanges.notifyChanged()
                analytics.proxyNodeSelect(node)
                // Persist the pick so it can be replayed on the next connect.
                profiles.getSelected()?.let { profiles.saveSelectedProxy(it.id, node) }
                loadGroups()
            } catch (e: MeowApiException) {
                // The engine is the source of truth; a failed select simply
                // leaves the previous node in place on the next refresh.
                loadGroups()
            }
        }
    }

    /**
     * Latency-tests every member of [group], filling each row in as its probe
     * lands (see [GroupDelayTester]). A test already running for the group is
     * restarted rather than joined; other groups' tests carry on.
     */
    fun onTestGroup(group: String) {
        val target = groups.value.firstOrNull { it.name == group } ?: return
        testJobs.remove(group)?.cancel()
        val members = target.nodes.map { it.name }
        groupTests.update { it + (group to GroupTestProgress.start(members)) }
        // Lazy so the job is registered before its body can reach `finally`.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                delayTester.test(group, target.type, members, target.testUrl).collect { result ->
                    groupTests.update { tests ->
                        val progress = tests[group] ?: return@update tests
                        tests + (group to progress.withResult(result))
                    }
                }
                // The engine kept every result as history (and an url-test
                // group its new pick); reload before dropping the overlay so
                // rows don't flash their pre-test values in between.
                loadGroups().join()
            } finally {
                // A superseded run must leave its successor's state alone.
                if (testJobs[group] === self) {
                    testJobs.remove(group)
                    groupTests.update { it - group }
                }
            }
        }
        testJobs[group] = job
        job.start()
    }

    private fun loadGroups(): Job {
        return viewModelScope.launch {
            // Read first: the mode decides whether GLOBAL is listed.
            val mode = loadRouteMode()
            groups.value = try {
                val result = api.proxies()
                result.visibleGroups(mode).map { group ->
                    ProxyGroupUi(
                        name = group.name,
                        type = group.type,
                        now = group.now,
                        testUrl = group.testUrl,
                        nodes = group.all.map { nodeName ->
                            val node = result.proxies[nodeName]
                            // Members can be groups themselves (every one of
                            // GLOBAL's is); those live in the other map.
                            val member = result.groups[nodeName]
                            ProxyNodeUi(
                                name = nodeName,
                                type = node?.type ?: member?.type.orEmpty(),
                                delay = NodeDelay.fromHistory(node?.history ?: member?.history.orEmpty()),
                                selected = nodeName == group.now,
                            )
                        },
                    )
                }
            } catch (e: MeowApiException) {
                // An empty group list and a failed fetch look identical on
                // screen, so the reason has to reach logcat or it is invisible.
                Timber.w(e, "loading proxy groups failed")
                emptyList()
            }
        }
    }

    private suspend fun loadRouteMode(): RouteMode? = try {
        api.configs().routeMode
    } catch (e: MeowApiException) {
        Timber.w(e, "loading route mode failed")
        null
    }
}
