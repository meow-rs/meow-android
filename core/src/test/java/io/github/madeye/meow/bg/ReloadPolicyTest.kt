package io.github.madeye.meow.bg

import io.github.madeye.meow.bg.BaseService.State
import io.github.madeye.meow.bg.ReloadPolicy.Decision
import io.github.madeye.meow.database.ClashProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadPolicyTest {

    private val a = ActiveConfig(1, "proxies: [a]")
    private val b = ActiveConfig(2, "proxies: [b]")

    @Test
    fun `switching profile reloads`() {
        assertTrue(ReloadPolicy.needsReload(a, b))
    }

    @Test
    fun `new YAML for the selected profile reloads`() {
        assertTrue(ReloadPolicy.needsReload(a, a.copy(yaml = "proxies: [a2]")))
    }

    @Test
    fun `byte-identical YAML does not reload`() {
        // A fresh read, not the same instance: equality is by content.
        assertFalse(ReloadPolicy.needsReload(a, ActiveConfig(1, "proxies: [a]")))
    }

    @Test
    fun `selecting a profile after none was selected reloads`() {
        // The running profile was deleted, then another one picked.
        assertTrue(ReloadPolicy.needsReload(null, b))
    }

    @Test
    fun `nothing selected afterwards does not reload`() {
        // Deleting the selected profile must not stop the VPN.
        assertFalse(ReloadPolicy.needsReload(a, null))
        assertFalse(ReloadPolicy.needsReload(null, null))
    }

    @Test
    fun `only the id and YAML of a profile count`() {
        val profile = ClashProfile(id = 1, name = "a", url = "https://a", yamlContent = "proxies: [a]")
        // What a refresh with unchanged content writes back.
        val refreshed = profile.copy(name = "renamed", lastUpdated = 42, yamlBackup = "proxies: [a]", tx = 9)

        assertEquals(a, ActiveConfig.of(profile))
        assertFalse(ReloadPolicy.needsReload(ActiveConfig.of(profile), ActiveConfig.of(refreshed)))
        assertNull(ActiveConfig.of(null))
    }

    @Test
    fun `log lines carry the YAML size, not the YAML`() {
        assertFalse(ActiveConfig(1, "secret-password").toString().contains("secret-password"))
    }

    @Test
    fun `connected restarts only when the selection differs from what runs`() {
        assertEquals(Decision.Restart, ReloadPolicy.onRequest(State.Connected, a) { b })
        assertEquals(Decision.Ignore, ReloadPolicy.onRequest(State.Connected, a) { ActiveConfig(1, "proxies: [a]") })
        assertEquals(Decision.Ignore, ReloadPolicy.onRequest(State.Connected, a) { null })
    }

    @Test
    fun `connecting defers without reading the selection`() {
        assertEquals(Decision.Defer, ReloadPolicy.onRequest(State.Connecting, a) { error("read while connecting") })
    }

    @Test
    fun `nothing happens unless the engine is running`() {
        // Stopping covers both a user disconnect and a reload's own restart.
        for (state in listOf(State.Idle, State.Stopping, State.Stopped)) {
            assertEquals(state.name, Decision.Ignore, ReloadPolicy.onRequest(state, a) { error("read in $state") })
        }
    }
}
