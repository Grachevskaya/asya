package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.zygote.util.Logcat.logI

// What the module says about itself: held here, read over the bridge, never written to disk.
internal fun AsyaService.saveModuleStatus() {
    StatusReporter.moduleStatus = mapOf("workMode" to StatusReporter.managerWorkMode.toString())
}

/**
 * Keeps the kernel's answer where the manager's queries read it. Read after each push, which is what
 * changes it: the inode hook depends on no configuration and no poll runs in between.
 */
internal fun AsyaService.refreshKernelStatus(): Map<String, String> {
    val status = runCatching { KernelPolicy.requestStatus() }.getOrDefault(emptyMap())
    kernelStatusCache = status

    logI(TAG) {
        "Kernel status: " + (status["state"] ?: "no answer") +
            " · apk inodes=" + (status["apkInodes"] ?: "?") +
            " · failed=" + (status["apkFailed"] ?: "?")
    }

    return status
}

/**
 * Waits for the unlock, with no deadline of its own, and answers whether the storage can be read.
 *
 * False is the one case that is not a wait: there is no context to wait on, or the receiver that would
 * carry the answer could not be registered. Both mean the caller has to go on without a complete model.
 */
internal fun AsyaService.waitForUserUnlocked(): Boolean {
    val context = systemServerContext()

    return io.github.grachevskaya.asya.common
        .awaitUserUnlocked(context)
}

/** The system_server's own application context: it runs in user 0. */
private fun systemServerContext(): android.content.Context? =
    runCatching {
        android.app.ActivityThread::class.java
            .getMethod("currentActivityThread")
            .invoke(null)
            ?.let { thread ->
                thread.javaClass.getMethod("getApplication").invoke(thread) as? android.content.Context
            }
    }.getOrNull()

// The state this part owns: what the module says about itself and the kernel's last answer.
internal object StatusReporter {
    internal var managerWorkMode: Int = Constants.MANAGER_WORK_MODE_UNKNOWN

    /** What the bridge answers, and what the kernel half last reported. */
    internal var moduleStatus: Map<String, String> = emptyMap()
}
