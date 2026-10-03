package io.github.madeye.meow.repo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /**
     * Another installed package shares this UID. VPN application rules are
     * UID-granular, so bulk-selecting one of these silently routes every
     * sibling the user never picked.
     */
    val sharesUid: Boolean,
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
        val infos = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        // UID sharing must be judged against every package PM knows —
        // including disabled, archived, and keep-data-uninstalled siblings
        // (a shared UID routes as a unit whenever a sibling exists again,
        // so the narrow picker enumeration alone would undercount and a
        // wrongly-"private" UID fails in the unsafe direction).
        val uidCounts = installedPackages(includeUninstalled = true)
            .mapNotNull { it.applicationInfo?.uid }
            .groupingBy { it }
            .eachCount()
        infos.asSequence()
            // An app with no launcher entry and no internet permission cannot
            // generate tunnelled traffic, but filtering on that is unreliable
            // across OEMs; keep every package and let the UI filter instead.
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = packageManager.getApplicationLabel(info).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    sharesUid = uidCounts.getValue(info.uid) > 1,
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
     * archived ones. [includeUninstalled] additionally counts
     * keep-data-uninstalled packages — needed for UID-sharing questions
     * (a sibling that comes back later still routes by UID), but NOT for
     * "is this package present" questions like ghost pruning.
     */
    private fun installedPackages(includeUninstalled: Boolean = false): List<PackageInfo> {
        // MATCH_ARCHIVED_PACKAGES is Long-typed; the legacy int flags widen.
        var flags: Long = (
            PackageManager.GET_META_DATA or
                PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
                if (includeUninstalled) PackageManager.MATCH_UNINSTALLED_PACKAGES else 0
            ).toLong()
        if (Build.VERSION.SDK_INT >= 35) {
            flags = flags or PackageManager.MATCH_ARCHIVED_PACKAGES
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(flags.toInt())
        }
    }

    /** Null when the package vanished between listing and drawing. */
    suspend fun icon(packageName: String): Drawable? = withContext(Dispatchers.IO) {
        try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
