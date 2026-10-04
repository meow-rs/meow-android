package io.github.madeye.meow.update

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `resources/github-release/latest.json` is the real `/releases/latest`
 * answer for meow-rs/meow-android, recorded verbatim (2026-10, v1.0.8).
 */
class GitHubReleasesTest {

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

    private fun fixture(): String =
        checkNotNull(javaClass.getResource("/github-release/latest.json")) { "missing fixture" }.readText()

    private fun releases() = GitHubReleases(userAgent = "meow-android/1.0.8", url = server.url("/latest"))

    private fun parse(json: String) = parseRelease(Json.parseToJsonElement(json).jsonObject)

    private fun latestFails(): IOException =
        assertThrows(IOException::class.java) { runBlocking { releases().latest() } }

    // -------------------------------------------------------------------------
    // Fetch
    // -------------------------------------------------------------------------

    @Test
    fun `reads the recorded release`() = runTest {
        server.enqueue(MockResponse().setBody(fixture()))

        val release = releases().latest()

        assertEquals("1.0.8", release.version)
        assertEquals("https://github.com/meow-rs/meow-android/releases/tag/v1.0.8", release.pageUrl)
        assertEquals(
            "https://github.com/meow-rs/meow-android/releases/download/v1.0.8/meow-v1.0.8-universal.apk",
            release.apkUrl,
        )
        assertTrue(release.notes.startsWith("## Meow 1.0.8 (versionCode 1000025)"))
    }

    @Test
    fun `asks for the v3 JSON with a user agent`() = runTest {
        server.enqueue(MockResponse().setBody(fixture()))

        releases().latest()

        val request = server.takeRequest()
        assertEquals("/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("meow-android/1.0.8", request.getHeader("User-Agent"))
    }

    @Test
    fun `an exhausted rate limit says so`() {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("x-ratelimit-remaining", "0")
                .setBody("""{"message": "API rate limit exceeded for 203.0.113.7."}"""),
        )

        assertEquals("GitHub rate limit reached, try again later", latestFails().message)
    }

    @Test
    fun `other HTTP errors carry the status`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message": "Not Found"}"""))

        assertEquals("GitHub returned HTTP 404", latestFails().message)
    }

    @Test
    fun `a body that is not JSON is an IOException`() {
        server.enqueue(MockResponse().setBody("<html>Sign in to the Wi-Fi</html>"))

        assertEquals("GitHub: unreadable answer", latestFails().message)
    }

    // -------------------------------------------------------------------------
    // parseRelease
    // -------------------------------------------------------------------------

    @Test
    fun `a release without an APK has no apkUrl`() {
        val release = parse(
            """{"tag_name": "v1.0.9", "html_url": "https://example.com/r", "assets": [
                {"name": "checksums.txt", "browser_download_url": "https://example.com/sums"}
            ]}""",
        )

        assertNull(release.apkUrl)
        assertEquals("", release.notes)
    }

    @Test
    fun `the universal APK wins over per-ABI ones`() {
        val release = parse(
            """{"tag_name": "v1.0.9", "html_url": "https://example.com/r", "assets": [
                {"name": "meow-v1.0.9-arm64-v8a.apk", "browser_download_url": "https://example.com/arm64"},
                {"name": "meow-v1.0.9-universal.apk", "browser_download_url": "https://example.com/universal"}
            ]}""",
        )

        assertEquals("https://example.com/universal", release.apkUrl)
    }

    @Test
    fun `a tag that is not a version is rejected`() {
        assertThrows(IOException::class.java) {
            parse("""{"tag_name": "nightly", "html_url": "https://example.com/r"}""")
        }
    }

    // -------------------------------------------------------------------------
    // isNewerVersion
    // -------------------------------------------------------------------------

    @Test
    fun `parts compare as numbers, not text`() {
        assertTrue(isNewerVersion("1.0.10", "1.0.9"))
        assertFalse(isNewerVersion("1.0.9", "1.0.10"))
        assertTrue(isNewerVersion("1.1.0", "1.0.99"))
        assertTrue(isNewerVersion("2.0", "1.9.9"))
    }

    @Test
    fun `the same version is not newer, however it is written`() {
        assertFalse(isNewerVersion("1.0.8", "1.0.8"))
        assertFalse(isNewerVersion("v1.0.8", "1.0.8"))
        assertFalse(isNewerVersion("1.1", "1.1.0"))
        assertFalse(isNewerVersion("1.1.0", "1.1"))
        assertFalse(isNewerVersion("1.0.8+build.7", "1.0.8"))
    }

    @Test
    fun `a pre-release ranks below its release and above the one before`() {
        assertTrue(isNewerVersion("1.1.0", "1.1.0-rc.1"))
        assertFalse(isNewerVersion("1.1.0-rc.1", "1.1.0"))
        assertTrue(isNewerVersion("1.1.0-rc.1", "1.0.9"))
    }

    @Test
    fun `pre-releases order as SemVer does`() {
        assertTrue(isNewerVersion("1.1.0-rc.10", "1.1.0-rc.9"))
        assertTrue(isNewerVersion("1.1.0-rc.1", "1.1.0-beta.2"))
        assertTrue(isNewerVersion("1.1.0-rc.1", "1.1.0-rc"))
        assertTrue(isNewerVersion("1.1.0-rc", "1.1.0-1"))
    }

    @Test
    fun `anything that is not a version is never newer`() {
        assertFalse(isNewerVersion("nightly", "1.0.8"))
        assertFalse(isNewerVersion("1.0.9", "unknown"))
        assertFalse(isNewerVersion("", "1.0.8"))
        assertFalse(isNewerVersion("1..9", "1.0.8"))
        assertFalse(isNewerVersion("1.0.x", "1.0.8"))
    }
}
