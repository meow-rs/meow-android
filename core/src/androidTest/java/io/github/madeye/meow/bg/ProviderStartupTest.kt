package io.github.madeye.meow.bg

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.madeye.meow.Core
import io.github.madeye.meow.api.MeowApi
import io.github.madeye.meow.core.MeowCore
import io.github.madeye.meow.database.ClashProfile
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderStartupTest {
    @Test
    fun delayedProviderDoesNotBlockMainAndPopulatesItsGroup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Core.init(app)
        val configDir = File(app.cacheDir, "provider-startup-test").apply { mkdirs() }
        val requested = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val mainAlive = CountDownLatch(1)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    requested.countDown()
                    check(releaseResponse.await(10, TimeUnit.SECONDS))
                    val body = "proxies:\n  - {name: remote-node, type: socks5, server: 127.0.0.1, port: 1080}\n".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            } catch (e: java.net.SocketException) {
                if (!server.isClosed) throw e
            }
        }
        val profile = ClashProfile(name = "Provider only", yamlContent = """
            proxies: []
            proxy-providers:
              remote:
                type: http
                url: http://127.0.0.1:${server.localPort}/nodes.yaml
                path: ./nodes.yaml
                interval: 3600
            proxy-groups:
              - name: selected
                type: select
                use: [remote]
            rules: ["MATCH,selected"]
        """.trimIndent())
        val instance = MeowInstance(profile)
        val startup = async(Dispatchers.Main) { instance.start(configDir, app) }
        try {
            assertTrue("engine never requested the remote provider", requested.await(10, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post { mainAlive.countDown() }
            val responsive = mainAlive.await(2, TimeUnit.SECONDS)
            releaseResponse.countDown()
            withTimeout(10_000) { startup.await() }
            assertTrue("provider download blocked Android's main thread", responsive)
            assertTrue(MeowCore.nativeIsRunning())
            val api = MeowApi()
            val result = withTimeout(5_000) {
                // The native API task binds just after engine publication.
                var response = runCatching { api.proxies() }
                while (response.isFailure) {
                    delay(50)
                    response = runCatching { api.proxies() }
                }
                response.getOrThrow()
            }
            assertEquals(listOf("remote-node"), result.groups.getValue("selected").all)
        } finally {
            releaseResponse.countDown()
            server.close()
            withContext(NonCancellable + Dispatchers.IO) {
                startup.join()
                responder.join(10_000)
                instance.stop()
            }
            configDir.deleteRecursively()
        }
    }
}
