package io.github.madeye.meow.database

import androidx.room.*
import io.github.madeye.meow.subscription.AutoUpdateSchedule
import io.github.madeye.meow.subscription.SubscriptionUserInfo
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "clash_profile")
data class ClashProfile(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var name: String = "",
    var url: String = "",
    @ColumnInfo(name = "yaml_content") var yamlContent: String = "",
    var selected: Boolean = false,
    @ColumnInfo(name = "last_updated") var lastUpdated: Long = 0,
    var tx: Long = 0,
    var rx: Long = 0,
    @ColumnInfo(name = "selected_proxy") var selectedProxy: String = "",
    @ColumnInfo(name = "yaml_backup") var yamlBackup: String = "",
    /** Provider-reported plan usage as of the last successful fetch; see [SubscriptionUserInfo]. */
    @Embedded(prefix = "sub_") var userInfo: SubscriptionUserInfo = SubscriptionUserInfo.NONE,
    // Background refresh schedule, see AutoUpdateSchedule. File imports carry
    // the defaults too but are never refreshed: they have no URL.
    @ColumnInfo(name = "auto_update", defaultValue = "1") var autoUpdate: Boolean = true,
    @ColumnInfo(name = "update_interval_hours", defaultValue = "24")
    var updateIntervalHours: Int = AutoUpdateSchedule.DEFAULT_INTERVAL_HOURS,
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM clash_profile ORDER BY id ASC")
    fun getAll(): List<ClashProfile>

    @Query("SELECT * FROM clash_profile WHERE selected = 1 LIMIT 1")
    fun getSelected(): ClashProfile?

    /**
     * Observable variants for the Compose UI, so a subscription edit propagates
     * to the home screen without an explicit "profile changed" signal.
     *
     * Room's invalidation tracker is per-process. That is safe today because
     * every write happens in the UI process and `:vpn` only reads; if the
     * service ever starts writing (e.g. [updateTraffic]), the database must be
     * built with `enableMultiInstanceInvalidation()` or these flows will go
     * stale without any visible error.
     */
    @Query("SELECT * FROM clash_profile ORDER BY id ASC")
    fun observeAll(): Flow<List<ClashProfile>>

    @Query("SELECT * FROM clash_profile WHERE selected = 1 LIMIT 1")
    fun observeSelected(): Flow<ClashProfile?>

    @Query("SELECT * FROM clash_profile WHERE id = :id")
    fun getById(id: Long): ClashProfile?

    @Insert
    fun insert(profile: ClashProfile): Long

    @Update
    fun update(profile: ClashProfile)

    @Delete
    fun delete(profile: ClashProfile)

    @Query("UPDATE clash_profile SET selected = 0")
    fun deselectAll()

    /** Selects [id] and deselects every other row in one atomic statement. */
    @Query("UPDATE clash_profile SET selected = (id = :id)")
    fun select(id: Long)

    @Query("UPDATE clash_profile SET tx = :tx, rx = :rx WHERE id = :id")
    fun updateTraffic(id: Long, tx: Long, rx: Long)

    @Query("UPDATE clash_profile SET selected_proxy = :proxyName WHERE id = :id")
    fun updateSelectedProxy(id: Long, proxyName: String)

    @Query("UPDATE clash_profile SET yaml_content = :yaml WHERE id = :id")
    fun updateYamlContent(id: Long, yaml: String)

    @Query("UPDATE clash_profile SET yaml_content = yaml_backup WHERE id = :id")
    fun revertYamlContent(id: Long)

    @Query(
        "UPDATE clash_profile SET auto_update = :enabled, update_interval_hours = :intervalHours WHERE id = :id",
    )
    fun updateAutoUpdate(id: Long, enabled: Boolean, intervalHours: Int)

    /**
     * Stores a download, writing only the columns a fetch produces. The fetch
     * can take a minute, and writing back the whole row read before it would
     * undo whatever changed meanwhile: a profile switch (`selected` is per
     * row, so two rows would end up selected), the selected proxy, traffic,
     * the name or the auto-update schedule. Use [storeFetched].
     *
     * With [onlyIfUnedited] nothing is written if the config has local edits
     * by now, checked in the same statement.
     */
    @Query(
        "UPDATE clash_profile SET yaml_content = :yaml, yaml_backup = :yaml, last_updated = :lastUpdated, " +
            "sub_upload = :upload, sub_download = :download, sub_total = :total, sub_expire = :expire " +
            "WHERE id = :id AND (NOT :onlyIfUnedited OR yaml_content = yaml_backup)",
    )
    fun updateFetched(
        id: Long,
        yaml: String,
        lastUpdated: Long,
        upload: Long,
        download: Long,
        total: Long,
        expire: Long,
        onlyIfUnedited: Boolean,
    )
}

/** [ProfileDao.updateFetched] for a profile returned by `SubscriptionService.fetchSubscription`. */
fun ProfileDao.storeFetched(fetched: ClashProfile, onlyIfUnedited: Boolean = false) = with(fetched) {
    updateFetched(
        id,
        yamlContent,
        lastUpdated,
        userInfo.upload,
        userInfo.download,
        userInfo.total,
        userInfo.expire,
        onlyIfUnedited,
    )
}
