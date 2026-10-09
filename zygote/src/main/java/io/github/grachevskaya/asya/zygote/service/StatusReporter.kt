package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW

/*
 * What the module says about itself, and the kernel's own answer about what it is doing. Both are
 * held here and both are read over the bridge: nothing about either reaches the disk for the manager
 * to pick up, and there is no stream generation for either to carry.
 */

/*
 * The status is held, not written: the manager asks the bridge for it and the answer is this map,
 * so nothing about the module reaches the disk for the manager to read.
 */
internal fun AsyaService.saveModuleStatus() {
    StatusReporter.moduleStatus = mapOf("workMode" to StatusReporter.managerWorkMode.toString())
}

/**
 * Asks the kernel half what it is doing, and keeps the answer where the manager's queries read it.
 *
 * The inode hook waits for no configuration: it is put in place for anything of the right kind that
 * passes the kernel, so nothing here depends on a pushed policy. The answer is read once when the
 * service comes up -- once /data is unlocked, since the kernel holds nothing before that -- and again
 * after every install and removal, which are the only things that change it: no poll runs in between.
 * It is the kernel's own answer, not a second opinion about it.
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
 * Waits for the user's storage to be the user's, with no deadline.
 *
 * Everything the kernel is handed names paths under /data, and until that storage is unlocked those
 * paths cannot be resolved: a pass that ran before this returns would hand the kernel a set that is
 * missing the apps living there. So there is no timeout and no giving up here -- the unlock is not
 * something to wait for at most five minutes, it is the thing being waited for, and it is waited for
 * as long as it takes. The wait is woken by the unlock itself, so nothing is polled while it sits.
 */
internal fun AsyaService.waitForUserUnlocked() {
    if (storageIsUnlocked()) return

    val unlocked = java.util.concurrent.CountDownLatch(1)
    val receiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: android.content.Context?,
                intent: android.content.Intent?,
            ) {
                unlocked.countDown()
            }
        }

    runCatching {
        io.github.grachevskaya.asya.zygote.util.ContextUtils.application
            .registerReceiver(
                receiver,
                android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED),
            )
    }.onFailure { logW(TAG, it) { "Could not listen for the unlock" } }

    try {
        if (!storageIsUnlocked()) unlocked.await()
    } catch (_: InterruptedException) {
        return
    } finally {
        runCatching {
            io.github.grachevskaya.asya.zygote.util.ContextUtils.application.unregisterReceiver(receiver)
        }
    }
}

/** Whether the storage the apks live on can be read yet. */
private fun storageIsUnlocked(): Boolean =
    runCatching {
        val app =
            Class
                .forName("android.app.AppGlobals")
                .getMethod("getInitialApplication")
                .invoke(null) as? android.content.Context

        app?.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked ?: true
    }.getOrDefault(true)

/*
 * The state this part owns: what the module says about itself, and the kernel half's last answer.
 * Neither is a file: the manager asks the bridge, and these maps are what it is told.
 */
internal object StatusReporter {
    internal var managerWorkMode: Int = Constants.MANAGER_WORK_MODE_UNKNOWN

    /** What the bridge answers, and what the kernel half last reported. */
    internal var moduleStatus: Map<String, String> = emptyMap()
}
