package io.github.grachevskaya.asya.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The list of apps that reach play services without saying so in their manifest is the whole check:
 * what is on it is treated as having the connection, what is not is left alone.
 */
class RiskyPackageUtilsTest {
    private val utils = RiskyPackageUtils.instance

    @Test
    fun anAppOnTheListIsTreatedAsConnected() {
        assertTrue(utils.appHasGMSConnection("com.anydesk.anydeskandroid"))
    }

    @Test
    fun anAppThatIsNotOnTheListIsLeftAlone() {
        assertFalse(utils.appHasGMSConnection("com.example.a"))
        assertFalse(utils.appHasGMSConnection(""))
    }
}
