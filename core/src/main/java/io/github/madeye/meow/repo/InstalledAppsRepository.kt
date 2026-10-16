package io.github.madeye.meow.repo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * The installed-app list behind the per-app proxy picker.
 *
 * Enumerating packages and resolving their labels is slow enough to be visible
 * (hundreds of apps, each a separate PackageManager round trip), so the list is
 * loaded once off the main thread and icons are fetched lazily per row.
 */
class InstalledAppsRepository(private val context: Context) {

    private val packageManager: PackageManager get() = context.packageManager

    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val infos = try {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            // DeadObjectException, OEM SecurityExceptions — an empty picker
            // beats a crash on the caller's uncaught coroutine.
            Timber.w(e, "installed-app enumeration failed")
            return@withContext emptyList()
        }
        infos.asSequence()
            // An app with no launcher entry and no internet permission cannot
            // generate tunnelled traffic, but filtering on that is unreliable
            // across OEMs; keep every package and let the UI filter instead.
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = try {
                        packageManager.getApplicationLabel(info).toString()
                    } catch (e: Exception) {
                        info.packageName
                    },
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Every package name PackageManager knows about, including disabled and
     * archived ones — the ghost-pruning baseline. Deliberately wider than
     * [load]'s picker list: a user-disabled or archived app is a transient
     * state, not an uninstall, and its selection must survive.
     */
    suspend fun installedPackageNames(): Set<String> = withContext(Dispatchers.IO) {
        installedPackages().mapTo(HashSet()) { it.packageName }
    }

    /**
     * The packages PackageManager knows about, including disabled and
     * archived ones — deliberately wider than "currently installed" so ghost
     * pruning keeps selections for apps that are only transiently disabled.
     */
    private fun installedPackages(): List<PackageInfo> {
        // MATCH_ARCHIVED_PACKAGES is Long-typed; the legacy int flags widen.
        var flags: Long = (
            PackageManager.GET_META_DATA or
                PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
            ).toLong()
        if (Build.VERSION.SDK_INT >= 35) {
            flags = flags or PackageManager.MATCH_ARCHIVED_PACKAGES
        }
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(flags.toInt())
            }
        } catch (e: Exception) {
            // Callers read an empty result as "enumeration unavailable":
            // ghost pruning is skipped this round.
            Timber.w(e, "installed-package census failed")
            emptyList()
        }
    }

    /** The display name of one package; null when it is not installed (anymore). */
    suspend fun label(packageName: String): String? = withContext(Dispatchers.IO) {
        try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Null when the package vanished between listing and drawing. */
    suspend fun icon(packageName: String): Drawable? = withContext(Dispatchers.IO) {
        try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }
}
