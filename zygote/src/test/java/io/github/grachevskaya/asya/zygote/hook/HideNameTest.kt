package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.zygote.service.AsyaServiceDataHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The name a hidden lookup carries, on this machine.
 *
 * It is computed from the asked name and a salt the service draws once: the bytes of the name are
 * mixed with the bytes of the salt and written as letters, so the same query gets the same name for
 * as long as the service lives, two packages get different names, no table grows with the names
 * queries mention, and the name says nothing about the one it was computed from.
 */
class HideNameTest {
    private val holder = AsyaServiceDataHolder()

    @Test fun theSameQueryGetsTheSameNameTwice() {
        assertEquals(holder.absentNameFor(ASKED), holder.absentNameFor(ASKED))
    }

    @Test fun differentPackagesGetDifferentNames() {
        assertNotEquals(holder.absentNameFor(ASKED), holder.absentNameFor(OTHER))
    }

    @Test fun theNameIsLettersAndStartsWithOne() {
        val absent = holder.absentNameFor(ASKED)

        assertTrue("$absent must start with a letter", absent.first().isLetter())
        assertTrue("$absent must be letters only", absent.all { it in 'a'..'z' })
        assertNotEquals(ASKED, absent)
    }

    @Test fun theNameCarriesOneLetterPerByteOfTheAskedOne() {
        assertEquals(ASKED.toByteArray().size + 1, holder.absentNameFor(ASKED).length)
    }

    @Test fun aByteIsWrittenAsTheLetterOfItsLowNibble() {
        // With nothing mixed in, 'a' and 'b' are their own low nibbles, and a letter comes in front.
        assertEquals("zbc", HideName.absent("ab", 0))
    }

    @Test fun anotherSaltIsAnotherName() {
        assertNotEquals(HideName.absent(ASKED, 1), HideName.absent(ASKED, 2))
        assertEquals(HideName.absent(ASKED, 7), HideName.absent(ASKED, 7))
    }

    @Test fun aCacheClearDoesNotChangeTheName() {
        val before = holder.absentNameFor(ASKED)
        holder.clearUidCache()
        assertEquals(before, holder.absentNameFor(ASKED))
    }

    private companion object {
        const val ASKED = "com.target"
        const val OTHER = "com.other"
    }
}
