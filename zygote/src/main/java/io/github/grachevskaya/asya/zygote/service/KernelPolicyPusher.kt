package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.Utils.getInstalledApplicationsCompat
import io.github.grachevskaya.asya.common.Utils.getPackageInfoCompat
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.UserManagerUtils
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// The one place the kernel is told what to watch; a pass is never run twice at once.

internal fun AsyaService.pushPolicyToKernel() {
    logI(TAG) { "Policy push asked for" }

    if (!KernelPolicyPusher.policyPushRunning.compareAndSet(false, true)) {
        KernelPolicyPusher.policyPushQueued.set(true)
        return
    }
    pushPolicyToKernelOnce()
}

internal fun AsyaService.pushPolicyToKernelOnce() {
    Thread {
        android.os.Process.setThreadPriority(
            android.os.Process.THREAD_PRIORITY_BACKGROUND,
        )
        try {
            // Read first: it depends on nothing this pass builds, and the manager's card shows it.
            refreshKernelStatus()

            // Both tables are built after the unlock: the package model is complete only then, and
            // the wait has no deadline because a device may sit at the lock screen for hours. It comes
            // back early only when there is no way to wait at all, which is worth saying out loud.
            if (!waitForUserUnlocked()) {
                logW(TAG) { "The unlock cannot be waited for here; the table is built from what is readable" }
            }

            val pairs = buildPolicyPairs()
            logI(TAG) { "Policy for the kernel: ${pairs.size / 2} pair(s)" }
            KernelPolicy.push(pairs)

            val apks = buildApkTable(pairs)
            logIsolationClasses()
            logI(TAG) { "Apks for the kernel: ${apks.size}" }
            KernelPolicy.pushApks(apks)

            // Read on the way back from the push: before it, the answer is what the kernel held then.
            var status = refreshKernelStatus()

            // A reloaded module holds nothing while this side remembers everything: forget with it and
            // send the table again.
            if (status["apkInodes"] == "0" && KernelPolicy.heldCount() > 0) {
                logI(TAG) { "The kernel half holds nothing while this side does; sending the table again" }
                KernelPolicy.forgetHeld()
                KernelPolicy.pushApks(apks)
                status = refreshKernelStatus()
            }

            val failed = status["apkFailed"]?.toIntOrNull() ?: 0

            if (apks.isNotEmpty() && failed == 0) {
                // A table that landed is what the budget is for. Handing it back means the next thing
                // that needs a retry gets one, instead of being told the budget was spent at boot.
                KernelPolicyPusher.apkRetries.set(0)
            } else {
                logI(TAG) { "The apk table did not land (entries=" + apks.size + ", failed=" + failed + "; retrying)" }
                scheduleApkRetry()
            }
        } catch (cause: Throwable) {
            logW(TAG, cause) { "Could not hand the policy to the kernel half" }
        } finally {
            KernelPolicyPusher.policyPushRunning.set(false)
            if (KernelPolicyPusher.policyPushQueued.compareAndSet(true, false)) {
                pushPolicyToKernel()
            }
        }
    }.apply {
        isDaemon = true
        name = "asya-policy-push"
    }.start()
}

/**
 * Tries the apk table again once the storage is readable, which at boot it is not yet. One retry per
 * need, not per boot: the budget is handed back when a table lands.
 */

internal fun AsyaService.scheduleApkRetry() {
    if (KernelPolicyPusher.apkRetries.getAndIncrement() >= 1) {
        logW(TAG) { "Gave up on the apk table after ${KernelPolicyPusher.apkRetries.get()} tries" }
        return
    }

    // The table needs the package list, and the list is what the user being unlocked produces:
    // waiting on that gate is what the retries stood in for, and one wait is cheaper than five.
    Thread {
        if (!waitForUserUnlocked()) {
            logW(TAG) { "The unlock cannot be waited for here; the retry runs on what is readable" }
        }

        pushPolicyToKernel()
    }.apply {
        isDaemon = true
        name = "asya-apk-retry"
    }.start()
}

