package io.github.madeye.meow.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.madeye.meow.subscription.SubscriptionUserInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [storeFetched] writes a download back up to a minute after the row was read.
 * Each test reads the row, changes it the way the UI could while the fetch
 * runs, then stores a "download" made from the stale snapshot.
 */
@RunWith(AndroidJUnit4::class)
class ProfileDaoFetchTest {

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

    private fun insertSubscription(name: String, selected: Boolean = false) = dao.insert(
        ClashProfile(
            name = name,
            url = "https://example.com/$name.yaml",
            yamlContent = "old: true\n",
            yamlBackup = "old: true\n",
            selected = selected,
            lastUpdated = 1,
        )
    )

    /**
     * Edits saved in the YAML editor before subscription configs became
     * read-only. [ProfileDao.updateYamlContent] refuses subscriptions now, but
     * such rows are still around and auto-update must keep them.
     */
    private fun saveLegacyEdits(id: Long) =
        dao.update(dao.getById(id)!!.copy(yamlContent = "edited: true\n"))

    private fun ClashProfile.downloaded() = copy(
        yamlContent = "new: true\n",
        yamlBackup = "new: true\n",
        lastUpdated = 2,
        userInfo = SubscriptionUserInfo(upload = 3, download = 4, total = 5, expire = 6),
    )

    @Test
    fun storeFetched_keepsChangesMadeDuringTheFetch() {
        val a = insertSubscription("a", selected = true)
        val b = insertSubscription("b")
        val snapshot = dao.getById(a)!!

        dao.select(b)
        dao.updateSelectedProxy(a, "node")
        dao.updateTraffic(a, 7, 8)
        dao.updateAutoUpdate(a, false, 6)
        dao.storeFetched(snapshot.downloaded())

        // A stale `selected = 1` written back would leave two rows selected.
        assertEquals(b, dao.getSelected()!!.id)
        val after = dao.getById(a)!!
        assertFalse(after.selected)
        assertEquals("node", after.selectedProxy)
        assertEquals(7L, after.tx)
        assertEquals(8L, after.rx)
        assertFalse(after.autoUpdate)
        assertEquals(6, after.updateIntervalHours)
        // ...while the download itself is stored.
        assertEquals("new: true\n", after.yamlContent)
        assertEquals("new: true\n", after.yamlBackup)
        assertEquals(2L, after.lastUpdated)
        assertEquals(SubscriptionUserInfo(3, 4, 5, 6), after.userInfo)
    }

    @Test
    fun storeFetched_onlyIfUnedited_keepsEditsSavedDuringTheFetch() {
        val id = insertSubscription("a")
        val snapshot = dao.getById(id)!!

        saveLegacyEdits(id)
        dao.storeFetched(snapshot.downloaded(), onlyIfUnedited = true)

        val after = dao.getById(id)!!
        assertEquals("edited: true\n", after.yamlContent)
        assertEquals("old: true\n", after.yamlBackup)
        assertEquals(1L, after.lastUpdated)
        assertEquals(SubscriptionUserInfo.NONE, after.userInfo)
    }

    @Test
    fun storeFetched_onlyIfUnedited_storesAnUneditedConfig() {
        val id = insertSubscription("a")

        dao.storeFetched(dao.getById(id)!!.downloaded(), onlyIfUnedited = true)

        assertEquals("new: true\n", dao.getById(id)!!.yamlContent)
    }

    @Test
    fun storeFetched_overwritesEditsByDefault() {
        // A manual refresh is the user asking for the provider's config.
        val id = insertSubscription("a")
        saveLegacyEdits(id)

        dao.storeFetched(dao.getById(id)!!.downloaded())

        val after = dao.getById(id)!!
        assertEquals("new: true\n", after.yamlContent)
        assertEquals("new: true\n", after.yamlBackup)
    }
}
