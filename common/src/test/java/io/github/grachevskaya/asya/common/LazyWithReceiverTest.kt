package io.github.grachevskaya.asya.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The lazy this module uses hands the receiver to its initializer. What has to hold is that the
 * initializer runs once and only when the value is first asked for: the hooks build their tables
 * with it and a table built twice is a table that leaks.
 */
class LazyWithReceiverTest {
    private class Holder {
        var builds = 0

        val value: String by lazyWithReceiver {
            builds++
            "value-$builds"
        }
    }

    @Test
    fun theInitializerRunsOnlyWhenTheValueIsAskedFor() {
        val holder = Holder()

        assertEquals(0, holder.builds)
        assertEquals("value-1", holder.value)
        assertEquals(1, holder.builds)
    }

    @Test
    fun theValueIsBuiltOnce() {
        val holder = Holder()

        val first = holder.value
        val second = holder.value

        assertEquals(first, second)
        assertEquals(1, holder.builds)
        assertTrue(holder.value.endsWith("1"))
    }

    @Test
    fun theReceiverIsTheOwner() {
        val holder = Holder()

        assertFalse(holder.value.contains("Holder"))
    }
}
