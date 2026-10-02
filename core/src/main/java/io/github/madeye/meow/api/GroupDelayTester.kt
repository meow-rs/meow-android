package io.github.madeye.meow.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

/**
 * One member's probe outcome. [delayMs] is 0 when the probe timed out or
 * failed — the same sentinel the engine appends to the member's history.
 */
data class MemberDelay(val name: String, val delayMs: Int)

/**
 * Latency-tests a group one member at a time, so each result can be shown the
 * moment it lands.
 *
 * `GET /group/{name}/delay` would do it in one call, but that call is
 * all-or-nothing: the whole batch shares one timeout, and a single member
 * timing out turns the response into a bare 504. Probing each member through
 * `GET /proxies/{name}/delay` leaves the same trace on the engine side (both
 * endpoints record through `health::probe_and_record`, so a later `/proxies`
 * still shows every result) while reporting each one as it completes. A
 * member that is itself a group is probed through whatever it currently
 * routes to, exactly as the group endpoint does.
 *
 * What the group endpoint does besides probing is replayed around the probes:
 *  - Before: an url-test or fallback group is unpinned (`DELETE
 *    /proxies/{group}`), as the group endpoint does first, so testing hands
 *    the group back to automatic selection.
 *  - After, url-test only: one probe through the group itself. An url-test
 *    group promotes its fastest member lazily, on its next dial, so its `now`
 *    would keep the old pick until a flow happened to pass through. Probing
 *    the group is such a dial, made once every member has a fresh delay.
 *    A fallback group's pick is computed on every read and needs no nudge.
 *
 * Every test run by one instance shares [concurrency] permits, so testing
 * several groups at once still bounds the probes in flight.
 */
class GroupDelayTester(
    private val api: MeowApi,
    concurrency: Int = MeowApi.PROBE_CONCURRENCY,
    private val timeoutMs: Int = PROBE_TIMEOUT_MS,
) {
    private val limiter = Semaphore(concurrency)

    /**
     * Probes every member of [group] and emits each result as it lands, in
     * completion order. [testUrl] is the group's own health-check URL when it
     * has one, so results match what the group itself measures.
     */
    fun test(
        group: String,
        type: String,
        members: List<String>,
        testUrl: String? = null,
    ): Flow<MemberDelay> = flow {
        val url = testUrl?.takeIf { it.isNotBlank() } ?: MeowApi.DELAY_TEST_URL
        if (type == URL_TEST || type == FALLBACK) {
            try {
                api.unfixProxy(group)
            } catch (e: MeowApiException) {
                // The probes are still worth running; the pin just stays.
                Timber.w(e, "unpinning %s before a latency test failed", group)
            }
        }
        emitAll(members.distinct().mapBounded(limiter) { MemberDelay(it, probe(it, url)) })
        if (type == URL_TEST) limiter.withPermit { probe(group, url) }
    }

    private suspend fun probe(name: String, url: String): Int =
        try {
            api.testProxyDelay(name, url, timeoutMs)
        } catch (e: MeowApiException) {
            // 504 is a timeout and 503 a failed dial; either way the node is
            // unusable right now, and the engine has recorded a 0 for it.
            0
        }

    companion object {
        /** Per member. The old whole-group call shared 60 s across every member. */
        const val PROBE_TIMEOUT_MS = 5_000

        private const val URL_TEST = "URLTest"
        private const val FALLBACK = "Fallback"
    }
}

/**
 * Runs [transform] over every item, at most [limiter]'s permits at a time, and
 * emits each result as soon as it is ready — so one slow item delays only
 * itself, never the results queued behind it. Permits are taken in list
 * order ([Semaphore] is fair), so the head of the list is probed first.
 */
internal fun <T, R> List<T>.mapBounded(
    limiter: Semaphore,
    transform: suspend (T) -> R,
): Flow<R> = channelFlow {
    for (item in this@mapBounded) {
        // Sent outside the permit: a slow collector must not hold up probes.
        launch { send(limiter.withPermit { transform(item) }) }
    }
}
