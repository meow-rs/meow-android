package io.github.madeye.meow.preference

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiSecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storeFile(): File = File(tmp.root, "api_secret")

    @Test
    fun `nothing saved means no secret`() {
        assertNull(ApiSecretStore(storeFile()).load())
    }

    @Test
    fun `a save is visible to a separate instance`() {
        // Stands in for `:vpn` writing and the UI process reading.
        ApiSecretStore(storeFile()).save("abc")

        assertEquals("abc", ApiSecretStore(storeFile()).load())
    }

    @Test
    fun `the next start's secret replaces the last one`() {
        val store = ApiSecretStore(storeFile())
        store.save("first")
        store.save("second")

        assertEquals("second", store.load())
        assertFalse(File(tmp.root, "api_secret.tmp").exists())
    }

    @Test
    fun `an engine without a secret saves none`() {
        val store = ApiSecretStore(storeFile())
        store.save("first")
        store.save("")

        assertNull(store.load())
    }

    @Test
    fun `minted secrets are 32 random bytes in hex`() {
        val a = ApiSecretStore.mint()
        val b = ApiSecretStore.mint()

        assertTrue(a, a.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(a, b)
    }
}
