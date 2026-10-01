package io.github.madeye.meow.preference

import io.github.madeye.meow.Core
import io.github.madeye.meow.api.RouteMode
import java.io.File
import java.io.IOException

/**
 * The route mode the user picked on Home, replayed into every engine start so
 * it survives reconnects and `:vpn` restarts. `null` until the user picks one,
 * meaning "follow the profile's `mode:`".
 *
 * The UI process writes it and `:vpn` reads it, so it cannot live in
 * [DataStore]: SharedPreferences loads its file once per process and never
 * re-reads it, and `:vpn` stays alive as long as an Activity is bound to the
 * service, so a later engine start would see the value from its first read.
 * A one-line file is read fresh every time.
 */
class RouteModeStore(private val file: File) {

    fun load(): RouteMode? =
        try {
            RouteMode.fromWire(file.readText())
        } catch (_: IOException) {
            null
        }

    fun save(mode: RouteMode) {
        // Write-then-rename so a concurrent load never sees a torn value.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(mode.wire)
        if (!tmp.renameTo(file)) throw IOException("cannot replace $file")
    }

    companion object {
        val default: RouteModeStore by lazy {
            RouteModeStore(File(Core.deviceStorage.noBackupFilesDir, "route_mode"))
        }
    }
}
