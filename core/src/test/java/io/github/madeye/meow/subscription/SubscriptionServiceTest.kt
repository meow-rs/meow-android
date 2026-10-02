package io.github.madeye.meow.subscription

import io.github.madeye.meow.database.ClashProfile
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The fetch path only — [SubscriptionService.fetchSubscription] returns the
 * updated profile without touching the database, so it runs on the JVM.
 */
class SubscriptionServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun profile(userInfo: SubscriptionUserInfo = SubscriptionUserInfo.NONE) =
        ClashProfile(name = "sub", url = server.url("/sub").toString(), userInfo = userInfo)

    @Test
    fun `fetch stores the userinfo header, whatever its case`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("proxies: []\n")
                .addHeader("Subscription-Userinfo", "upload=1; download=2; total=3; expire=1798675200"),
        )

        val fetched = SubscriptionService.fetchSubscription(profile())

        assertEquals("proxies: []\n", fetched.yamlContent)
        assertEquals(SubscriptionUserInfo(1, 2, 3, 1798675200), fetched.userInfo)
    }

    @Test
    fun `a fetch without the header clears the stored figures`() = runTest {
        server.enqueue(MockResponse().setBody("proxies: []\n"))

        val fetched = SubscriptionService.fetchSubscription(
            profile(SubscriptionUserInfo(upload = 1, download = 2, total = 3, expire = 4)),
        )

        assertEquals(SubscriptionUserInfo.NONE, fetched.userInfo)
    }

    @Test
    fun `a failed fetch throws, so callers keep the last-known figures`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .addHeader("subscription-userinfo", "total=1"),
        )

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { SubscriptionService.fetchSubscription(profile()) }
        }
    }
}
