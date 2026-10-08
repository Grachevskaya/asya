package io.github.grachevskaya.asya.common

import io.github.grachevskaya.asya.common.CollectionUtils.firstOrNullWithType
import io.github.grachevskaya.asya.common.CollectionUtils.firstWithType
import io.github.grachevskaya.asya.common.CollectionUtils.lastOrNullWithType
import io.github.grachevskaya.asya.common.CollectionUtils.lastWithType
import io.github.grachevskaya.asya.common.CollectionUtils.removeIf
import io.github.grachevskaya.asya.common.CollectionUtils.removeIfWithCount
import io.github.grachevskaya.asya.common.CollectionUtils.sync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * The helpers the two halves use to read a frame's arguments and to prune a config. The typed readers
 * throw when the element is absent -- that is what the hooked call sites rely on -- and the pruning
 * counts what it actually removed, because that count is what is written back.
 */
class CollectionUtilsTest {
    @Test
    fun aTypedReadTakesTheFirstElementOfThatType() {
        val frame = arrayOf<Any>(1, "first", "second")

        assertEquals("first", frame.firstWithType<String>())
        assertEquals("second", frame.lastWithType<String>())
        assertEquals(1, frame.firstWithType<Int>())
    }

    @Test
    fun anOptionalTypedReadAnswersNullWhenTheTypeIsNotThere() {
        val frame = arrayOf<Any>(1, 2L)

        assertNull(frame.firstOrNullWithType<String>())
        assertNull(frame.lastOrNullWithType<String>())
    }

    @Test(expected = NoSuchElementException::class)
    fun aTypedReadThrowsWhenTheTypeIsNotThere() {
        arrayOf<Any>(1, 2L).firstWithType<String>()
    }

    @Test(expected = NoSuchElementException::class)
    fun aLastTypedReadThrowsWhenTheTypeIsNotThere() {
        arrayOf<Any>(1, 2L).lastWithType<String>()
    }

    @Test
    fun pruningAMapCountsWhatItRemoved() {
        val map = mutableMapOf("a" to 1, "b" to 2, "c" to 3)

        val removed = map.removeIfWithCount { _, value -> value > 1 }

        assertEquals(2, removed)
        assertEquals(mapOf("a" to 1), map)
    }

    @Test
    fun pruningNothingChangesNothing() {
        val map = mutableMapOf("a" to 1)

        assertEquals(0, map.removeIfWithCount { _, value -> value > 9 })
        assertEquals(mapOf("a" to 1), map)
    }

    @Test
    fun pruningEverythingLeavesAnEmptyMap() {
        val map = mutableMapOf("a" to 1, "b" to 2)

        assertEquals(2, map.removeIfWithCount { _, _ -> true })
        assertEquals(emptyMap<String, Int>(), map)
    }

    @Test
    fun pruningASetCountsWhatItRemoved() {
        val set = mutableSetOf("a", "b", "c")

        assertEquals(2, set.removeIfWithCount { it != "a" })
        assertEquals(setOf("a"), set)
    }

    @Test
    fun syncingAListWithItselfKeepsItsContents() {
        // The trap the copy in sync() is for: clear() first would make the source empty.
        val list = mutableListOf("a", "b")

        list.sync(list)

        assertEquals(listOf("a", "b"), list)
    }

    @Test
    fun syncingReplacesWhateverWasThere() {
        val list = mutableListOf("a", "b")

        list.sync(listOf("c", "d"))

        assertEquals(listOf("c", "d"), list)

        list.sync(emptyList<String>())

        assertEquals(emptyList<String>(), list)
    }

    @Test
    fun syncingASetAndAMapTakesASnapshotToo() {
        val set = mutableSetOf("a", "b")
        val map = mutableMapOf("a" to 1)

        set.sync(set)
        map.sync(map)

        assertEquals(setOf("a", "b"), set)
        assertEquals(mapOf("a" to 1), map)
    }

    @Test
    fun aSourceThatIsAViewOfTheListIsReadBeforeTheListIsCleared() {
        // subList is a view, not a copy: the snapshot is what makes this work, because the view
        // is read while the list still has its contents.
        val list = mutableListOf("a", "b", "c")

        list.sync(list.subList(0, 2))

        assertEquals(listOf("a", "b"), list)
    }

    @Test
    fun aLazySourceIsConsumedOnceAndThatIsEnough() {
        val list = mutableListOf("x")

        list.sync(generateSequence("a") { if (it == "a") "b" else null }.asIterable())

        assertEquals(listOf("a", "b"), list)
    }

    @Test
    fun theMapPruneSurvivesAMatchingPredicateWhileItWalks() {
        // The removal is done over a copy: pruning every entry while walking the map itself would
        // throw, and this is called on the config the service is holding.
        val map = mutableMapOf("a" to 1, "b" to 2)

        map.removeIf { _, _ -> true }

        assertEquals(emptyMap<String, Int>(), map)
    }
}
