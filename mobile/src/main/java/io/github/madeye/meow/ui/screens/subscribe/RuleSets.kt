package io.github.madeye.meow.ui.screens.subscribe

import androidx.compose.runtime.Immutable
import io.github.madeye.meow.api.RuleProviderInfo
import io.github.madeye.meow.bg.BaseService
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

/**
 * Whether [yaml] has a top-level `rule-providers:` key, i.e. rule sets the
 * engine downloads. Matched at the start of a line, so an indented key or a
 * comment does not count; a profile saved with a byte-order mark still does.
 */
fun declaresRuleSets(yaml: String): Boolean = RULE_PROVIDERS_KEY.containsMatchIn(yaml)

private val RULE_PROVIDERS_KEY = Regex("^\uFEFF?rule-providers:", RegexOption.MULTILINE)

/**
 * Only an HTTP provider has anything to update. The engine re-reads a File
 * one, but nothing on Android changes that file, and an inline one has
 * nothing to re-read.
 */
private val RuleProviderInfo.isHttp: Boolean
    get() = vehicleType.equals("HTTP", ignoreCase = true)

/**
 * The running engine's HTTP rule sets: how many, and the oldest of their
 * update times, so a subtitle can say every set is at least that fresh.
 * [oldestUpdateMillis] is null when no time parses: the engine sends an empty
 * one for a set it has never fetched.
 */
@Immutable
data class RuleSetSummary(val count: Int, val oldestUpdateMillis: Long?) {
    companion object {
        fun of(providers: List<RuleProviderInfo>): RuleSetSummary {
            val http = providers.filter { it.isHttp }
            return RuleSetSummary(
                count = http.size,
                oldestUpdateMillis = http
                    .mapNotNull { runCatching { Instant.parse(it.updatedAt).toEpochMilli() }.getOrNull() }
                    .minOrNull(),
            )
        }
    }
}

/**
 * What the Subscriptions page's "Update rule sets" row needs. Rule sets are
 * the running engine's state, so the row only works while [connected]; the
 * running profile is then the selected one, since a switch restarts the
 * engine. [summary] is null until the engine has listed them, and when it
 * could not.
 */
@Immutable
data class RuleSetsUi(
    val connected: Boolean = false,
    val summary: RuleSetSummary? = null,
    val updating: Boolean = false,
) {
    /** Connected, and the engine says there is nothing to update. */
    val nothingToUpdate: Boolean get() = connected && summary?.count == 0
}

/**
 * "Update rule sets", as Surge's "Update External Resources": downloads every
 * HTTP rule set of the running profile again, without waiting for each one's
 * `interval`.
 *
 * Kept out of [SubscribeViewModel], which owns it, so tests can drive it with
 * plain functions in place of the engine. The ViewModel lives on Home's
 * back-stack entry, so an update keeps going when the user leaves the page.
 */
class RuleSetUpdater(
    vpnState: Flow<BaseService.State>,
    private val listProviders: suspend () -> List<RuleProviderInfo>,
    private val updateProvider: suspend (name: String) -> Unit,
) {
    companion object {
        /**
         * Updates in flight at once. Each holds its call open until the
         * engine has downloaded the set, and OkHttp runs five calls per host,
         * so this leaves one for the rest of the app.
         */
        const val UPDATE_CONCURRENCY = 4
    }

    private val summary = MutableStateFlow<RuleSetSummary?>(null)
    private val updating = MutableStateFlow(false)

    /**
     * Lists the rule sets each time the VPN comes up, while collected. The
     * page's state collects it, so opening the page lists them too.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val connected: Flow<Boolean> = vpnState
        .map { it == BaseService.State.Connected }
        .distinctUntilChanged()
        .transformLatest { up ->
            // The next start may run another profile; what was listed is not its.
            if (!up) summary.value = null
            emit(up)
            if (up) relist()
        }

    val state: Flow<RuleSetsUi> = combine(connected, summary, updating) { up, listed, running ->
        RuleSetsUi(connected = up, summary = listed.takeIf { up }, updating = running)
    }

    /**
     * Updates every HTTP rule set the engine runs, [UPDATE_CONCURRENCY] at a
     * time, then lists them again for the new times. A failed set keeps the
     * engine's old copy, and the rest still go ahead.
     *
     * Returns what to tell the user, or null when an update is already
     * running: the tap is dropped. Throws when the engine cannot even list
     * the sets. Call it from the main thread, which keeps the running check
     * and the flag that follows it a single step.
     */
    suspend fun updateAll(): SubscribeEvent? {
        if (updating.value) return null
        updating.value = true
        try {
            // Listed afresh rather than taken from [summary], which may be
            // missing (the listing failed) or predate a reload.
            val names = listProviders().filter { it.isHttp }.map { it.name }
            val limiter = Semaphore(UPDATE_CONCURRENCY)
            val failed = coroutineScope {
                names.map { name ->
                    async { limiter.withPermit { update(name) } }
                }.awaitAll().count { ok -> !ok }
            }
            relist()
            return if (failed == 0) {
                SubscribeEvent.RuleSetsUpdated(names.size)
            } else {
                SubscribeEvent.RuleSetsUpdateFailed(failed)
            }
        } finally {
            updating.value = false
        }
    }

    private suspend fun update(name: String): Boolean =
        try {
            updateProvider(name)
            true
        } catch (e: IOException) {
            // The engine answers 503 when the download failed; its log says why.
            Timber.w(e, "updating rule set %s failed", name)
            false
        }

    /**
     * A failed listing leaves the row usable without its count: the update
     * lists the sets again anyway. Logged, as nothing on screen says so.
     */
    private suspend fun relist() {
        summary.value = try {
            RuleSetSummary.of(listProviders())
        } catch (e: IOException) {
            Timber.w(e, "listing rule sets failed")
            null
        }
    }
}
