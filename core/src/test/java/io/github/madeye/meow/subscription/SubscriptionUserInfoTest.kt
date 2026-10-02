package io.github.madeye.meow.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUserInfoTest {

    // -------------------------------------------------------------------------
    // parse
    // -------------------------------------------------------------------------

    @Test
    fun `parses the canonical header`() {
        val info = SubscriptionUserInfo.parse(
            "upload=455727941; download=6174315083; total=1073741824000; expire=1798675200",
        )

        assertEquals(SubscriptionUserInfo(455727941, 6174315083, 1073741824000, 1798675200), info)
    }

    @Test
    fun `keys parse in any order and case, with or without spaces`() {
        val info = SubscriptionUserInfo.parse("EXPIRE = 4 ;Total=3;download =2;  Upload=  1  ")

        assertEquals(SubscriptionUserInfo(1, 2, 3, 4), info)
    }

    @Test
    fun `missing keys read as not reported`() {
        val info = SubscriptionUserInfo.parse("upload=10; download=20")

        assertEquals(SubscriptionUserInfo(upload = 10, download = 20), info)
        assertFalse(info.hasQuota)
        assertFalse(info.hasExpiry)
    }

    @Test
    fun `an absent or empty header is NONE`() {
        assertEquals(SubscriptionUserInfo.NONE, SubscriptionUserInfo.parse(null))
        assertEquals(SubscriptionUserInfo.NONE, SubscriptionUserInfo.parse(""))
        assertEquals(SubscriptionUserInfo.NONE, SubscriptionUserInfo.parse("   "))
    }

    @Test
    fun `a header without any known key is NONE`() {
        assertEquals(SubscriptionUserInfo.NONE, SubscriptionUserInfo.parse("garbage"))
        assertEquals(SubscriptionUserInfo.NONE, SubscriptionUserInfo.parse("plan=pro; renew=yes"))
    }

    @Test
    fun `segments without a value, stray separators and unknown keys are skipped`() {
        val info = SubscriptionUserInfo.parse(";;upload; download=5;=7; plan=pro; total=9;")

        assertEquals(SubscriptionUserInfo(download = 5, total = 9), info)
    }

    @Test
    fun `junk values read as 0 without hiding the good fields`() {
        val info = SubscriptionUserInfo.parse("upload=abc; download=12MB; total=; expire=1798675200")

        assertEquals(SubscriptionUserInfo(expire = 1798675200), info)
    }

    @Test
    fun `negative values clamp to 0`() {
        val info = SubscriptionUserInfo.parse("upload=-1; download=-2.5; total=-100; expire=-1")

        assertEquals(SubscriptionUserInfo.NONE, info)
    }

    @Test
    fun `decimal and exponent values truncate`() {
        val info = SubscriptionUserInfo.parse("upload=1.9; download=1.5E10; total=1e12; expire=1798675200.0")

        assertEquals(SubscriptionUserInfo(1, 15_000_000_000, 1_000_000_000_000, 1798675200), info)
    }

    @Test
    fun `values past Long range saturate instead of wrapping`() {
        val info = SubscriptionUserInfo.parse(
            "upload=99999999999999999999999; total=9223372036854775808; download=1e400",
        )

        assertEquals(Long.MAX_VALUE, info.upload)
        assertEquals(Long.MAX_VALUE, info.download)
        assertEquals(Long.MAX_VALUE, info.total)
    }

    @Test
    fun `NaN reads as 0`() {
        assertEquals(SubscriptionUserInfo.NONE, SubscriptionUserInfo.parse("total=NaN"))
    }

    @Test
    fun `a repeated key keeps the last value`() {
        assertEquals(SubscriptionUserInfo(total = 2), SubscriptionUserInfo.parse("total=1; total=2"))
    }

    // -------------------------------------------------------------------------
    // derived values
    // -------------------------------------------------------------------------

    @Test
    fun `used sums upload and download, saturating`() {
        assertEquals(30L, SubscriptionUserInfo(upload = 10, download = 20).used)
        assertEquals(Long.MAX_VALUE, SubscriptionUserInfo(upload = Long.MAX_VALUE, download = 1).used)
    }

    @Test
    fun `usedFraction is 0 without a quota and clamps past it`() {
        assertEquals(0f, SubscriptionUserInfo(download = 50).usedFraction(), 0f)
        assertEquals(0.25f, SubscriptionUserInfo(upload = 10, download = 15, total = 100).usedFraction(), 1e-6f)
        assertEquals(1f, SubscriptionUserInfo(download = 150, total = 100).usedFraction(), 0f)
    }

    @Test
    fun `isExpired is never true without an expiry`() {
        assertFalse(SubscriptionUserInfo.NONE.isExpired(nowEpochSeconds = Long.MAX_VALUE))
    }

    @Test
    fun `isExpired flips at the expiry instant`() {
        val info = SubscriptionUserInfo(expire = 1_000)

        assertFalse(info.isExpired(nowEpochSeconds = 999))
        assertTrue(info.isExpired(nowEpochSeconds = 1_000))
        assertTrue(info.isExpired(nowEpochSeconds = 5_000))
    }
}
