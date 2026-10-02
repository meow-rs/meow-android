package io.github.madeye.meow.ui.screens.home

import io.github.madeye.meow.net.ExitIp
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitIpFormatTest {

    private fun exit(
        countryCode: String? = "FI",
        country: String? = "Finland",
        asn: Int? = 24940,
        org: String? = "Hetzner Online GmbH",
    ) = ExitIp("203.0.113.7", countryCode, country, asn, org)

    @Test
    fun `flag is the pair of regional indicators`() {
        assertEquals("🇫🇮", exit(countryCode = "FI").flagEmoji())
        assertEquals("🇺🇸", exit(countryCode = "US").flagEmoji())
        assertNull(exit(countryCode = null).flagEmoji())
    }

    @Test
    fun `country is named in the UI language, not the service's English`() {
        assertEquals("Finland", exit().countryName(Locale.ENGLISH))
        assertEquals("芬兰", exit().countryName(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `a code the platform cannot name falls back to the service's name, then the code`() {
        // AA is a user-assigned code: valid syntax, no name.
        assertEquals("Somewhere", exit(countryCode = "AA", country = "Somewhere").countryName(Locale.ENGLISH))
        assertEquals("AA", exit(countryCode = "AA", country = null).countryName(Locale.ENGLISH))
        assertEquals("Finland", exit(countryCode = null).countryName(Locale.ENGLISH))
        assertNull(exit(countryCode = null, country = null).countryName(Locale.ENGLISH))
    }

    @Test
    fun `network label joins whichever of ASN and org is known`() {
        assertEquals("AS24940 Hetzner Online GmbH", exit().networkLabel())
        assertEquals("AS24940", exit(org = null).networkLabel())
        assertEquals("Hetzner Online GmbH", exit(asn = null).networkLabel())
        assertNull(exit(asn = null, org = null).networkLabel())
    }
}
