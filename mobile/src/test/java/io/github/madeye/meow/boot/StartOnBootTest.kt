package io.github.madeye.meow.boot

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class StartOnBootTest {

    @Test
    fun `a boot connects with a profile and consent`() {
        assertEquals(BootStart.Connect, bootStart(hasProfile = true, hasConsent = true))
    }

    @Test
    fun `a boot without consent asks for it through the notification`() {
        assertEquals(BootStart.AskToConnect, bootStart(hasProfile = true, hasConsent = false))
    }

    @Test
    fun `a boot without a profile does nothing, consent or not`() {
        assertEquals(BootStart.Skip, bootStart(hasProfile = false, hasConsent = true))
        assertEquals(BootStart.Skip, bootStart(hasProfile = false, hasConsent = false))
    }

    @Test
    fun `the preference starts from the component state and writes every flip`() {
        val written = mutableListOf<Boolean>()
        val pref = StartOnBootPreference(read = { true }, write = { written += it })
        assertTrue(pref.enabled.value)

        pref.set(false)
        pref.set(true)

        assertTrue(pref.enabled.value)
        assertEquals(listOf(false, true), written)
    }

    @Test
    fun `a write that throws leaves the switch where it was`() {
        val pref = StartOnBootPreference(read = { false }, write = { error("refused") })

        runCatching { pref.set(true) }

        assertFalse(pref.enabled.value)
    }

    /**
     * The manifest is the setting's other half: off until the switch enables
     * the receiver, and reachable by the system alone. Gradle runs JVM tests
     * from the module directory.
     */
    @Test
    fun `the receiver ships disabled, unexported, in vpn and for boot only`() {
        val receiver = elements(File("src/main/AndroidManifest.xml"), "receiver")
            .single { it.androidAttr("name") == "io.github.madeye.meow.boot.BootReceiver" }

        assertEquals("false", receiver.androidAttr("enabled"))
        assertEquals("false", receiver.androidAttr("exported"))
        assertEquals(":vpn", receiver.androidAttr("process"))
        val actions = receiver.getElementsByTagName("action").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).androidAttr("name") }
        }
        assertEquals(listOf("android.intent.action.BOOT_COMPLETED"), actions)
    }

    @Test
    fun `the boot permission is declared`() {
        val permissions = elements(File("../core/src/main/AndroidManifest.xml"), "uses-permission")
            .map { it.androidAttr("name") }
        assertTrue(permissions.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
    }

    private fun elements(manifest: File, tag: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidAttr(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
