package io.github.madeye.meow.ui.screens.providers

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import io.github.madeye.meow.repo.ConfigValidator
import io.github.madeye.meow.repo.ProfileRepository
import io.github.madeye.meow.ui.nav.Dest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The editor uses a protocol-specific control for each kind of value. */
enum class ProxyEditFieldKind { Text, Number, Boolean, List, Select }

@Immutable
data class ProxyEditField(
    val key: String,
    val value: String,
    val kind: ProxyEditFieldKind = ProxyEditFieldKind.Text,
    val label: String = key,
    val section: String = "Connection",
    val options: List<String> = emptyList(),
    /** Whether the key existed in the original YAML. Used to add optional keys only on edit. */
    val present: Boolean = true,
    val sensitive: Boolean = false,
)

@Immutable
data class ProxyEditUiState(
    val name: String,
    val type: String = "",
    val fields: List<ProxyEditField> = emptyList(),
    val initialFields: List<ProxyEditField> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val dirty: Boolean get() = fields != initialFields
}

/** Edits one proxy in a local profile. Subscription profiles remain read-only. */
class ProxyEditViewModel(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val validator: ConfigValidator,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Dest.ProxyEdit>()
    private val _state = MutableStateFlow(ProxyEditUiState(name = route.name))
    val uiState: StateFlow<ProxyEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = profiles.getById(route.profileId)
            val parsed = profile?.takeIf { it.url.isBlank() }?.let { parseProxyEntry(it.yamlContent, route.name) }
            if (parsed == null) {
                _state.update { it.copy(loading = false, error = "Proxy entry is unavailable") }
            } else {
                _state.value = ProxyEditUiState(
                    name = route.name,
                    type = parsed.type,
                    fields = parsed.fields,
                    initialFields = parsed.fields,
                    loading = false,
                )
            }
        }
    }

    fun update(key: String, value: String) {
        _state.update { state ->
            state.copy(
                fields = state.fields.map { field ->
                    if (field.key == key) field.copy(value = value, present = true) else field
                },
                error = null,
            )
        }
    }

    fun save(onDone: () -> Unit) {
        val current = _state.value
        if (current.loading || current.saving || !current.dirty) {
            if (!current.loading && !current.saving && !current.dirty) onDone()
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val profile = profiles.getById(route.profileId)
            if (profile == null || profile.url.isNotBlank()) {
                _state.update { it.copy(saving = false, error = "Only local profiles can be edited") }
                return@launch
            }
            val fields = _state.value.fields
            val updated = replaceProxyEntry(
                yaml = profile.yamlContent,
                name = route.name,
                fields = fields.associate { it.key to it.value },
                kinds = fields.associate { it.key to it.kind },
                presence = fields.associate { it.key to it.present },
            )
            if (updated == null) {
                _state.update { it.copy(saving = false, error = "Proxy entry is unavailable") }
                return@launch
            }
            val validationError = validator.validate(updated)
            if (validationError != null) {
                _state.update { it.copy(saving = false, error = validationError) }
                return@launch
            }
            profiles.updateYaml(route.profileId, updated)
            _state.update { it.copy(saving = false, initialFields = it.fields) }
            onDone()
        }
    }
}

private data class ProxyFieldSpec(
    val path: String,
    val label: String,
    val kind: ProxyEditFieldKind,
    val section: String,
    val defaultValue: String = "",
    val options: List<String> = emptyList(),
    val sensitive: Boolean = false,
)

private fun text(path: String, label: String, section: String, sensitive: Boolean = false) =
    ProxyFieldSpec(path, label, ProxyEditFieldKind.Text, section, sensitive = sensitive)

private fun number(path: String, label: String, section: String) =
    ProxyFieldSpec(path, label, ProxyEditFieldKind.Number, section)

private fun boolean(path: String, label: String, section: String, default: Boolean = false) =
    ProxyFieldSpec(path, label, ProxyEditFieldKind.Boolean, section, defaultValue = default.toString())

private fun list(path: String, label: String, section: String) =
    ProxyFieldSpec(path, label, ProxyEditFieldKind.List, section)

private fun select(path: String, label: String, section: String, options: List<String>, default: String = "") =
    ProxyFieldSpec(path, label, ProxyEditFieldKind.Select, section, defaultValue = default, options = options)

