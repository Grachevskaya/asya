package io.github.grachevskaya.asya.zygote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The value a hook decides with: one object per thread per depth, and never a leftover of the call
 * before it.
 */
class ReturnValuePoolTest {
    @Test fun aValueComesBackWithNothingOfTheCallBeforeIt() {
        val pool = ReturnValuePool()
        val value = pool.acquire()
        assertFalse(value.replace)
        assertNull(value.result)

        value.result = "hidden"
        value.throwable = IllegalStateException("injected")
        pool.release(value)

        // The same object, and nothing of the call that used it.
        assertSame(value, pool.acquire())
        assertFalse(value.replace)
        assertNull(value.result)
        assertNull(value.throwable)
    }

    @Test fun aCallInsideACallKeepsItsOwnAnswer() {
        val pool = ReturnValuePool()
        val outer = pool.acquire()
        outer.result = "hidden"

        val inner = pool.acquire()

        assertNotSame(outer, inner)
        assertTrue(outer.replace)
        assertEquals("hidden", outer.result)
        assertFalse(inner.replace)

        pool.release(inner)
        assertTrue(outer.replace)
    }

    @Test fun aThreadKeepsOneValueAndAnotherThreadGetsItsOwn() {
        val here = BulkHooker.threadReturnValues.get().acquire()
        BulkHooker.threadReturnValues.get().release(here)

        var there: ReturnValue? = null
        val worker = Thread { there = BulkHooker.threadReturnValues.get().acquire() }
        worker.start()
        worker.join()

        assertSame(here, BulkHooker.threadReturnValues.get().acquire())
        assertNotSame(here, there)
    }
}
