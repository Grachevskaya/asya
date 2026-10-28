package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.FilterHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/*
 * The query path, tested as the hooks use it: a caller uid and the name of the package it asked for,
 * answered from a table that is only ever replaced, and counted without a lock.
 */
class AsyaServiceDataHolderTest {
    private val uids =
        mapOf(
            CALLER to 10101,
            TARGET to 10102,
            OTHER to 10103,
        )

    private fun resolved() = AsyaServiceDataHolder { name -> uids[name] }

    @Test fun aHiddenTargetIsAnsweredForTheCallerThatHasIt() {
        val holder = resolved()
        holder.putShouldHideUidCache(10101, CALLER, TARGET)

        assertTrue(holder.shouldHideFromUid(10101, TARGET) == true)
        assertTrue(holder.shouldHideFromUid(10101, OTHER) == false)

        // Another caller has its own row, and a uid with no row at all hides nothing.
        assertTrue(holder.shouldHideFromUid(10102, TARGET) == false)
        assertNull(holder.shouldHideFromUid(10101, null))

        assertEquals(CALLER, holder.findCallerByUid(10101))
        assertNull(holder.findCallerByUid(10102))
    }

    @Test fun aQueryTheResolverCannotPlaceIsAnsweredByName() {
        val holder = AsyaServiceDataHolder { null }
        holder.putShouldHideUidCache(10101, CALLER, TARGET)

        assertTrue(holder.shouldHideFromUid(10101, TARGET) == true)
        assertTrue(holder.shouldHideFromUid(10101, OTHER) == false)
    }

    @Test fun aNameWrittenBeforeItsUidWasKnownIsStillHiddenAfterwards() {
        var resolver: (String) -> Int? = { null }
        val holder = AsyaServiceDataHolder { name -> resolver(name) }

        holder.putShouldHideUidCache(10101, CALLER, TARGET)
        resolver = { name -> uids[name] }

        // The uid is known now; the pair was written when it was not, and both halves are asked.
        assertTrue(holder.shouldHideFromUid(10101, TARGET) == true)
    }

    @Test fun theSameTargetWrittenTwiceIsOneRow() {
        val holder = resolved()
        holder.putShouldHideUidCache(10101, CALLER, TARGET)
        holder.putShouldHideUidCache(10101, CALLER, TARGET)

        assertTrue(holder.shouldHideFromUid(10101, TARGET) == true)
        assertTrue(holder.shouldHideFromUid(10101, OTHER) == false)
    }

    @Test fun droppingTheTableDropsEveryAnswer() {
        val holder = resolved()
        holder.putShouldHideUidCache(10101, CALLER, TARGET)
        holder.clearUidCache()

        assertTrue(holder.shouldHideFromUid(10101, TARGET) == false)
        assertNull(holder.findCallerByUid(10101))
    }

    @Test fun aPublishedTableAnswersAQueryWithoutResolvingAnything() {
        // The policy publishes the two sets it decided: the query path reads them and nothing else, so
        // the resolver -- which a query must not need -- is never asked.
        val holder = AsyaServiceDataHolder { error("a query must read the published table, not resolve") }
        holder.publishHideTables(
            mapOf(10101 to HideTables(hidden = setOf(TARGET), installed = setOf(TARGET, OTHER))),
        )

        val tables = holder.hideTablesFor(10101)
        assertTrue(tables?.hidden?.contains(TARGET) == true)
        // A name the caller may see is not in the hidden set, and the two sets are all a query reads.
        assertFalse(tables?.hidden?.contains(OTHER) == true)
        assertTrue(tables?.installed?.contains(OTHER) == true)

        // A caller no push answered for has no table at all: that one read is the whole answer.
        assertNull(holder.hideTablesFor(10102))
    }

    @Test fun aClearedTableKeepsWhatTheLastPushPublished() {
        // Emptying the table between two pushes would hide nothing until the push arrives, which a scan
        // in that window sees; the next push replaces the table whole.
        val holder = resolved()
        val published = HideTables(hidden = setOf(TARGET), installed = setOf(TARGET, OTHER))
        holder.publishHideTables(mapOf(10101 to published))

        assertSame(published, holder.hideTablesFor(10101))
        holder.clearUidCache()
        assertSame(published, holder.hideTablesFor(10101))
        assertTrue(holder.hideTablesFor(10101)?.hidden?.contains(TARGET) == true)
    }

    @Test fun aClearedTableIsRebuiltWhenThePolicyIsAskedAgain() {
        // A configuration change is what clears the table; the next policy answer builds it again,
        // lazily, and a package keeps the absent name it had before the clear.
        val holder = resolved()
        holder.putShouldHideUidCache(10101, CALLER, TARGET)
        val name = holder.absentNameFor(TARGET)

        holder.clearUidCache()
        assertTrue(holder.shouldHideFromUid(10101, TARGET) == false)

        holder.putShouldHideUidCache(10101, CALLER, TARGET)
        assertTrue(holder.shouldHideFromUid(10101, TARGET) == true)
        assertEquals(name, holder.absentNameFor(TARGET))
    }

    @Test fun aPackageListQuestionDoesNotDropTheTable() {
        // A name the resolver has not seen is the package-list path -- a package that was installed,
        // replaced or removed -- not a configuration change: asking it must leave every hidden
        // entry, and the caller behind it, alone. Only a configuration change clears the table.
        val holder = resolved()
        holder.putShouldHideUidCache(10101, CALLER, TARGET)

        repeat(64) { holder.shouldHideFromUid(10101, "com.unseen.$it") }

        assertTrue(holder.shouldHideFromUid(10101, TARGET) == true)
        assertEquals(CALLER, holder.findCallerByUid(10101))
    }

