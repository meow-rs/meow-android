package io.github.madeye.meow.ui.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionsTest {

    @Test
    fun `a two-letter code renders as its flag`() {
        assertEquals("🇨🇳", regionFlag("CN"))
        assertEquals("🇺🇸", regionFlag("US"))
    }

    @Test
    fun `codes that are not two capital letters have no flag`() {
        // The GeoX country database files some ranges by owner or use.
        assertNull(regionFlag("GOOGLE"))
        assertNull(regionFlag("PRIVATE"))
        assertNull(regionFlag("cn"))
        assertNull(regionFlag(""))
    }

    @Test
    fun `the platform names a region in the given language`() {
        assertEquals("China", regionName("CN", Locale.ENGLISH))
        assertEquals("中国", regionName("CN", Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `no name for a code the platform does not know`() {
        assertNull(regionName("AA", Locale.ENGLISH))
        assertNull(regionName("GOOGLE", Locale.ENGLISH))
        assertNull(regionName("", Locale.ENGLISH))
    }
}
