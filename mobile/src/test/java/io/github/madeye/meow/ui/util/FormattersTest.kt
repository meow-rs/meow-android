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

    @Test
    fun `dateTime shows an instant in the local zone, to the second`() {
        assertEquals("2026-10-16 02:13:01", Formatters.dateTime("2026-10-16T02:13:01Z", ZoneOffset.UTC))
        assertEquals("2026-10-16 10:13:01", Formatters.dateTime("2026-10-16T02:13:01Z", ZoneId.of("Asia/Shanghai")))
        assertEquals("", Formatters.dateTime("", ZoneOffset.UTC))
        assertEquals("", Formatters.dateTime("yesterday", ZoneOffset.UTC))
    }

    @Test
    fun `dateTime shows a Unix time in milliseconds in the local zone`() {
        assertEquals("2026-10-16 02:13:01", Formatters.dateTime(1_792_116_781_500L, ZoneOffset.UTC))
        assertEquals("2026-10-16 10:13:01", Formatters.dateTime(1_792_116_781_500L, ZoneId.of("Asia/Shanghai")))
        assertEquals("", Formatters.dateTime(0L, ZoneOffset.UTC))
    }

    @Test
    fun `clockTimeMillis shows an engine note time to the millisecond`() {
        val time = "2026-10-16T02:13:01.042917Z"
        assertEquals("02:13:01.042", Formatters.clockTimeMillis(time, ZoneOffset.UTC))
        assertEquals("10:13:01.042", Formatters.clockTimeMillis(time, ZoneId.of("Asia/Shanghai")))
        assertEquals("02:13:01.000", Formatters.clockTimeMillis("2026-10-16T02:13:01Z", ZoneOffset.UTC))
        assertEquals("", Formatters.clockTimeMillis("", ZoneOffset.UTC))
    }

    @Test
    fun `clockTime shows only the local time of day`() {
        val instant = 1_792_116_781_000L // 2026-10-16T02:13:01Z
        assertEquals("02:13:01", Formatters.clockTime(instant, ZoneOffset.UTC))
        assertEquals("10:13:01", Formatters.clockTime(instant, ZoneId.of("Asia/Shanghai")))
        assertEquals("", Formatters.clockTime(0, ZoneOffset.UTC))
    }

    @Test
    fun `bitRate steps by thousands like link speeds`() {
        assertEquals("850 bps", Formatters.bitRate(850.0))
        assertEquals("940 Kbps", Formatters.bitRate(940_000.0))
        assertEquals("85.3 Mbps", Formatters.bitRate(85_300_000.0))
        assertEquals("1.2 Gbps", Formatters.bitRate(1_200_000_000.0))
    }
}
