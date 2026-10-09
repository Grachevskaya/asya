package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW

/* What the module says about itself: held here, read over the bridge, never written to disk. */
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

internal fun AsyaService.waitForUserUnlocked() =
    io.github.grachevskaya.asya.common.awaitUserUnlocked(null)

/* The state this part owns: what the module says about itself and the kernel's last answer. */
internal object StatusReporter {
    internal var managerWorkMode: Int = Constants.MANAGER_WORK_MODE_UNKNOWN

    /** What the bridge answers, and what the kernel half last reported. */
    internal var moduleStatus: Map<String, String> = emptyMap()
}
