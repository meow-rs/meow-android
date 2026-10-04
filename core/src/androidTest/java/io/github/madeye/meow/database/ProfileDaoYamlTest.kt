package io.github.madeye.meow.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for the YAML editor's persistence layer: [ProfileDao.updateYamlContent]
 * (called when the user saves an edited config) and [ProfileDao.revertYamlContent]
 * (called when the user taps Revert — must restore the last upstream-fetched
 * yaml stored in [ClashProfile.yamlBackup]).
 */
@RunWith(AndroidJUnit4::class)
class ProfileDaoYamlTest {

    private lateinit var db: PrivateDatabase
    private lateinit var dao: ProfileDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PrivateDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.profileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun updateYamlContent_changesContent_keepsBackup() {
        // A local profile (file import or copy): no URL.
        val id = dao.insert(
            ClashProfile(
                name = "test",
                yamlContent = "imported: true\n",
                yamlBackup = "imported: true\n",
            )
        )

        dao.updateYamlContent(id, "edited: true\n")

        val after = dao.getById(id)!!
        assertEquals("edited: true\n", after.yamlContent)
        // Backup must NOT change on edit — it is what Revert restores.
        assertEquals("imported: true\n", after.yamlBackup)
    }

    @Test
    fun updateYamlContent_refusesSubscriptions() {
        val id = dao.insert(
            ClashProfile(
                name = "test",
                url = "https://example.com/c.yaml",
                yamlContent = "fetched: true\n",
                yamlBackup = "fetched: true\n",
            )
        )

        dao.updateYamlContent(id, "edited: true\n")

        // The provider's config is read-only; the user edits a copy.
        assertEquals("fetched: true\n", dao.getById(id)!!.yamlContent)
    }

    @Test
    fun revertYamlContent_restoresBackupIntoContent() {
        val id = dao.insert(
            ClashProfile(
                name = "test",
                yamlContent = "edited: true\n",
                yamlBackup = "pristine: true\n",
            )
        )

        dao.revertYamlContent(id)

        val after = dao.getById(id)!!
        assertEquals("pristine: true\n", after.yamlContent)
        // Backup remains the upstream snapshot — revert is idempotent.
        assertEquals("pristine: true\n", after.yamlBackup)
    }

    @Test
    fun revertYamlContent_isIdempotentWhenAlreadyPristine() {
        val id = dao.insert(
            ClashProfile(
                name = "test",
                yamlContent = "same: true\n",
                yamlBackup = "same: true\n",
            )
        )

        dao.revertYamlContent(id)

        val after = dao.getById(id)!!
        assertEquals("same: true\n", after.yamlContent)
        assertEquals("same: true\n", after.yamlBackup)
    }

    @Test
    fun updateYamlContent_doesNotTouchOtherProfiles() {
        val id1 = dao.insert(ClashProfile(name = "a", yamlContent = "x: 1\n", yamlBackup = "x: 1\n"))
        val id2 = dao.insert(ClashProfile(name = "b", yamlContent = "y: 2\n", yamlBackup = "y: 2\n"))

        dao.updateYamlContent(id1, "x: 99\n")

        assertEquals("x: 99\n", dao.getById(id1)!!.yamlContent)
        assertEquals("y: 2\n", dao.getById(id2)!!.yamlContent)
        assertEquals("y: 2\n", dao.getById(id2)!!.yamlBackup)
    }
}
