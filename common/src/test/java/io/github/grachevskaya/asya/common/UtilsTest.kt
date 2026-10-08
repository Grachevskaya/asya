package io.github.grachevskaya.asya.common

import android.content.pm.ApplicationInfo
import io.github.grachevskaya.asya.common.Utils.containsMultiple
import io.github.grachevskaya.asya.common.Utils.endsWithMultiple
import io.github.grachevskaya.asya.common.Utils.getUserFromCallingUid
import io.github.grachevskaya.asya.common.Utils.isSystemApp
import io.github.grachevskaya.asya.common.Utils.startsWithMultiple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The little decisions every hiding call leans on: which user a uid belongs to, whether an app is a
 * system app, and the list questions asked of strings. Each of them is one expression, and a wrong
 * one changes an answer silently.
 */
class UtilsTest {
    @Test
    fun theUserIsTheUidDividedByTheUserSpace() {
        assertEquals(0, getUserFromCallingUid(0))
        assertEquals(0, getUserFromCallingUid(1000))
        assertEquals(1, getUserFromCallingUid(100000))
        assertEquals(10, getUserFromCallingUid(10 * 100000 + 10123))
    }

    @Test
    fun anIsolatedProcessOfAUserBelongsToThatUser() {
        // An isolated process of user 0 is u0_i0, which is uid 99000: it must resolve to user 0 and
        // not to a user of its own.
        assertEquals(0, getUserFromCallingUid(99000))
        assertEquals(1, getUserFromCallingUid(100000 + 99000))
    }

    @Test
    fun aNegativeUidTruncatesTowardsZero() {
        // Not a uid the system hands out; the division is written down so a change of operator is
        // visible here rather than only wherever a negative value would be passed.
        assertEquals(0, getUserFromCallingUid(-1))
        assertEquals(-1, getUserFromCallingUid(-100000))
    }

    @Test
    fun theSameAppInTheNextUserIsTheNextUser() {
        val inUser0 = 10123
        val inUser1 = 100000 + 10123

        assertEquals(getUserFromCallingUid(inUser0) + 1, getUserFromCallingUid(inUser1))
    }

    @Test
    fun anAppWithNoFlagsIsNotASystemApp() {
        val app = ApplicationInfo()

        assertFalse(app.isSystemApp())
    }

    @Test
    fun theSystemFlagIsEnough() {
        val app = ApplicationInfo()

        app.flags = ApplicationInfo.FLAG_SYSTEM

        assertTrue(app.isSystemApp())
    }

    @Test
    fun anUpdatedSystemAppCountsAsOne() {
        val app = ApplicationInfo()

        app.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP

        assertTrue(app.isSystemApp())
    }

    @Test
    fun anotherFlagAloneIsNotEnough() {
        val app = ApplicationInfo()

        app.flags = ApplicationInfo.FLAG_INSTALLED

        assertFalse(app.isSystemApp())
    }

    @Test
    fun bothFlagsTogetherAreStillASystemApp() {
        val app = ApplicationInfo()

        app.flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP

        assertTrue(app.isSystemApp())
    }

    @Test
    fun aStringQuestionNeedsBothAStringAndATarget() {
        val nothing: String? = null

        assertFalse(nothing.startsWithMultiple("a"))
        assertFalse("".startsWithMultiple("a"))
        assertFalse("abc".startsWithMultiple())
        assertFalse(nothing.endsWithMultiple("c"))
        assertFalse("abc".containsMultiple())
    }

    @Test
    fun anyTargetMatchingIsEnough() {
        assertTrue("abc".startsWithMultiple("x", "ab"))
        assertTrue("abc".endsWithMultiple("x", "bc"))
        assertTrue("abc".containsMultiple("x", "b"))
        assertFalse("abc".startsWithMultiple("x", "y"))
        assertFalse("abc".endsWithMultiple("x", "y"))
        assertFalse("abc".containsMultiple("x", "y"))
    }
}
