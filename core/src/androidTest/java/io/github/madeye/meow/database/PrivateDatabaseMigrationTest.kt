package io.github.madeye.meow.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A missing or wrong migration is silent data loss here: the database is built
 * with `fallbackToDestructiveMigration()`, so Room would drop every profile.
 * Validates against the exported core/schemas JSON.
 */
@RunWith(AndroidJUnit4::class)
class PrivateDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PrivateDatabase::class.java,
    )

    @Test
    fun migrate5To6_keepsProfilesAndEnablesAutoUpdate() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO clash_profile (name, url, yaml_content, selected, last_updated, tx, rx, " +
                    "selected_proxy, yaml_backup, sub_upload, sub_download, sub_total, sub_expire) " +
                    "VALUES ('sub', 'https://example.com/c.yaml', 'edited: true', 1, 1234, 5, 6, 'node', " +
                    "'fetched: true', 7, 8, 9, 10)",
            )
        }

        helper.runMigrationsAndValidate(DB, 6, true, PrivateDatabase.MIGRATION_5_6).use { db ->
            db.query(
                "SELECT name, yaml_content, yaml_backup, last_updated, selected_proxy, sub_total, " +
                    "auto_update, update_interval_hours FROM clash_profile",
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("sub", row.getString(0))
                assertEquals("edited: true", row.getString(1))
                assertEquals("fetched: true", row.getString(2))
                assertEquals(1234L, row.getLong(3))
                assertEquals("node", row.getString(4))
                assertEquals(9L, row.getLong(5))
                assertEquals(1, row.getInt(6))
                assertEquals(24, row.getInt(7))
            }
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
