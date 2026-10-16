package io.github.madeye.meow.preference

import io.github.madeye.meow.Core
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale

/**
 * The secret the engine's controller API on 127.0.0.1:9090 runs with, so the
 * app's own client can authenticate and other apps on the device cannot read
 * `/connections` or drive the engine.
 *
 * `:vpn` writes it on every engine start: the profile's `secret:` when it
 * sets one (an on-device dashboard keeps the credential its user chose),
 * else one minted by [mint] for that start. The UI process reads it on every
 * request, so a restart's new secret applies from the next call. A one-line
 * file in no-backup storage, like [BuiltinDnsStore], because
 * SharedPreferences would be read only once per process.
 */
class ApiSecretStore(private val file: File) {

    /** The current engine's secret; null when none was ever saved. */
    fun load(): String? =
        try {
            file.readText().ifEmpty { null }
        } catch (_: Exception) {
            // IOException for a missing file; the wide catch keeps an
            // undeclared RuntimeException out of OkHttp's interceptor chain.
            null
        }

    fun save(secret: String) {
        val tmp = File(file.path + ".tmp")
        try {
            tmp.writeText(secret)
            if (!tmp.renameTo(file)) throw IOException("cannot replace $file")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val SECRET_BYTES = 32

        private val random by lazy { SecureRandom() }

        /** A fresh random secret: [SECRET_BYTES] bytes as lowercase hex. */
        fun mint(): String {
            val bytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
            return bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
        }

        val default: ApiSecretStore by lazy {
            ApiSecretStore(File(Core.deviceStorage.noBackupFilesDir, "api_secret"))
        }
    }
}
