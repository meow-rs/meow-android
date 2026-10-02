package io.github.madeye.meow.database

import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.madeye.meow.Core

@Database(entities = [ClashProfile::class, DailyTraffic::class], version = 6)
abstract class PrivateDatabase : RoomDatabase() {
    companion object {
        // Every shipped schema step must have a migration here; the SQL
        // mirrors core/schemas/.../{N}.json. Without them Room falls back to
        // dropping every table, wiping all profiles and traffic history.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_traffic` (`date` TEXT NOT NULL, " +
                        "`tx` INTEGER NOT NULL, `rx` INTEGER NOT NULL, PRIMARY KEY(`date`))",
                )
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `clash_profile` ADD COLUMN `selected_proxy` TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `clash_profile` ADD COLUMN `yaml_backup` TEXT NOT NULL DEFAULT ''")
                // Seed the revert target with the current config so "revert"
                // doesn't blank out a migrated profile.
                db.execSQL("UPDATE `clash_profile` SET `yaml_backup` = `yaml_content`")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // subscription-userinfo plan usage. 0 is "not reported", the
                // same value a fetch without the header writes.
                db.execSQL("ALTER TABLE `clash_profile` ADD COLUMN `sub_upload` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `clash_profile` ADD COLUMN `sub_download` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `clash_profile` ADD COLUMN `sub_total` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `clash_profile` ADD COLUMN `sub_expire` INTEGER NOT NULL DEFAULT 0")
            }
        }
        // Existing subscriptions get auto-update ON at the default interval,
        // the same as a new one: a stale node list fails quietly, which is
        // what the feature exists to prevent, and hand-edited configs stay
        // safe because the worker skips them (AutoUpdateSchedule.isDue). The
        // DEFAULTs must match ClashProfile's @ColumnInfo, which Room validates.
        @VisibleForTesting
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `clash_profile` ADD COLUMN `auto_update` INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "ALTER TABLE `clash_profile` ADD COLUMN `update_interval_hours` INTEGER NOT NULL DEFAULT 24",
                )
            }
        }

        private val instance by lazy {
            // The database was named mihomo.db before the app-wide
            // mihomo→meow rename; move an existing file (and its WAL/SHM
            // sidecars) so installed users keep their profiles.
            val legacy = Core.deviceStorage.getDatabasePath("mihomo.db")
            val current = Core.deviceStorage.getDatabasePath("meow.db")
            if (legacy.exists() && !current.exists()) {
                current.parentFile?.mkdirs()
                for (suffix in listOf("", "-wal", "-shm")) {
                    val from = java.io.File(legacy.path + suffix)
                    if (from.exists()) from.renameTo(java.io.File(current.path + suffix))
                }
            }
            Room.databaseBuilder(Core.deviceStorage, PrivateDatabase::class.java, "meow.db")
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // Only reached when no migration path exists (e.g. a downgrade).
                .fallbackToDestructiveMigration()
                .build()
        }

        val profileDao get() = instance.profileDao()
        val dailyTrafficDao get() = instance.dailyTrafficDao()
    }

    abstract fun profileDao(): ProfileDao
    abstract fun dailyTrafficDao(): DailyTrafficDao
}
