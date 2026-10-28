package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.zygote.service.AsyaServiceDataHolder
import io.github.grachevskaya.asya.zygote.service.HideTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The name a query is answered with, and the name it is handed instead.
 *
 * A caller reads two sets published with the policy -- what it may not see, and what is installed at
 * all -- and every answer writes the same three things. A name the caller may see is handed over as it
 * is; a hidden name and a name nothing has are both handed over masked, so the framework resolves
 * neither and the two answers cannot be told apart by what it was given.
 */
class HideRewriteTest {
    private companion object {
        const val UID = 10101
        const val HIDDEN = "com.target"
        const val VISIBLE = "com.other"
        const val NOT_INSTALLED = "com.missing"
        const val PACKAGE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_."
    }

    private val holder = AsyaServiceDataHolder { error("a query reads the published table, not a resolver") }

    private fun publish() {
        holder.publishHideTables(
            mapOf(UID to HideTables(hidden = setOf(HIDDEN), installed = setOf(HIDDEN, VISIBLE))),
        )
    }

    /**
     * The rewrite's own statements, in its own order: what is tested is the answer, not a summary.
     *
     * The answer is read out of the scratch before it is returned, because the scratch is one entry per
     * thread and reused: the next call fills the same entry again.
     */
    private fun answered(
        asked: String,
        uid: Int = UID,
    ): Pair<String, Boolean> {
        val entry = HideScratch.get()
        val tables = holder.hideTablesFor(uid)
        val hidden = tables?.hidden?.contains(asked) == true
        val installed = tables?.installed?.contains(asked) == true || tables == null
        val x = xorName(asked)
        entry.write = if (hidden || !installed) x else asked
        entry.hidden = hidden

        return entry.write to entry.hidden
    }

    /** A masked name is a name no package can carry: no character of it is one a package name has. */
    private fun assertMasked(name: String) {
        assertTrue("$name must carry no character a package name can carry", name.none { it in PACKAGE_CHARS })
    }

    @Test fun aPackageTheCallerMaySeeKeepsItsName() {
        publish()

        val (write, hidden) = answered(VISIBLE)
        assertEquals(VISIBLE, write)
        assertFalse(hidden)
    }

    @Test fun aHiddenPackageIsWrittenAsItsMaskedName() {
        publish()

        val (write, hidden) = answered(HIDDEN)
        assertEquals(xorName(HIDDEN), write)
        assertTrue(hidden)
    }

    @Test fun aPackageNothingHasIsWrittenTheSameWayAHiddenOneIs() {
        publish()

        val (absentWrite, absentHidden) = answered(NOT_INSTALLED)
        val (hiddenWrite, hiddenHidden) = answered(HIDDEN)

        assertEquals(xorName(NOT_INSTALLED), absentWrite)
        // Not a hidden query -- nothing is counted and nothing is put back -- but the same kind of
        // name: the framework is handed a masked name either way and resolves neither.
        assertFalse(absentHidden)
        assertTrue(hiddenHidden)
        assertMasked(absentWrite)
        assertMasked(hiddenWrite)
        assertEquals(NOT_INSTALLED.length, absentWrite.length)
        assertEquals(HIDDEN.length, hiddenWrite.length)
    }

    @Test fun aMaskedNameIsAsLongAsTheAskedOne() {
        for (asked in listOf("a", HIDDEN, NOT_INSTALLED, "com.example.a.much.longer.package.name")) {
            assertEquals(asked.length, xorName(asked).length)
        }
    }

    @Test fun maskingTwiceIsTheAskedNameAgain() {
        for (asked in listOf("a", HIDDEN, NOT_INSTALLED, "com.example.a.much.longer.package.name")) {
            assertEquals(asked, xorName(xorName(asked)))
        }
    }

    @Test fun aMaskedNameIsNeverTheAskedOneAndNeverAPackageName() {
        // One character for one character, every character of the mask having its high bit set: the
        // asked name never comes back, and what does is a name no package can carry.
        for (asked in listOf("a", HIDDEN, VISIBLE, NOT_INSTALLED)) {
            val masked = xorName(asked)
            assertNotEquals(asked, masked)
            assertMasked(masked)
        }
    }

    @Test fun aCallerWithNoTableKeepsEveryNameItAsks() {
        // Only a caller the policy speaks for has the names it may see; rewriting for anyone else
        // would answer every installed package as missing.
        publish()
        val (answer, hidden) = answered(VISIBLE, UID + 1)

        assertEquals(VISIBLE, answer)
        assertFalse(hidden)
    }
}
