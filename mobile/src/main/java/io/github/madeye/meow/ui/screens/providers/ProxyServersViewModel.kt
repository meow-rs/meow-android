package io.github.madeye.meow.ui.screens.providers

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.Proxy
import io.github.madeye.meow.api.ProxiesResult
import io.github.madeye.meow.api.ProxyProvider
import io.github.madeye.meow.api.ProxyProviderNode
import io.github.madeye.meow.repo.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.launch
import timber.log.Timber

private const val INLINE_PROXIES_ID = "inline-proxies"
private val BUILTIN_PROXY_TYPES = setOf("Direct", "Reject", "RejectDrop", "Pass", "PassRule", "Compatible")

enum class ProxyServerKind {
    Provider,
    Inline,
}

@Immutable
data class ProxyServerNodeUi(
    val name: String,
    val type: String,
    val delayMs: Int?,
    val timedOut: Boolean,
    val testing: Boolean = false,
)

@Immutable
data class ProxyServerProviderUi(
    /** Stable identity used for callbacks and Compose keys. */
    val id: String,
    val name: String,
    val vehicleType: String,
    val testUrl: String,
    val expectedStatus: String,
    val nodes: List<ProxyServerNodeUi>,
    val kind: ProxyServerKind = ProxyServerKind.Provider,
    val checking: Boolean = false,
)

@Immutable
data class ProxyServersUiState(
    val providers: List<ProxyServerProviderUi> = emptyList(),
    /** The selected local profile can be edited from the inline proxy card. */
    val editableProfileId: Long? = null,
    /** Case-insensitive search applied to node names and protocol types. */
    val query: String = "",
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** Loads both configured provider nodes and inline `proxies:` nodes. */
class ProxyServersViewModel(
    private val api: MeowApi,
    private val profiles: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProxyServersUiState())
    val uiState: StateFlow<ProxyServersUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val (inline, providers, selectedProfile) = supervisorScope {
                val inline = async { read("inline proxies") { api.proxies() } }
                val providers = async { read("proxy providers") { api.proxyProviders() } }
                val selectedProfile = async { profiles.getSelected() }
                Triple(inline.await(), providers.await(), selectedProfile.await())
            }
            if (inline == null && providers == null) {
                _state.update { it.copy(loading = false, failed = true) }
            } else {
                _state.value = ProxyServersUiState(
                    providers = proxyServerProviders(inline, providers.orEmpty()),
                    editableProfileId = selectedProfile?.takeIf { it.url.isBlank() }?.id,
                    query = _state.value.query,
                    loading = false,
                )
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun healthCheck(providerId: String) {
        val provider = _state.value.providers.firstOrNull { it.id == providerId } ?: return
        if (provider.kind != ProxyServerKind.Provider) return
        if (provider.checking) return
        setProviderChecking(providerId, true)
        viewModelScope.launch {
            try {
                api.healthCheckProxyProvider(provider.name)
                val refreshed = api.proxyProviders().firstOrNull { it.name == provider.name }
                if (refreshed != null) replaceProvider(refreshed.toUi()) else setProviderChecking(providerId, false)
            } catch (e: MeowApiException) {
                Timber.w(e, "proxy provider health check failed: %s", provider.name)
                setProviderChecking(providerId, false)
            }
        }
    }

    fun testNode(providerId: String, nodeName: String) {
        val provider = _state.value.providers.firstOrNull { it.id == providerId } ?: return
        val node = provider.nodes.firstOrNull { it.name == nodeName } ?: return
        if (node.testing) return
        updateNode(providerId, nodeName) { it.copy(testing = true) }
        viewModelScope.launch {
            try {
                val delay = if (provider.kind == ProxyServerKind.Inline) {
                    api.testProxyDelay(
                        name = nodeName,
                        url = provider.testUrl.ifBlank { MeowApi.DELAY_TEST_URL },
                    )
                } else {
                    api.testProxyProviderDelay(
                        provider = provider.name,
                        name = nodeName,
                        url = provider.testUrl.ifBlank { MeowApi.DELAY_TEST_URL },
                        expectedStatus = provider.expectedStatus,
                    )
                }
                updateNode(providerId, nodeName) {
                    it.copy(delayMs = delay.takeIf { measured -> measured > 0 }, timedOut = delay <= 0, testing = false)
                }
            } catch (e: MeowApiException) {
                Timber.w(e, "proxy node test failed: %s/%s", provider.name, nodeName)
                updateNode(providerId, nodeName) { it.copy(testing = false, timedOut = true) }
            }
        }
    }

    private fun setProviderChecking(name: String, checking: Boolean) {
        _state.update { state ->
            state.copy(providers = state.providers.map { provider ->
                if (provider.id == name) provider.copy(checking = checking) else provider
            })
        }
    }

    private fun replaceProvider(provider: ProxyServerProviderUi) {
        _state.update { state ->
            state.copy(providers = state.providers.map { if (it.id == provider.id) provider else it })
        }
    }

    private fun updateNode(
        providerId: String,
        nodeName: String,
        transform: (ProxyServerNodeUi) -> ProxyServerNodeUi,
    ) {
        _state.update { state ->
            state.copy(providers = state.providers.map { provider ->
                if (provider.id != providerId) provider else provider.copy(
                    nodes = provider.nodes.map { node ->
                        if (node.name == nodeName) transform(node) else node
                    },
                )
            })
        }
    }
}

private suspend fun <T> read(label: String, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: MeowApiException) {
    Timber.w(e, "loading %s failed", label)
    null
}

/** Converts both API views into the cards shown by the screen. */
internal fun proxyServerProviders(
    inline: ProxiesResult?,
    providers: List<ProxyProvider>,
): List<ProxyServerProviderUi> {
    val providerCards = providers.map(ProxyProvider::toUi)
    val inlineNodes = inline?.proxies.orEmpty()
        .values
        .filterNot { it.type in BUILTIN_PROXY_TYPES }
        .sortedWith(compareBy<Proxy, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name })
        .map { proxy ->
            ProxyServerNodeUi(
                name = proxy.name,
                type = proxy.type,
                delayMs = proxy.latestDelay.takeIf { it > 0 },
                timedOut = proxy.history.isNotEmpty() && proxy.latestDelay <= 0,
            )
        }
    if (inlineNodes.isEmpty()) return providerCards
    return providerCards + ProxyServerProviderUi(
        id = INLINE_PROXIES_ID,
        name = INLINE_PROXIES_ID,
        vehicleType = "",
        testUrl = "",
        expectedStatus = "",
        nodes = inlineNodes,
        kind = ProxyServerKind.Inline,
    )
}

/** Keeps provider cards stable while filtering only their node rows. */
internal fun filterProxyServerProviders(
    providers: List<ProxyServerProviderUi>,
    query: String,
): List<ProxyServerProviderUi> {
    val needle = query.trim()
    if (needle.isEmpty()) return providers
    return providers.mapNotNull { provider ->
        val nodes = provider.nodes.filter { node ->
            node.name.contains(needle, ignoreCase = true) ||
                node.type.contains(needle, ignoreCase = true)
        }
        provider.takeIf { nodes.isNotEmpty() }?.copy(nodes = nodes)
    }
}

private fun ProxyProvider.toUi() = ProxyServerProviderUi(
    id = name,
    name = name,
    vehicleType = vehicleType,
    testUrl = testUrl,
    expectedStatus = expectedStatus,
    nodes = nodes.map(ProxyProviderNode::toUi),
)

private fun ProxyProviderNode.toUi() = ProxyServerNodeUi(
    name = name,
    type = type,
    delayMs = latestDelay,
    timedOut = timedOut,
)
