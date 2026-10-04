package io.github.madeye.meow.subscription

import io.github.madeye.meow.database.ClashProfile
import io.github.madeye.meow.database.PrivateDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object SubscriptionService {
    /** Upper bound on a subscription body; real configs are well under this. */
    private const val MAX_SUBSCRIPTION_BYTES = 5 * 1024 * 1024

    /** Bounds the whole fetch; `readTimeout` only bounds each individual read. */
    private const val FETCH_TIMEOUT_MS = 60_000L

    suspend fun fetchSubscription(profile: ClashProfile): ClashProfile = withContext(Dispatchers.IO) {
        val url = URL(profile.url)
        val connection = url.openConnection()
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "clash.meta/1.0")
        val (yaml, userInfo) = try {
            // The timeout interrupts the reading thread; readCapped checks the
            // flag between reads, so a slow-trickle body stops within one
            // readTimeout of the deadline.
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                runInterruptible {
                    val body = connection.inputStream.use(::readCapped)
                    body to SubscriptionUserInfo.parse(connection.getHeaderField(SubscriptionUserInfo.HEADER))
                }
            } ?: throw IOException("subscription download timed out")
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
        // The header describes the plan as of this response, so a successful
        // fetch without it clears the stored figures (parse returns NONE)
        // instead of keeping them: the provider dropped it, or the URL now
        // points elsewhere, and a stale quota would be worse than none. A
        // failed fetch throws above and leaves the last-known figures alone.
        profile.copy(
            yamlContent = yaml,
            yamlBackup = yaml,
            lastUpdated = System.currentTimeMillis(),
            userInfo = userInfo,
        )
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            if (Thread.interrupted()) throw InterruptedException()
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_SUBSCRIPTION_BYTES) {
                throw IOException("subscription exceeds ${MAX_SUBSCRIPTION_BYTES / (1024 * 1024)} MB")
            }
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    suspend fun addSubscription(name: String, url: String): ClashProfile = withContext(Dispatchers.IO) {
        val profile = ClashProfile(name = name, url = url)
        val fetched = fetchSubscription(profile)
        val id = PrivateDatabase.profileDao.insert(fetched)
        fetched.copy(id = id)
    }

    /// Create a profile from a YAML string the user imported from a file, or
    /// copied from another profile (`ProfileRepository.duplicate`). It has no
    /// source URL, so refresh-from-URL skips it (see
    /// `ProfileRepository.refresh`) and its YAML stays editable.
    suspend fun addLocal(name: String, yamlContent: String): ClashProfile = withContext(Dispatchers.IO) {
        val profile = ClashProfile(
            name = name,
            url = "",
            yamlContent = yamlContent,
            yamlBackup = yamlContent,
            lastUpdated = System.currentTimeMillis(),
        )
        val id = PrivateDatabase.profileDao.insert(profile)
        profile.copy(id = id)
    }
}