private fun muxSpecs(root: String) = listOf(
    boolean("$root.enabled", "Multiplexing", "Multiplexing"),
    select("$root.protocol", "Protocol", "Multiplexing", listOf("", "smux", "yamux", "h2mux", "muxcool"), "h2mux"),
    boolean("$root.padding", "Padding", "Multiplexing"),
    number("$root.max-connections", "Maximum connections", "Multiplexing"),
    number("$root.min-streams", "Minimum streams", "Multiplexing"),
    number("$root.max-streams", "Maximum streams", "Multiplexing"),
    boolean("$root.only-tcp", "TCP only", "Multiplexing"),
)

private fun pluginOptsSpecs(plugin: String?): List<ProxyFieldSpec> = when (plugin.orEmpty().lowercase()) {
    "obfs", "obfs-local", "simple-obfs" -> listOf(
        select("plugin-opts.mode", "Obfuscation mode", "Plugin options", listOf("", "http", "tls")),
        text("plugin-opts.host", "Obfuscation host", "Plugin options"),
        number("plugin-opts.port", "Obfuscation port", "Plugin options"),
    )
    "v2ray-plugin" -> listOf(
        select("plugin-opts.mode", "Mode", "Plugin options", listOf("", "websocket", "quic", "grpc")),
        text("plugin-opts.host", "Host", "Plugin options"),
        text("plugin-opts.path", "Path", "Plugin options"),
        boolean("plugin-opts.tls", "TLS", "Plugin options"),
        boolean("plugin-opts.mux", "Mux", "Plugin options"),
        text("plugin-opts.headers.Host", "Host header", "Plugin options"),
    )
    "kcptun" -> listOf(
        select("plugin-opts.crypt", "Encryption", "Plugin options", listOf("", "aes", "aes-128", "aes-192", "aes-256", "salsa20", "blowfish", "none")),
        select("plugin-opts.mode", "Mode", "Plugin options", listOf("", "fast", "fast2", "fast3", "normal")),
        number("plugin-opts.mtu", "MTU", "Plugin options"),
        number("plugin-opts.sndwnd", "Send window", "Plugin options"),
        number("plugin-opts.rcvwnd", "Receive window", "Plugin options"),
        number("plugin-opts.datashard", "Data shard", "Plugin options"),
        number("plugin-opts.parityshard", "Parity shard", "Plugin options"),
        text("plugin-opts.key", "Key", "Plugin options", sensitive = true),
        number("plugin-opts.smuxver", "SMux version", "Plugin options"),
    )
    "shadow-tls" -> listOf(
        text("plugin-opts.host", "Host", "Plugin options"),
        text("plugin-opts.password", "Password", "Plugin options", sensitive = true),
        select("plugin-opts.version", "Version", "Plugin options", listOf("", "1", "2", "3")),
        boolean("plugin-opts.strict", "Strict mode", "Plugin options"),
    )
    "restls", "jls" -> listOf(
        text("plugin-opts.host", "Host", "Plugin options"),
        text("plugin-opts.password", "Password", "Plugin options", sensitive = true),
        select("plugin-opts.version", "Version", "Plugin options", listOf("", "1", "2", "3")),
        list("plugin-opts.alpn", "ALPN (comma separated)", "Plugin options"),
    )
    "gost-plugin" -> listOf(
        select("plugin-opts.mode", "Mode", "Plugin options", listOf("", "websocket", "quic", "grpc")),
        text("plugin-opts.host", "Host", "Plugin options"),
        text("plugin-opts.path", "Path", "Plugin options"),
        boolean("plugin-opts.tls", "TLS", "Plugin options"),
        boolean("plugin-opts.mux", "Mux", "Plugin options"),
        text("plugin-opts.servername", "Server name", "Plugin options"),
    )
    else -> emptyList()
}

private fun endpointSpecs(withDialer: Boolean = true, udpDefault: Boolean = false): List<ProxyFieldSpec> = buildList {
    add(text("server", "Server", "Endpoint"))
    add(number("port", "Port", "Endpoint"))
    add(boolean("udp", "UDP relay", "Transport", default = udpDefault))
    if (withDialer) add(text("dialer-proxy", "Dialer proxy", "Transport"))
}

