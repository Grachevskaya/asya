package io.github.grachevskaya.asya.common

import io.github.grachevskaya.asya.common.ServiceBridge.CODE_CONFIG_JSON
import io.github.grachevskaya.asya.common.ServiceBridge.CODE_CONFIG_SET
import io.github.grachevskaya.asya.common.ServiceBridge.CODE_FILTER_COUNT
import io.github.grachevskaya.asya.common.ServiceBridge.CODE_FILTER_STATS
import io.github.grachevskaya.asya.common.ServiceBridge.CODE_HELLO
import io.github.grachevskaya.asya.common.ServiceBridge.CODE_KERNEL_STATUS
import io.github.grachevskaya.asya.common.ServiceBridge.CODE_MODULE_STATUS
import io.github.grachevskaya.asya.common.ServiceBridge.CODE_PACKAGES_CHANGED
import io.github.grachevskaya.asya.common.ServiceBridge.DISPATCH_HANDLED
import io.github.grachevskaya.asya.common.ServiceBridge.DISPATCH_INTERFACE
import io.github.grachevskaya.asya.common.ServiceBridge.DISPATCH_UNKNOWN
import io.github.grachevskaya.asya.common.ServiceBridge.managerDispatch
import io.github.grachevskaya.asya.common.ServiceBridge.serviceHandles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The transaction codes are the protocol. An unknown code that reaches the wrong branch, or two of
 * the module's own codes that collide, breaks the live channel without anything failing to compile.
 */
class BridgeDispatchTest {
    private val moduleCodes =
        listOf(
            CODE_MODULE_STATUS,
            CODE_KERNEL_STATUS,
            CODE_CONFIG_JSON,
            CODE_CONFIG_SET,
            CODE_FILTER_COUNT,
            CODE_FILTER_STATS,
        )

    @Test
    fun theModulesOwnCodesAreAllDifferent() {
        assertEquals(moduleCodes.size, moduleCodes.toSet().size)
        assertFalse(moduleCodes.contains(CODE_HELLO))
        assertFalse(moduleCodes.contains(CODE_PACKAGES_CHANGED))
    }

    @Test
    fun theManagerHandlesItsOwnTwoCodes() {
        assertEquals(DISPATCH_HANDLED, managerDispatch(CODE_HELLO))
        assertEquals(DISPATCH_HANDLED, managerDispatch(CODE_PACKAGES_CHANGED))
    }

    @Test
    fun theManagerSwallowsTheInterfacesOwnCodes() {
        // The framework calls the observer through this same object: those codes are answered here
        // and never forwarded, because there is nothing behind them but the framework's own stub.
        assertEquals(DISPATCH_INTERFACE, managerDispatch(1))
        assertEquals(DISPATCH_INTERFACE, managerDispatch(android.os.IBinder.LAST_CALL_TRANSACTION))
    }

    @Test
    fun theManagerForwardsWhatIsNeither() {
        assertEquals(DISPATCH_UNKNOWN, managerDispatch(0))
        assertEquals(DISPATCH_UNKNOWN, managerDispatch(android.os.IBinder.LAST_CALL_TRANSACTION + 1))
        assertEquals(DISPATCH_UNKNOWN, managerDispatch(-1))
    }

    @Test
    fun theServiceAnswersExactlyItsOwnCodes() {
        moduleCodes.forEach { assertTrue(serviceHandles(it)) }
        assertFalse(serviceHandles(CODE_HELLO))
        assertFalse(serviceHandles(CODE_PACKAGES_CHANGED))
        assertFalse(serviceHandles(0))
        assertFalse(serviceHandles(1))
    }
}
