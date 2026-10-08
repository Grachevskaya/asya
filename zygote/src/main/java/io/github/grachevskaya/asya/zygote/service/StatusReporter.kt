package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW

/*
 * What the module tells the manager and the kernel about itself: the module status, the kernel
 * status and the stream generation it reports back, each written where the other side reads it.
 */

/*
 * The status is held, not written: the manager asks the bridge for it and the answer is this map,
 * so nothing about the module reaches the disk for the manager to read.
 */
internal fun AsyaService.saveModuleStatus() {
    StatusReporter.moduleStatus = mapOf("workMode" to StatusReporter.managerWorkMode.toString())
}

/**
 * Asks the kernel half what it is doing, and keeps the answer next to the status.
 *
 * This is the half of the module's description line that was about the kernel: which registrations
 * are in place, how many entries are hooked where, and what the apk side is holding. The manager
 * reads this directory already, so the card that shows it needs no channel of its own -- and the
 * numbers are the kernel's own, not a second opinion about them.
 */

/**
 * The kernel's numbers move as apps come and go, so they are asked for again now and then; the card
 * would otherwise show whatever was true the last time the configuration changed.
 */

/**
 *
 */

internal fun AsyaService.publishStatusToKernelSu(
    pairs: Int,
    apks: Int,
    isolated: String,
) {
    val kernelUp =
        runCatching {
            KernelPolicy.requestStatus()["state"] == "loaded"
        }.getOrDefault(false)
    val serviceUp = apks > 0 || pairs > 0
    val description =
        buildString {
            append(if (serviceUp) "userspace service running" else "userspace service not ready")
            append(" · ")
            append(if (kernelUp) "kernel module loaded" else "kernel module not loaded")
        }

    logI(TAG) { "Status: " + description + " · apk=" + apks + " · policy=" + pairs / 2 + " · " + isolated }
}

internal fun AsyaService.jsonDecode(text: String): Map<String, String> =
    runCatching {
        Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }.getOrDefault(emptyMap())

/**
 */

internal fun AsyaService.waitForUserUnlocked(timeoutMs: Long) {
    val deadline = System.currentTimeMillis() + timeoutMs

    val um: android.os.UserManager? =
        runCatching {
            val app =
                Class
                    .forName("android.app.AppGlobals")
                    .getMethod("getInitialApplication")
                    .invoke(null) as? android.content.Context
            app?.getSystemService(android.os.UserManager::class.java)
        }.getOrNull()

    while (System.currentTimeMillis() < deadline) {
        val unlocked = runCatching { um?.isUserUnlocked ?: true }.getOrDefault(true)
        if (unlocked) return
        try {
            Thread.sleep(2_000L)
        } catch (_: InterruptedException) {
            return
        }
    }
    logW(TAG) { "Gave up waiting for the user to be unlocked; building the table anyway" }
}

/*
 * The state this part owns: what the module says about itself, the file the manager reads it from, and
 * the file the kernel half writes its own answer into.
 */
internal object StatusReporter {
    internal var managerWorkMode: Int = Constants.MANAGER_WORK_MODE_UNKNOWN

    /** What the bridge answers, and what the kernel half last reported. */
    internal var moduleStatus: Map<String, String> = emptyMap()
}
