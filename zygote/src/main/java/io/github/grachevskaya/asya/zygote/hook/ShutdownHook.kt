package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.zygote.util.Logcat.logI

/**
 * The power off path, and the one write the throttled files never get.
 *
 * The counts are written on the hundred and at most every two seconds, so the tail of a session is
 * in memory when the device goes down -- and a clean shutdown is the one moment it can still be
 * written. ShutdownThread.shutdown and .reboot are the doors every power off goes through, and
 * both are entered before the sequence starts, which is why this is a hook before and not after:
 * once they return, the system is gone and there is nothing left to write to.
 *
 * The log needs nothing here: it is appended line by line as it happens.
 */
class ShutdownHook : IFrameworkHook {
    override val TAG = "ShutdownHook"

    override fun load() {
        logI(TAG) { "Load hook" }

        for (method in arrayOf("shutdown", "reboot")) {
            hooker.hookBefore(SHUTDOWN_THREAD_CLASS, method) { _, _, _ -> service.flushForShutdown() }
            if (!hooker.isHookAvailable(SHUTDOWN_THREAD_CLASS, method)) {
                logI(TAG) { "Not on this build: " + SHUTDOWN_THREAD_CLASS + "." + method }
            }
        }
    }

    private companion object {
        private const val SHUTDOWN_THREAD_CLASS = "com.android.server.power.ShutdownThread"
    }
}
