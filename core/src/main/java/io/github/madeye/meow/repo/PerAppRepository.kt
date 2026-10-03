package io.github.madeye.meow.repo

import io.github.madeye.meow.preference.DataStore
import io.github.madeye.meow.preference.PerAppConfigStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Which apps the tunnel applies to. */
enum class PerAppMode(val key: String) {
    /** Only the selected packages are routed through the tunnel. */
    Proxy("proxy"),

    /** Everything except the selected packages is routed. */
    Bypass("bypass"),
    ;

    companion object {
        fun from(key: String?): PerAppMode =
            entries.firstOrNull { it.key == key } ?: Proxy
    }
}

data class PerAppConfig(
    val mode: PerAppMode = PerAppMode.Proxy,
    val packages: Set<String> = emptySet(),
)

/**
 * Per-app proxy selection. `VpnService` reads the same config when building
 * the TUN interface — through [PerAppConfigStore]'s file, not SharedPreferences,
 * because `:vpn` caches the preferences file for the life of the process and
 * would otherwise replay a stale selection after edits. The legacy
 * SharedPreferences keys are still written so downgrades and first-run reads
 * keep working; the file wins whenever it exists.
 */
class PerAppRepository(
    private val json: Json = Json,
    private val store: PerAppConfigStore = PerAppConfigStore.default,
) {

    suspend fun load(): PerAppConfig = withContext(Dispatchers.IO) {
        store.load()?.let {
            return@withContext PerAppConfig(PerAppMode.from(it.mode), it.packages)
        }
        val packages = try {
            json.decodeFromString(ListSerializer(String.serializer()), DataStore.perAppPackages)
        } catch (e: Exception) {
            emptyList()
        }
        PerAppConfig(
            mode = PerAppMode.from(DataStore.perAppMode),
            packages = packages.toSet(),
        )
    }

    suspend fun save(config: PerAppConfig) = withContext(Dispatchers.IO) {
        try {
            store.save(config.mode.key, config.packages)
        } catch (e: IOException) {
            // The file is the authority for :vpn; failing it degrades back to
            // the stale-prefs path this used to be — worth a warning, but the
            // visible save still lands via the prefs below.
            Timber.w(e, "per-app config file write failed; :vpn may see stale selection")
        }
        DataStore.perAppMode = config.mode.key
        DataStore.perAppPackages =
            json.encodeToString(ListSerializer(String.serializer()), config.packages.toList())
    }
}
