package io.github.madeye.meow.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class FormattersTest {

    // 2026-12-31T23:30:00Z
    private val newYearsEveLate = 1_798_759_800L

    @Test
    fun `date formats the local calendar day`() {
        assertEquals("2026-12-31", Formatters.date(newYearsEveLate, ZoneOffset.UTC))
    }

    @Test
    fun `date follows the zone across midnight`() {
        assertEquals("2027-01-01", Formatters.date(newYearsEveLate, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-12-31", Formatters.date(newYearsEveLate, ZoneId.of("America/New_York")))
    }

    @Test
    fun `date is blank when not reported`() {
        assertEquals("", Formatters.date(0, ZoneOffset.UTC))
        assertEquals("", Formatters.date(-5, ZoneOffset.UTC))
    }

    @Test
    fun `date is blank past what java time can represent`() {
        assertEquals("", Formatters.date(Long.MAX_VALUE, ZoneOffset.UTC))
    }
}
