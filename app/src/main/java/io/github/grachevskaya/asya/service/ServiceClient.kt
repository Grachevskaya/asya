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

/*
 * The manager, seen from itself: the bridge the module establishes in this process, and the files the
 * manager keeps. The configuration is read from its file once and held in memory after that; the log
 * lines and the totals arrive over the bridge and are written out here.
 */
object ServiceClient {
    private const val TAG = "ServiceClient"

    /** The totals per caller, as they have arrived so far. */
    @Volatile
    private var counts =
        io.github.grachevskaya.asya.common
            .FilterHolder()

    /** Where the totals live between runs: the manager is the side that keeps them. */
    private fun countsFile(): File? = File(serviceDir(), io.github.grachevskaya.asya.common.StatePaths.FILTER_COUNTS)

    init {
        // The totals have to survive the manager being restarted, so a read that fails is said out
        // loud: an empty ledger and a ledger that could not be read look the same on the screen.
        val kept: String? =
            try {
                countsFile()?.takeIf { it.isFile }?.readText()
            } catch (cause: Throwable) {
                Log.e(TAG, "Could not read the totals kept between runs", cause)
                null
            }

        if (kept != null) {
            val keptCounts =
                try {
                    io.github.grachevskaya.asya.common.FilterHolder
                        .parse(kept)
                } catch (cause: Throwable) {
                    Log.e(TAG, "Could not parse the totals kept between runs", cause)
                    null
                }

            if (keptCounts != null) counts = keptCounts
        }

        io.github.grachevskaya.asya.common.ServiceBridge.logSink = { batch -> appendLogLines(batch) }

        // The manager accumulates them, so the total outlives a service that started over.
        io.github.grachevskaya.asya.common.ServiceBridge.filterSink = { json -> addFilterCounts(json) }

        // The file is what the root side reads, so a write that fails is a configuration the kernel
        // never sees: it is logged rather than dropped.
        io.github.grachevskaya.asya.common.ServiceBridge.configSink = { json ->
            try {
                configFile()?.writeText(json)
            } catch (cause: Throwable) {
                Log.e(TAG, "Could not write the configuration the service sent", cause)
            }
        }

        io.github.grachevskaya.asya.common.ServiceBridge.onServiceReady = { config = config }

        // On its own thread: the host's main thread is busy for seconds at boot, and the accessors
        // below read a file and call a binder, which any thread may do.
        if (io.github.grachevskaya.asya.common.ServiceBridge.serviceReady) {
            Thread({ config = config }, "asya-push-config").apply { isDaemon = true }.start()
        }

        android.util.Log.i(
            "AsyaManager",
            "client up: ready=" + io.github.grachevskaya.asya.common.ServiceBridge.serviceReady +
                ", host=" + (io.github.grachevskaya.asya.common.ServiceBridge.host != null) +
                ", bridge=" + System.identityHashCode(io.github.grachevskaya.asya.common.ServiceBridge) +
                ", loader=" + io.github.grachevskaya.asya.common.ServiceBridge::class.java.classLoader,
        )
    }

    private fun addFilterCounts(json: String) {
        val incoming =
            runCatching {
                io.github.grachevskaya.asya.common.FilterHolder
                    .parse(json)
            }.getOrNull()
                ?: return

        synchronized(counts) {
            incoming.filterCounts.forEach { (caller, added) ->
                val kept =
                    counts.filterCounts.getOrPut(caller) {
                        io.github.grachevskaya.asya.common.FilterHolder
                            .FilterCount()
                    }
                kept.packageManagerCount += added.packageManagerCount
                kept.activityLaunchCount += added.activityLaunchCount
            }
        }

        // Posted, so the write lands after the answer the service waits for before clearing its ledger.
        android.os.Handler(android.os.Looper.getMainLooper()).post { persistCounts() }
    }

    /** Writes the totals as they stand, off the binder thread that took them. */
    private fun persistCounts() {
        try {
            countsFile()?.writeText(synchronized(counts) { counts.toString() })
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not write the totals out", cause)
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

    /** The manager's own directory, from the one place that names it. */
    private fun serviceDir(): File =
        io.github.grachevskaya.asya.common.StatePaths
            .stateDir()

    private fun configFile(): File = File(serviceDir(), io.github.grachevskaya.asya.common.StatePaths.CONFIG_NAME)

    private fun logFile(): File = File(serviceDir(), io.github.grachevskaya.asya.common.StatePaths.LOG_NAME)

    /** The kernel's own answer, held by the service and handed over the bridge. */
    val kernelStatus: Map<String, String> get() =
        try {
            io.github.grachevskaya.asya.common.ServiceBridge.host
                ?.kernelStatus() ?: emptyMap()
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not read the kernel status", cause)
            emptyMap()
        }

    private fun statusValue(name: String): Int =
        io.github.grachevskaya.asya.common.ServiceBridge.host
            ?.moduleStatus()
            ?.get(name)
            ?.toIntOrNull() ?: 0

    /** Read from the file once, then held in memory: after the start the file is written, not read. */
    @Volatile
    private var heldConfig: String? = null

    var config: String
        get() {
            heldConfig?.let { return it }

            val loaded =
                try {
                    configFile()?.takeIf { it.isFile }?.readText() ?: "{}"
                } catch (cause: Throwable) {
                    Log.e(TAG, "Could not read the configuration", cause)
                    "{}"
                }

            heldConfig = loaded

            return loaded
        }
        set(text) {
            heldConfig = text
            android.util.Log.i(
                "AsyaManager",
                "pushing configuration: " + text.length + " chars, " +
                    (
                        runCatching {
                            io.github.grachevskaya.asya.common.JsonConfig
                                .parse(text)
                                .scope.size
                        }.getOrNull()
                    ) +
                    " caller(s)",
            )

            try {
                configFile()?.writeText(text)
            } catch (cause: Throwable) {
                Log.e(TAG, "Could not write the configuration", cause)
            }

            io.github.grachevskaya.asya.common.ServiceBridge.host
                ?.setConfig(text)
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
    } catch (cause: Throwable) {
        // Not logged into the same file that refused the write; the system log is the fallback.
        Log.e(TAG, "Could not append the log line", cause)
    }

    // ---------------------------------------------------------------- what the service reports

    val serviceVersion: Int get() = BuildConfig.VERSION_CODE

    val serviceVersionName: String get() = BuildConfig.VERSION_NAME

    /** 0 loading, 1 working, 2 sick, 3 no hooks: what the service last wrote. */
    val managerWorkMode: Int get() = statusValue("workMode")

    /** The total this side has added up, plus the increments the service has not handed over yet. */
    val filterCount: Int get() =
        synchronized(counts) { counts.totalCount } +
            (
                io.github.grachevskaya.asya.common.ServiceBridge.host
                    ?.filterCount() ?: 0
            )

    /** The per-caller breakdown of what has been counted, as the totals this side keeps. */
    val detailedFilterStats: String get() = synchronized(counts) { counts.toString() }

    fun clearFilterStats() {
        synchronized(counts) {
            counts =
                io.github.grachevskaya.asya.common
                    .FilterHolder()
            runCatching { countsFile()?.writeText(counts.toString()) }
        }

        // The service still holds what it has not handed over: a reset has to reach that too, or
        // the next flush puts the total back on the screen.
        io.github.grachevskaya.asya.common.ServiceBridge.host
            ?.clearFilterStats()
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

    /** Hands the configuration this side already holds over again. No file is read. */
    fun repushConfig() {
        config = config
    }

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