internal fun AsyaService.buildApkTable(
    @Suppress("UNUSED_PARAMETER") pairs: IntArray,
): List<Pair<Int, String>> {
    val table = ArrayList<Pair<Int, String>>()

    // One entry per file: the kernel's ledger is keyed by the file, so the same apk offered twice is
    // a refusal rather than a second hook.
    val offered = HashSet<String>()

    for (userId in UserManagerUtils.getUsers(false, false, false).map { it.id }.distinct()) {
        runCatching {
            val apps = pms.getInstalledApplicationsCompat(0L, userId)

            for (app in apps) {
                // Below the application range the kernel has no app to bind the file to, and counts it
                // as a failure.
                if (app.uid < android.os.Process.FIRST_APPLICATION_UID) continue

                val info = pms.getPackageInfoCompat(app.packageName, 0L, userId) ?: continue
                val application = info.applicationInfo ?: continue

                val kind = isolationClass(app.packageName, userId)
                if (kind == ISOLATION_NONE) continue

                application.sourceDir?.let { if (offered.add(it)) table.add(app.uid to it) }
                application.splitSourceDirs?.forEach { if (offered.add(it)) table.add(app.uid to it) }
                    /*
                     *
                     */

                    /*
                     *
                     *
                     */

                    /*
                     *
                     *
                     */
                if (kind == ISOLATION_NATIVE) {
                    // A native app also gets the libraries it can load: the declared name is only the
                    // entry point, so the ELF closure is what is hooked. Declared nothing means the
                    // framework default libmain.so, and nothing on disk means every library.
                    val declaredNames =
                        LinkedHashSet<String>().apply {
                            declaredNativeLibNames(app.packageName)?.let { addAll(it) }
                            appNativePreloadLib(application)?.let { add(it) }
                            if (isEmpty()) add("libmain.so")
                        }
                    val base = application.nativeLibraryDir?.let { File(it) }
                    val closure = base?.let { ElfDeps.closure(declaredNames, it) }
                    logI(TAG) {
                        "Native app " + app.packageName + " declares " + declaredNames +
                            "; elf closure=" + (closure?.size ?: -1)
                    }
                    val existing =
                        closure
                            ?.mapNotNull { n ->
                                base.let { File(it, n) }.takeIf { f -> f.isFile }?.absolutePath
                            }.orEmpty()
                    val paths =
                        if (existing.isNotEmpty()) {
                            existing
                        } else {
                            base?.listFiles()?.map { it.absolutePath } ?: emptyList()
                        }

                    paths.forEach { path ->
                        if (path.endsWith(".so") && offered.add(path)) table.add(app.uid to path)
                    }
                }
            }
        }.onFailure { logD(TAG) { "No apks for user $userId: ${it.message}" } }
    }

    return table
}

internal fun AsyaService.buildPolicyPairs(): IntArray {
    val pairs = LinkedHashSet<Long>()
    val callerPackages = config.scope.keys.toList()

    var targets = 0

    for (userId in UserManagerUtils.getUsers(false, false, false).map { it.id }.distinct()) {
        val packages = pms.getInstalledApplicationsCompat(0L, userId)

        for (app in packages) {
            targets++
            val target = app.packageName

            for (caller in callerPackages) {
                if (!shouldHide(caller, target, userId)) continue

                //noinspection NewApi
                val callerUid = pms.getPackageUid(caller, 0L, userId)
                val targetUid = app.uid
                if (callerUid < 0 || targetUid <= 0) continue

                pairs.add((callerUid.toLong() shl 32) or (targetUid.toLong() and 0xffffffffL))
            }
        }
    }

    logI(TAG) { "Policy computed: ${pairs.size} pair(s) across $targets package(s)" }

    val out = IntArray(pairs.size * 2)
    var i = 0
    for (packed in pairs) {
        out[i++] = (packed ushr 32).toInt()
        out[i++] = packed.toInt()
    }
    return out
}

/*
 * The state this part owns: whether a pass is running, whether one was asked for while it ran, and
 * how many times the apk table has been tried.
 */
internal object KernelPolicyPusher {
    /** How many times the apk table has been retried; it cannot resolve before /data is readable. */
    internal val apkRetries = AtomicInteger()

        /*
         * One rebuild at a time: the kernel keeps its stream state once, not per caller, so two passes
         * at the same time interleave their records. A request that arrives while one runs sets a flag
         * and the running pass takes it once more when it finishes.
         */
    internal val policyPushRunning =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    internal val policyPushQueued =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
}
