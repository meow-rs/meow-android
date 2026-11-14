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

@Immutable
data class ProxyEditField(val key: String, val value: String)

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

/** Edits one top-level `proxies:` entry while keeping subscription profiles read-only. */
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
                val fields = parsed.fields.map { ProxyEditField(it.key, it.value) }
                _state.value = ProxyEditUiState(
                    name = route.name,
                    type = parsed.type,
                    fields = fields,
                    initialFields = fields,
                    loading = false,
                )
            }
        }
    }

    fun update(key: String, value: String) {
        _state.update { state ->
            state.copy(fields = state.fields.map { if (it.key == key) it.copy(value = value) else it }, error = null)
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
            val updated = replaceProxyEntry(
                yaml = profile.yamlContent,
                name = route.name,
                fields = _state.value.fields.associate { it.key to it.value },
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

internal data class ParsedProxyEntry(val type: String, val fields: List<ProxyEditField>)

private val proxyEntryPattern = Regex("^  -\\s+name:\\s*(.*?)\\s*$")
private val proxyFieldPattern = Regex("^    ([A-Za-z0-9_-]+):\\s*(.*?)\\s*$")

/** Parses the simple scalar fields of one top-level proxy entry. */
internal fun parseProxyEntry(yaml: String, name: String): ParsedProxyEntry? {
    val lines = yaml.replace("\r\n", "\n").split('\n')
    val start = lines.indexOfFirst { line ->
        proxyEntryPattern.matchEntire(line)?.groupValues?.get(1)?.let(::yamlScalar) == name
    }
    if (start < 0) return null
    val end = (start + 1 until lines.size).firstOrNull { index ->
        val line = lines[index]
        line.startsWith("  - ") || (line.isNotEmpty() && !line[0].isWhitespace())
    } ?: lines.size
    val fields = lines.subList(start + 1, end).mapNotNull { line ->
        proxyFieldPattern.matchEntire(line)?.let { ProxyEditField(it.groupValues[1], yamlScalar(it.groupValues[2])) }
    }
    val type = fields.firstOrNull { it.key == "type" }?.value.orEmpty()
    return ParsedProxyEntry(type, fields)
}

/** Replaces existing scalar fields in one entry and leaves the rest of the profile byte-for-byte intact. */
internal fun replaceProxyEntry(yaml: String, name: String, fields: Map<String, String>): String? {
    val normalized = yaml.replace("\r\n", "\n")
    val lines = normalized.split('\n').toMutableList()
    val start = lines.indexOfFirst { line ->
        proxyEntryPattern.matchEntire(line)?.groupValues?.get(1)?.let(::yamlScalar) == name
    }
    if (start < 0) return null
    val end = (start + 1 until lines.size).firstOrNull { index ->
        val line = lines[index]
        line.startsWith("  - ") || (line.isNotEmpty() && !line[0].isWhitespace())
    } ?: lines.size
    for (index in start + 1 until end) {
        val match = proxyFieldPattern.matchEntire(lines[index]) ?: continue
        fields[match.groupValues[1]]?.let { value ->
            lines[index] = "    ${match.groupValues[1]}: ${yamlRenderScalar(value)}"
        }
    }
    return lines.joinToString("\n")
}

private fun yamlScalar(value: String): String = value.trim()
    .removeSurrounding("\"")
    .removeSurrounding("'")

private fun yamlRenderScalar(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return "\"\""
    if (trimmed == "true" || trimmed == "false" || trimmed.toIntOrNull() != null) return trimmed
    return if (trimmed.matches(Regex("[A-Za-z0-9._:/@+\\-]+"))) trimmed
    else "'${trimmed.replace("'", "''")}'"
}
