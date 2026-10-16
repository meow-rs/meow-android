package io.github.madeye.meow.ui.screens.connections

import io.github.madeye.meow.api.ConnectionNote
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class NotesPlainTextTest {

    @Test
    fun `copied notes are one local-time line each, in order`() {
        val notes = listOf(
            ConnectionNote("2026-10-16T02:13:01.042917Z", "dns", "tls://dns.alidns.com failed for aweme.snssdk.com"),
            ConnectionNote("2026-10-16T02:13:11.057Z", "rule", "Matched rule #27 MATCH → 风之谷"),
        )
        assertEquals(
            "10:13:01.042  tls://dns.alidns.com failed for aweme.snssdk.com\n" +
                "10:13:11.057  Matched rule #27 MATCH → 风之谷",
            notes.asPlainText(ZoneId.of("Asia/Shanghai")),
        )
    }

    @Test
    fun `a note without a readable time copies its text alone`() {
        assertEquals("closed", listOf(ConnectionNote(time = "", text = "closed")).asPlainText(ZoneId.of("UTC")))
    }
}
