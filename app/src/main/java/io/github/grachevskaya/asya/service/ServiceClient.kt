package io.github.grachevskaya.asya.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import io.github.grachevskaya.asya.BuildConfig
import io.github.grachevskaya.asya.MyApp.Companion.asyaApp
import io.github.grachevskaya.asya.common.FilterHolder
import java.io.File

/**
 * The manager reads and writes what the service reads and writes. There is no binder, because
 * nothing on the other end needs one: the manager shows from a host process that runs as the
 * system, so it opens the configuration file itself, appends to the log itself, answers package
 * queries with the same package manager the service used, and stops a package through the
 * activity manager it can reach.
 *
 * What keeps the two in step is the file. The service watches it and reloads when it changes,
 * and that is the moment it also hands the policy to the kernel.
 *
 * The names and shapes below are the ones callers already used, so nothing else had to change
 * when the binder went away.
 */
object ServiceClient {
    private const val TAG = "ServiceClient"

    /**
     * The host's own private directory, and the neutral prefix the service names its directory
     * with. The two sides agree on the prefix, and the tail is random so that the name is not a
     * fixed thing to look for.
     */
    private const val SERVICE_DIR = io.github.grachevskaya.asya.common.StatePaths.HOST_DATA_ROOT
    private const val DIR_PREFIX = io.github.grachevskaya.asya.common.StatePaths.DIR_PREFIX

    /** The totals per caller, as they have arrived so far. */
    @Volatile
    private var counts = io.github.grachevskaya.asya.common.FilterHolder()

    /** Where the totals live between runs: the manager is the side that keeps them. */
    private fun countsFile(): File? =
        serviceDir()?.let { dir -> File(dir, io.github.grachevskaya.asya.common.StatePaths.FILTER_COUNTS) }

    init {
        // The totals outlive this process: what was kept is read back before anything is added.
        val kept: String? = runCatching { countsFile()?.takeIf { it.isFile }?.readText() }.getOrNull()
        if (kept != null) {
            val keptCounts =
                runCatching { io.github.grachevskaya.asya.common.FilterHolder.parse(kept) }.getOrNull()
            if (keptCounts != null) counts = keptCounts
        }

        // The lines arrive in batches from the service, and this side is the one that keeps a file.
        io.github.grachevskaya.asya.common.ServiceBridge.logSink = { batch -> appendLogLines(batch) }

        // The counts arrive gathered, and the ledger behind them starts at zero again: they are
        // added here, which is what lets the total outlive a service that started over.
        io.github.grachevskaya.asya.common.ServiceBridge.filterSink = { json -> addFilterCounts(json) }

        // The service can change the configuration on its own, when a package has just been installed:
        // what it then holds is what is kept here, and nothing has to travel back for that.
        io.github.grachevskaya.asya.common.ServiceBridge.configSink = { json ->
            runCatching { configFile()?.writeText(json) }
        }

        // The handshake is the moment this side hands its configuration over: the service starts
        // with an empty one, and nothing else would push until the next save.
        io.github.grachevskaya.asya.common.ServiceBridge.onServiceReady = { config = config }
    }

    private fun addFilterCounts(json: String) {
        val incoming =
            runCatching { io.github.grachevskaya.asya.common.FilterHolder.parse(json) }.getOrNull()
                ?: return

        synchronized(counts) {
            incoming.filterCounts.forEach { (caller, added) ->
                val kept = counts.filterCounts.getOrPut(caller) { io.github.grachevskaya.asya.common.FilterHolder.FilterCount() }
                kept.packageManagerCount += added.packageManagerCount
                kept.activityLaunchCount += added.activityLaunchCount
            }

            runCatching { countsFile()?.writeText(counts.toString()) }
        }
    }

    private fun appendLogLines(batch: String) {
        try {
            val file = logFile() ?: return

            file.parentFile?.mkdirs()
            file.appendText(batch)
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not append the log lines", cause)
        }
    }

    // ---------------------------------------------------------------- the service's files

