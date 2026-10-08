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

    @Test
    fun theHolderSumsWhatEveryAppWasFilteredBy() {
        val holder = FilterHolder()

        holder.filterCounts["com.example.a"] = FilterHolder.FilterCount(packageManagerCount = 1, activityLaunchCount = 0)
        holder.filterCounts["com.example.b"] = FilterHolder.FilterCount(packageManagerCount = 0, activityLaunchCount = 4)

        assertEquals(5, holder.totalCount)
    }

    @Test
    fun theCountsSurviveTheRoundTripTheyAreWrittenAs() {
        val holder = FilterHolder()

        holder.filterCounts["com.example.a"] = FilterHolder.FilterCount(packageManagerCount = 7, activityLaunchCount = 1)
        holder.filterCounts["com.example.b"] = FilterHolder.FilterCount()

        assertEquals(holder, FilterHolder.parse(holder.toString()))
    }
}
