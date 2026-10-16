package io.github.madeye.meow.net

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.madeye.meow.Core
import io.github.madeye.meow.bg.MeowInstance
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The connection detail page's IP lookups, through JNI into the bundled GeoX databases. */
@RunWith(AndroidJUnit4::class)
class IpGeoLookupTest {

    @Test
    fun looksUpCountryAndNetworkOwnerOffline() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Core.init(app)
        val lookup = IpGeoLookup { MeowInstance.prepareEngineHome(app) }

        val unicom = lookup.lookup("157.148.41.176")
        assertEquals("CN", unicom.country)
        assertEquals(136958L, unicom.asn)

        val google = lookup.lookup("8.8.8.8")
        assertEquals(15169L, google.asn)
        // The same answer for the IPv4-mapped form.
        assertEquals(google, lookup.lookup("::ffff:8.8.8.8"))

        assertEquals(IpGeo(), lookup.lookup("not an ip"))
        assertEquals(IpGeo(), IpGeoLookup { File(app.cacheDir, "no-geox") }.lookup("8.8.8.8"))
    }
}
