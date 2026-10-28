package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.common.FilterHolder
import io.github.grachevskaya.asya.zygote.service.AsyaServiceDataHolder
import io.github.grachevskaya.asya.zygote.service.HideTables
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * What the answer costs per query, on this machine.
 *
 * The name is computed from the asked name and a salt drawn once, so nothing is drawn here and
 * nothing grows with the names queries mention. The whole path still has to cost the same on either
 * answer -- the table read, the entry it fills, and the count that is zero when nothing was hidden --
 * because a difference between the two answers is the side channel this scheme exists to avoid.
 */
class HideCostTest {
    private companion object {
        const val ROUNDS = 5
        const val PER_ROUND = 200_000
    }

    @Volatile private var sink: Any? = null

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

    @Test fun theCostIsTheSameWithNoAnswerAsWithAnAnswer() {
        val table = AsyaServiceDataHolder { error("a query reads the published table, not a resolver") }
        table.publishHideTables(
            mapOf(
                10101 to
                    HideTables(
                        hidden = setOf("com.target"),
                        installed = setOf("com.target", "com.other"),
                    ),
            ),
        )

        // The statements are the rewrite's own, in its own order: written out here so that what is
        // measured is the path and not a summary of it.
        val entry = HideScratch.get()

        fun query(asked: String): Long =
            best {
                val tables = table.hideTablesFor(10101)
                val hidden = tables?.hidden?.contains(asked) == true
                val installed = tables?.installed?.contains(asked) == true
                val x = xorName(asked)
                entry.write = if (hidden || !installed) x else asked
                entry.hidden = hidden
                table.increaseFilterCount(10101, if (hidden) 1 else 0, FilterHolder.FilterType.PACKAGE_MANAGER)
                sink = entry.write
            }

        val hidden = query("com.target")
        val visible = query("com.other")
        val absent = query("com.missing")

        println("per query: hidden ${hidden}ns  visible ${visible}ns  absent ${absent}ns")

        val worst = maxOf(hidden, visible, absent)
        val smallest = minOf(hidden, visible, absent)
        assertTrue(
            "hidden ${hidden}ns, visible ${visible}ns and absent ${absent}ns are too far apart",
            worst <= smallest * 2 + 5,
        )
    }
}
