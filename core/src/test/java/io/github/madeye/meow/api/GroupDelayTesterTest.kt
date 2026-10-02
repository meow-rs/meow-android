package io.github.madeye.meow.api

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Virtual-time controls (currentTime, advanceTimeBy) are still marked experimental.
@OptIn(ExperimentalCoroutinesApi::class)
class GroupDelayTesterTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MeowApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = MeowApi(baseUrl = server.url("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -------------------------------------------------------------------------
    // mapBounded — on virtual time, so "when" is exact
    // -------------------------------------------------------------------------

    @Test
    fun `mapBounded never runs more than the permits at once`() = runTest {
        var inFlight = 0
        var peak = 0

        val results = (1..20).toList().mapBounded(Semaphore(4)) { item ->
            inFlight++
            peak = maxOf(peak, inFlight)
            delay(100L * (item % 3 + 1))
            inFlight--
            item
        }.toList()

        assertEquals(4, peak)
        assertEquals((1..20).toSet(), results.toSet())
    }

    @Test
    fun `mapBounded emits each result when it lands, not when the batch ends`() = runTest {
        val landed = mutableListOf<Pair<Int, Long>>()

        // The 900 ms straggler must not hold back anything behind it.
        listOf(900, 100, 300, 200).mapBounded(Semaphore(8)) {
            delay(it.toLong())
            it
        }.collect { landed += it to currentTime }

        assertEquals(listOf(100 to 100L, 200 to 200L, 300 to 300L, 900 to 900L), landed)
    }

    @Test
    fun `mapBounded hands freed permits to the queue in list order`() = runTest {
        val started = mutableListOf<Pair<String, Long>>()

        listOf("a" to 500L, "b" to 100L, "c" to 100L, "d" to 100L)
            .mapBounded(Semaphore(2)) { (name, ms) ->
                started += name to currentTime
                delay(ms)
                name
            }.toList()

        // c takes b's permit, then d takes c's, all while a is still running.
        assertEquals(listOf("a" to 0L, "b" to 0L, "c" to 100L, "d" to 200L), started)
    }

    @Test
    fun `one limiter bounds concurrent runs together`() = runTest {
        val limiter = Semaphore(3)
        var inFlight = 0
        var peak = 0
        val work: suspend (Int) -> Int = {
            inFlight++
            peak = maxOf(peak, inFlight)
            delay(100)
            inFlight--
            it
        }

        val first = async { (1..5).toList().mapBounded(limiter, work).toList() }
        val second = async { (6..10).toList().mapBounded(limiter, work).toList() }

        assertEquals(10, first.await().size + second.await().size)
        assertEquals(3, peak)
    }

    @Test
    fun `cancelling the collector cancels the probes in flight and queued`() = runTest {
        var finished = 0
        val collector = launch {
            listOf(1, 2, 3).mapBounded(Semaphore(2)) {
                delay(1_000)
                finished++
                it
            }.collect {}
        }

        advanceTimeBy(500)
        collector.cancelAndJoin()
        advanceUntilIdle()

        assertEquals(0, finished)
    }

    // -------------------------------------------------------------------------
    // GroupDelayTester against a mock engine
    // -------------------------------------------------------------------------

    private fun route(routes: Map<String, MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.route()] ?: MockResponse().setResponseCode(404)
        }
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setBody(body)

    private fun RecordedRequest.route() = "$method ${requestUrl!!.encodedPath}"

    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    @Test
    fun `an url-test group is unpinned, probed member by member, then re-picked`() = runTest {
        route(
            mapOf(
                "DELETE /proxies/Auto" to MockResponse().setResponseCode(204),
                "GET /proxies/Tokyo/delay" to json("""{"delay": 80}"""),
                "GET /proxies/SG/delay" to json("""{"message": "Timeout"}""", code = 504),
                "GET /proxies/HK/delay" to
                    json("""{"message": "An error occurred in the delay test"}""", code = 503),
                "GET /proxies/Auto/delay" to json("""{"delay": 81}"""),
            ),
        )

        val results = GroupDelayTester(api)
            .test("Auto", "URLTest", listOf("Tokyo", "SG", "HK"), testUrl = "https://cp.cloudflare.com/")
            .toList()

        // One dead member no longer costs the others their results.
        assertEquals(
            mapOf("Tokyo" to 80, "SG" to 0, "HK" to 0),
            results.associate { it.name to it.delayMs },
        )
        val requests = requests()
        assertEquals("DELETE /proxies/Auto", requests.first().route())
        // The group's own probe is not a member result, and comes after all of them.
        assertEquals("GET /proxies/Auto/delay", requests.last().route())
        for (probe in requests.drop(1)) {
            assertEquals("https://cp.cloudflare.com/", probe.requestUrl!!.queryParameter("url"))
            assertEquals("5000", probe.requestUrl!!.queryParameter("timeout"))
        }
    }

    @Test
    fun `a selector is neither unpinned nor re-picked and uses the default url`() = runTest {
        route(
            mapOf(
                "GET /proxies/a/delay" to json("""{"delay": 12}"""),
                "GET /proxies/b/delay" to json("""{"delay": 34}"""),
            ),
        )

        val results = GroupDelayTester(api).test("Proxy", "Selector", listOf("a", "b", "a")).toList()

        assertEquals(setOf(MemberDelay("a", 12), MemberDelay("b", 34)), results.toSet())
        val requests = requests()
        // The duplicate "a" is probed once.
        assertEquals(listOf("GET /proxies/a/delay", "GET /proxies/b/delay"), requests.map { it.route() }.sorted())
        assertTrue(requests.all { it.requestUrl!!.queryParameter("url") == MeowApi.DELAY_TEST_URL })
    }

    @Test
    fun `a fallback group is unpinned but not re-picked, and a failed unpin still probes`() = runTest {
        route(
            mapOf(
                "DELETE /proxies/FB" to json("""{"message": "Body invalid"}""", code = 400),
                "GET /proxies/a/delay" to json("""{"delay": 50}"""),
            ),
        )

        val results = GroupDelayTester(api).test("FB", "Fallback", listOf("a")).toList()

        assertEquals(listOf(MemberDelay("a", 50)), results)
        assertEquals(listOf("DELETE /proxies/FB", "GET /proxies/a/delay"), requests().map { it.route() })
    }

    @Test
    fun `a dead engine fails every member instead of throwing`() = runTest {
        val deadPort = server.port
        server.shutdown()
        val offline = MeowApi(baseUrl = "http://127.0.0.1:$deadPort".toHttpUrl())

        val results = GroupDelayTester(offline).test("Proxy", "Selector", listOf("a", "b")).toList()

        assertEquals(setOf(MemberDelay("a", 0), MemberDelay("b", 0)), results.toSet())
    }

    @Test
    fun `probes run eight at a time, past OkHttp's five-per-host default`() = runTest {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val allOpen = CountDownLatch(MeowApi.PROBE_CONCURRENCY)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                allOpen.countDown()
                // Hold each answer until the full complement is open at once;
                // a client capped below it gives up waiting and shows a lower peak.
                allOpen.await(5, TimeUnit.SECONDS)
                inFlight.decrementAndGet()
                return json("""{"delay": 10}""")
            }
        }

        val results = GroupDelayTester(api).test("Proxy", "Selector", (1..12).map { "n$it" }).toList()

        assertEquals(12, results.size)
        assertEquals(MeowApi.PROBE_CONCURRENCY, peak.get())
    }
}
