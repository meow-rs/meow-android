package io.github.madeye.meow.preference

import io.github.madeye.meow.Core
import java.io.File
import java.io.IOException

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

    private val tmpFile get() = File(file.path + ".tmp")

    fun save(mode: String, packages: Set<String>) {
        // Write-then-rename so a concurrent load never sees a torn value.
        // File.renameTo, not Files.move: java.nio.file only exists at API 26+
        // (the desugar flavor in use doesn't cover it), while renameTo lowers
        // to rename(2) — already an atomic replace for a same-directory move.
        // copyTo is the fallback for a filesystem that refuses the rename —
        // not atomic, but strictly better than failing the write.
        tmpFile.writeText((listOf(mode) + packages).joinToString("\n"))
        if (!tmpFile.renameTo(file)) {
            tmpFile.copyTo(file, overwrite = true)
            tmpFile.delete()
        }
    }

    /** Drops the file (and any tmp leftover) so readers fall back to the prefs keys. */
    fun clear() {
        file.delete()
        tmpFile.delete()
    }

    data class Stored(val mode: String, val packages: Set<String>)

    companion object {
        val default: PerAppConfigStore by lazy {
            PerAppConfigStore(File(Core.deviceStorage.noBackupFilesDir, "per_app"))
        }
    }
}
