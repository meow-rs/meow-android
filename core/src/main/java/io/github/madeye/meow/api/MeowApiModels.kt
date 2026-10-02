package io.github.madeye.meow.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Group types as reported by the engine's `/proxies` endpoint. */
internal val GROUP_TYPES = setOf("Selector", "URLTest", "Fallback", "LoadBalance", "Relay")

/** The engine's built-in selector that global mode routes every flow through. */
internal const val GLOBAL_GROUP = "GLOBAL"

/**
 * The engine's outbound routing mode — the `mode` that `GET /configs` reports
 * and `PATCH /configs` accepts. Mirrors meow-ios's `RouteMode`.
 */
enum class RouteMode(val wire: String) {
    Rule("rule"),
    Global("global"),
    Direct("direct"),
    ;

    companion object {
        fun fromWire(value: String): RouteMode? =
            entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }

        /**
         * The top-level `mode:` of a Clash YAML profile, i.e. the mode the
         * engine starts in when the user never picked one. [Rule] (the
         * engine's own fallback) when the key is missing or unrecognised.
         *
         * A line scan rather than a YAML parse: profiles can carry thousands
         * of rules and only this one scalar is needed. Indented `mode:` keys
         * (plugin options) are not top-level and are skipped.
         */
        fun configured(yaml: String): RouteMode {
            for (raw in yaml.lineSequence()) {
                // Profiles saved by some editors start with a byte-order mark.
                val line = raw.removePrefix("\uFEFF")
                if (!line.startsWith("mode:")) continue
                val value = line.removePrefix("mode:").substringBefore('#').trim().trim('"', '\'')
                return fromWire(value) ?: Rule
            }
            return Rule
        }
    }
}

/**
 * One latency probe.
 *
 * `time` arrives in two different shapes depending on which engine serves it:
 * a plain ISO string (meow-go) or a Rust `SystemTime`
 * (`{"secs_since_epoch": N, "nanos_since_epoch": N}`). Both are normalised to
 * epoch millis here so callers never see the difference.
 */
data class ProxyHistory(
    val timeMillis: Long,
    val delay: Int,
)

data class Proxy(
    val name: String,
    val type: String,
    val history: List<ProxyHistory>,
) {
    /** Delay of the most recent probe; 0 when never tested or timed out. */
    val latestDelay: Int get() = history.lastOrNull()?.delay ?: 0
}

data class ProxyGroup(
    val name: String,
    val type: String,
    /** Currently selected member. */
    val now: String,
    val all: List<String>,
    val history: List<ProxyHistory>,
    /** Health-check URL of an url-test / fallback group; selectors report none. */
    val testUrl: String? = null,
)

/** Parsed `GET /proxies`, split into selector groups and leaf proxies. */
data class ProxiesResult(
    val groups: Map<String, ProxyGroup>,
    val proxies: Map<String, Proxy>,
    /**
     * Group names in the profile's `proxy-groups:` order. Empty when the
     * engine could not report it, which sorts every group by name.
     */
    val groupOrder: List<String> = emptyList(),
) {
    /**
     * User-selectable groups, in the order the profile declares them.
     *
     * Hides the top-level `GLOBAL` aggregator like meow-ios's
     * `ProxyGroupModel.build`. The `/proxies` map has no order of its own (Go
     * and Rust both iterate maps non-deterministically, so an unsorted list
     * reshuffles on every refresh), hence [groupOrder]: the first group of a
     * profile is usually its main selector, and that is where users look for
     * it. Groups the profile does not declare follow by name, so the list is
     * stable even when [groupOrder] is empty.
     */
    val selectableGroups: List<ProxyGroup>
        get() {
            val rank = groupOrder.distinct().withIndex().associate { (index, name) -> name to index }
            return groups.values
                .filter { it.name != GLOBAL_GROUP && it.type in GROUP_TYPES }
                .sortedWith(
                    compareBy<ProxyGroup>(
                        { rank[it.name] ?: Int.MAX_VALUE },
                        { it.name.lowercase() },
                        // Names differing only in case must not swap places between refreshes.
                        { it.name },
                    ),
                )
        }

    /**
     * The groups to list under [mode]. In global mode every flow goes
     * through `GLOBAL`, so it is listed first: picking its member is the only
     * choice that changes routing. Any other mode never consults it.
     */
    fun visibleGroups(mode: RouteMode?): List<ProxyGroup> {
        val global = groups[GLOBAL_GROUP]
        return if (mode == RouteMode.Global && global != null) {
            listOf(global) + selectableGroups
        } else {
            selectableGroups
        }
    }

    companion object {
        fun parse(root: JsonObject): ProxiesResult {
            val raw = root["proxies"]?.asObjectOrNull() ?: JsonObject(emptyMap())
            val groups = mutableMapOf<String, ProxyGroup>()
            val proxies = mutableMapOf<String, Proxy>()
            for ((name, element) in raw) {
                val data = element.asObjectOrNull() ?: continue
                val type = data.string("type")
                if (type in GROUP_TYPES) {
                    groups[name] = ProxyGroup(
                        name = name,
                        type = type,
                        now = data.string("now"),
                        all = data.stringList("all"),
                        history = data.historyList(),
                        testUrl = data.string("testUrl").ifEmpty { null },
                    )
                } else {
                    proxies[name] = Proxy(
                        name = name,
                        type = type,
                        history = data.historyList(),
                    )
                }
            }
            return ProxiesResult(groups, proxies)
        }
    }
}