/** Every protocol has its own option set, while unknown keys remain in Advanced. */
private fun proxyFieldSpecs(type: String, existing: Map<String, ParsedValue>): List<ProxyFieldSpec> {
    val muxRoot = when {
        existing.keys.any { it == "smux" || it.startsWith("smux.") } -> "smux"
        existing.keys.any { it == "mux" || it.startsWith("mux.") } -> "mux"
        else -> "smux"
    }
    val plugin = existing["plugin"]?.value
    val hasRawPluginOpts = existing.containsKey("plugin-opts")
    val hasNestedPluginOpts = existing.keys.any { it.startsWith("plugin-opts.") }
    val pluginFields = if (hasNestedPluginOpts || !hasRawPluginOpts) {
        pluginOptsSpecs(plugin)
    } else {
        emptyList()
    }
    val rawPluginField = if (hasNestedPluginOpts) emptyList() else {
        listOf(text("plugin-opts", "Plugin options (key=value;...)", "Transport"))
    }
    return when (type.lowercase()) {
    "direct" -> listOf(
        number("connect-timeout", "Connect timeout (seconds)", "Direct"),
        list("dns", "DNS servers (comma separated)", "Direct"),
    )
    "ss" -> endpointSpecs() + listOf(
        text("cipher", "Cipher", "Authentication"),
        text("password", "Password", "Authentication", sensitive = true),
        select("plugin", "Plugin", "Transport", listOf("", "obfs", "obfs-local", "simple-obfs", "v2ray-plugin", "kcptun", "shadow-tls", "restls", "gost-plugin", "jls")),
        text("client-fingerprint", "Client fingerprint", "TLS"),
    ) + rawPluginField + pluginFields + muxSpecs(muxRoot)
    "trojan" -> endpointSpecs() + listOf(
        text("password", "Password", "Authentication", sensitive = true),
        text("sni", "SNI", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
        list("alpn", "ALPN (comma separated)", "TLS"),
    ) + muxSpecs(muxRoot)
    "vless" -> endpointSpecs() + listOf(
        text("uuid", "UUID", "Authentication", sensitive = true),
        select("encryption", "Encryption", "Authentication", listOf("none", "mlkem768x25519plus"), "none"),
        select("flow", "Flow", "Transport", listOf("", "xtls-rprx-vision")),
        select("network", "Network", "Transport", listOf("tcp", "ws", "grpc", "h2", "httpupgrade", "xhttp"), "tcp"),
        boolean("tls", "TLS", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
        text("servername", "Server name (SNI)", "TLS"),
        text("client-fingerprint", "Client fingerprint", "TLS"),
        list("alpn", "ALPN (comma separated)", "TLS"),
        text("reality-opts.public-key", "REALITY public key", "REALITY"),
        text("reality-opts.short-id", "REALITY short ID", "REALITY"),
        boolean("reality-opts.support-x25519mlkem768", "REALITY X25519ML-KEM", "REALITY"),
        text("ech-opts.config", "ECH config (base64)", "TLS"),
        boolean("ech-opts.enable", "ECH", "TLS"),
        text("ws-opts.path", "WebSocket path", "WebSocket"),
        text("ws-opts.headers.Host", "WebSocket Host", "WebSocket"),
        number("ws-opts.max-early-data", "WebSocket early data", "WebSocket"),
        text("ws-opts.early-data-header-name", "Early data header", "WebSocket"),
        text("grpc-opts.grpc-service-name", "gRPC service", "gRPC"),
        text("h2-opts.host", "HTTP/2 hosts (comma separated)", "HTTP/2"),
        text("h2-opts.path", "HTTP/2 path", "HTTP/2"),
        text("http-upgrade-opts.host", "HTTP Upgrade Host", "HTTP Upgrade"),
        text("http-upgrade-opts.path", "HTTP Upgrade path", "HTTP Upgrade"),
        text("xhttp-opts.host", "XHTTP hosts (comma separated)", "XHTTP"),
        text("xhttp-opts.path", "XHTTP path", "XHTTP"),
        select("xhttp-opts.mode", "XHTTP mode", "XHTTP", listOf("stream-one"), "stream-one"),
        boolean("xhttp-opts.no-grpc-header", "XHTTP without gRPC header", "XHTTP"),
        text("xhttp-opts.x-padding-bytes", "XHTTP padding range", "XHTTP"),
    ) + muxSpecs(muxRoot)
    "vmess" -> endpointSpecs() + listOf(
        text("uuid", "UUID", "Authentication", sensitive = true),
        select("cipher", "Cipher", "Authentication", listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none"), "auto"),
        number("alterId", "Alter ID", "Authentication"),
        select("network", "Network", "Transport", listOf("tcp", "ws"), "tcp"),
        boolean("tls", "TLS", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
        text("servername", "Server name (SNI)", "TLS"),
        text("client-fingerprint", "Client fingerprint", "TLS"),
        list("alpn", "ALPN (comma separated)", "TLS"),
        text("ws-opts.path", "WebSocket path", "WebSocket"),
        text("ws-opts.headers.Host", "WebSocket Host", "WebSocket"),
        number("ws-opts.max-early-data", "WebSocket early data", "WebSocket"),
        text("ws-opts.early-data-header-name", "Early data header", "WebSocket"),
    ) + muxSpecs(muxRoot)
    "hysteria2" -> endpointSpecs(withDialer = false, udpDefault = true) + listOf(
        text("ports", "Port hopping range", "Endpoint"),
        text("password", "Password", "Authentication", sensitive = true),
        text("up", "Upload limit", "QUIC"),
        text("down", "Download limit", "QUIC"),
        text("sni", "SNI", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
        text("fingerprint", "Certificate fingerprint", "TLS"),
        select("obfs", "Obfuscation", "QUIC", listOf("", "salamander", "gecko")),
        text("obfs-password", "Obfuscation password", "QUIC", sensitive = true),
        number("obfs-min-packet-size", "Obfuscation minimum packet", "QUIC"),
        number("obfs-max-packet-size", "Obfuscation maximum packet", "QUIC"),
        text("hop-interval", "Hop interval", "QUIC"),
        boolean("fast-open", "Fast open", "QUIC", default = true),
        list("alpn", "ALPN (comma separated)", "TLS"),
    )
    "anytls" -> endpointSpecs(withDialer = false) + listOf(
        text("password", "Password", "Authentication", sensitive = true),
        text("sni", "SNI", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
    )
    "snell" -> endpointSpecs() + listOf(
        text("psk", "PSK", "Authentication", sensitive = true),
        select("version", "Version", "Authentication", listOf("3", "4", "5", "6"), "4"),
        boolean("reuse", "Connection reuse", "Transport"),
        select("mode", "v6 mode", "Transport", listOf("", "default", "unshaped", "unsafe-raw")),
        select("obfs-opts.mode", "Obfuscation mode", "Obfuscation", listOf("off", "http", "tls"), "off"),
        text("obfs-opts.host", "Obfuscation host", "Obfuscation"),
    )
    "http" -> endpointSpecs() + listOf(
        text("username", "Username", "Authentication"),
        text("password", "Password", "Authentication", sensitive = true),
        boolean("tls", "TLS", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
    )
    "socks5" -> endpointSpecs() + listOf(
        text("username", "Username", "Authentication"),
        text("password", "Password", "Authentication", sensitive = true),
        boolean("tls", "TLS", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
    )
    "trusttunnel" -> endpointSpecs(withDialer = false) + listOf(
        text("username", "Username", "Authentication"),
        text("password", "Password", "Authentication", sensitive = true),
        text("sni", "SNI", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
        text("name-cert-verify", "Certificate name", "TLS"),
        text("client-fingerprint", "Client fingerprint", "TLS"),
        boolean("quic", "HTTP/3 (QUIC)", "Transport"),
        list("alpn", "ALPN (comma separated)", "TLS"),
        number("max-connections", "Maximum connections", "Transport"),
        number("min-streams", "Minimum streams", "Transport"),
        number("max-streams", "Maximum streams", "Transport"),
        text("platform", "Platform", "Headers"),
        text("app-name", "App name", "Headers"),
        boolean("health-check", "Health check", "Transport"),
    )
    else -> endpointSpecs() // Keep a useful editor for a newer engine type.
    }
}

private data class ParsedValue(val path: String, val value: String, val kind: ProxyEditFieldKind)

internal data class ParsedProxyEntry(val type: String, val fields: List<ProxyEditField>)

private data class ProxyLocation(val start: Int, val end: Int, val itemIndent: Int, val flow: Boolean)

private data class MappingLine(val indent: String, val key: String, val raw: String)

private val proxyListPattern = Regex("^(\\s*)proxies\\s*:")
private val mappingPattern = Regex("^(\\s*)([^:#][^:]*?)\\s*:\\s*(.*)?$")

/** Finds an entry under the top-level `proxies:` list, independent of indentation. */
private fun findProxyLocation(lines: List<String>, name: String): ProxyLocation? {
    var inProxies = false
    var proxiesIndent = -1
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (!inProxies) {
            val match = proxyListPattern.matchEntire(line.substringBefore('#').trimEnd())
            if (match != null && !line.trimStart().startsWith("-")) {
                inProxies = true
                proxiesIndent = match.groupValues[1].length
            }
            index++
            continue
        }
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && leadingSpaces(line) <= proxiesIndent) {
            inProxies = false
            continue
        }
        val itemIndent = leadingSpaces(line)
        if (itemIndent > proxiesIndent && trimmed.startsWith("-")) {
            val candidate = proxyNameFromItem(line)
            val end = (index + 1 until lines.size).firstOrNull { next ->
                val nextLine = lines[next]
                val nextTrimmed = nextLine.trim()
                nextTrimmed.isNotEmpty() && !nextTrimmed.startsWith("#") &&
                    (leadingSpaces(nextLine) < itemIndent ||
                        (leadingSpaces(nextLine) == itemIndent && nextTrimmed.startsWith("-")))
            } ?: lines.size
            if (candidate == name) {
                return ProxyLocation(index, end, itemIndent, line.trimStart().startsWith("- {"))
            }
            index = end
            continue
        }
        index++
    }
    return null
}

private fun proxyNameFromItem(line: String): String? {
    val body = line.substringAfter('-', "").trimStart()
    if (body.startsWith("{")) {
        return parseFlowValues(body).firstOrNull { it.first == "name" }?.second?.let(::yamlScalar)
    }
    val match = Regex("^name\\s*:\\s*(.*?)\\s*$").matchEntire(body) ?: return null
    return yamlScalar(match.groupValues[1])
}

private fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.length

/** Parses a protocol entry, including nested transport/TLS maps and list values. */
internal fun parseProxyEntry(yaml: String, name: String): ParsedProxyEntry? {
    val lines = yaml.replace("\r\n", "\n").split('\n')
    val location = findProxyLocation(lines, name) ?: return null
    val values = if (location.flow) {
        parseFlowValues(lines[location.start])
            .map { ParsedValue(it.first, yamlScalar(it.second), inferFieldKind(it.first, it.second)) }
    } else {
        parseBlockValues(lines, location.start + 1, location.end)
    }
    val type = values.firstOrNull { it.path == "type" }?.value.orEmpty()
    val byPath = values.associateBy { it.path }
    val specs = proxyFieldSpecs(type, byPath)
    val specPaths = specs.map { it.path }.toSet()
    val fields = buildList {
        specs.forEach { spec ->
            val parsed = byPath[spec.path]
            add(
                ProxyEditField(
                    key = spec.path,
                    value = normalizeFieldValue(parsed?.value ?: spec.defaultValue, spec.kind),
                    kind = spec.kind,
                    label = spec.label,
                    section = spec.section,
                    options = spec.options,
                    present = parsed != null,
                    sensitive = spec.sensitive,
                ),
            )
        }
        values.forEach { parsed ->
            if (parsed.path !in specPaths && parsed.path != "name" && parsed.path != "type") {
                add(
                    ProxyEditField(
                        key = parsed.path,
                        value = normalizeFieldValue(parsed.value, parsed.kind),
                        kind = parsed.kind,
                        label = parsed.path,
                        section = "Advanced",
                        present = true,
                    ),
                )
            }
        }
    }
    return ParsedProxyEntry(type, fields)
}

private fun parseBlockValues(lines: List<String>, from: Int, until: Int): List<ParsedValue> {
    val values = mutableListOf<ParsedValue>()
    val stack = mutableListOf<Pair<Int, String>>()
    var index = from
    while (index < until) {
        val mapping = parseMappingLine(lines[index])
        if (mapping == null || lines[index].trimStart().startsWith("-")) {
            index++
            continue
        }
        val indent = mapping.indent.length
        while (stack.lastOrNull()?.first ?: -1 >= indent) stack.removeLast()
        val path = (stack.map { it.second } + mapping.key).joinToString(".")
        if (mapping.raw.isEmpty()) {
            val list = mutableListOf<String>()
            var next = index + 1
            while (next < until) {
                val item = lines[next]
                val itemTrimmed = item.trimStart()
                if (itemTrimmed.startsWith("-") && leadingSpaces(item) > indent) {
                    list += yamlScalar(itemTrimmed.substringAfter('-', "").trim())
                    next++
                } else {
                    break
                }
            }
            if (list.isNotEmpty()) {
                values += ParsedValue(path, list.joinToString(", "), ProxyEditFieldKind.List)
                index = next
                continue
            }
            stack += indent to mapping.key
        } else if (isInlineMap(mapping.raw)) {
            values += flattenInlineMap(path, mapping.raw)
        } else {
            values += ParsedValue(path, yamlScalar(mapping.raw), inferFieldKind(path, mapping.raw))
        }
        index++
    }
    return values
}

private fun parseMappingLine(line: String): MappingLine? {
    if (line.trim().isEmpty() || line.trimStart().startsWith("#") || line.trimStart().startsWith("-")) return null
    val match = mappingPattern.matchEntire(line) ?: return null
    val key = match.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
    return MappingLine(match.groupValues[1], key, stripYamlComment(match.groupValues.getOrNull(3).orEmpty()).trim())
}

private fun isInlineMap(value: String): Boolean = value.trim().let { it.startsWith("{") && it.endsWith("}") }

private fun flattenInlineMap(prefix: String, raw: String): List<ParsedValue> =
    splitFlowPairs(raw.trim().removePrefix("{").removeSuffix("}")).flatMap { (key, value) ->
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        if (isInlineMap(value)) flattenInlineMap(path, value)
        else listOf(ParsedValue(path, yamlScalar(value), inferFieldKind(path, value)))
    }

private fun parseFlowValues(line: String): List<Pair<String, String>> {
    val open = line.indexOf('{')
    val close = line.lastIndexOf('}')
    if (open < 0 || close <= open) return emptyList()
    return flattenInlinePairs("", line.substring(open + 1, close))
}

private fun flattenInlinePairs(prefix: String, body: String): List<Pair<String, String>> =
    splitFlowPairs(body).flatMap { (key, value) ->
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        if (isInlineMap(value)) flattenInlinePairs(path, value.removePrefix("{").removeSuffix("}"))
        else listOf(path to value)
    }

private fun splitFlowPairs(body: String): List<Pair<String, String>> = splitYamlFlow(body).mapNotNull { part ->
    val colon = findFlowColon(part)
    if (colon <= 0) null else {
        val key = part.substring(0, colon).trim().removeSurrounding("\"").removeSurrounding("'")
        key to part.substring(colon + 1).trim()
    }
}

private fun findFlowColon(part: String): Int {
    var quote: Char? = null
    var depth = 0
    part.forEachIndexed { index, char ->
        if (quote != null) {
            if (char == quote && (index == 0 || part[index - 1] != '\\')) quote = null
        } else {
            when (char) {
                '\'', '"' -> quote = char
                '{', '[' -> depth++
                '}', ']' -> depth--
                ':' -> if (depth == 0) return index
            }
        }
    }
    return -1
}

private fun splitYamlFlow(value: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var depth = 0
    var quote: Char? = null
    value.forEachIndexed { index, char ->
        if (quote != null) {
            if (char == quote && (index == 0 || value[index - 1] != '\\')) quote = null
        } else {
            when (char) {
                '\'', '"' -> quote = char
                '{', '[' -> depth++
                '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    result += value.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
    }
    result += value.substring(start).trim()
    return result.filter(String::isNotEmpty)
}

private fun inferFieldKind(path: String, raw: String): ProxyEditFieldKind = when {
    path.substringAfterLast('.') in setOf("enabled", "enable", "udp", "tls", "quic", "reuse", "fast-open", "skip-cert-verify", "padding", "only-tcp", "no-grpc-header", "health-check", "support-x25519mlkem768") -> ProxyEditFieldKind.Boolean
    raw.equals("true", true) || raw.equals("false", true) -> ProxyEditFieldKind.Boolean
    path.substringAfterLast('.') in setOf("port", "alterId", "version", "connect-timeout", "max-connections", "min-streams", "max-streams", "obfs-min-packet-size", "obfs-max-packet-size", "max-early-data") -> ProxyEditFieldKind.Number
    raw.trim().startsWith("[") && raw.trim().endsWith("]") -> ProxyEditFieldKind.List
    else -> ProxyEditFieldKind.Text
}

private fun normalizeFieldValue(value: String, kind: ProxyEditFieldKind): String = when (kind) {
    ProxyEditFieldKind.List -> value.trim().removePrefix("[").removeSuffix("]").let { body ->
        splitYamlFlow(body).joinToString(", ") { yamlScalar(it) }
    }
    else -> value
}

/** Replaces existing paths and adds only optional fields the user touched. */
internal fun replaceProxyEntry(
    yaml: String,
    name: String,
    fields: Map<String, String>,
    kinds: Map<String, ProxyEditFieldKind> = emptyMap(),
    presence: Map<String, Boolean> = emptyMap(),
): String? {
    val normalized = yaml.replace("\r\n", "\n")
    val lines = normalized.split('\n').toMutableList()
    val location = findProxyLocation(lines, name) ?: return null
    if (location.flow) return replaceFlowEntry(lines, location.start, fields, kinds, presence)

    val existing = parseBlockValues(lines, location.start + 1, location.end).associateBy { it.path }
    val occurrences = findBlockOccurrences(lines, location.start + 1, location.end)
    val inlineUpdates = mutableMapOf<Int, MutableMap<String, Pair<String, ProxyEditFieldKind>>>()
    fields.forEach { (path, value) ->
        val occurrence = occurrences[path]
        val shouldWrite = presence[path] ?: true
        if (occurrence == null || !shouldWrite) return@forEach
        val kind = kinds[path] ?: existing[path]?.kind ?: inferFieldKind(path, value)
        if (occurrence.inlineRoot != null) {
            val relative = path.removePrefix(occurrence.inlineRoot + ".")
            inlineUpdates.getOrPut(occurrence.index) { mutableMapOf() }[relative] = value to kind
        } else {
            lines[occurrence.index] = "${occurrence.indent}${occurrence.key}: ${yamlRenderValue(value, kind)}"
        }
    }
    inlineUpdates.forEach { (index, updates) ->
        val mapping = parseMappingLine(lines[index]) ?: return@forEach
        lines[index] = "${mapping.indent}${mapping.key}: ${renderInlineMap(mapping.raw, updates)}"
    }

    val missing = fields.filter { (path, value) ->
        path !in occurrences && (presence[path] ?: true) && value.isNotEmpty() && path !in setOf("name", "type")
    }
    val additions = mutableListOf<String>()
    val nested = missing.filterKeys { it.contains('.') }
    missing.filterKeys { !it.contains('.') }.forEach { (path, value) ->
        additions += "${fieldIndent(lines, location)}$path: ${yamlRenderValue(value, kinds[path] ?: inferFieldKind(path, value))}"
    }
    nested.entries.groupBy { it.key.substringBefore('.') }.forEach { (parent, entries) ->
        val parentOccurrence = occurrences[parent]
        if (parentOccurrence?.inlineRoot != null) {
            val updates = entries.associate { it.key.substringAfter('.') to (it.value to (kinds[it.key] ?: inferFieldKind(it.key, it.value))) }
            val mapping = parseMappingLine(lines[parentOccurrence.index]) ?: return@forEach
            lines[parentOccurrence.index] = "${mapping.indent}${mapping.key}: ${renderInlineMap(mapping.raw, updates)}"
        } else if (parentOccurrence != null) {
            val parentIndent = parentOccurrence.indent.length
            val insertAt = (parentOccurrence.index + 1 until location.end).firstOrNull { index ->
                val line = lines[index]
                line.isNotBlank() && leadingSpaces(line) <= parentIndent
            } ?: location.end
            val rendered = renderNestedBlock(
                entries.map { (path, value) ->
                    path.substringAfter('.') to (value to (kinds[path] ?: inferFieldKind(path, value)))
                },
                " ".repeat(parentIndent + 2),
            )
            lines.addAll(insertAt, rendered)
        } else {
            val baseIndent = fieldIndent(lines, location)
            additions += "$baseIndent$parent:"
            additions += renderNestedBlock(
                entries.map { (path, value) ->
                    path.substringAfter('.') to (value to (kinds[path] ?: inferFieldKind(path, value)))
                },
                "$baseIndent  ",
            )
        }
    }
    if (additions.isNotEmpty()) lines.addAll(location.end, additions)
    return lines.joinToString("\n")
}

private data class BlockOccurrence(
    val index: Int,
    val indent: String,
    val key: String,
    val inlineRoot: String? = null,
)

private fun findBlockOccurrences(lines: List<String>, from: Int, until: Int): Map<String, BlockOccurrence> {
    val result = mutableMapOf<String, BlockOccurrence>()
    val stack = mutableListOf<Pair<Int, String>>()
    for (index in from until until) {
        val mapping = parseMappingLine(lines[index]) ?: continue
        val indent = mapping.indent.length
        while (stack.lastOrNull()?.first ?: -1 >= indent) stack.removeLast()
        val path = (stack.map { it.second } + mapping.key).joinToString(".")
        if (isInlineMap(mapping.raw)) {
            result[path] = BlockOccurrence(index, mapping.indent, mapping.key, path)
            flattenInlineMap(path, mapping.raw).forEach { value ->
                result[value.path] = BlockOccurrence(index, mapping.indent, mapping.key, path)
            }
        } else {
            result[path] = BlockOccurrence(index, mapping.indent, mapping.key)
        }
        if (mapping.raw.isEmpty()) stack += indent to mapping.key
    }
    return result
}

private fun fieldIndent(lines: List<String>, location: ProxyLocation): String {
    val existing = lines.subList(location.start + 1, location.end).firstOrNull { parseMappingLine(it) != null }
    return existing?.let { parseMappingLine(it)?.indent } ?: " ".repeat(location.itemIndent + 2)
}

private fun replaceFlowEntry(
    lines: MutableList<String>,
    index: Int,
    fields: Map<String, String>,
    kinds: Map<String, ProxyEditFieldKind>,
    presence: Map<String, Boolean>,
): String {
    val line = lines[index]
    val open = line.indexOf('{')
    val close = line.lastIndexOf('}')
    if (open < 0 || close <= open) return lines.joinToString("\n")
    val updates = fields.filter { (path, value) ->
        (presence[path] ?: true) && path !in setOf("name", "type")
    }.mapValues { (path, value) -> value to (kinds[path] ?: inferFieldKind(path, value)) }
    lines[index] = line.substring(0, open) + renderInlineMap(line.substring(open, close + 1), updates) + line.substring(close + 1)
    return lines.joinToString("\n")
}

private fun renderInlineMap(raw: String, updates: Map<String, Pair<String, ProxyEditFieldKind>>): String {
    val trimmed = raw.trim()
    val body = trimmed.removePrefix("{").removeSuffix("}")
    val pairs = splitFlowPairs(body).toMutableList()
    val used = mutableSetOf<String>()
    for (index in pairs.indices) {
        val (key, value) = pairs[index]
        val direct = updates[key]
        val nested = updates.filterKeys { it.startsWith("$key.") }
        pairs[index] = when {
            direct != null -> {
                used += key
                key to yamlRenderValue(direct.first, direct.second)
            }
            nested.isNotEmpty() && isInlineMap(value) -> {
                nested.keys.forEach { used += it }
                key to renderInlineMap(value, nested.mapKeys { it.key.removePrefix("$key.") })
            }
            else -> key to value
        }
    }
    updates.filterKeys { it !in used && !it.contains('.') }.forEach { (key, value) ->
        pairs += key to yamlRenderValue(value.first, value.second)
        used += key
    }
    updates.keys.filter { it !in used && it.contains('.') }.groupBy { it.substringBefore('.') }.forEach { (parent, children) ->
        val nested = children.associate { it.removePrefix("$parent.") to updates.getValue(it) }
        pairs += parent to renderInlineMap("{}", nested)
    }
    return "{ ${pairs.joinToString(", ") { (key, value) -> "$key: $value" }} }"
}

private fun yamlScalar(value: String): String = stripYamlComment(value.trim())
    .removeSuffix(",")
    .trim()
    .removeSurrounding("\"")
    .removeSurrounding("'")
    .takeUnless { it.equals("null", true) }
    .orEmpty()

private fun stripYamlComment(value: String): String {
    var quote: Char? = null
    value.forEachIndexed { index, char ->
        if (quote != null) {
            if (char == quote && (index == 0 || value[index - 1] != '\\')) quote = null
        } else if (char == '\'' || char == '"') {
            quote = char
        } else if (char == '#' && (index == 0 || value[index - 1].isWhitespace())) {
            return value.substring(0, index)
        }
    }
    return value
}

private fun yamlRenderValue(value: String, kind: ProxyEditFieldKind): String = when (kind) {
    ProxyEditFieldKind.Boolean -> if (value.equals("true", true)) "true" else "false"
    ProxyEditFieldKind.Number -> value.trim().ifEmpty { "0" }
    ProxyEditFieldKind.List -> {
        val body = value.trim().removePrefix("[").removeSuffix("]")
        "[${splitYamlFlow(body).joinToString(", ") { yamlRenderScalar(yamlScalar(it)) }}]"
    }
    ProxyEditFieldKind.Select, ProxyEditFieldKind.Text -> yamlRenderScalar(value)
}

/** Renders missing descendants as real YAML maps instead of dotted keys. */
private fun renderNestedBlock(
    entries: List<Pair<String, Pair<String, ProxyEditFieldKind>>>,
    indent: String,
): List<String> {
    val grouped = linkedMapOf<String, MutableList<Pair<String, Pair<String, ProxyEditFieldKind>>>>()
    entries.forEach { (path, value) ->
        grouped.getOrPut(path.substringBefore('.')) { mutableListOf() } +=
            path.substringAfter('.', missingDelimiterValue = "") to value
    }
    return grouped.flatMap { (key, descendants) ->
        val direct = descendants.firstOrNull { it.first.isEmpty() }
        if (direct != null) {
            listOf("$indent$key: ${yamlRenderValue(direct.second.first, direct.second.second)}")
        } else {
            listOf("$indent$key:") + renderNestedBlock(descendants, "$indent  ")
        }
    }
}

private fun yamlRenderScalar(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return "\"\""
    if (trimmed == "true" || trimmed == "false" || trimmed.toLongOrNull() != null) return trimmed
    return if (trimmed.matches(Regex("[A-Za-z0-9._:/@+\\-]+"))) trimmed
    else "'${trimmed.replace("'", "''")}'"
}
