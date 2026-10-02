package io.github.madeye.meow.bg

import io.github.madeye.meow.database.ClashProfile

/**
 * The part of the selected profile a running engine was started from.
 *
 * The id counts as well as the YAML: two profiles with identical YAML are
 * still different selections, and the service reports the running profile's
 * name, so a switch has to take effect.
 */
data class ActiveConfig(val profileId: Long, val yaml: String) {
    // A config can run to megabytes; keep it out of log lines.
    override fun toString() = "ActiveConfig(profileId=$profileId, ${yaml.length} chars)"

    companion object {
        fun of(profile: ClashProfile?): ActiveConfig? = profile?.let { ActiveConfig(it.id, it.yamlContent) }
    }
}

/**
 * When a profile change has to restart the running engine. Pure, so both
 * halves of the reload — the UI-process trigger and the `:vpn` receiver —
 * share one rule and it can be tested on the JVM.
 */
object ReloadPolicy {

    enum class Decision { Restart, Defer, Ignore }

    /**
     * Whether an engine started from [before] must restart to run [after].
     *
     * Nothing selected afterwards never reloads: a restart with no profile
     * stops the VPN, and deleting the selected profile is not a request to
     * disconnect. The old config keeps running until the next connect.
     */
    fun needsReload(before: ActiveConfig?, after: ActiveConfig?): Boolean =
        after != null && after != before

    /**
     * What the service does with a reload request in [state], given the
     * config it is [running]. [selected] reads Room, so it is only called when
     * the answer depends on it.
     */
    fun onRequest(
        state: BaseService.State,
        running: ActiveConfig?,
        selected: () -> ActiveConfig?,
    ): Decision = when (state) {
        BaseService.State.Connected ->
            if (needsReload(running, selected())) Decision.Restart else Decision.Ignore
        // The start in flight may have read the profile before the write
        // landed. Cancelling it half-way buys nothing; check again once it
        // has settled.
        BaseService.State.Connecting -> Decision.Defer
        // Stopping: a user disconnect wins, and a reload's own restart reads
        // the profile afresh when it starts again. Idle / Stopped: nothing is
        // running, and a reload must never start the VPN.
        else -> Decision.Ignore
    }
}