@Serializable
data class Rule(
    val type: String = "",
    val payload: String = "",
    val proxy: String = "",
)

@Serializable
internal data class RulesResponse(val rules: List<Rule> = emptyList())

/**
 * One cached lookup from `GET /dns/results`: the engine's live DNS cache,
 * sorted by name. [ttl] is the seconds left before the entry expires, not the
 * record's original TTL; [fromServer] is the upstream that answered, absent for
 * entries the engine seeded itself.
 */
@Serializable
data class DnsResult(
    val name: String = "",
    val ips: List<String> = emptyList(),
    @SerialName("from_server") val fromServer: String? = null,
    val ttl: Long = 0,
)

/** One entry of `GET /api/proxy-groups`; only the name is read, for ordering. */
@Serializable
internal data class ConfiguredGroup(val name: String = "")

@Serializable
data class ConnectionMeta(
    val network: String = "",
    val type: String = "",
    val sourceIP: String = "",
    val destinationIP: String = "",
    val sourcePort: String = "",
    val destinationPort: String = "",
    val host: String = "",
    val dnsMode: String = "",
    val processName: String = "",
    val uid: Int = 0,
)

@Serializable
data class Connection(
    val id: String = "",
    val metadata: ConnectionMeta = ConnectionMeta(),
    val upload: Long = 0,
    val download: Long = 0,
    /** ISO-8601 timestamp; the UI derives elapsed time from it. */
    val start: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = "",
)

@Serializable
data class ConnectionsSnapshot(
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val connections: List<Connection> = emptyList(),
)

@Serializable
data class LogEntry(
    /** INFO | WARN | ERROR | DEBUG | SILENT */
    val type: String = "",
    val payload: String = "",
    val time: String = "",
)

@Serializable
data class RuntimeConfig(
    /** rule | global | direct */
    val mode: String = "rule",
    val ipv6: Boolean = false,
    @SerialName("allow-lan") val allowLan: Boolean = false,
    @SerialName("log-level") val logLevel: String = "info",
    @SerialName("mixed-port") val mixedPort: Int = 7890,
    @SerialName("external-controller") val externalController: String = "",
) {
    val routeMode: RouteMode? get() = RouteMode.fromWire(mode)
}

// -----------------------------------------------------------------------------
// JSON helpers — the /proxies payload is a heterogeneous map discriminated by a
// field *value* rather than a key, so it is parsed by hand rather than by
// generated serializers.
// -----------------------------------------------------------------------------

private fun kotlinx.serialization.json.JsonElement.asObjectOrNull(): JsonObject? =
    runCatching { jsonObject }.getOrNull()

private fun JsonObject.string(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()

private fun JsonObject.stringList(key: String): List<String> =
    runCatching {
        (this[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.content }
    }.getOrNull().orEmpty()

private fun JsonObject.historyList(): List<ProxyHistory> =
    runCatching {
        (this["history"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { entry -> entry.asObjectOrNull()?.toHistory() }
    }.getOrNull().orEmpty()

private fun JsonObject.toHistory(): ProxyHistory {
    val delay = runCatching { this["delay"]?.jsonPrimitive?.content?.toInt() }.getOrNull() ?: 0
    val time = this["time"]
    val millis = when {
        time == null -> 0L
        // Rust SystemTime struct.
        time.asObjectOrNull() != null -> {
            val secs = time.asObjectOrNull()
                ?.get("secs_since_epoch")?.jsonPrimitive?.longOrNull ?: 0L
            secs * 1000
        }
        // Go ISO-8601 string. Kept as 0 when unparseable; only ordering uses it.
        else -> runCatching {
            java.time.Instant.parse(time.jsonPrimitive.content).toEpochMilli()
        }.getOrDefault(0L)
    }
    return ProxyHistory(timeMillis = millis, delay = delay)
}
