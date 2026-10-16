package io.github.madeye.meow.ui.screens.connections

import io.github.madeye.meow.api.Connection
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Display names of the apps that open connections, by package name. Each
 * package is resolved once and kept for the process: the connection screens
 * re-read the same few apps every second, and a label only changes with an
 * app update. A package [resolve] has no label for (uninstalled since) maps
 * to itself.
 */
class AppLabels(
    private val scope: CoroutineScope,
    private val resolve: suspend (packageName: String) -> String?,
) {
    private val resolved = MutableStateFlow<Map<String, String>>(emptyMap())
    private val requested = ConcurrentHashMap.newKeySet<String>()

    /** Grows as [request]ed packages resolve. */
    val labels: StateFlow<Map<String, String>> = resolved.asStateFlow()

    /** Starts resolving whichever of [packageNames] are new; [labels] gains them. */
    fun request(packageNames: Iterable<String>) {
        for (name in packageNames) {
            if (name.isEmpty() || !requested.add(name)) continue
            scope.launch { labelOf(name) }
        }
    }

    /** [packageName]'s label, resolving it now if it is not known yet. */
    suspend fun labelOf(packageName: String): String {
        resolved.value[packageName]?.let { return it }
        requested.add(packageName)
        val label = resolve(packageName)?.takeIf { it.isNotBlank() } ?: packageName
        resolved.update { it + (packageName to label) }
        return label
    }
}

/** The package of the app that opened the connection; null when unknown. */
internal val Connection.appPackage: String?
    get() = metadata.process.ifEmpty { null }
