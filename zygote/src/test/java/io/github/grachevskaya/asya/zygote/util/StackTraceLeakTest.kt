package io.github.grachevskaya.asya.zygote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * What an application can see of a stack trace this module took part in.
 *
 * A hidden package answers with the framework's own failure -- the client throws NameNotFoundException
 * itself, and none of our frames are in it. The other half of that promise is here: when an exception
 * does pass through a hook, the frames that name this module, its hooking library, method-handle
 * plumbing or the obfuscation maps are removed before it is rethrown, so the trace handed back looks
 * like the framework's.
 */
class StackTraceLeakTest {
    private fun frame(
        className: String,
        fileName: String? = null,
        method: String = "run",
    ) = StackTraceElement(className, method, fileName, 42)

    private fun sample(): Throwable {
        val cause = IllegalStateException("the package is not installed")
        cause.stackTrace =
            arrayOf(
                frame("android.app.ApplicationPackageManager", "ApplicationPackageManager.java", "getPackageInfo"),
                frame("io.github.grachevskaya.asya.zygote.service.BulkHooker\$hookBefore\$1", "BulkHooker.kt"),
                frame("com.v7878.vmtools.Hooks", "Hooks.kt"),
                frame("java.lang.invoke.MethodHandle", null),
                frame("com.example.app.Probe", "r8-map-id-1234", "look"),
                frame("com.example.app.Probe", "dex-id-5678", "look"),
            )
        val outer = RuntimeException("while answering a query", cause)
        outer.stackTrace =
            arrayOf(
                frame("android.os.Binder", "Binder.java"),
                frame("io.github.grachevskaya.asya.zygote.service.BulkHooker", "BulkHooker.kt"),
            )
        return outer
    }

    private fun names(t: Throwable) = t.stackTrace.map { "${it.className}#${it.methodName}" }

    @Test fun theModulesFramesAreGoneFromWhatIsHandedBack() {
        val t = sample()

        ServiceUtils.clearStackTraces(t)

        val outer = names(t)
        assertEquals(listOf("android.os.Binder#run"), outer)

        val inner = names(t.cause!!)
        assertEquals(listOf("android.app.ApplicationPackageManager#getPackageInfo"), inner)
    }

    @Test fun theMessageAndTheCauseChainAreKept() {
        val t = sample()

        ServiceUtils.clearStackTraces(t)

        assertEquals("while answering a query", t.message)
        assertEquals("the package is not installed", t.cause?.message)
    }

    @Test fun aTraceWithNothingOfOursIsLeftAlone() {
        val t = IllegalStateException("plain")
        t.stackTrace =
            arrayOf(
                frame("android.app.ApplicationPackageManager", "ApplicationPackageManager.java"),
                frame("com.example.app.Probe", "Probe.java"),
            )

        ServiceUtils.clearStackTraces(t)

        assertTrue(names(t).contains("com.example.app.Probe#run"))
        assertEquals(2, t.stackTrace.size)
    }

    @Test fun anObfuscatedFileFrameDoesNotSurvive() {
        val t = IllegalStateException("plain")
        t.stackTrace = arrayOf(frame("com.example.app.Probe", "r8-map-id-1234", "look"))

        ServiceUtils.clearStackTraces(t)

        assertFalse(t.stackTrace.any { it.fileName?.startsWith("r8-map-id-") == true })
        assertEquals(0, t.stackTrace.size)
    }
}
