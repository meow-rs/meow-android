package io.github.madeye.meow.preference

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinDnsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storeFile(): File = File(tmp.root, "builtin_dns")

    @Test
    fun `nothing saved means the profile's DNS`() {
        assertFalse(BuiltinDnsStore(storeFile()).load())
    }

    @Test
    fun `a save is visible to a separate instance`() {
        // Stands in for the UI process writing and `:vpn` reading.
        BuiltinDnsStore(storeFile()).save(true)

        assertTrue(BuiltinDnsStore(storeFile()).load())
    }

    @Test
    fun `switching back off is saved too`() {
        val store = BuiltinDnsStore(storeFile())
        store.save(true)
        store.save(false)

        assertFalse(store.load())
        assertFalse(File(tmp.root, "builtin_dns.tmp").exists())
    }

    @Test
    fun `an unreadable value means the profile's DNS`() {
        storeFile().writeText("yes")

        assertFalse(BuiltinDnsStore(storeFile()).load())
    }
}
