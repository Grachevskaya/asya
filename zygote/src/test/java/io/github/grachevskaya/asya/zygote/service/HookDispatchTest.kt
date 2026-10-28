package io.github.grachevskaya.asya.zygote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * The dispatch the hooks run through, on this machine: what a hook that answered does to the original
 * call, and what happens to an exception that came out of it.
 */
class HookDispatchTest {
    private class Counter {
        var calls = 0
    }

    private fun run(
        value: ReturnValue = ReturnValue(),
        counter: Counter = Counter(),
        hook: () -> Unit = {},
        original: () -> Unit = { counter.calls++ },
        cleared: MutableList<Throwable> = mutableListOf(),
    ): Pair<ReturnValue, Counter> {
        HookDispatch.before(
            value = value,
            hook = hook,
            invokeOriginal = original,
            onHookError = {},
            onOriginalError = {},
            writeResult = {},
            clearFrames = { cleared += it },
        )
        return value to counter
    }

    @Test fun anAnswerThatIsFinalSkipsTheOriginal() {
        val counter = Counter()
        val value = ReturnValue()

        run(value = value, counter = counter, hook = { value.result = null })

        assertEquals(0, counter.calls)
        assertTrue(value.replace)
    }

    @Test fun aHookWithNoAnswerLetsTheOriginalRunOnce() {
        val counter = Counter()

        val (value, _) = run(counter = counter)

        assertEquals(1, counter.calls)
        assertNull(value.result)
    }

    @Test fun anExceptionFromTheOriginalIsClearedAndRethrown() {
        val cleared = mutableListOf<Throwable>()
        val boom = IllegalStateException("the package is not installed")

        try {
            run(original = { throw boom }, cleared = cleared)
            fail("the exception should have come out")
        } catch (it: Throwable) {
            assertSame(boom, it)
        }

        assertEquals(listOf(boom), cleared)
    }

    @Test fun aHookThatThrowsDoesNotStopTheOriginal() {
        val counter = Counter()
        var seen: Throwable? = null

        HookDispatch.before(
            value = ReturnValue(),
            hook = { throw IllegalArgumentException("hook bug") },
            invokeOriginal = { counter.calls++ },
            onHookError = { seen = it },
            onOriginalError = {},
            writeResult = {},
            clearFrames = {},
        )

        assertNotNull(seen)
        assertEquals(1, counter.calls)
    }

    @Test fun theRestoreRunsAfterTheOriginal() {
        val order = mutableListOf<String>()

        HookDispatch.before(
            value = ReturnValue(),
            hook = { order += "hook" },
            invokeOriginal = { order += "original" },
            onHookError = {},
            onOriginalError = {},
            writeResult = {},
            clearFrames = {},
            restore = { order += "restore" },
        )

        assertEquals(listOf("hook", "original", "restore"), order)
    }

    @Test fun theRestoreRunsEvenWhenTheOriginalThrows() {
        var restored = false
        val boom = IllegalStateException("the package is not installed")

        try {
            HookDispatch.before(
                value = ReturnValue(),
                hook = {},
                invokeOriginal = { throw boom },
                onHookError = {},
                onOriginalError = {},
                writeResult = {},
                clearFrames = {},
                restore = { restored = true },
            )
            fail("the exception should have come out")
        } catch (it: Throwable) {
            assertSame(boom, it)
        }

        assertTrue(restored)
    }

    @Test fun afterRunsTheOriginalAndThenTheHook() {
        val order = mutableListOf<String>()
        val value = ReturnValue()

        HookDispatch.after(
            value = value,
            invokeOriginal = { order += "original" },
            readResult = {
                order += "read"
                value.result = "found"
            },
            hook = { order += "hook" },
            onHookError = {},
            onOriginalError = {},
            handleAfterThrows = false,
            writeResult = { order += "write" },
            clearFrames = {},
        )

        assertEquals(listOf("original", "read", "hook", "write"), order)
    }
}

/*
 * The argument a rewritten call took away: the slot the restore takes is the one its own call put
 * there, so a rewritten call inside a rewritten one keeps its own argument, and an early exit -- a
 * call that took nothing away -- leaves nothing to write back.
 */
class RewrittenArgumentsTest {
    @Test fun aCallTakesBackWhatItPutThere() {
        RewrittenArguments.remember("com.target")

        assertEquals("com.target", RewrittenArguments.take())
    }

    @Test fun aCallInsideACallKeepsItsOwnArgument() {
        RewrittenArguments.remember("com.outer")
        RewrittenArguments.remember("com.inner")

        assertEquals("com.inner", RewrittenArguments.take())
        assertEquals("com.outer", RewrittenArguments.take())
    }

    @Test fun aCallThatTookNothingHasNothingToPutBack() {
        RewrittenArguments.remember(null)

        assertNull(RewrittenArguments.take())
    }
}
