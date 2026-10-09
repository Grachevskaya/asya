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

/*
 * The one place the kernel is told what to watch: the apk table is built from the config and the
 * packages on the device, the pairs are handed over, and a pass is never run twice at once.
 */

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
            // The kernel's own state is asked for first: it is what the manager's card reads, and it
            // depends on nothing this pass is about to build. Leaving that read until the end is what
            // made a freshly opened manager say "not loaded" for as long as a whole push takes.
            refreshKernelStatus()

            // Nothing is built before the storage is there, and not only the apk table: the package
            // model itself -- every installed app, and the uid each one runs as -- is complete only
            // once the user's storage is unlocked, and a policy computed from half of it would hide
            // the wrong things. So the wait is here, at the top, and both tables are built after it.
            waitForUserUnlocked()

            val pairs = buildPolicyPairs()
            logI(TAG) { "Policy for the kernel: ${pairs.size / 2} pair(s)" }
            KernelPolicy.push(pairs)

            val apks = buildApkTable(pairs)
            logIsolationClasses()
            logI(TAG) { "Apks for the kernel: ${apks.size}" }
            KernelPolicy.pushApks(apks)

            // The kernel replaces the ->open of what it was just handed, and that happens as the table
            // arrives -- so the count is read here, on the way back from the push, and nowhere else. A
            // read taken before this answers with what the kernel held before the push. It is the
            // answer the ledger check below needs too, which is why it is taken at this one point.
            var status = refreshKernelStatus()

                /*
                 * The kernel's ledger is as long as the module has been loaded, and this side's is as
                 * long as the service has been running -- so after the module is reloaded the two
                 * disagree: the kernel holds nothing while this side remembers everything, and a
                 * difference computed against a ledger the kernel does not have would offer nothing.
                 * When it says it holds none, this side forgets with it and sends the table again.
                 */
            if (status["apkInodes"] == "0" && KernelPolicy.heldCount() > 0) {
                logI(TAG) { "The kernel half holds nothing while this side does; sending the table again" }
                KernelPolicy.forgetHeld()
                KernelPolicy.pushApks(apks)
                status = refreshKernelStatus()
            }

                /*
                 *
                 */
            val failed = status["apkFailed"]?.toIntOrNull() ?: 0
            if (apks.isEmpty() || failed > 0) {
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
 * Tries the apk table again a little later.
 *
 * An app's path only resolves once the storage it lives on is readable, and at boot that is not
 * yet true -- the tool this replaces learned to wait for exactly this and said why in its log
 * ("cannot watch yet; /data may still be encrypted"). Waiting costs nothing and is the difference
 * between an app being watchable and an isolated child of it staying a number forever.
 */

internal fun AsyaService.scheduleApkRetry() {
    if (KernelPolicyPusher.apkRetries.getAndIncrement() >= 1) {
        logW(TAG) { "Gave up on the apk table after ${KernelPolicyPusher.apkRetries.get()} tries" }
        return
    }

    // The table needs the package list, and the list is what the user being unlocked produces:
    // waiting on that gate is what the retries stood in for, and one wait is cheaper than five.
    Thread {
        waitForUserUnlocked()

        pushPolicyToKernel()
    }.apply {
        isDaemon = true
        name = "asya-apk-retry"
    }.start()
}

internal fun AsyaService.buildApkTable(
    @Suppress("UNUSED_PARAMETER") pairs: IntArray,
): List<Pair<Int, String>> {
        /*
         * Every installed apk, not only the ones the rules name.
         *
         * The inode hook is not something to add and remove while the system is running: the record
         * belongs to that one file for as long as the module is loaded, so the set this hands over has
         * to be the whole of what is installed -- rebuilt on every boot, after every install and after
         * every start. The tool this replaces said the same in one line: one entry per apk file, even
         * when several share one.
         */
    val table = ArrayList<Pair<Int, String>>()

        /*
         * One entry per apk file: the kernel's ledger is keyed by the file itself, so the same
         * base.apk offered again -- another user, another package sharing it, a split listed twice --
         * is not a second hook but a refusal.
         */
    val offered = HashSet<String>()

    for (userId in UserManagerUtils.getUsers(false, false, false).map { it.id }.distinct()) {
        runCatching {
            val apps = pms.getInstalledApplicationsCompat(0L, userId)

            for (app in apps) {
                    /*
                     * Only the uids the kernel can name.
                     *
                     * A file handed over here is bound to the app that a child born from it belongs
                     * to, so a uid below the application range -- a system or shared uid, 1000 and
                     * 1001 and the rest -- has no such app to be bound to, and every one of them came
                     * back counted as a failure. On this device that was 69 of them, which is exactly
                     * how many packages carry such a uid, and it was the whole of the number the home
                     * screen showed. They cannot be named this way, so they are not offered.
                     */
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
                        /*
                         * A native app: hook the apk (already added) plus the libraries it can
                         * load. The declared name is only the entry point -- uidscan declares
                         * libnatsvc.so while the isolated child opens its dependency libscan.so
                         * -- so the ELF closure is what gets hooked. Nothing declared
                         * means the framework default, libmain.so; if that resolves to nothing
                         * on disk, every library of the app is hooked instead.
                         */
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
