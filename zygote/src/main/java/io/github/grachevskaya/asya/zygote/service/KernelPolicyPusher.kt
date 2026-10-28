package io.github.grachevskaya.asya.zygote.service

import android.content.pm.ApplicationInfo
import io.github.grachevskaya.asya.common.Utils.getInstalledApplicationsCompat
import io.github.grachevskaya.asya.common.Utils.getPackageInfoCompat
import io.github.grachevskaya.asya.common.storageIsUnlocked
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.UserManagerUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// The one place the kernel is told what to watch; a pass is never run twice at once.

internal fun AsyaService.pushPolicyToKernel() {
    logI(TAG) { "Policy push asked for" }

    policyWorker.submit { pushPolicyToKernelOnce() }
}

private val policyWorker = CoalescingWorker("asya-policy-push") { logW(TAG, it) { "Policy worker failed" } }
private val libraryWorker = CoalescingWorker("asya-native-libs") { logW(TAG, it) { "Library worker failed" } }

internal fun AsyaService.pushPolicyToKernelOnce() {
    run {
        android.os.Process.setThreadPriority(
            android.os.Process.THREAD_PRIORITY_BACKGROUND,
        )
        try {
            // Read first: it depends on nothing this pass builds, and the manager's card shows it.
            refreshKernelStatus()

            /* Nothing below reads the user's storage: package records, their paths and the apks
             * behind them are on the device's encrypted-at-boot half, and the module's own rules
             * come from /data/adb. The one pass that does read an app's files is the native
             * library pass, and that one waits for itself. */
            val policy = buildPolicyTables()
            val pairs = policy.pairs
            // What the kernel is handed is also what the query path reads, so both decide from one pass.
            dataHolder.publishPolicy(pairs, policy.hideTables)
            // The two sets behind the same pairs, decided here with them and published once: the names
            // each caller may not see, and the names the user has installed at all. The query path
            // reads them instead of deciding anything, and it reads both whichever answer it gives, so
            // a hidden name and a name that is not installed are answered the same way.
            // A push that decided nothing (a policy that is not readable yet, a moment with no callers)
            // must not replace a table that was already right: an empty table hides nothing.
            logI(TAG) { "Policy for the kernel: ${pairs.size / 2} pair(s), ${policy.hideTables.size} caller table(s)" }
            val policySaved = KernelPolicy.push(pairs)
            // The names are a stream of their own: a stream carries one kind, and the kernel replaces
            // the whole set of names at its END, so the pass that decided the pairs decides them too.
            val namesSaved = KernelPolicy.pushHiddenNames(policy.hideTables)

            /* The apk table is every installed package and the files behind them, which is a full
             * enumeration of the user's half and is only complete once that half is readable.
             * Building it before the unlock is what held a boot at the lock screen, so the pairs
             * above go now and the table is left to the unlock -- see awaitUnlockForApkTable. */
            if (!storageIsUnlocked(systemServerContext())) {
                logI(TAG) { "The apk table waits for the user's unlock" }
                awaitUnlockForApkTable()

                return@run
            }
            KernelPolicyPusher.apkTableWaitsForUnlock.set(false)

            val apks = buildApkTable(pairs)
            logIsolationClasses()
            logI(TAG) { "Apks for the kernel: ${apks.size}" }
            var apksSaved = KernelPolicy.pushApks(apks)

            // Read on the way back from the push: before it, the answer is what the kernel held then.
            var status = refreshKernelStatus()

            // A reloaded module holds nothing while this side remembers everything: forget with it and
            // send the table again.
            if (status["apkInodes"] == "0" && KernelPolicy.heldCount() > 0) {
                logI(TAG) { "The kernel half holds nothing while this side does; sending the table again" }
                KernelPolicy.forgetHeld()
                apksSaved = KernelPolicy.pushApks(apks)
                status = refreshKernelStatus()
            }

            val failed = status["apkFailed"]?.toIntOrNull()

            if (policySaved && namesSaved && apksSaved && failed == 0 && status["apkInodes"]?.toIntOrNull() != null) {
                // A table that landed is what the budget is for. Handing it back means the next thing
                // that needs a retry gets one, instead of being told the budget was spent at boot.
                KernelPolicyPusher.apkRetries.set(0)
            } else {
                logI(TAG) {
                    "The apk table did not land (entries=" + apks.size + ", failed=" + failed +
                        ", names=" + namesSaved + "; retrying)"
                }
                scheduleApkRetry()
            }
        } catch (cause: Throwable) {
            logW(TAG, cause) { "Could not hand the policy to the kernel half" }
            scheduleApkRetry()
        }
    }
}

