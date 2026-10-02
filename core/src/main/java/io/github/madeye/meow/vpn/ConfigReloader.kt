package io.github.madeye.meow.vpn

import android.content.Intent
import io.github.madeye.meow.Core
import io.github.madeye.meow.bg.ActiveConfig
import io.github.madeye.meow.bg.MeowInstance
import io.github.madeye.meow.bg.ReloadPolicy
import io.github.madeye.meow.database.PrivateDatabase
import io.github.madeye.meow.repo.ConfigValidator
import io.github.madeye.meow.utils.Action
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Applies profile changes to a running VPN.
 *
 * The engine reads the selected profile once, at start, so a write that
 * changes it — switching profile, or new YAML for the selected one from a
 * refresh, a URL edit, the YAML editor or a revert — has no effect until the
 * engine restarts. Those writes run inside [applying]; when the selected
 * profile's id or YAML differs afterwards, [Action.RELOAD] goes to `:vpn`,
 * which restarts from Room if what it runs is out of date
 * (`BaseService.Interface.reloadIfChanged`). With the VPN off nothing has the
 * receiver registered, so the broadcast is dropped and nothing starts.
 *
 * Changes settle for [settleMs] first, so rapid switching ends in a single
 * restart, and switching away and straight back ends in none.
 *
 * The settled YAML then goes through the engine's own [validate] before
 * anything restarts. A refresh can bring back an error page or broken YAML,
 * and restarting into a config that fails to load would stop a VPN that was
 * working, so a rejected update keeps the running config and is reported on
 * [rejected]. An update that can't be checked at all (the validator threw)
 * is held back the same way, only logged.
 *
 * The broadcast always follows the commit: DAO calls return once the write
 * is committed, and `:vpn` reads the selected profile afresh when it handles
 * the broadcast and again when it restarts. A second write landing in the
 * few milliseconds between the last check here and that read would restart
 * unvalidated; it still gets its own check and reload right after.
 */
class ConfigReloader(
    private val selected: () -> ActiveConfig?,
    /** Null when the engine accepts the YAML, else the engine's error. */
    private val validate: suspend (String) -> String?,
    private val sendReload: () -> Unit,
    private val scope: CoroutineScope,
    private val settleMs: Long = SETTLE_MS,
) {
    private val _reloads = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * One emission per reload sent, for the "reconnecting" notice. Not
     * replayed: a reload that happened while nobody watched is old news.
     */
    val reloads: SharedFlow<Unit> = _reloads.asSharedFlow()

    private val _rejected = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The engine's error, once per update kept off the running VPN because it
     * would not load. Not replayed, like [reloads].
     */
    val rejected: SharedFlow<String> = _rejected.asSharedFlow()

    private val lock = Any()

    /**
     * The selection before the first change of the burst now settling. It
     * stays put until a job finishes unsuperseded, validation included, so a
     * change made mid-validation is still compared against the same start.
     */
    private var burstStart: ActiveConfig? = null
    private var settling: Job? = null

    /**
     * Runs [write] and schedules a reload if it changed the selected profile.
     *
     * Reads Room synchronously on either side of [write], so call it off the
     * main thread. Writes to other profiles, and refreshes that return the
     * same bytes, leave the selection as it was and reload nothing.
     */
    suspend fun <T> applying(write: suspend () -> T): T {
        // A failed read must not cost the write; at worst it sends a reload
        // that :vpn finds it doesn't need.
        val before = readSelected()
        try {
            return write()
        } finally {
            // Also after a failure or cancellation: refresh-all can commit the
            // selected profile before a later fetch throws.
            onWritten(before)
        }
    }

    private fun onWritten(before: ActiveConfig?) {
        if (!ReloadPolicy.needsReload(before, readSelected())) return
        synchronized(lock) {
            if (settling == null) burstStart = before
            settling?.cancel()
            settling = scope.launch {
                delay(settleMs)
                val outcome = settle(synchronized(lock) { burstStart }, readSelected())
                synchronized(lock) {
                    // Superseded by a change made meanwhile, possibly too late
                    // to cancel us; the newer job keeps the burst's start.
                    if (settling !== coroutineContext.job) return@launch
                    settling = null
                }
                when (outcome) {
                    Outcome.Reload -> {
                        Timber.i("ConfigReloader: selected profile changed, asking :vpn to reload")
                        sendReload()
                        _reloads.tryEmit(Unit)
                    }
                    is Outcome.Rejected -> {
                        Timber.w("ConfigReloader: update rejected, keeping the running config: ${outcome.error}")
                        _rejected.tryEmit(outcome.error)
                    }
                    Outcome.Keep -> {}
                }
            }
        }
    }

    private sealed interface Outcome {
        data object Keep : Outcome
        data object Reload : Outcome
        class Rejected(val error: String) : Outcome
    }

    private suspend fun settle(from: ActiveConfig?, now: ActiveConfig?): Outcome {
        // Compared against the burst's start rather than per write, so
        // A -> B -> A within the window is no change at all.
        if (now == null || !ReloadPolicy.needsReload(from, now)) return Outcome.Keep
        val error = try {
            validate(now.yaml)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Including a native library that failed to load. Unknown is not
            // valid: keep what runs.
            Timber.w(e, "ConfigReloader: validating the updated profile failed")
            return Outcome.Keep
        }
        return if (error == null) Outcome.Reload else Outcome.Rejected(error)
    }

    /** A failed read means "unknown", which must not restart anything. */
    private fun readSelected(): ActiveConfig? =
        try {
            selected()
        } catch (e: Exception) {
            Timber.w(e, "ConfigReloader: reading the selected profile failed")
            null
        }

    companion object {
        /** Long enough to fold a burst of taps, short enough to feel prompt. */
        const val SETTLE_MS = 500L

        val default: ConfigReloader by lazy {
            ConfigReloader(
                selected = { ActiveConfig.of(PrivateDatabase.profileDao.getSelected()) },
                validate = { yaml ->
                    // Only MainActivity prepares the engine home. A background
                    // refresh can run without it, and GeoIP rules would then
                    // fail validation. Idempotent.
                    withContext(Dispatchers.IO) { MeowInstance.prepareEngineHome(Core.app) }
                    ConfigValidator().validate(yaml)
                },
                sendReload = {
                    // The receiver lives in :vpn and is RECEIVER_NOT_EXPORTED,
                    // which still hears its own app's other processes; the
                    // package keeps the intent from being implicit.
                    val app = Core.app
                    app.sendBroadcast(Intent(Action.RELOAD).setPackage(app.packageName))
                },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        }
    }
}
