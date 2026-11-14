package io.github.madeye.meow.ui.screens.providers

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.ProxyProvider
import io.github.madeye.meow.api.ProxyProviderNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

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
    val name: String,
    val vehicleType: String,
    val testUrl: String,
    val expectedStatus: String,
    val nodes: List<ProxyServerNodeUi>,
    val checking: Boolean = false,
)

@Immutable
data class ProxyServersUiState(
    val providers: List<ProxyServerProviderUi> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** Loads the engine's proxy-provider nodes for the Home auxiliary page. */
class ProxyServersViewModel(private val api: MeowApi) : ViewModel() {

    private val _state = MutableStateFlow(ProxyServersUiState())
    val uiState: StateFlow<ProxyServersUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            try {
                _state.value = ProxyServersUiState(
                    providers = api.proxyProviders().map(ProxyProvider::toUi),
                    loading = false,
                )
            } catch (e: MeowApiException) {
                Timber.w(e, "loading proxy providers failed")
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun healthCheck(providerName: String) {
        val provider = _state.value.providers.firstOrNull { it.name == providerName } ?: return
        if (provider.checking) return
        setProviderChecking(providerName, true)
        viewModelScope.launch {
            try {
                api.healthCheckProxyProvider(providerName)
                val refreshed = api.proxyProviders().firstOrNull { it.name == providerName }
                if (refreshed != null) replaceProvider(refreshed.toUi()) else setProviderChecking(providerName, false)
            } catch (e: MeowApiException) {
                Timber.w(e, "proxy provider health check failed: %s", providerName)
                setProviderChecking(providerName, false)
            }
        }
    }

    fun testNode(providerName: String, nodeName: String) {
        val provider = _state.value.providers.firstOrNull { it.name == providerName } ?: return
        val node = provider.nodes.firstOrNull { it.name == nodeName } ?: return
        if (node.testing) return
        updateNode(providerName, nodeName) { it.copy(testing = true) }
        viewModelScope.launch {
            try {
                val delay = api.testProxyProviderDelay(
                    provider = providerName,
                    name = nodeName,
                    url = provider.testUrl.ifBlank { MeowApi.DELAY_TEST_URL },
                    expectedStatus = provider.expectedStatus,
                )
                updateNode(providerName, nodeName) {
                    it.copy(delayMs = delay.takeIf { measured -> measured > 0 }, timedOut = delay <= 0, testing = false)
                }
            } catch (e: MeowApiException) {
                Timber.w(e, "proxy provider node test failed: %s/%s", providerName, nodeName)
                updateNode(providerName, nodeName) { it.copy(testing = false, timedOut = true) }
            }
        }
    }

    private fun setProviderChecking(name: String, checking: Boolean) {
        _state.update { state ->
            state.copy(providers = state.providers.map { provider ->
                if (provider.name == name) provider.copy(checking = checking) else provider
            })
        }
    }

    private fun replaceProvider(provider: ProxyServerProviderUi) {
        _state.update { state ->
            state.copy(providers = state.providers.map { if (it.name == provider.name) provider else it })
        }
    }

    private fun updateNode(
        providerName: String,
        nodeName: String,
        transform: (ProxyServerNodeUi) -> ProxyServerNodeUi,
    ) {
        _state.update { state ->
            state.copy(providers = state.providers.map { provider ->
                if (provider.name != providerName) provider else provider.copy(
                    nodes = provider.nodes.map { node ->
                        if (node.name == nodeName) transform(node) else node
                    },
                )
            })
        }
    }
}

private fun ProxyProvider.toUi() = ProxyServerProviderUi(
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