/**
 * The apk table waits for the unlock on a thread of its own, once per boot.
 *
 * The wait has no deadline -- a device can sit at the lock screen for as long as its owner leaves it
 * there -- so the push that asked for the table is not held by it: that push has done its work, and
 * the unlock is what asks for the next one.
 */
private fun AsyaService.awaitUnlockForApkTable() {
    // One wait, not one per pass: a configuration push or a package transaction before the unlock
    // asks for the table again, and each of those would otherwise start a thread of its own.
    if (!KernelPolicyPusher.apkTableWaitsForUnlock.compareAndSet(false, true)) return

    Thread {
        if (!waitForUserUnlocked()) {
            // There is nothing to wait on: the bounded retry is what covers this boot.
            logW(TAG) { "Could not wait for the unlock; retrying the apk table" }
            KernelPolicyPusher.apkTableWaitsForUnlock.set(false)
            scheduleApkRetry()

            return@Thread
        }

        logI(TAG) { "The user is unlocked; the apk table follows" }
        pushPolicyToKernel()
    }.apply {
        isDaemon = true
        name = "asya-apk-unlock"
    }.start()
}

/**
 * Tries the apk table again once the storage is readable, which at boot it is not yet. One retry per
 * need, not per boot: the budget is handed back when a table lands.
 */

