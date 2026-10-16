package io.github.madeye.meow.ui.screens.connections

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.madeye.meow.api.Connection
import io.github.madeye.meow.api.DnsResult
import io.github.madeye.meow.api.MeowApiException
import io.github.madeye.meow.api.ProxiesResult
import io.github.madeye.meow.api.Rule
import io.github.madeye.meow.net.IpGeo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The engine's names for a route no listed rule chose. */
enum class UnlistedRoute(val wire: String) {
    /** No rule matched, so the flow went DIRECT. */
    Final("Final"),

    /** Global route mode: everything through the GLOBAL group. */
    Global("Global"),

    /** Direct route mode. */
    Direct("Direct"),
    ;

    companion object {
        fun of(connection: Connection): UnlistedRoute? = entries.firstOrNull { it.wire == connection.rule }
    }
}

/** The far end of a connection, as far as this device can tell. */
sealed interface RemoteIp {
    /**
     * [ip] is where the flow went: the engine's destination IP, or, when the
     * engine dialed a host name DIRECT without reporting one ([fromDnsCache]),
     * what its DNS cache holds for the name. [geo] arrives after [ip].
     */
    data class Known(val ip: String, val fromDnsCache: Boolean = false, val geo: IpGeo? = null) : RemoteIp

    /** The proxy server resolves the host name; this device never learns the address. */
    data object ResolvedByProxy : RemoteIp
}

@Immutable
data class ConnectionDetailUiState(
    /** Null once the connection is in neither list, e.g. after closed ones were cleared. */
    val connection: Connection? = null,
    /** When a poll last listed it, once it has closed; null while it is open. */
    val lastSeenAtMillis: Long? = null,
    /** The matched rule's place in the engine's rule list (0-based), once found. */
    val ruleIndex: Int? = null,
    /** Null until worked out, and when there is nothing to say (no host, rejected…). */
    val remote: RemoteIp? = null,
) {
    val active: Boolean get() = connection != null && lastSeenAtMillis == null
}

/**
 * One connection's detail page: where it went, and which rule sent it there.
 *
 * Reads the same process-scoped [store] as the list, polling it while shown,
 * so a connection that closes under the page turns into its closed row.
 */
class ConnectionDetailViewModel(
    private val id: String,
    private val store: RecentConnectionsStore,
    polling: Flow<Unit>,
    private val rules: suspend () -> List<Rule>,
    private val closeConnection: suspend (String) -> Unit,
    private val proxies: suspend () -> ProxiesResult,
    /** The engine's DNS cache entries matching a host name. */
    private val dnsCache: suspend (host: String) -> List<DnsResult>,
    private val ipGeo: suspend (ip: String) -> IpGeo,
) : ViewModel() {

    private val ruleIndex = MutableStateFlow<Int?>(null)
    private val remote = MutableStateFlow<RemoteIp?>(null)

    val uiState: StateFlow<ConnectionDetailUiState> =
        combine(store.lists, ruleIndex, remote, polling) { lists, index, remoteIp, _ ->
            lists.detail(index).copy(remote = remoteIp)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.lists.value.detail(null))

    init {
        // Both once: the rule and the address were fixed when the flow
        // opened, and later lookups can only be staler, not more right.
        viewModelScope.launch {
            val connection = firstSeen()
            if (UnlistedRoute.of(connection) != null || connection.rule.isEmpty()) return@launch
            ruleIndex.value = try {
                rules().indexOfMatch(connection)
            } catch (e: MeowApiException) {
                null
            }
        }
        viewModelScope.launch {
            val known = remoteIpOf(firstSeen(), proxies, dnsCache)
            remote.value = known
            if (known is RemoteIp.Known) remote.value = known.copy(geo = ipGeo(known.ip))
        }
    }

    private suspend fun firstSeen(): Connection = store.lists.mapNotNull { it.detail(null).connection }.first()

    fun close() {
        viewModelScope.launch {
            store.closeLocal(id)
            runCatching { closeConnection(id) }
        }
    }

    private fun ConnectionLists.detail(index: Int?): ConnectionDetailUiState {
        active.firstOrNull { it.id == id }?.let { return ConnectionDetailUiState(it, null, index) }
        recent.firstOrNull { it.connection.id == id }?.let {
            return ConnectionDetailUiState(it.connection, it.lastSeenAtMillis, index)
        }
        return ConnectionDetailUiState(ruleIndex = index)
    }
}

/**
 * Where [connection]'s rule sits in the engine's rule list: the first rule of
 * its type and payload that targets its policy, as matching runs top-down.
 * Null when there is none, e.g. after a config reload.
 */
internal fun List<Rule>.indexOfMatch(connection: Connection): Int? {
    val policy = connection.chains.lastOrNull() ?: return null
    return indexOfFirst {
        it.type == connection.rule && it.payload == connection.rulePayload && it.proxy == policy
    }.takeIf { it >= 0 }
}

/**
 * Where [connection] went, when the engine did not report the address: a host
 * name dialed DIRECT was resolved by the engine, so its DNS cache still holds
 * the answer while the record lives; any other policy hands the name to a
 * proxy server. Null when there is nothing to say, e.g. the flow was rejected
 * or the engine is gone.
 */
internal suspend fun remoteIpOf(
    connection: Connection,
    proxies: suspend () -> ProxiesResult,
    dnsCache: suspend (host: String) -> List<DnsResult>,
): RemoteIp? {
    val meta = connection.metadata
    if (meta.destinationIP.isNotEmpty()) return RemoteIp.Known(meta.destinationIP)
    val host = meta.host.ifEmpty { return null }
    val policy = connection.chains.lastOrNull() ?: return null
    return try {
        when (proxies().leafType(policy)) {
            null, "Reject", "RejectDrop" -> null
            "Direct" -> dnsCache(host).ipOf(host)?.let { RemoteIp.Known(it, fromDnsCache = true) }
            else -> RemoteIp.ResolvedByProxy
        }
    } catch (e: MeowApiException) {
        null
    }
}

/**
 * The type of the proxy [name] currently routes through: a group's selection,
 * followed down to a leaf. Null when [name] is unknown or the groups loop.
 */
internal fun ProxiesResult.leafType(name: String): String? {
    var current = name
    repeat(MAX_GROUP_DEPTH) {
        val group = groups[current] ?: return proxies[current]?.type
        // A group without a single pick (load-balance) stands for its members.
        current = group.now.ifEmpty { return group.type }
    }
    return null
}

private const val MAX_GROUP_DEPTH = 16

/** The first address the cache holds for exactly [host]; the search also matches substrings. */
internal fun List<DnsResult>.ipOf(host: String): String? =
    firstOrNull { it.name.trimEnd('.').equals(host.trimEnd('.'), ignoreCase = true) && it.ips.isNotEmpty() }
        ?.ips?.first()
