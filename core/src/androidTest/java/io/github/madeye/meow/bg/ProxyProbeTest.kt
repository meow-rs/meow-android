package io.github.madeye.meow.bg

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.madeye.meow.Core
import io.github.madeye.meow.core.MeowCore
import io.github.madeye.meow.database.ClashProfile
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The proxy detail page's probes through the real JNI entry points and a
 * running engine (the binder hop in front of them is a straight pass-through).
 */
@RunWith(AndroidJUnit4::class)
class ProxyProbeTest {

    private fun probe(id: Long, proxy: String, kind: String, arg: String = ""): JsonObject {
        val answer = MeowCore.nativeProbeProxy(id, proxy, kind, arg)
        return Json.parseToJsonElement(checkNotNull(answer) { "no answer" }).jsonObject
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun probesRunThroughTheNamedProxyOfTheRunningEngine() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Core.init(app)
        val configDir = File(app.cacheDir, "proxy-probe-test").apply { mkdirs() }
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true) {
            try {
                while (true) {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                        val body = "{\"ip\":\"198.51.100.1\"}".toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    }
                }
            } catch (e: java.net.SocketException) {
                if (!server.isClosed) throw e
            }
        }

        assertEquals(
            "engine-stopped",
            probe(1, "DIRECT", "http-get", "http://127.0.0.1:${server.localPort}/").text("code"),
        )

        val instance = MeowInstance(ClashProfile(name = "Probe", yamlContent = "proxies: []\nrules: [\"MATCH,DIRECT\"]\n"))
        try {
            withContext(Dispatchers.Main) { instance.start(configDir, app) }
            assertTrue(MeowCore.nativeIsRunning())

            val get = probe(2, "DIRECT", "http-get", "http://127.0.0.1:${server.localPort}/geoip")
            assertEquals("200", get.text("status"))
            assertEquals("{\"ip\":\"198.51.100.1\"}", get.text("body"))

            assertEquals("no-proxy", probe(3, "no such node", "http-get", "http://127.0.0.1/").text("code"))
            assertEquals("bad-request", probe(4, "DIRECT", "no such probe").text("code"))

            // A cancel can reach `:vpn` before its probe; the probe still ends.
            MeowCore.nativeCancelProxyProbe(5)
            assertEquals("cancelled", probe(5, "DIRECT", "http-get", "http://127.0.0.1:${server.localPort}/").text("code"))
        } finally {
            server.close()
            withContext(Dispatchers.IO) {
                responder.join(10_000)
                instance.stop()
            }
            configDir.deleteRecursively()
        }
    }
}
