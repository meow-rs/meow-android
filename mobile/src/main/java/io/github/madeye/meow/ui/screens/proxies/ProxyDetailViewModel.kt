package io.github.madeye.meow.ui.screens.proxies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.ProxyInfo
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.net.ExitIp
import io.github.madeye.meow.net.ExitIpLookup
import io.github.madeye.meow.net.NatType
import io.github.madeye.meow.net.ProxyProbeException
import io.github.madeye.meow.net.ProxyProber
import io.github.madeye.meow.net.Throughput
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** The tests a proxy's detail page offers, in the order it lists them. */
enum class ProxyProbe { TcpLatency, UdpLatency, ExitIp, Nat, Download, Upload }

@Immutable
sealed interface ProbeResult {
    data class Latency(val ms: Long) : ProbeResult

    @Immutable
    data class Exit(val exitIp: ExitIp) : ProbeResult

    @Immutable
    data class Nat(val type: NatType) : ProbeResult

    @Immutable
    data class Bandwidth(val throughput: Throughput) : ProbeResult
}

enum class ProbeFailure {
    TimedOut,
    UdpUnsupported,
    Failed,
    ;

    companion object {
        fun of(e: IOException): ProbeFailure = when {
            e is ProxyProbeException && e.code == ProxyProbeException.TIMEOUT -> TimedOut
            e is ProxyProbeException && e.code == ProxyProbeException.UDP_UNSUPPORTED -> UdpUnsupported
            // The engine's delay test answers a timeout with a bare 504.
            e is MeowApiException.Http && e.code == 504 -> TimedOut
            else -> Failed
        }
    }
}

@Immutable
sealed interface ProbeState {
    data object Idle : ProbeState

    data object Running : ProbeState

    data class Done(val result: ProbeResult) : ProbeState

    /** [detail]: the engine's own words, for failures with no friendlier name. */
    data class Failed(val reason: ProbeFailure, val detail: String? = null) : ProbeState
}

@Immutable
data class ProxyDetailUiState(
    val name: String,
    /** Null until the engine has described the proxy. */
    val type: String? = null,
    val udp: Boolean? = null,
    /** Tests need the engine; they are offered only while connected. */
    val connected: Boolean = false,
    val probes: Map<ProxyProbe, ProbeState> = ProxyProbe.entries.associateWith { ProbeState.Idle },
) {
    fun probe(probe: ProxyProbe): ProbeState = probes[probe] ?: ProbeState.Idle
}

/**
 * One proxy's detail page: what it is, and on-demand tests through it alone.
 *
 * The TCP test is the engine's own latency test, against the URL its group
 * tests with, so it lands in the same history the group list shows. The rest
 * run inside `:vpn` through [prober]. Leaving the page, or the VPN going
 * down, ends whatever is running.
 */
class ProxyDetailViewModel(
    private val name: String,
    /** The health-check URL of the group the page was opened from, if it has one. */
    private val testUrl: String?,
    vpnState: StateFlow<BaseService.State>,
    private val describe: suspend (String) -> ProxyInfo,
    private val tcpDelay: suspend (name: String, url: String) -> Int,
    private val prober: ProxyProber,
) : ViewModel() {

    private val state = MutableStateFlow(ProxyDetailUiState(name))
    val uiState: StateFlow<ProxyDetailUiState> = state.asStateFlow()

    private val running = mutableMapOf<ProxyProbe, Job>()

    /** Download and upload take turns: run together, each would eat into the other's sample. */
    private val bandwidth = Mutex()

    init {
        viewModelScope.launch {
            vpnState.map { it == BaseService.State.Connected }
                .distinctUntilChanged()
                .collect { connected ->
                    state.update { it.copy(connected = connected) }
                    if (connected) loadDescription() else stopAll()
                }
        }
    }

    /** A tap on a test. Ignored while that test runs or the VPN is down. */
    fun test(probe: ProxyProbe) {
        if (!state.value.connected || running[probe]?.isActive == true) return
        set(probe, ProbeState.Running)
        running[probe] = viewModelScope.launch {
            val outcome = try {
                ProbeState.Done(measure(probe))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.d(e, "proxy detail: %s of %s failed", probe, name)
                failure(e)
            }
            set(probe, outcome)
        }
    }

    private suspend fun loadDescription() {
        try {
            val proxy = describe(name)
            state.update { it.copy(type = proxy.type, udp = proxy.udp) }
        } catch (e: IOException) {
            // The tests still run; the header just stays blank.
            Timber.w(e, "proxy detail: describing %s failed", name)
        }
    }

    private suspend fun measure(probe: ProxyProbe): ProbeResult = when (probe) {
        ProxyProbe.TcpLatency -> {
            val ms = tcpDelay(name, testUrl ?: MeowApi.DELAY_TEST_URL)
            if (ms <= 0) throw ProxyProbeException("no answer in time", ProxyProbeException.TIMEOUT)
            ProbeResult.Latency(ms.toLong())
        }
        ProxyProbe.UdpLatency -> ProbeResult.Latency(prober.udpLatency(name))
        ProxyProbe.ExitIp -> ProbeResult.Exit(ExitIpLookup(fetcher = prober.exitIpFetcher(name)).lookup())
        ProxyProbe.Nat -> ProbeResult.Nat(prober.natType(name))
        ProxyProbe.Download -> ProbeResult.Bandwidth(bandwidth.withLock { prober.download(name) })
        ProxyProbe.Upload -> ProbeResult.Bandwidth(bandwidth.withLock { prober.upload(name) })
    }

    private fun failure(e: IOException): ProbeState.Failed = when (val reason = ProbeFailure.of(e)) {
        ProbeFailure.Failed -> ProbeState.Failed(reason, e.message)
        else -> ProbeState.Failed(reason)
    }

    private fun stopAll() {
        running.values.forEach { it.cancel() }
        running.clear()
        state.update { current ->
            current.copy(
                probes = current.probes.mapValues { (_, probe) ->
                    if (probe == ProbeState.Running) ProbeState.Idle else probe
                },
            )
        }
    }

    private fun set(probe: ProxyProbe, value: ProbeState) {
        state.update { it.copy(probes = it.probes + (probe to value)) }
    }
}
