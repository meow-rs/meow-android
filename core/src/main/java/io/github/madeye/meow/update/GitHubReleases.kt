package io.github.madeye.meow.update

import io.github.madeye.meow.api.await
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A published release of the app on GitHub. */
data class AppRelease(
    /** The tag without its `v`, e.g. `1.0.9`. */
    val version: String,
    /** The release notes as written, in Markdown; empty when there are none. */
    val notes: String,
    /** The release's page on github.com. */
    val pageUrl: String,
    /** The APK attached to the release, or null when it has none. */
    val apkUrl: String?,
)

/**
 * Asks the GitHub API for the newest release of the app — the update channel
 * of every build but the Play one, which may only update through Play.
 *
 * `/releases/latest` already leaves out drafts and pre-releases. Without a
 * token GitHub allows 60 calls an hour per address, plenty for a check that
 * only runs on tap; behind a shared exit address the limit can still run out,
 * so that refusal gets its own message.
 */
class GitHubReleases(
    private val userAgent: String,
    private val url: HttpUrl = LATEST_URL.toHttpUrl(),
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        const val LATEST_URL = "https://api.github.com/repos/meow-rs/meow-android/releases/latest"
        const val TIMEOUT_MS = 15_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Throws [IOException] when GitHub could not be reached or gave no usable answer. */
    suspend fun latest(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            // GitHub rejects API calls without one.
            .header("User-Agent", userAgent)
            .build()
        val body = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                // An exhausted rate limit is a 403 or 429 with nothing remaining.
                val limited = response.code in RATE_LIMITED && response.header("x-ratelimit-remaining") == "0"
                if (limited) throw IOException("GitHub rate limit reached, try again later")
                throw IOException("GitHub returned HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
        try {
            parseRelease(Json.parseToJsonElement(body).jsonObject)
        } catch (e: IllegalArgumentException) {
            // Not JSON (a captive portal's page), or not an object.
            throw IOException("GitHub: unreadable answer", e)
        }
    }
}

private val RATE_LIMITED = setOf(403, 429)

/**
 * `{"tag_name": "v1.0.8", "html_url": "…", "body": "…", "assets": [{"name":
 * "meow-v1.0.8-universal.apk", "browser_download_url": "…"}, …], …}`
 */
internal fun parseRelease(json: JsonObject): AppRelease {
    val tag = json.string("tag_name") ?: throw IOException("GitHub: release has no tag")
    val version = tag.removePrefix("v")
    if (Version.parse(version) == null) throw IOException("GitHub: unrecognised release tag $tag")
    val apks = (json["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
        val asset = element as? JsonObject ?: return@mapNotNull null
        val name = asset.string("name") ?: return@mapNotNull null
        val download = asset.string("browser_download_url") ?: return@mapNotNull null
        (name to download).takeIf { name.endsWith(".apk", ignoreCase = true) }
    }
    return AppRelease(
        version = version,
        notes = json.string("body").orEmpty(),
        pageUrl = json.string("html_url") ?: throw IOException("GitHub: release has no page"),
        // Should per-ABI APKs ever sit beside the universal one, only the
        // universal one is sure to install.
        apkUrl = (apks.firstOrNull { (name, _) -> "universal" in name } ?: apks.firstOrNull())?.second,
    )
}

/**
 * Whether [candidate] is a newer version than [installed]. Versions compare
 * numerically part by part, so `1.0.10` is newer than `1.0.9` and `1.1`
 * equals `1.1.0`; a leading `v` and `+build` metadata are ignored, and a
 * pre-release (`1.1.0-rc.1`) ranks below its release, as in SemVer. False
 * when either is not a version.
 */
fun isNewerVersion(candidate: String, installed: String): Boolean {
    val new = Version.parse(candidate) ?: return false
    val old = Version.parse(installed) ?: return false
    return new > old
}

private class Version(val numbers: List<Long>, val preRelease: List<String>) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val byNumber = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (byNumber != 0) return byNumber
        }
        return when {
            preRelease.isEmpty() && other.preRelease.isEmpty() -> 0
            preRelease.isEmpty() -> 1
            other.preRelease.isEmpty() -> -1
            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    companion object {
        fun parse(text: String): Version? {
            val bare = text.trim().removePrefix("v").substringBefore('+')
            // Signs are cut off with the suffixes, so this only takes digits.
            val numbers = bare.substringBefore('-').split('.').map { it.toLongOrNull() ?: return null }
            val preRelease = bare.substringAfter('-', missingDelimiterValue = "")
                .split('.')
                .filter { it.isNotEmpty() }
            return Version(numbers, preRelease)
        }

        /**
         * SemVer's order: identifier by identifier, numbers by value and below
         * words; when one list runs out first, it ranks lower.
         */
        private fun comparePreRelease(a: List<String>, b: List<String>): Int {
            for ((x, y) in a.zip(b)) {
                val xn = x.toLongOrNull()
                val yn = y.toLongOrNull()
                val order = when {
                    xn != null && yn != null -> xn.compareTo(yn)
                    xn != null -> -1
                    yn != null -> 1
                    else -> x.compareTo(y)
                }
                if (order != 0) return order
            }
            return a.size.compareTo(b.size)
        }
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
