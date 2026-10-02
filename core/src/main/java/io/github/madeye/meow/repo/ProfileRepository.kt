package io.github.madeye.meow.repo

import io.github.madeye.meow.core.MeowCore
import io.github.madeye.meow.database.ClashProfile
import io.github.madeye.meow.database.PrivateDatabase
import io.github.madeye.meow.database.storeFetched
import io.github.madeye.meow.subscription.SubscriptionService
import io.github.madeye.meow.vpn.ConfigReloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Profile and subscription CRUD for the UI.
 *
 * The database is still built with `allowMainThreadQueries()` because
 * `BaseService` reads the selected profile synchronously on the service's main
 * thread. UI code must not rely on that — everything here hops to IO.
 *
 * Writes that can change what the engine runs go through
 * [ConfigReloader.applying], so a connected VPN picks them up.
 */
class ProfileRepository(
    private val reloader: ConfigReloader = ConfigReloader.default,
) {

    private val dao get() = PrivateDatabase.profileDao

    fun observeAll(): Flow<List<ClashProfile>> = dao.observeAll()

    fun observeSelected(): Flow<ClashProfile?> = dao.observeSelected()

    suspend fun getAll(): List<ClashProfile> = withContext(Dispatchers.IO) { dao.getAll() }

    suspend fun getById(id: Long): ClashProfile? = withContext(Dispatchers.IO) { dao.getById(id) }

    suspend fun getSelected(): ClashProfile? = withContext(Dispatchers.IO) { dao.getSelected() }

    suspend fun select(id: Long) = withContext(Dispatchers.IO) {
        reloader.applying { dao.select(id) }
    }

    suspend fun add(name: String, url: String): ClashProfile =
        SubscriptionService.addSubscription(name, url)

    suspend fun addLocal(name: String, yamlContent: String): ClashProfile =
        SubscriptionService.addLocal(name, yamlContent)

    /** Renames/re-points a subscription and immediately re-fetches it. */
    suspend fun update(id: Long, name: String, url: String) = withContext(Dispatchers.IO) {
        reloader.applying {
            val existing = dao.getById(id) ?: return@applying
            existing.name = name
            existing.url = url
            dao.update(existing)
            if (url.isNotEmpty()) {
                dao.storeFetched(SubscriptionService.fetchSubscription(existing))
            }
        }
    }

    /** Stores the background refresh schedule. Unlike [update], no re-fetch. */
    suspend fun setAutoUpdate(id: Long, enabled: Boolean, intervalHours: Int) = withContext(Dispatchers.IO) {
        dao.updateAutoUpdate(id, enabled, intervalHours)
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.getById(id)?.let(dao::delete)
    }

    /**
     * Re-downloads a subscription. With [onlyIfUnedited] a config the user
     * edits while the fetch runs is kept and the download dropped: background
     * updates must not discard edits, while a manual refresh asks to overwrite.
     */
    suspend fun refresh(id: Long, onlyIfUnedited: Boolean = false) = withContext(Dispatchers.IO) {
        reloader.applying {
            val profile = dao.getById(id) ?: return@applying
            if (profile.url.isEmpty()) return@applying
            dao.storeFetched(SubscriptionService.fetchSubscription(profile), onlyIfUnedited)
        }
    }

    suspend fun refreshAll() = SubscriptionService.refreshAll()

    suspend fun updateYaml(id: Long, yaml: String) = withContext(Dispatchers.IO) {
        reloader.applying { dao.updateYamlContent(id, yaml) }
    }

    /** Restores the last downloaded YAML and returns it. */
    suspend fun revertYaml(id: Long): String = withContext(Dispatchers.IO) {
        reloader.applying { dao.revertYamlContent(id) }
        dao.getById(id)?.yamlContent.orEmpty()
    }

    suspend fun saveSelectedProxy(id: Long, proxyName: String) = withContext(Dispatchers.IO) {
        dao.updateSelectedProxy(id, proxyName)
    }
}

/**
 * Validates a Clash config through the Rust engine — the UI never parses YAML
 * itself, so the editor and the engine can never disagree.
 *
 * Safe to call in the UI process: it is a pure function over the passed text.
 * It does require `MeowInstance.prepareEngineHome` to have run, so GeoIP-backed
 * rules resolve.
 */
class ConfigValidator {
    /** Returns null when the config is valid, or the engine's error message. */
    suspend fun validate(yaml: String): String? = withContext(Dispatchers.IO) {
        // The native call reports a status code; the message lives in a
        // separate last-error slot.
        if (MeowCore.nativeValidateConfig(yaml) == 0) null else MeowCore.nativeGetLastError()
    }
}