    @Test fun aPairWrittenWhileOtherPairsAreWrittenIsNotLost() {
        val holder = AsyaServiceDataHolder { name -> name.removePrefix("pkg.").toInt() }
        val callers = 4
        val targets = 50
        val start = CountDownLatch(1)

        val writers =
            (0 until callers).map { caller ->
                Thread {
                    start.await()
                    repeat(targets) { target ->
                        holder.putShouldHideUidCache(20000 + caller, "caller$caller", "pkg.${30000 + target}")
                    }
                }
            }

        writers.forEach { it.start() }
        start.countDown()
        writers.forEach { it.join() }

        for (caller in 0 until callers) {
            for (target in 0 until targets) {
                assertTrue(holder.shouldHideFromUid(20000 + caller, "pkg.${30000 + target}") == true)
            }
            assertTrue(holder.shouldHideFromUid(20000 + caller, "pkg.40000") == false)
            assertEquals("caller$caller", holder.findCallerByUid(20000 + caller))
        }
    }

    @Test fun countsAccumulateAndTheFlushLandsThemOnce() {
        val holder = AsyaServiceDataHolder()
        holder.increaseFilterCount(CALLER, 2, FilterHolder.FilterType.PACKAGE_MANAGER)
        holder.increaseFilterCount(CALLER, 3, FilterHolder.FilterType.ACTIVITY_LAUNCH)

        // What the manager reads between two flushes counts what has not been landed yet.
        assertEquals(5, holder.filterCountSnapshot())
        val beforeFlush = FilterHolder.parse(holder.filterCountsSnapshot())
        assertEquals(2, beforeFlush.filterCounts[CALLER]?.packageManagerCount)
        assertEquals(3, beforeFlush.filterCounts[CALLER]?.activityLaunchCount)

        assertTrue(holder.flushPendingCounts())
        assertEquals(5, holder.filterCountSnapshot())
        val afterFlush = FilterHolder.parse(holder.filterCountsSnapshot())
        assertEquals(2, afterFlush.filterCounts[CALLER]?.packageManagerCount)
        assertEquals(3, afterFlush.filterCounts[CALLER]?.activityLaunchCount)

        // The delta is gone once it has landed: a second pass has nothing to land.
        assertFalse(holder.flushPendingCounts())

        holder.increaseFilterCount(CALLER, 1, FilterHolder.FilterType.PACKAGE_MANAGER)
        assertTrue(holder.flushPendingCounts())
        assertEquals(6, holder.filterCountSnapshot())

        holder.clearFilterCounts()
        assertEquals(0, holder.filterCountSnapshot())
        assertFalse(holder.flushPendingCounts())
    }

    @Test fun aCountAskedForByUidLandsOnTheCallerThatUidRuns() {
        val holder = resolved()
        holder.putShouldHideUidCache(10101, CALLER, TARGET)

        holder.increaseFilterCount(10101, 1, FilterHolder.FilterType.PACKAGE_MANAGER)
        // A uid the table never answered for has no name to put a count under.
        holder.increaseFilterCount(10199, 1, FilterHolder.FilterType.PACKAGE_MANAGER)
        holder.flushPendingCounts()

        val ledger = FilterHolder.parse(holder.filterCountsSnapshot())
        assertEquals(1, ledger.filterCounts.size)
        assertEquals(1, ledger.filterCounts[CALLER]?.packageManagerCount)
    }

    @Test fun incrementsAreAllCountedWhileFlushesRun() {
        val holder = AsyaServiceDataHolder()
        val threads = 8
        val perThread = 2_000
        val start = CountDownLatch(1)

        val workers =
            (0 until threads).map {
                Thread {
                    start.await()
                    repeat(perThread) {
                        holder.increaseFilterCount(CALLER, 1, FilterHolder.FilterType.PACKAGE_MANAGER)
                    }
                }
            }

        workers.forEach { it.start() }
        start.countDown()

        // The flush runs while the increments do, which is the case the accumulators exist for: a
        // count is in the delta this pass takes or in the one after it, never in neither.
        while (workers.any { it.isAlive }) holder.flushPendingCounts()
        workers.forEach { it.join() }

        holder.flushPendingCounts()
        assertEquals(threads * perThread, holder.filterCountSnapshot())
    }

    @Test fun aHiddenAnswerIsBoundedAndNeverCostsMoreThanAVisibleOne() {
        // The side channel this guards is the cost of the answer. A hidden pair must not be found by
        // walking something -- the table is a sorted array and a set, so one lookup is the whole
        // answer -- and it must not cost more than the answer for a package the caller may see.
        var looked = 0
        val holder =
            AsyaServiceDataHolder { name ->
                looked++
                uids[name]
            }
        holder.putShouldHideUidCache(10101, CALLER, TARGET)

        looked = 0
        assertTrue(holder.shouldHideFromUid(10101, TARGET) == true)
        val hiddenLookups = looked

        looked = 0
        assertTrue(holder.shouldHideFromUid(10101, OTHER) == false)
        val visibleLookups = looked

        assertTrue("a hidden answer resolved $hiddenLookups names", hiddenLookups <= 1)
        assertTrue(
            "hidden cost $hiddenLookups, visible cost $visibleLookups",
            hiddenLookups <= visibleLookups,
        )
    }

    private companion object {
        const val CALLER = "com.caller"
        const val TARGET = "com.target"
        const val OTHER = "com.other"
    }
}
