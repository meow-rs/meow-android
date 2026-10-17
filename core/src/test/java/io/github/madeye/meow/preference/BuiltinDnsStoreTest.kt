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
    fun `nothing saved means built-in DNS`() {
        assertTrue(BuiltinDnsStore(storeFile()).load())
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
        assertFalse(BuiltinDnsStore(storeFile()).load())
        assertFalse(File(tmp.root, "builtin_dns.tmp").exists())
    }

    @Test
    fun `an invalid value means built-in DNS`() {
        storeFile().writeText("yes")

        assertTrue(BuiltinDnsStore(storeFile()).load())
    }

    @Test
    fun `an empty file means built-in DNS`() {
        storeFile().writeText("")

        assertTrue(BuiltinDnsStore(storeFile()).load())
    }

    @Test
    fun `an unreadable path means built-in DNS`() {
        // A directory reliably fails readText even when tests run as root.
        assertTrue(storeFile().mkdir())

        assertTrue(BuiltinDnsStore(storeFile()).load())
    }

    @Test
    fun `removing a saved preference restores built-in DNS`() {
        BuiltinDnsStore(storeFile()).save(false)
        assertTrue(storeFile().delete())

        assertTrue(BuiltinDnsStore(storeFile()).load())
    }
}
