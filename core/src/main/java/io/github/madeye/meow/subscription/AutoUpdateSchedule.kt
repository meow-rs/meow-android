package io.github.madeye.meow.subscription

import io.github.madeye.meow.database.ClashProfile

/**
 * When a URL subscription is due for a background refresh.
 *
 * Pure functions over a profile's stored fields, so the policy is testable
 * without Room or WorkManager: the worker only picks which profiles to hand
 * to the normal refresh path, and the edit dialog previews the same answer.
 */
object AutoUpdateSchedule {
    const val DEFAULT_INTERVAL_HOURS = 24

    /** The worker runs hourly, so a shorter interval could not be honoured. */
    const val MIN_INTERVAL_HOURS = 1

    /** 30 days. Anyone wanting less than that is better served by switching it off. */
    const val MAX_INTERVAL_HOURS = 24 * 30

    private const val HOUR_MS = 60 * 60 * 1000L

    fun isDue(profile: ClashProfile, now: Long): Boolean =
        profile.url.isNotEmpty() &&
            profile.autoUpdate &&
            !hasLocalEdits(profile) &&
            dueAt(profile.lastUpdated, profile.updateIntervalHours, now) <= now

    /**
     * Epoch millis from which a profile fetched at [lastUpdated] is due again.
     *
     * A stamp from the future means the clock was set back; waiting it out
     * could stall updates for as long as the skew, so it counts as due now and
     * the refresh re-anchors the schedule. The interval is clamped because the
     * column can hold anything raw SQL put there, and 0 would mean a refetch
     * on every run.
     */
    fun dueAt(lastUpdated: Long, intervalHours: Int, now: Long): Long =
        if (lastUpdated > now) {
            now
        } else {
            lastUpdated + intervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS) * HOUR_MS
        }

    /**
     * The user saved changes in the YAML editor since the last download. A
     * refresh replaces the config wholesale, so doing it unasked would silently
     * throw those edits away; a manual refresh or a revert clears the state.
     * Subscription configs are read-only now, so on a subscription these can
     * only be edits saved before that. They stay, keeping auto-update paused,
     * until the user refreshes or reverts.
     */
    fun hasLocalEdits(profile: ClashProfile): Boolean = profile.yamlContent != profile.yamlBackup

    /** The edit dialog's interval field, or null when it is not a usable value. */
    fun parseIntervalHours(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in MIN_INTERVAL_HOURS..MAX_INTERVAL_HOURS }
}
