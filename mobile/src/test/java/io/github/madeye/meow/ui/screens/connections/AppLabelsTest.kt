package io.github.madeye.meow.ui.screens.connections

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLabelsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val lookups = CopyOnWriteArrayList<String>()
    private val labels = AppLabels(scope) { pkg ->
        lookups += pkg
        INSTALLED[pkg]
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `each package is resolved once however often it is requested`() = runBlocking {
        labels.request(listOf("com.android.chrome", "com.android.chrome", ""))
        labels.request(listOf("com.android.chrome", "com.example.mail"))

        val resolved = withTimeout(5_000) { labels.labels.first { it.size == 2 } }

        assertEquals(mapOf("com.android.chrome" to "Chrome", "com.example.mail" to "Inbox"), resolved)
        assertEquals(listOf("com.android.chrome", "com.example.mail"), lookups.sorted())
        assertEquals("Chrome", labels.labelOf("com.android.chrome"))
        assertEquals(2, lookups.size)
    }

    @Test
    fun `a package without a label stands for itself`() = runBlocking {
        assertEquals("com.example.gone", labels.labelOf("com.example.gone"))
        assertEquals("com.example.blank", labels.labelOf("com.example.blank"))

        labels.request(listOf("com.example.gone"))
        assertEquals(listOf("com.example.gone", "com.example.blank"), lookups.toList())
        assertEquals("com.example.gone", labels.labels.value["com.example.gone"])
    }

    private companion object {
        val INSTALLED = mapOf(
            "com.android.chrome" to "Chrome",
            "com.example.mail" to "Inbox",
            "com.example.blank" to " ",
        )
    }
}