internal fun AsyaService.scheduleApkRetry() {
    if (KernelPolicyPusher.apkRetries.getAndIncrement() >= 5) {
        logW(TAG) { "Gave up on the apk table after ${KernelPolicyPusher.apkRetries.get()} tries" }
        return
    }

    // The table needs the package list, and the list is what the user being unlocked produces:
    // waiting on that gate is what the retries stood in for, and one wait is cheaper than five.
    Thread {
        Thread.sleep(2_000L)
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
                    val declaredNames = nativeLibNamesFor(app.packageName, application)
                    /* A push reads what is remembered and nothing else: working a package's
                     * libraries out means reading its directory, and that is done by the install
                     * that made it, or by the one pass this boot runs before any of this. */
                    val paths = cachedNativeLibs(application, userId)
                    logI(TAG) {
                        "Native app " + app.packageName + " declares " + declaredNames +
                            "; libs=" + paths.size
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

/*
 * The libraries a native app can load, per install. Working them out means reading the app's own
 * directory -- and something may be watching that directory, which is the whole reason the apk side
 * is careful about what it touches. It is therefore done once for an install and remembered: a push
 * that changes nothing does not read the app's files again.
 */
private val nativeLibs = HashMap<String, List<String>>()

/**
 * What a push uses: the answer an install worked out, and nothing else.
 */
private fun cachedNativeLibs(
    application: ApplicationInfo,
    userId: Int,
): List<String> = synchronized(nativeLibs) { nativeLibs[nativeLibsKey(application, userId)] }.orEmpty()

/**
 * What a package's libraries are is decided by the install it came from, so the key is the install:
 * the paths the package manager hands out. An update replaces every one of them, which is exactly
 * when the answer has to be worked out again.
 */
private fun nativeLibsKey(
    application: ApplicationInfo,
    userId: Int,
): String =
    userId.toString() + ":" + (application.sourceDir ?: "") + "|" +
        (application.splitSourceDirs?.joinToString("|") ?: "") + "|" +
        (application.nativeLibraryDir ?: "")

/*
 * Remembered across boots: working the libraries out means reading the app's own directory.
 *
 * It is kept beside the module and not in the host's data directory, because /data/adb is decrypted
 * long before the user's storage is: what was worked out last time is readable from the first moment
 * of a boot, and nothing has to wait for the unlock to have it. The host's directory is only a
 * fallback for a module that carries no state directory of its own.
 */

/**
 * The file this state lives in, opened while this process was still root and handed on as a
 * descriptor: the directory itself is root-only, and nothing of this module is written anywhere
 * else for the host to read.
 */

@Volatile private var librariesLoaded = false
private var savedLibraryText: String? = null
private val nativeDiscoveryLock = Any()

internal fun flushNativeLibCache() {
    if (!librariesLoaded) librariesLoaded = loadNativeLibCache()
    if (librariesLoaded) saveNativeLibCache()
}

internal fun loadNativeLibCache(): Boolean {
    return runCatching {
        val bytes = StateClient.read(StateClient.STATE_LIBRARIES) ?: return false
        val json = if (bytes.isEmpty()) JSONObject() else JSONObject(bytes.toString(Charsets.UTF_8))
        synchronized(nativeLibs) {
            for (key in json.keys()) {
                val array = json.optJSONArray(key) ?: continue
                val paths = ArrayList<String>(array.length())
                for (i in 0 until array.length()) {
                    val path = array.optString(i)
                    if (path.isNotEmpty()) paths.add(path)
                }
                nativeLibs.putIfAbsent(key, paths)
            }
        }
        logI(TAG) { "Libraries remembered from the last time: " + nativeLibs.size }
        true
    }.onFailure { logW(TAG, it) { "Corrupt derived library cache; rebuilding" } }.getOrDefault(true)
}

private val librarySaveLock = Any()

private fun saveNativeLibCache(): Boolean =
    synchronized(librarySaveLock) {
        if (!librariesLoaded) return false
        runCatching {
            val json = JSONObject()
            synchronized(nativeLibs) { nativeLibs.forEach { (key, paths) -> json.put(key, JSONArray(paths)) } }
            val text = json.toString()
            if (text == savedLibraryText) return true
            check(StateClient.write(StateClient.STATE_LIBRARIES, text.toByteArray(Charsets.UTF_8))) { "Cache write was rejected" }
            savedLibraryText = text
            logI(TAG) { "Libraries persisted: " + json.length() + " entries" }
            true
        }.onFailure { logW(TAG, it) { "Could not remember the libraries" } }.getOrDefault(false)
    }

/** True when this install's libraries are already known, so nothing has to be read. */
internal fun hasNativeLibs(
    application: ApplicationInfo,
    userId: Int,
): Boolean = synchronized(nativeLibs) { nativeLibs.containsKey(nativeLibsKey(application, userId)) }

/**
 * Scan installed native apps after unlock in a coalesced background worker. The APK table pushes
 * independently; once libraries are known, a second policy push picks up their identities.
 */
internal fun AsyaService.precomputeAllNativeLibs() {
    libraryWorker.submit {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        // Readable before the user's storage is: this is what makes a boot have the answers.
        logI(TAG) { "Starting library cache pass" }
        if (!librariesLoaded) librariesLoaded = loadNativeLibCache()
        // A failed disk read must not block discovery or the ordinary APK table.
        // Existing state is merged later; it is not overwritten before a successful read.
        try {
            waitForUserUnlocked()
            logI(TAG) { "The storage is readable; working out what was not remembered" }
            for (userId in UserManagerUtils.getUsers(false, false, false).map { it.id }.distinct()) {
                var worked = 0
                runCatching {
                    for (app in pms.getInstalledApplicationsCompat(0L, userId)) {
                        if (app.uid < android.os.Process.FIRST_APPLICATION_UID) continue
                        // known already: nothing is read, and the package manager is not asked
                        if (hasNativeLibs(app, userId)) continue
                        worked++
                        precomputeNativeLibs(app.packageName, userId)
                    }
                }.onFailure { logD(TAG) { "No libraries to work out for user " + userId + ": " + it.message } }
                logI(TAG) { "Libraries worked out for user " + userId + ": " + worked }
            }
        } catch (cause: Throwable) {
            logD(TAG) { "The native library pass did not finish: " + cause.message }
        } finally {
            // Rebuild after the cache is warm, independently of whether persistence succeeded.
            pushPolicyToKernel()
            // A failed save stays dirty and is retried by the service's periodic state flush.
            if (!librariesLoaded) librariesLoaded = loadNativeLibCache()
            if (librariesLoaded) saveNativeLibCache()
        }
    }
}

/** Worked out while the install runs, so that no later pass has to read the app's files. */
internal fun AsyaService.precomputeNativeLibs(
    packageName: String,
    userId: Int,
) {
    runCatching {
        val application =
            pms.getPackageInfoCompat(packageName, 0L, userId)?.applicationInfo ?: return
        if (application.nativeLibraryDir == null) return
        if (isolationClass(packageName, userId) != ISOLATION_NATIVE) return
        nativeLibPaths(application, userId, nativeLibNamesFor(packageName, application))
    }.onFailure { logW(TAG, it) { "No native libraries for " + packageName } }
}

internal fun AsyaService.nativeLibNamesFor(
    packageName: String,
    application: ApplicationInfo,
): LinkedHashSet<String> =
    LinkedHashSet<String>().apply {
        declaredNativeLibNames(packageName)?.let { addAll(it) }
        appNativePreloadLib(application)?.let { add(it) }
        if (isEmpty()) add("libmain.so")
    }

internal fun clearNativeLibCache() {
    synchronized(nativeDiscoveryLock) { synchronized(nativeLibs) { nativeLibs.clear() } }
}

private fun nativeLibPaths(
    application: ApplicationInfo,
    userId: Int,
    declaredNames: Set<String>,
): List<String> =
    synchronized(nativeDiscoveryLock) {
        val dir = application.nativeLibraryDir ?: return emptyList()
        if (application.sourceDir == null) return emptyList()
        val key = nativeLibsKey(application, userId)
        synchronized(nativeLibs) { nativeLibs[key] }?.let { return it }

        val paths =
            runCatching {
                val base = File(dir)
                // A stat, not a listing: an install with no libraries on disk is left alone.
                if (!base.exists()) {
                    // Absence or EACCES must not become a permanent negative cache entry.
                    return emptyList()
                } else {
                    check(base.isDirectory && base.canRead()) { "Library directory is unreadable: $dir" }
                    val closure = ElfDeps.closure(declaredNames, base)
                    val existing =
                        closure.orEmpty().mapNotNull { n ->
                            File(base, n).takeIf { it.isFile }?.absolutePath
                        }
                    if (existing.isNotEmpty()) {
                        existing
                    } else {
                        (
                            base.listFiles() ?: error(
                                "Cannot list library directory: $dir",
                            )
                        ).filter { it.isFile && it.name.endsWith(".so") }.map { it.absolutePath }
                    }
                }
            }.getOrElse { cause ->
                logW(TAG, cause) { "Native library discovery failed: $dir" }
                return emptyList()
            }

        synchronized(nativeLibs) { nativeLibs[key] = paths }
        return paths
    }

/**
 * What one pass over the installed packages decides: the pairs the kernel is handed, and the table the
 * query path reads. Both come from the same walk of the same package list, so the kernel and the hooks
 * cannot be told different things.
 */
private class PolicyTables(
    val pairs: IntArray,
    val hideTables: Map<Int, HideTables>,
    val ownerByPackage: Map<String, Int>,
)

private fun AsyaService.buildPolicyTables(): PolicyTables {
    val pairs = LinkedHashSet<Long>()
    // Per caller uid: the names it may not see, and the names the user has at all. Two caller packages
    // that are one application share a uid and therefore share one entry.
    val hiddenByCaller = HashMap<Int, MutableSet<String>>()
    val installedByCaller = HashMap<Int, Set<String>>()
    val callerPackages = config.scope.keys.toList()

    var targets = 0
    // A name record names the uid the directory belongs to: the kernel asks the policy whether this
    // caller may see that uid, the same question the uid queries ask.
    val ownerByPackage = HashMap<String, Int>()

    for (userId in UserManagerUtils.getUsers(false, false, false).map { it.id }.distinct()) {
        val packages = pms.getInstalledApplicationsCompat(0L, userId)
        targets += packages.size
        val installed = packages.mapTo(HashSet<String>(packages.size)) { it.packageName }

        for (caller in callerPackages) {
            //noinspection NewApi
            val callerUid = pms.getPackageUid(caller, 0L, userId)
            if (callerUid < 0) continue

            // A caller whose enumeration came back empty has no table: a table with no installed names
            // would answer every name as absent, which is worse than not hiding at all.
            if (installed.isEmpty()) continue

            val hidden = hiddenByCaller.getOrPut(callerUid) { HashSet() }
            // Every user's names, not the last user's: one uid serves them all, and a set that carried only
            // the last profile would answer every other name as not installed.
            installedByCaller[callerUid] =
                installedByCaller[callerUid]?.let { it + installed } ?: installed.toSet()

            for (app in packages) {
                val target = app.packageName
                val targetUid = app.uid
                // A package is never hidden from itself, and the check comes before the name is
                // recorded: the kernel hides a directory by that name, so a name left in the set would
                // take the caller's own device-encrypted directory away and its process could not start.
                if (targetUid > 0 && targetUid == callerUid) continue
                if (!shouldHide(caller, target, userId)) continue

                hidden.add(target)
                if (targetUid <= 0) continue

                ownerByPackage[target] = targetUid

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

    val tables = HashMap<Int, HideTables>(hiddenByCaller.size)
    for ((callerUid, hidden) in hiddenByCaller) {
        tables[callerUid] = HideTables(hidden, installedByCaller[callerUid].orEmpty())
    }

    return PolicyTables(out, tables, ownerByPackage)
}

/*
 * The state this part owns: whether a pass is running, whether one was asked for while it ran, and
 * how many times the apk table has been tried.
 */
internal object KernelPolicyPusher {
    /** How many times the apk table has been retried; it cannot resolve before /data is readable. */
    internal val apkRetries = AtomicInteger()

    /** True while a thread waits for the unlock the apk table cannot be built without. */
    internal val apkTableWaitsForUnlock = AtomicBoolean()
}
