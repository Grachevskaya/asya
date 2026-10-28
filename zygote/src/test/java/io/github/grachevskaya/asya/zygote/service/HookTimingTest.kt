package io.github.grachevskaya.asya.zygote.service

import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * What the four answers cost, measured here rather than argued about.
 *
 * A caller can time a query, so the work behind each answer is measured. The framework's lookup is
 * modelled the way the source is shaped: a few pre-checks that a miss also pays, then a body that
 * resolves the name and reads the map -- and, on a hit only, generates the package info.
 *
 * The two hidden answers are the two hook points this module has: before the outer call, and at the
 * body, one step above the map read. The comparison that matters is against a package that is not
 * installed, because that is what a caller can put next to a hidden one.
 */
class HookTimingTest {
    private companion object {
        const val ROUNDS = 5
        const val PER_ROUND = 200_000
        const val PRE_WORK = 4
        const val RESOLVE_WORK = 6
        const val GENERATE_WORK = 64
    }

    @Volatile private var sink: Int = 0

    private fun work(units: Int): Int {
        var acc = sink
        repeat(units) { acc = acc * 31 + it }
        sink = acc
        return acc
    }

    /** Stands for the client: a null answer becomes the framework's own not-found failure. */
    private fun notFound(): Nothing = throw IllegalStateException("com.hidden.package")

    private fun best(block: () -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(ROUNDS) {
            val start = System.nanoTime()
            repeat(PER_ROUND) { block() }
            val round = (System.nanoTime() - start) / PER_ROUND
            if (round < best) best = round
        }
        return best
    }

    @Test fun aHiddenAnswerAtTheBodyCostsWhatAMissingPackageCosts() {
        val value = ReturnValue()

        // Nothing hidden: the framework runs, the package is there, and its info is generated.
        val visible =
            best {
                value.reset()
                work(PRE_WORK)
                work(RESOLVE_WORK)
                work(GENERATE_WORK)
                sink = 1
            }

        // Hidden before the outer call: nothing of the framework runs.
        val hiddenOuter =
            best {
                value.reset()
                HookDispatch.before(
                    value = value,
                    hook = { value.result = null },
                    invokeOriginal = {
                        work(PRE_WORK)
                        work(RESOLVE_WORK)
                        work(GENERATE_WORK)
                    },
                    onHookError = {},
                    onOriginalError = {},
                    writeResult = {},
                    clearFrames = {},
                )
                try {
                    notFound()
                } catch (_: IllegalStateException) {
                }
            }

        // Hidden at the body: the pre-checks and the name resolution run, the map read answers null.
        val hiddenBody =
            best {
                value.reset()
                work(PRE_WORK)
                HookDispatch.before(
                    value = value,
                    hook = { value.result = null },
                    invokeOriginal = {
                        work(RESOLVE_WORK)
                        work(GENERATE_WORK)
                    },
                    onHookError = {},
                    onOriginalError = {},
                    writeResult = {},
                    clearFrames = {},
                )
                try {
                    notFound()
                } catch (_: IllegalStateException) {
                }
            }

        // A package that is not installed: pre-checks, resolve, and a map read that finds nothing.
        val missing =
            best {
                value.reset()
                work(PRE_WORK)
                work(RESOLVE_WORK)
                try {
                    notFound()
                } catch (_: IllegalStateException) {
                }
            }

        // What the same hook would cost if it ran the body's work before answering: resolve, the map
        // read, and the generation of an answer that is then thrown away.
        val hiddenBodyWorked =
            best {
                value.reset()
                work(PRE_WORK)
                work(RESOLVE_WORK)
                work(GENERATE_WORK)
                HookDispatch.before(
                    value = value,
                    hook = { value.result = null },
                    invokeOriginal = {},
                    onHookError = {},
                    onOriginalError = {},
                    writeResult = {},
                    clearFrames = {},
                )
                try {
                    notFound()
                } catch (_: IllegalStateException) {
                }
            }

        println("hidden@body+work ${hiddenBodyWorked}ns")

        // The scheme that is in the tree now: the name is replaced, so the framework runs its own
        // resolve and its own miss, and this module only pays for the decision.
        val uids = mapOf("com.target" to 10102, "com.other" to 10103)
        val table = AsyaServiceDataHolder { uids[it] }
        table.putShouldHideUidCache(10101, "com.caller", "com.target")
        val hiddenRewrite =
            best {
                value.reset()
                val hidden = table.shouldHideFromUid(10101, "com.target") == true
                work(PRE_WORK)
                work(RESOLVE_WORK)
                sink = if (hidden) 0 else 1
                try {
                    notFound()
                } catch (_: IllegalStateException) {
                }
            }

        println("hidden@rewrite ${hiddenRewrite}ns")

        println("visible ${visible}ns  hidden@outer ${hiddenOuter}ns  hidden@body ${hiddenBody}ns  missing ${missing}ns")

        // What a caller can compare is hidden against missing: they must be the same answer, and the
        // body hook is the one that leaves the framework the same work to do on both.
        val worst = maxOf(hiddenBody, missing)
        val best = minOf(hiddenBody, missing)
        assertTrue("hidden@body ${hiddenBody}ns and missing ${missing}ns are too far apart", worst <= best * 2)
        // The pre-checks and the name resolution are what the body hook keeps and the outer one
        // drops; both are far below the failure the client pays for, which is why the two hidden
        // numbers land on top of each other here.
        assertTrue(
            "hidden@outer ${hiddenOuter}ns and hidden@body ${hiddenBody}ns differ too much",
            hiddenOuter <= hiddenBody * 2,
        )
        assertTrue("visible ${visible}ns should not be cheaper than hidden@body", visible <= hiddenBody)
    }
}
