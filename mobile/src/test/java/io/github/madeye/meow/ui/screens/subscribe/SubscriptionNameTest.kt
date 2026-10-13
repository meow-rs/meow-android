package io.github.madeye.meow.ui.screens.subscribe

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionNameTest {

    private val generated = Regex("meow-[a-z]{6}")

    @Test
    fun `an entered name is kept`() {
        assertEquals("My Sub", subscriptionName("My Sub"))
        assertEquals("meow", subscriptionName("meow"))
    }

    @Test
    fun `a blank name becomes meow- and six random letters`() {
        listOf("", " ", "\t\n").forEach { blank ->
            val name = subscriptionName(blank)
            assertTrue(name, generated.matches(name))
        }
    }

    @Test
    fun `generated names come from the random source`() {
        assertEquals(subscriptionName("", Random(7)), subscriptionName("", Random(7)))
        val random = Random(7)
        val names = List(100) { subscriptionName("", random) }
        assertTrue(names.all(generated::matches))
        assertEquals(100, names.toSet().size)
        assertNotEquals(subscriptionName("", Random(7)), subscriptionName("", Random(8)))
    }
}
