package io.github.madeye.meow.preference

import io.github.madeye.meow.Core
import java.io.File
import java.io.IOException

/**
 * Settings' "Built-in DNS" switch, replayed into every engine start. On, the
 * engine drops the profile's DNS upstreams for the built-in pool (the
 * default). Only an explicitly saved off value opts into the profile's
 * `dns:` nameservers; missing, unreadable or invalid state stays built-in.
 *
 * Same constraint as [RouteModeStore]: the UI process writes and `:vpn`
 * reads, so it is a one-line file read fresh on every start rather than
 * SharedPreferences, which `:vpn` would only ever read once.
 */
class BuiltinDnsStore(private val file: File) {

    fun load(): Boolean =
        try {
            file.readText() != PROFILE
        } catch (_: Exception) {
            // IOException for a missing/gone file; the wide catch keeps an
            // undeclared RuntimeException from aborting MeowInstance.start
            // in :vpn — a lost switch restores the built-in default.
            true
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
