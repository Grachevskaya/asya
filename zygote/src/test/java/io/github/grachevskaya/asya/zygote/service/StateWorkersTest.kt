package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.FilterHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class StateWorkersTest {
    @Test fun requestsDuringWorkCoalesceWithoutLosingTheLastPass() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val runs = AtomicInteger()
        val failures = AtomicInteger()
        val worker = CoalescingWorker("test") { failures.incrementAndGet() }
        worker.submit {
            runs.incrementAndGet()
            entered.countDown()
            check(release.await(3, SECONDS))
            error("injected")
        }
        assertTrue(entered.await(3, SECONDS))
        repeat(20) {
            worker.submit {
                runs.incrementAndGet()
                done.countDown()
            }
        }
        release.countDown()
        assertTrue(done.await(3, SECONDS))
        assertEquals(2, runs.get())
        assertEquals(1, failures.get())
    }

    @Test fun countsAreSnapshotsAndRestoreMergesEarlyBootIncrementsOnce() {
        val totals = AsyaServiceDataHolder()
        totals.increaseFilterCount("caller", 3, FilterHolder.FilterType.PACKAGE_MANAGER)
        val saved = FilterHolder.parse(totals.filterCountsSnapshot())
        val rebooted = AsyaServiceDataHolder()
        rebooted.increaseFilterCount("caller", 2, FilterHolder.FilterType.PACKAGE_MANAGER)
        rebooted.mergeFilterCounts(saved)
        assertEquals(5, rebooted.filterCountSnapshot())
        rebooted.filterCountsSnapshot()
        assertEquals(5, rebooted.filterCountSnapshot())
        rebooted.clearFilterCounts()
        assertEquals(0, rebooted.filterCountSnapshot())
    }
}
