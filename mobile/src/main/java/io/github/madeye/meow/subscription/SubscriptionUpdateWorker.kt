package io.github.madeye.meow.subscription

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.madeye.meow.AppGraph
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Re-downloads the URL subscriptions whose auto-update interval has elapsed.
 *
 * One periodic job polls hourly and [AutoUpdateSchedule] decides per profile,
 * rather than a job per profile: intervals are edited freely, and a single
 * job never has to be kept in sync with the profile table.
 *
 * Refreshes go through [io.github.madeye.meow.repo.ProfileRepository.refresh],
 * the path the manual refresh uses, so anything hooked onto it applies to
 * background updates too. A failed fetch leaves the stored config and
 * `lastUpdated` alone, so the profile stays due and the next run is the retry.
 * `onlyIfUnedited` covers edits saved while the fetch runs, which the due
 * check, made before it, cannot see.
 *
 * Runs in the UI process: WorkManager is initialised by its startup provider,
 * which only exists in the default process, and :vpn never touches it.
 */
class SubscriptionUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val profiles = AppGraph.profiles
        val now = System.currentTimeMillis()
        val due = profiles.getAll().filter { AutoUpdateSchedule.isDue(it, now) }
        for (profile in due) {
            try {
                profiles.refresh(profile.id, onlyIfUnedited = true)
                Timber.i("auto-update: refreshed profile %d (%s)", profile.id, profile.name)
            } catch (e: CancellationException) {
                // WorkManager stopped us (constraints lost, quota): unwind, the
                // rest stay due for the next run.
                throw e
            } catch (e: Exception) {
                // One bad subscription must not hold back the others.
                Timber.w(e, "auto-update: profile %d (%s) failed", profile.id, profile.name)
            }
        }
        // Never retry(): its backoff would hammer a dead subscription between
        // periodic runs, which already retry hourly.
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "subscription-auto-update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            // KEEP: this runs on every UI-process start, including the ones
            // JobScheduler causes in order to run this very worker. KEEP leaves
            // the existing job alone; UPDATE would bump its generation and
            // re-register it under the starting job, and CANCEL_AND_REENQUEUE
            // would restart the hourly clock on every launch. Changing the
            // request later means a new WORK_NAME (or a one-off UPDATE).
            try {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } catch (e: IllegalStateException) {
                // WorkManager is not initialised outside the default process.
                // Only reachable if the caller misjudged which process it is in;
                // auto-update is not worth crashing for.
                Timber.w(e, "auto-update: cannot schedule")
            }
        }
    }
}