    private fun serviceDir(): File? =
        try {
            val root = File(SERVICE_DIR)
            val found = root.listFiles { file -> file.isDirectory && file.name.startsWith(DIR_PREFIX) }

            // Says what it saw, because everything else here is silence when it sees nothing.
            Log.i(
                TAG,
                "Looking in $SERVICE_DIR: isDirectory=${root.isDirectory} " +
                    "entries=${found?.size ?: -1} ${found?.joinToString { it.name }}",
            )

            // The directory is this side's own name under the host's data root: the prefix is kept for
            // the sibling the plugin uses, and nothing here has to guess between the two.
            found?.firstOrNull()
                ?: File(root, io.github.grachevskaya.asya.common.StatePaths.MANAGER_DIR).apply { mkdirs() }
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not look into $SERVICE_DIR", cause)
            null
        }

    private fun configFile(): File? = serviceDir()?.let { File(it, "config.json") }

    private fun logFile(): File? =
        try {
            serviceDir()?.let { dir -> File(dir, io.github.grachevskaya.asya.common.StatePaths.LOG_NAME) }
        } catch (_: Throwable) {
            null
        }

    /**
     * What the kernel half reported about itself, or nothing when it has not reported yet.
     *
     * The service asks the kernel and writes the answer next to the status it already keeps, so this
     * is the same file the manager reads for everything else -- the kernel's own numbers, shown where
     * a user can see them instead of only on the module's description line.
     */

    /**
     * What the kernel half reported about itself, or nothing when it has not reported yet.
     *
     * The service asks the kernel and holds the answer; the bridge hands it over. Nothing is
     * written for the manager to read, and a process the service is not in has no answer.
     */
    val kernelStatus: Map<String, String> get() =
        try {
            io.github.grachevskaya.asya.common.ServiceBridge.host
                ?.kernelStatus() ?: emptyMap()
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not read the kernel status", cause)
            emptyMap()
        }

    /*
     * The status lives in the service, not in a file: the bridge answers with what it holds. A
     * process the service is not in has no status, which is what the caller had before the file.
     */
    private fun statusValue(name: String): Int =
        io.github.grachevskaya.asya.common.ServiceBridge.host
            ?.moduleStatus()
            ?.get(name)
            ?.toIntOrNull() ?: 0

    var config: String
        get() =
            try {
                configFile()?.takeIf { it.isFile }?.readText() ?: "{}"
            } catch (cause: Throwable) {
                Log.e(TAG, "Could not read the configuration", cause)
                "{}"
            }
        set(text) {
            // The manager owns the disk and the service owns the decision: the document is written
            // here for the next start, then handed over the bridge for this one.
            try {
                configFile()?.writeText(text)
            } catch (cause: Throwable) {
                Log.e(TAG, "Could not write the configuration", cause)
            }

            io.github.grachevskaya.asya.common.ServiceBridge.host?.setConfig(text)
        }

    val logs: String
        get() =
            try {
                logFile()?.takeIf { it.isFile }?.readText()?.takeLast(64 * 1024) ?: ""
            } catch (_: Throwable) {
                ""
            }

    val logFileLocation: String get() = logFile()?.absolutePath ?: "(the service has not started)"

    fun clearLogs() =
        try {
            logFile()?.writeText("")
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not clear the log", cause)
        }

    fun log(
        level: Int,
        tag: String,
        message: String,
    ) = try {
        logFile()?.appendText("$level\t$tag\t$message\n")
    } catch (_: Throwable) {
    }

    // ---------------------------------------------------------------- what the service reports

    val serviceVersion: Int get() = BuildConfig.VERSION_CODE

    val serviceVersionName: String get() = BuildConfig.VERSION_NAME

    /** 0 loading, 1 working, 2 sick, 3 no hooks: what the service last wrote. */
    val managerWorkMode: Int get() = statusValue("workMode")

    /**
     * How many queries have been filtered: the total this side has added up, plus the increments the
     * service has not handed over yet. The service clears what it sends, so nothing is counted twice.
     */
    val filterCount: Int get() =
        counts.totalCount + (io.github.grachevskaya.asya.common.ServiceBridge.host?.filterCount() ?: 0)

    /** The per-caller breakdown of what has been counted, as the totals this side keeps. */
    val detailedFilterStats: String get() = counts.toString()

    fun clearFilterStats() {
        synchronized(counts) {
            counts = io.github.grachevskaya.asya.common.FilterHolder()
            runCatching { countsFile()?.writeText(counts.toString()) }
        }
    }

    // ---------------------------------------------------------------- the system, asked directly

