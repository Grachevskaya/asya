package io.github.grachevskaya.asya.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The counts the manager shows come from here, and an empty holder is what a service with no rule
 * yet has: nothing counted, nothing summed.
 */
class FilterHolderTest {
    @Test
    fun anEmptyHolderCountsNothing() {
        val holder = FilterHolder()

        assertTrue(holder.filterCounts.isEmpty())
        assertEquals(0, holder.totalCount)
    }
}
