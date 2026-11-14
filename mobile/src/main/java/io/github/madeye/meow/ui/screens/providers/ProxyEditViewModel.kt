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
    val required: Boolean = false,
)

@Immutable
data class ProxyEditUiState(
    val name: String,
    val type: String = "",
    val fields: List<ProxyEditField> = emptyList(),
    val initialFields: List<ProxyEditField> = emptyList(),
    /** Keys explicitly removed by the user, including unknown Advanced keys. */
    val removedKeys: Set<String> = emptySet(),
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
            if (state.saving) return@update state
            val changed = state.fields.map { field ->
                if (field.key == key) {
                    // Clearing an optional control means "use the protocol
                    // default" and removes the key on save. Required values
                    // stay present so the validator can report a useful
                    // configuration error instead of silently dropping them.
                    field.copy(value = value, present = field.required || value.isNotEmpty())
                } else {
                    field
                }
            }
            state.copy(
                fields = adaptProxyFields(state.type, changed),
                removedKeys = state.removedKeys - key,
                error = null,
            )
        }
    }

    /** Removes an optional key from the YAML while keeping it available to add again. */
    fun remove(key: String) {
        _state.update { state ->
            if (state.saving) return@update state
            val changed = state.fields.map { field ->
                if (field.key == key) field.copy(value = "", present = false) else field
            }
            state.copy(
                fields = adaptProxyFields(state.type, changed),
                removedKeys = state.removedKeys + key,
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
            // Read the state after the repository lookup. Editing is disabled
            // while saving, so this snapshot includes the complete set of
            // removal tombstones and field values that will be validated.
            val latest = _state.value
            val fields = latest.fields
            val updated = replaceProxyEntry(
                yaml = profile.yamlContent,
                name = route.name,
                fields = fields.associate { it.key to it.value },
                kinds = fields.associate { it.key to it.kind },
                presence = fields.associate { it.key to it.present },
                removed = latest.removedKeys + fields.filter { !it.present }.mapTo(mutableSetOf()) { it.key },
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
            _state.update { it.copy(saving = false, initialFields = it.fields, removedKeys = emptySet()) }
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
    val required: Boolean = false,
)

private fun text(
    path: String,
    label: String,
    section: String,
    sensitive: Boolean = false,
    required: Boolean = false,
) = ProxyFieldSpec(path, label, ProxyEditFieldKind.Text, section, sensitive = sensitive, required = required)

private fun number(
    path: String,
    label: String,
    section: String,
    required: Boolean = false,
    default: String = "",
) = ProxyFieldSpec(
    path,
    label,
    ProxyEditFieldKind.Number,
    section,
    defaultValue = default,
    required = required,
)

private fun boolean(path: String, label: String, section: String, default: Boolean = false) =
    ProxyFieldSpec(path, label, ProxyEditFieldKind.Boolean, section, defaultValue = default.toString())

private fun list(path: String, label: String, section: String) =
    ProxyFieldSpec(path, label, ProxyEditFieldKind.List, section)

private fun select(
    path: String,
    label: String,
    section: String,
    options: List<String>,
    default: String = "",
    required: Boolean = false,
) = ProxyFieldSpec(
    path,
    label,
    ProxyEditFieldKind.Select,
    section,
    defaultValue = default,
    options = options,
    required = required,
)

private fun muxSpecs(
    root: String,
    existing: Map<String, ParsedValue>,
    current: Map<String, String>,
): List<ProxyFieldSpec> {
    val enabled = current["$root.enabled"].toBoolean()
    val hasOptions = existing.keys.any { it.startsWith("$root.") && it != "$root.enabled" }
    // A legacy entry may omit `enabled` and still carry mux options. Once the
    // user explicitly turns the switch off, those children belong in
    // Advanced until the switch is turned back on.
    val showOptions = enabled || (!existing.containsKey("$root.enabled") && hasOptions)
    return listOf(boolean("$root.enabled", "Multiplexing", "Multiplexing")) +
        if (showOptions) {
            listOf(
                select(
                    "$root.protocol",
                    "Protocol",
                    "Multiplexing",
                    listOf("", "smux", "yamux", "h2mux", "muxcool"),
                    "h2mux",
                ),
                boolean("$root.padding", "Padding", "Multiplexing"),
                number("$root.max-connections", "Maximum connections", "Multiplexing"),
                number("$root.min-streams", "Minimum streams", "Multiplexing"),
                number("$root.max-streams", "Maximum streams", "Multiplexing"),
                boolean("$root.only-tcp", "TCP only", "Multiplexing"),
            )
        } else {
            emptyList()
        }
}

private fun pluginOptsSpecs(
    plugin: String?,
    actualKeys: Set<String> = emptySet(),
): List<ProxyFieldSpec> = when (plugin.orEmpty().lowercase()) {
    "obfs", "obfs-local", "simple-obfs" -> listOf(
        select("plugin-opts.mode", "Obfuscation mode", "Plugin options", listOf("", "http", "tls")),
        text("plugin-opts.host", "Obfuscation host", "Plugin options"),
    )
    "v2ray-plugin" -> listOf(
        // The in-process adapter implements the websocket transport only.
        // Do not offer quic/grpc values that the backend cannot consume.
        select("plugin-opts.mode", "Mode", "Plugin options", listOf("", "websocket")),
        text("plugin-opts.host", "Host", "Plugin options"),
        text("plugin-opts.path", "Path", "Plugin options"),
        boolean("plugin-opts.tls", "TLS", "Plugin options"),
        boolean("plugin-opts.mux", "Mux", "Plugin options"),
        boolean("plugin-opts.skip-cert-verify", "Skip certificate verification", "Plugin options"),
        text("plugin-opts.headers.Host", "Host header", "Plugin options"),
    )
    "kcptun" -> listOf(
        select("plugin-opts.crypt", "Encryption", "Plugin options", listOf("", "aes", "aes-128", "aes-192", "aes-256", "aes-128-gcm", "salsa20", "blowfish", "none", "null", "xor", "tea", "xtea", "twofish", "cast5", "3des")),
        select("plugin-opts.mode", "Mode", "Plugin options", listOf("", "fast", "fast2", "fast3", "normal", "manual")),
        number("plugin-opts.conn", "Parallel sessions", "Plugin options"),
        number("plugin-opts.mtu", "MTU", "Plugin options"),
        number("plugin-opts.sndwnd", "Send window", "Plugin options"),
        number("plugin-opts.rcvwnd", "Receive window", "Plugin options"),
        number("plugin-opts.datashard", "Data shard", "Plugin options"),
        number("plugin-opts.parityshard", "Parity shard", "Plugin options"),
        text("plugin-opts.key", "Key", "Plugin options", sensitive = true),
        number("plugin-opts.smuxver", "SMux version", "Plugin options"),
        boolean("plugin-opts.nocomp", "Disable compression", "Plugin options"),
        number("plugin-opts.keepalive", "Keepalive (seconds)", "Plugin options"),
        number("plugin-opts.autoexpire", "Session expiry (seconds)", "Plugin options"),
        number("plugin-opts.scavengettl", "Scavenge TTL (seconds)", "Plugin options"),
        number("plugin-opts.ratelimit", "Rate limit", "Plugin options"),
        number("plugin-opts.dscp", "DSCP", "Plugin options"),
        boolean("plugin-opts.acknodelay", "ACK no delay", "Plugin options"),
        number("plugin-opts.nodelay", "No delay", "Plugin options"),
        number("plugin-opts.interval", "Flush interval (ms)", "Plugin options"),
        number("plugin-opts.resend", "Resend", "Plugin options"),
        number("plugin-opts.nc", "No congestion control", "Plugin options"),
        number("plugin-opts.sockbuf", "Socket buffer", "Plugin options"),
        number("plugin-opts.smuxbuf", "SMux buffer", "Plugin options"),
        number("plugin-opts.framesize", "Frame size", "Plugin options"),
        number("plugin-opts.streambuf", "Stream buffer", "Plugin options"),
    )
    "shadow-tls" -> listOf(
        text("plugin-opts.host", "Host", "Plugin options", required = true),
        text("plugin-opts.password", "Password", "Plugin options", sensitive = true),
        select("plugin-opts.version", "Version", "Plugin options", listOf("", "1", "2", "3"), required = true),
        list("plugin-opts.alpn", "ALPN (comma separated)", "Plugin options"),
        boolean("plugin-opts.strict-mode", "Strict mode", "Plugin options"),
        boolean("plugin-opts.skip-cert-verify", "Skip certificate verification", "Plugin options"),
        text("plugin-opts.name-cert-verify", "Certificate name", "Plugin options"),
        text("plugin-opts.fingerprint", "Certificate fingerprint", "Plugin options"),
        text("plugin-opts.certificate", "Client certificate", "Plugin options", sensitive = true),
        text("plugin-opts.private-key", "Client private key", "Plugin options", sensitive = true),
    )
    "restls" -> listOf(
        text("plugin-opts.host", "Host", "Plugin options", required = true),
        text("plugin-opts.password", "Password", "Plugin options", sensitive = true, required = true),
        select("plugin-opts.version-hint", "TLS version", "Plugin options", listOf("", "tls12", "tls13"), required = true),
        text("plugin-opts.restls-script", "Handshake script", "Plugin options"),
        boolean("plugin-opts.force-tls12", "Force TLS 1.2", "Plugin options"),
        boolean("plugin-opts.skip-cert-verify", "Skip certificate verification", "Plugin options"),
        text("plugin-opts.name-cert-verify", "Certificate name", "Plugin options"),
        text("plugin-opts.fingerprint", "Certificate fingerprint", "Plugin options"),
    )
    "jls" -> listOf(
        text("plugin-opts.host", "Host", "Plugin options", required = true),
        text("plugin-opts.username", "Username", "Plugin options", required = true),
        text("plugin-opts.password", "Password", "Plugin options", sensitive = true, required = true),
        list("plugin-opts.alpn", "ALPN (comma separated)", "Plugin options"),
    )
    "gost-plugin" -> listOf(
        // The in-process gost adapter accepts websocket mode only.
        select("plugin-opts.mode", "Mode", "Plugin options", listOf("", "websocket"), required = true),
        text("plugin-opts.host", "Host", "Plugin options"),
        text("plugin-opts.path", "Path", "Plugin options"),
        boolean("plugin-opts.tls", "TLS", "Plugin options"),
        boolean("plugin-opts.mux", "Mux", "Plugin options", default = true),
        boolean("plugin-opts.skip-cert-verify", "Skip certificate verification", "Plugin options"),
        text("plugin-opts.name-cert-verify", "Certificate name", "Plugin options"),
        text("plugin-opts.fingerprint", "Certificate fingerprint", "Plugin options"),
        text("plugin-opts.headers.Host", "Host header", "Plugin options"),
        text("plugin-opts.certificate", "Client certificate", "Plugin options", sensitive = true),
        text("plugin-opts.private-key", "Client private key", "Plugin options", sensitive = true),
        boolean("plugin-opts.ech-opts.enable", "ECH", "Plugin options"),
        text("plugin-opts.ech-opts.config", "ECH config (base64)", "Plugin options"),
    )
    "ech-tls-tunnel" -> listOf(
        select("plugin-opts.mode", "Mode", "Plugin options", listOf("", "client"), required = true),
        text("plugin-opts.sni", "Inner SNI", "Plugin options", required = true),
        text("plugin-opts.path", "Path", "Plugin options", required = true),
        // The upstream option is spelled `ech_config`; the Rust parser also
        // accepts the hyphenated alias. Preserve whichever spelling an
        // existing profile uses so editing does not create duplicate tokens.
        text(
            if ("plugin-opts.ech-config" in actualKeys) "plugin-opts.ech-config" else "plugin-opts.ech_config",
            "ECH config (base64)",
            "Plugin options",
            required = true,
        ),
        text("plugin-opts.fingerprint", "Client fingerprint", "Plugin options"),
        boolean(
            if ("plugin-opts.fast-open" in actualKeys) "plugin-opts.fast-open" else "plugin-opts.fast_open",
            "Fast open",
            "Plugin options",
        ),
    )
    else -> emptyList()
}

private fun endpointSpecs(
    withDialer: Boolean = true,
    udpDefault: Boolean = false,
    portRequired: Boolean = true,
): List<ProxyFieldSpec> = buildList {
    add(text("server", "Server", "Endpoint", required = true))
    add(number("port", "Port", "Endpoint", required = portRequired))
    add(boolean("udp", "UDP relay", "Transport", default = udpDefault))
    if (withDialer) add(text("dialer-proxy", "Dialer proxy", "Transport"))
}

/** Every protocol has its own option set, while unknown keys remain in Advanced. */
private fun proxyFieldSpecs(
    type: String,
    actual: Map<String, ParsedValue>,
    current: Map<String, String> = actual.mapValues { it.value.value },
): List<ProxyFieldSpec> {
    val muxRoot = when {
        actual.keys.any { it == "smux" || it.startsWith("smux.") } -> "smux"
        actual.keys.any { it == "mux" || it.startsWith("mux.") } -> "mux"
        else -> "smux"
    }
    val plugin = current["plugin"] ?: actual["plugin"]?.value
    val hasRawPluginOpts = actual.containsKey("plugin-opts")
    val hasNestedPluginOpts = actual.keys.any { it.startsWith("plugin-opts.") }
    val pluginChanged = current["plugin"]?.lowercase() != actual["plugin"]?.value?.lowercase()
    val pluginFields = if (hasRawPluginOpts && !hasNestedPluginOpts && !pluginChanged) {
        emptyList()
    } else {
        pluginOptsSpecs(plugin, actual.keys)
    }
    val rawPluginField = if (hasRawPluginOpts && !hasNestedPluginOpts && !pluginChanged) {
        // SIP003 strings can carry passwords even when the plugin is unknown.
        listOf(text("plugin-opts", "Plugin options (key=value;...)", "Transport", sensitive = true))
    } else emptyList()
    val network = current["network"].orEmpty().ifEmpty { "tcp" }.lowercase()
    val tls = current["tls"].toBoolean()
    // TLS controls are capability-gated. If TLS is switched off, existing
    // values are moved to Advanced and retained in YAML until the user removes
    // them explicitly; switching TLS back on restores the controls.
    val tlsFields = if (tls) {
        listOf(
            boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
            text("servername", "Server name (SNI)", "TLS"),
            text("client-fingerprint", "Client fingerprint", "TLS"),
            list("alpn", "ALPN (comma separated)", "TLS"),
        )
    } else emptyList()
    // Keep REALITY controls available for a plain VLESS node too. They are
    // optional and are only serialized after the user edits them, so this
    // does not add empty maps to existing profiles.
    val realityFields = if (type.equals("vless", ignoreCase = true)) {
        listOf(
            text("reality-opts.public-key", "REALITY public key", "REALITY"),
            text("reality-opts.short-id", "REALITY short ID", "REALITY"),
            boolean("reality-opts.support-x25519mlkem768", "REALITY X25519-Mlkem", "REALITY"),
        )
    } else emptyList()
    val echFields = if (tls || actual.keys.any { it.startsWith("ech-opts.") }) {
        listOf(
            boolean("ech-opts.enable", "ECH", "TLS"),
            text("ech-opts.config", "ECH config (base64)", "TLS"),
        )
    } else emptyList()
    val transportFields = when (network) {
        "ws" -> listOf(
            text("ws-opts.path", "WebSocket path", "WebSocket"),
            text("ws-opts.headers.Host", "WebSocket Host", "WebSocket"),
            number("ws-opts.max-early-data", "WebSocket early data", "WebSocket"),
            text("ws-opts.early-data-header-name", "Early data header", "WebSocket"),
        )
        "grpc" -> listOf(text("grpc-opts.grpc-service-name", "gRPC service", "gRPC"))
        "h2" -> listOf(
            list("h2-opts.host", "HTTP/2 hosts (comma separated)", "HTTP/2"),
            text("h2-opts.path", "HTTP/2 path", "HTTP/2"),
        )
        "httpupgrade" -> listOf(
            text("http-upgrade-opts.host", "HTTP Upgrade Host", "HTTP Upgrade"),
            text("http-upgrade-opts.path", "HTTP Upgrade path", "HTTP Upgrade"),
        )
        "xhttp" -> listOf(
            list("xhttp-opts.host", "XHTTP hosts (comma separated)", "XHTTP"),
            text("xhttp-opts.path", "XHTTP path", "XHTTP"),
            select("xhttp-opts.mode", "XHTTP mode", "XHTTP", listOf("stream-one"), "stream-one"),
            boolean("xhttp-opts.no-grpc-header", "XHTTP without gRPC header", "XHTTP"),
            text("xhttp-opts.x-padding-bytes", "XHTTP padding range", "XHTTP"),
        )
        else -> emptyList()
    }
    val hysteriaPortRequired = current["ports"].orEmpty().isBlank()
    val hysteriaObfs = current["obfs"].orEmpty().lowercase()
    val hysteriaObfsFields = listOf(
        select("obfs", "Obfuscation", "QUIC", listOf("", "salamander", "gecko")),
    ) + if (hysteriaObfs == "salamander" || hysteriaObfs == "gecko") {
        listOf(text("obfs-password", "Obfuscation password", "QUIC", sensitive = true, required = true))
    } else {
        emptyList()
    } + if (hysteriaObfs == "gecko") {
        listOf(
            number("obfs-min-packet-size", "Minimum packet size", "QUIC", default = "512"),
            number("obfs-max-packet-size", "Maximum packet size", "QUIC", default = "1200"),
        )
    } else {
        emptyList()
    }
    val snellVersion = current["version"].orEmpty().ifEmpty { "4" }
        .removePrefix("v")
        .lowercase()
    val snellObfsEnabled = current["obfs-opts.mode"].orEmpty().lowercase()
        .let { it.isNotEmpty() && it != "off" && it != "none" }
    val snellTransportFields = if (snellVersion == "6") {
        listOf(select("mode", "v6 mode", "Transport", listOf("", "default", "unshaped", "unsafe-raw")))
    } else {
        emptyList()
    } + if (snellVersion != "6") {
        listOf(
            select("obfs-opts.mode", "Obfuscation mode", "Obfuscation", listOf("off", "http", "tls")),
        ) + if (snellObfsEnabled) {
            listOf(text("obfs-opts.host", "Obfuscation host", "Obfuscation"))
        } else {
            emptyList()
        }
    } else {
        emptyList()
    }
    return when (type.lowercase()) {
    "direct" -> listOf(
        number("connect-timeout", "Connect timeout (seconds)", "Direct"),
        list("dns", "DNS servers (comma separated)", "Direct"),
    )
    "ss" -> endpointSpecs() + listOf(
        text("cipher", "Cipher", "Authentication", required = true),
        text("password", "Password", "Authentication", sensitive = true, required = true),
        select("plugin", "Plugin", "Transport", listOf("", "obfs", "obfs-local", "simple-obfs", "v2ray-plugin", "kcptun", "shadow-tls", "restls", "gost-plugin", "jls", "ech-tls-tunnel")),
        text("client-fingerprint", "Client fingerprint", "TLS"),
    ) + rawPluginField + pluginFields + muxSpecs(muxRoot, actual, current)
    "trojan" -> endpointSpecs() + listOf(
        text("password", "Password", "Authentication", sensitive = true, required = true),
        text("sni", "SNI", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
    ) + muxSpecs(muxRoot, actual, current)
    "vless" -> endpointSpecs() + listOf(
        text("uuid", "UUID", "Authentication", sensitive = true, required = true),
        select("encryption", "Encryption", "Authentication", listOf("none", "mlkem768x25519plus"), "none"),
        select("flow", "Flow", "Transport", listOf("", "xtls-rprx-vision")),
        select("network", "Network", "Transport", listOf("tcp", "ws", "grpc", "h2", "httpupgrade", "xhttp"), "tcp"),
        boolean("tls", "TLS", "TLS"),
    ) + tlsFields + realityFields + echFields + transportFields + muxSpecs(muxRoot, actual, current)
    "vmess" -> endpointSpecs() + listOf(
        text("uuid", "UUID", "Authentication", sensitive = true, required = true),
        select("cipher", "Cipher", "Authentication", listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none"), "auto"),
        number("alterId", "Alter ID", "Authentication"),
        select("network", "Network", "Transport", listOf("tcp", "ws"), "tcp"),
        boolean("tls", "TLS", "TLS"),
    ) + tlsFields + transportFields.filter { it.path.startsWith("ws-opts.") } + muxSpecs(muxRoot, actual, current)
    "hysteria2" -> endpointSpecs(
        withDialer = false,
        udpDefault = true,
        portRequired = hysteriaPortRequired,
    ) + listOf(
        text("ports", "Port hopping range", "Endpoint"),
        text("password", "Password", "Authentication", sensitive = true, required = true),
        text("up", "Upload limit", "QUIC"),
        text("down", "Download limit", "QUIC"),
        text("sni", "SNI", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
        text("fingerprint", "Certificate fingerprint", "TLS"),
        text("hop-interval", "Hop interval", "QUIC"),
        boolean("fast-open", "Fast open", "QUIC", default = true),
        list("alpn", "ALPN (comma separated)", "TLS"),
    ) + hysteriaObfsFields
    "anytls" -> endpointSpecs(withDialer = false) + listOf(
        text("password", "Password", "Authentication", sensitive = true, required = true),
        text("sni", "SNI", "TLS"),
        boolean("skip-cert-verify", "Skip certificate verification", "TLS"),
    )
    "snell" -> endpointSpecs() + listOf(
        text("psk", "PSK", "Authentication", sensitive = true, required = true),
        select("version", "Version", "Authentication", listOf("3", "4", "5", "6"), "4"),
        boolean("reuse", "Connection reuse", "Transport"),
    ) + snellTransportFields
    "http" -> endpointSpecs() + listOf(
        text("username", "Username", "Authentication"),
        text("password", "Password", "Authentication", sensitive = true),
        boolean("tls", "TLS", "TLS"),
    ) + if (tls) listOf(boolean("skip-cert-verify", "Skip certificate verification", "TLS")) else emptyList()
    "socks5" -> endpointSpecs() + listOf(
        text("username", "Username", "Authentication"),
        text("password", "Password", "Authentication", sensitive = true),
        boolean("tls", "TLS", "TLS"),
    ) + if (tls) listOf(boolean("skip-cert-verify", "Skip certificate verification", "TLS")) else emptyList()
    "trusttunnel" -> endpointSpecs(withDialer = false) + listOf(
        text("username", "Username", "Authentication", required = true),
        text("password", "Password", "Authentication", sensitive = true, required = true),
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
                    required = spec.required,
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
                        label = humanizeFieldPath(parsed.path),
                        section = parsedFieldSection(type, parsed.path),
                        present = true,
                        sensitive = isSensitivePath(parsed.path),
                    ),
                )
            }
        }
    }
    return ParsedProxyEntry(type, fields)
}

/** Header maps are a supported part of several transports, even though their
 * key set is intentionally open ended. Keep existing entries in a dedicated
 * section so they are editable without pretending the editor knows every
 * possible header name. Other unknown paths remain in Advanced. */
private fun parsedFieldSection(type: String, path: String): String = when {
    path.startsWith("headers.") && type.equals("http", ignoreCase = true) -> "Headers"
    path.startsWith("headers.") && type.equals("trusttunnel", ignoreCase = true) -> "Headers"
    path.startsWith("ws-opts.headers.") -> "WebSocket headers"
    path.startsWith("http-upgrade-opts.headers.") -> "HTTP Upgrade headers"
    path.startsWith("xhttp-opts.headers.") -> "XHTTP headers"
    path.startsWith("plugin-opts.headers.") -> "Plugin headers"
    else -> "Advanced"
}

private fun humanizeFieldPath(path: String): String = path.substringAfterLast('.')
    .replace('-', ' ')
    .replace('_', ' ')
    .replace(Regex("\\s+"), " ")
    .trim()
    .replaceFirstChar { it.titlecase() }

/** Rebuilds the visible schema after a selector changes, without losing values. */
internal fun adaptProxyFields(type: String, fields: List<ProxyEditField>): List<ProxyEditField> {
    val oldByPath = fields.associateBy { it.key }
    // The list also contains schema controls that were never present in YAML.
    // Only present values should influence capability detection; otherwise
    // toggling a switch would make every generated child look configured.
    val actual = oldByPath.filterValues { it.present }.mapValues { (_, field) ->
        ParsedValue(field.key, field.value, field.kind)
    }
    val current = oldByPath.mapValues { it.value.value }
    val specs = proxyFieldSpecs(type, actual, current)
    val specPaths = specs.mapTo(mutableSetOf()) { it.path }
    return buildList {
        specs.forEach { spec ->
            val old = oldByPath[spec.path]
            add(
                ProxyEditField(
                    key = spec.path,
                    value = old?.value ?: spec.defaultValue,
                    kind = spec.kind,
                    label = spec.label,
                    section = spec.section,
                    options = spec.options,
                    present = old?.present ?: false,
                    sensitive = spec.sensitive,
                    required = spec.required,
                ),
            )
        }
        fields.filter { it.key !in specPaths && it.present && it.key != "name" && it.key != "type" }
            // A value moved out of its active capability is optional in the
            // current schema. Keep it editable/removable in Advanced rather
            // than leaving a stale required marker from the old capability.
            .forEach { add(it.copy(section = "Advanced", required = false)) }
    }
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
        while (stack.lastOrNull()?.first ?: -1 >= indent) stack.removeAt(stack.lastIndex)
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

/** Keep credentials hidden even when a newer engine key falls into Advanced. */
private fun isSensitivePath(path: String): Boolean =
    path.split('.', '-', '_').any {
        it.lowercase() in setOf(
            "password",
            "passwd",
            "psk",
            "secret",
            "token",
            "uuid",
            "private",
            "credential",
            "credentials",
            "authorization",
        )
    }

/** Replaces existing paths and adds only optional fields the user touched. */
internal fun replaceProxyEntry(
    yaml: String,
    name: String,
    fields: Map<String, String>,
    kinds: Map<String, ProxyEditFieldKind> = emptyMap(),
    presence: Map<String, Boolean> = emptyMap(),
    removed: Set<String> = emptySet(),
): String? {
    val normalized = yaml.replace("\r\n", "\n")
    val lines = normalized.split('\n').toMutableList()
    val location = findProxyLocation(lines, name) ?: return null
    if (location.flow) return replaceFlowEntry(lines, location.start, fields, kinds, presence, removed)

    val existing = parseBlockValues(lines, location.start + 1, location.end).associateBy { it.path }
    val occurrences = findBlockOccurrences(lines, location.start + 1, location.end)
    val inlineUpdates = mutableMapOf<Int, MutableMap<String, Pair<String, ProxyEditFieldKind>>>()
    val inlineRemovals = mutableMapOf<Int, MutableSet<String>>()
    val removeIndexes = mutableSetOf<Int>()
    val removedPaths = mutableSetOf<String>()
    fields.forEach { (path, value) ->
        val occurrence = occurrences[path]
        val shouldWrite = presence[path] ?: true
        if (occurrence == null) return@forEach
        if (!shouldWrite || path in removed) {
            removedPaths += path
            if (occurrence.inlineRoot != null) {
                inlineRemovals.getOrPut(occurrence.index) { mutableSetOf() } +=
                    path.removePrefix(occurrence.inlineRoot + ".")
            } else {
                removeIndexes += occurrence.index
            }
            return@forEach
        }
        val kind = kinds[path] ?: existing[path]?.kind ?: inferFieldKind(path, value)
        if (occurrence.inlineRoot != null) {
            val relative = path.removePrefix(occurrence.inlineRoot + ".")
            inlineUpdates.getOrPut(occurrence.index) { mutableMapOf() }[relative] = value to kind
        } else {
            lines[occurrence.index] = "${occurrence.indent}${occurrence.key}: ${yamlRenderValue(value, kind)}"
        }
    }
    // Unknown Advanced fields are intentionally removed from the form after
    // the user taps delete. Their tombstones are therefore not present in
    // `fields`; apply those removals separately so the original YAML cannot
    // silently retain them.
    removed.filter { it !in fields }.forEach { path ->
        val occurrence = occurrences[path] ?: return@forEach
        removedPaths += path
        if (occurrence.inlineRoot != null) {
            inlineRemovals.getOrPut(occurrence.index) { mutableSetOf() } +=
                path.removePrefix(occurrence.inlineRoot + ".")
        } else {
            removeIndexes += occurrence.index
        }
    }
    // Render each inline map once. Applying removals and updates in separate
    // passes against the original raw value lets the second pass resurrect a
    // key removed by the first pass when the same map has both operations.
    (inlineRemovals.keys + inlineUpdates.keys).distinct().forEach { index ->
        val mapping = parseMappingLine(lines[index]) ?: return@forEach
        val updates = inlineUpdates[index].orEmpty()
        val removals = inlineRemovals[index].orEmpty()
        lines[index] = "${mapping.indent}${mapping.key}: ${renderInlineMap(mapping.raw, updates, removals)}"
    }
    // Keep line indices stable for nested additions below. A blank line is
    // valid YAML and preserves the surrounding profile formatting.
    removeIndexes.forEach { index -> lines[index] = "" }

    // Removal changes indices but additions are relative to the proxy's end,
    // so the original location remains the correct insertion point.
    val missing = fields.filter { (path, value) ->
        path !in occurrences && (presence[path] ?: true) && value.isNotEmpty() && path !in setOf("name", "type")
    }
    val additions = mutableListOf<String>()
    val nestedInsertions = mutableListOf<Pair<Int, List<String>>>()
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
            // Apply these after collecting all insertions. Keeping the
            // original indices lets multiple nested maps be added without
            // one insertion shifting the next parent's location.
            nestedInsertions += insertAt to rendered
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
    var insertedBeforeEnd = 0
    nestedInsertions.sortedByDescending { it.first }.forEach { (index, rendered) ->
        lines.addAll(index, rendered)
        if (index <= location.end) insertedBeforeEnd += rendered.size
    }
    if (additions.isNotEmpty()) lines.addAll(location.end + insertedBeforeEnd, additions)

    // Removing the final child of a nested map should remove that map too;
    // leaving `headers:`/`plugin-opts:`/`future-map:` with no children is
    // both noisy in the editor and can change how strict YAML consumers
    // interpret the value. Run this after additions so a map that receives a
    // replacement child in the same save is retained.
    if (removedPaths.isNotEmpty()) {
        removeEmptyBlockMaps(
            lines = lines,
            from = location.start + 1,
            until = location.end + insertedBeforeEnd + additions.size,
            removedPaths = removedPaths,
        )
    }
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
        while (stack.lastOrNull()?.first ?: -1 >= indent) stack.removeAt(stack.lastIndex)
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

/** Removes only maps made empty by a user removal, deepest map first. */
private fun removeEmptyBlockMaps(
    lines: MutableList<String>,
    from: Int,
    until: Int,
    removedPaths: Set<String>,
) {
    val candidates = removedPaths
        .flatMap { path ->
            val parts = path.split('.')
            (1 until parts.size).map { parts.take(it).joinToString(".") }
        }
        .toSet()
        .sortedByDescending { it.count { char -> char == '.' } }
    candidates.forEach { candidate ->
        val stack = mutableListOf<Pair<Int, String>>()
        var mapIndex: Int? = null
        var mapIndent = 0
        for (index in from until until.coerceAtMost(lines.size)) {
            val mapping = parseMappingLine(lines[index]) ?: continue
            val indent = mapping.indent.length
            while (stack.lastOrNull()?.first ?: -1 >= indent) stack.removeAt(stack.lastIndex)
            val path = (stack.map { it.second } + mapping.key).joinToString(".")
            if (path == candidate && mapping.raw.isEmpty()) {
                mapIndex = index
                mapIndent = indent
                break
            }
            if (mapping.raw.isEmpty()) stack += indent to mapping.key
        }
        val index = mapIndex ?: return@forEach
        var hasChild = false
        for (childIndex in index + 1 until until.coerceAtMost(lines.size)) {
            val line = lines[childIndex]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            } else if (leadingSpaces(line) <= mapIndent) {
                break
            } else {
                if (parseMappingLine(line) != null || trimmed.startsWith("-")) {
                    hasChild = true
                    break
                }
            }
        }
        if (!hasChild) lines[index] = ""
    }
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
    removed: Set<String>,
): String {
    val line = lines[index]
    val open = line.indexOf('{')
    val close = line.lastIndexOf('}')
    if (open < 0 || close <= open) return lines.joinToString("\n")
    val updates = fields.filter { (path, value) ->
        (presence[path] ?: true) && path !in removed && path !in setOf("name", "type")
    }.mapValues { (path, value) -> value to (kinds[path] ?: inferFieldKind(path, value)) }
    lines[index] = line.substring(0, open) +
        renderInlineMap(line.substring(open, close + 1), updates, removed) +
        line.substring(close + 1)
    return lines.joinToString("\n")
}

private fun renderInlineMap(
    raw: String,
    updates: Map<String, Pair<String, ProxyEditFieldKind>>,
    removals: Set<String> = emptySet(),
): String {
    val trimmed = raw.trim()
    val body = trimmed.removePrefix("{").removeSuffix("}")
    val pairs = splitFlowPairs(body).toMutableList()
    val used = mutableSetOf<String>()
    for (index in pairs.indices) {
        val (key, value) = pairs[index]
        if (key in removals) {
            pairs[index] = "" to ""
            used += key
            continue
        }
        val direct = updates[key]
        val nested = updates.filterKeys { it.startsWith("$key.") }
        val nestedRemovals = removals.filter { it.startsWith("$key.") }
        pairs[index] = when {
            direct != null -> {
                used += key
                key to yamlRenderValue(direct.first, direct.second)
            }
            (nested.isNotEmpty() || nestedRemovals.isNotEmpty()) && isInlineMap(value) -> {
                nested.keys.forEach { used += it }
                nestedRemovals.forEach { used += it }
                val rendered = renderInlineMap(
                    value,
                    nested.mapKeys { it.key.removePrefix("$key.") },
                    nestedRemovals.map { it.removePrefix("$key.") }.toSet(),
                )
                if (isEmptyInlineMap(rendered)) "" to "" else key to rendered
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
    return "{ ${pairs.filter { it.first.isNotEmpty() }.joinToString(", ") { (key, value) -> "$key: $value" }} }"
}

private fun isEmptyInlineMap(value: String): Boolean =
    value.trim().removePrefix("{").removeSuffix("}").trim().isEmpty()

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
