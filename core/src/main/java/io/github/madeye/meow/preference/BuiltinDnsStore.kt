package io.github.madeye.meow.preference

import io.github.madeye.meow.Core
import java.io.File
import java.io.IOException

/**
 * Settings' "Built-in DNS" switch, replayed into every engine start. On, the
 * engine drops the profile's DNS upstreams for the built-in pool; off — the
 * default, and what a missing or unreadable file means — it runs on the
 * profile's `dns:` nameservers.
 *
 * Same constraint as [RouteModeStore]: the UI process writes and `:vpn`
 * reads, so it is a one-line file read fresh on every start rather than
 * SharedPreferences, which `:vpn` would only ever read once.
 */
class BuiltinDnsStore(private val file: File) {

    fun load(): Boolean =
        try {
            file.readText() == BUILTIN
        } catch (_: Exception) {
            // IOException for a missing/gone file; the wide catch keeps an
            // undeclared RuntimeException from aborting MeowInstance.start
            // in :vpn — a lost switch just follows the profile.
            false
        }

    fun save(enabled: Boolean) {
        val tmp = File(file.path + ".tmp")
        try {
            tmp.writeText(if (enabled) BUILTIN else PROFILE)
            if (!tmp.renameTo(file)) throw IOException("cannot replace $file")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val BUILTIN = "builtin"
        private const val PROFILE = "profile"

        val default: BuiltinDnsStore by lazy {
            BuiltinDnsStore(File(Core.deviceStorage.noBackupFilesDir, "builtin_dns"))
        }
    }
}
