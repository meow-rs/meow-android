package io.github.madeye.meow.preference

import io.github.madeye.meow.Core
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The per-app proxy selection, replayed into every TUN establish.
 *
 * Same constraint as [RouteModeStore]: the UI process writes and `:vpn`
 * reads, so it cannot live in SharedPreferences — `VpnService` would see the
 * value from whatever the `:vpn` process first cached. A small file is read
 * fresh on every establish. The old `DataStore.perAppMode`/`perAppPackages`
 * keys stay written for backward compatibility but are no longer read here.
 *
 * Layout: first line is the mode key, remaining lines are package names
 * (package names cannot contain newlines).
 */
class PerAppConfigStore(private val file: File) {

    fun load(): Stored? = try {
        val lines = file.readLines()
        if (lines.isEmpty()) null else Stored(mode = lines[0], packages = lines.drop(1).toSet())
    } catch (_: IOException) {
        null
    }

    fun save(mode: String, packages: Set<String>) {
        // Write-then-rename so a concurrent load never sees a torn value.
        // Files.move, not File.renameTo: replace semantics on renameTo are
        // platform-defined, while ATOMIC_MOVE + REPLACE_EXISTING is the
        // guaranteed form on every filesystem that can do it (tmp and target
        // sit in the same directory, so an atomic move always can).
        val tmp = File(file.path + ".tmp")
        tmp.writeText((listOf(mode) + packages).joinToString("\n"))
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
    }

    data class Stored(val mode: String, val packages: Set<String>)

    companion object {
        val default: PerAppConfigStore by lazy {
            PerAppConfigStore(File(Core.deviceStorage.noBackupFilesDir, "per_app"))
        }
    }
}
