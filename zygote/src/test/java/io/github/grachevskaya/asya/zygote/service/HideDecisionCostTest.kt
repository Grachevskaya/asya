package io.github.grachevskaya.asya.zygote.service

import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * What the decision costs next to the lookup it would replace.
 *
 * Answering at the resolution layer only works as a no-difference scheme if the work done there is the
 * same order as the work the framework would have done: the package manager resolves a name with a
 * hash lookup, and the hide table answers with one binary search in a short array. This measures both
 * so the claim is a number and not an opinion.
 */
class HideDecisionCostTest {
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

    @Test fun theDecisionCostsWhatTheLookupItReplacesCosts() {
        val uids = mapOf("com.target" to 10102, "com.other" to 10103)
        val holder = AsyaServiceDataHolder { uids[it] }
        holder.putShouldHideUidCache(10101, "com.caller", "com.target")

        val plain = HashMap<String, Int>(uids)
        val lookup = best { sink = plain["com.target"] }
        val decision = best { sink = holder.shouldHideFromUid(10101, "com.target") }

        println("hash lookup ${lookup}ns  hide decision ${decision}ns")
        assertTrue("decision ${decision}ns against lookup ${lookup}ns", decision <= lookup * 20 + 5)
    }
}
