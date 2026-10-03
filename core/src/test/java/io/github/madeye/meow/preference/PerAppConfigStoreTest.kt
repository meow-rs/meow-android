package io.github.madeye.meow.preference

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerAppConfigStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storeFile(): File = File(tmp.root, "per_app")

    @Test
    fun `nothing saved means legacy prefs still own the config`() {
        assertNull(PerAppConfigStore(storeFile()).load())
    }

    @Test
    fun `a save is visible to a separate instance`() {
        // Stands in for the UI process writing and `:vpn` reading.
        PerAppConfigStore(storeFile())
            .save("bypass", setOf("com.tencent.mm", "com.eg.android.AlipayGphone"))

        val stored = PerAppConfigStore(storeFile()).load()
        assertEquals("bypass", stored?.mode)
        assertEquals(setOf("com.tencent.mm", "com.eg.android.AlipayGphone"), stored?.packages)
    }

    @Test
    fun `a later save replaces the earlier one`() {
        val store = PerAppConfigStore(storeFile())
        store.save("proxy", setOf("a.b.c"))
        store.save("bypass", setOf("x.y.z"))

        val stored = store.load()
        assertEquals("bypass", stored?.mode)
        assertEquals(setOf("x.y.z"), stored?.packages)
        assertFalse(File(tmp.root, "per_app.tmp").exists())
    }

    @Test
    fun `an empty file is ignored`() {
        storeFile().writeText("")

        assertNull(PerAppConfigStore(storeFile()).load())
    }

    @Test
    fun `mode-only file round-trips with an empty package set`() {
        PerAppConfigStore(storeFile()).save("proxy", emptySet())

        val stored = PerAppConfigStore(storeFile()).load()
        assertEquals("proxy", stored?.mode)
        assertTrue(stored?.packages?.isEmpty() == true)
    }

    @Test
    fun `clear drops the file and any tmp leftover`() {
        // The write-failure path relies on this: with the stale file gone,
        // the just-written SharedPreferences keys become the source again.
        val store = PerAppConfigStore(storeFile())
        store.save("bypass", setOf("a.b.c"))
        File(tmp.root, "per_app.tmp").writeText("leftover")

        store.clear()

        assertNull(store.load())
        assertFalse(storeFile().exists())
        assertFalse(File(tmp.root, "per_app.tmp").exists())
    }
}