    /**
     * The system context, which is what a process that is not an app can ask with. The manager
     * is one of those: it shows inside the host, so it has no application of its own.
     */
    private fun systemContext(): Context? =
        try {
            val thread =
                Class
                    .forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread")
                    .invoke(null)
            thread?.javaClass?.getMethod("getSystemContext")?.invoke(thread) as? Context
        } catch (_: Throwable) {
            null
        }

    /**
     * The package manager of the system, the same one the service used, reached the same way.
     */
    private fun packageManager(): Any? =
        try {
            Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null)
        } catch (_: Throwable) {
            null
        }

    val userProfiles: IntArray?
        get() =
            try {
                val context = systemContext() ?: return null
                val manager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return null
                manager.userProfiles
                    .map { it.hashCode() / 100000 }
                    .distinct()
                    .toIntArray()
            } catch (cause: Throwable) {
                Log.e(TAG, "Could not list users", cause)
                null
            }

    fun getPackageNames(userId: Int): Array<String>? =
        try {
            val manager = packageManager() ?: return null
        /*
         * The parameter types must be the primitives. Long::class.java is Kotlin's boxed
         * java.lang.Long, which does not match the AIDL method (String, long, int): getMethod
         * throws NoSuchMethodException, the catch below swallows it, every package comes back
         * null and most of the manager's lists and detail pages end up empty.
         */
            val slice =
                manager.javaClass
                    .getMethod("getInstalledPackages", Long::class.javaPrimitiveType, Integer.TYPE)
                    .invoke(manager, PackageManager.GET_ACTIVITIES.toLong(), userId)
            unserialize(slice, "getInstalledPackages")
                ?.mapNotNull { info ->
                    (info as? PackageInfo)?.packageName
                }?.distinct()
                ?.toTypedArray()
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not list packages for user $userId", cause)
            null
        }

    fun getPackageInfo(
        packageName: String?,
        userId: Int,
    ): PackageInfo? =
        try {
            val manager = packageManager() ?: return null
            manager.javaClass
                .getMethod("getPackageInfo", String::class.java, Long::class.javaPrimitiveType, Integer.TYPE)
                .invoke(manager, packageName, PackageManager.GET_ACTIVITIES.toLong(), userId) as? PackageInfo
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not read $packageName for user $userId", cause)
            null
        }

    fun forceStop(packageName: String) = forceStop(packageName, 0)

    fun forceStop(
        packageName: String,
        userId: Int,
    ) {
        try {
            val activityManager =
                Class
                    .forName("android.app.ActivityManager")
                    .getMethod("getService")
                    .invoke(null)
            val method =
                Class
                    .forName("android.app.ActivityManager")
                    .getMethod("forceStopPackage", String::class.java, Integer.TYPE)
            method.isAccessible = true
            method.invoke(activityManager, packageName, userId)
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not stop $packageName", cause)
        }
    }

    /**
     * Opens an app for a user: the launcher's own intent for that package, started as that user.
     *
     * The call is the framework's own (Context.startActivityAsUser), reached reflectively because
     * this module compiles against the public stubs only. The manager runs as the host, which is the
     * system, so the user named here is the user that gets it.
     */
    fun startMainActivityAsUser(
        packageName: String,
        userId: Int,
    ) {
        val context = asyaApp
        val launch =
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalStateException("No activity to open for $packageName")

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        try {
            val method =
                Context::class.java.getMethod(
                    "startActivityAsUser",
                    Intent::class.java,
                    UserHandle::class.java,
                )
            method.isAccessible = true

            // UserHandle.of is hidden, and the user is the one the caller named.
            val userMethod = UserHandle::class.java.getMethod("of", Integer.TYPE)
            userMethod.isAccessible = true
            method.invoke(context, launch, userMethod.invoke(null, userId))
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not open $packageName", cause)
            throw IllegalStateException(cause)
        }
    }

    fun reloadConfigFromFile() = Unit

    /** A parceled list, without naming the class: the api that returns it is hidden. */
    private fun unserialize(
        slice: Any?,
        what: String,
    ): List<Any>? =
        try {
            val list = slice?.javaClass?.getMethod("getList")?.invoke(slice) as? List<*> ?: return null
            @Suppress("UNCHECKED_CAST")
            list as List<Any>
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not read the list from $what", cause)
            null
        }
}
