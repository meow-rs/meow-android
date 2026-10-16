package io.github.madeye.meow.net

import io.github.madeye.meow.core.MeowCore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where an IP is registered, as far as the bundled GeoX databases know. */
@Serializable
data class IpGeo(
    /**
     * ISO 3166-1 alpha-2, or one of the database's own codes for ranges it
     * files by owner or use (`GOOGLE`, `CLOUDFLARE`, `PRIVATE`…) — what a
     * GEOIP rule matches on. Null when unknown.
     */
    val country: String? = null,
    val asn: Long? = null,
    /** The AS organisation. */
    val org: String? = null,
)

/**
 * Looks IPs up in the GeoX databases held in the engine home dir, from this
 * process and without the network. The VPN service seeds them on start, so
 * before its first start every answer is empty.
 */
class IpGeoLookup(private val dir: () -> File) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(ip: String): IpGeo = withContext(Dispatchers.IO) {
        val answer = MeowCore.nativeLookupIp(dir().absolutePath, ip) ?: return@withContext IpGeo()
        try {
            json.decodeFromString(IpGeo.serializer(), answer)
        } catch (e: SerializationException) {
            IpGeo()
        }
    }
}
