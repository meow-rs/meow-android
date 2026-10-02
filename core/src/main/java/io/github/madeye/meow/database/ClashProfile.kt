package io.github.madeye.meow.database

import androidx.room.*
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
}
