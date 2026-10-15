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
import io.github.grachevskaya.asya.common.JsonConfig
import io.github.grachevskaya.asya.common.ServiceBridge
import io.github.grachevskaya.asya.common.StatePaths
import java.io.File

/*
 * The manager sees the service over the bridge. Persistent configuration and count totals belong
 * to the root state daemon; this side keeps a display cache, never the authoritative copy.
 */
object ServiceClient {
    private const val TAG = "ServiceClient"

    /** Last service snapshot, used only when the bridge is temporarily unavailable. */
    @Volatile
    private var counts =
        FilterHolder()

    // The totals live with the service, which has them before this side is even awake.

    init {
        // The totals have to survive the manager being restarted, so a read that fails is said out
        // loud: an empty ledger and a ledger that could not be read look the same on the screen.
        val kept: String? =
            try {
                ServiceBridge.host
                    ?.filterStatsJson()
            } catch (cause: Throwable) {
                Log.e(TAG, "Could not read the totals from the service", cause)
                null
            }

        if (kept != null) {
            val keptCounts =
                try {
                    FilterHolder
                        .parse(kept)
                } catch (cause: Throwable) {
                    Log.e(TAG, "Could not parse the totals kept between runs", cause)
                    null
                }

            if (keptCounts != null) counts = keptCounts
        }

        ServiceBridge.logSink = { batch -> appendLogLines(batch) }

        // A service restart restores its own totals; these notifications only refresh the manager's cache.
        ServiceBridge.filterSink = { json -> addFilterCounts(json) }

        // This notification describes an already committed service-side update, not a save request.
        ServiceBridge.configSink = { json ->
            heldConfig = json
            ConfigManager.init()
        }

        // Only a configuration that was really read is handed over: an absent storage is not an empty
        // configuration, and pushing one would be answered by an empty policy in the kernel.
        ServiceBridge.onServiceReady = {
            Thread({
                heldConfig = null
                ConfigManager.init()
            }, "asya-load-config").apply { isDaemon = true }.start()
        }

        // On its own thread: the host's main thread is busy for seconds at boot, and the accessors
        // below read a file and call a binder, which any thread may do.
        if (ServiceBridge.serviceReady) {
            Thread({
                heldConfig = null
                ConfigManager.init()
            }, "asya-load-config").apply { isDaemon = true }.start()
        }

        android.util.Log.i(
            "AsyaManager",
            "client up: ready=" + ServiceBridge.serviceReady +
                ", host=" + (ServiceBridge.host != null) +
                ", bridge=" + System.identityHashCode(ServiceBridge) +
                ", loader=" + ServiceBridge::class.java.classLoader,
        )
    }

    /**
     * Hands the configuration over, whichever side is listening. Nothing at all is handed over when
     * nothing has been read: the service's own copy is then the one from before, and the kernel half
     * keeps what it has rather than being emptied with a document that was never anybody's.
     */
    fun pushConfig() {
        val loaded = configOrNull
        if (loaded == null) {
            Log.w(TAG, "There is no configuration to push yet; the storage has not been read")

            return
        }

        setConfig(loaded)
    }

    private fun addFilterCounts(json: String) {
        val incoming =
            runCatching {
                FilterHolder
                    .parse(json)
            }.getOrNull()
                ?: return

        // Only snapshots are cached. Totals remain authoritative in the service.
        counts = incoming
    }

    /** The lines the service hands over, kept here for the page: the file belongs to the service. */
    private val logLines = StringBuilder()

    private fun appendLogLines(batch: String) {
        try {
            synchronized(logLines) {
                logLines.append(batch)
                if (logLines.length > 64 * 1024) logLines.delete(0, logLines.length - 64 * 1024)
            }
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not append the log lines", cause)
        }
    }

    // ---------------------------------------------------------------- the service's files

    /** The manager's own directory, from the one place that names it: null until the storage is there. */
    private fun serviceDir(): File? =
        StatePaths
            .stateDirOrNull()

    private fun configFile(): File? = serviceDir()?.let { File(it, StatePaths.CONFIG_NAME) }

    /** The kernel's own answer, held by the service and handed over the bridge. */
    val kernelStatus: Map<String, String> get() =
        try {
            ServiceBridge.host
                ?.kernelStatus() ?: emptyMap()
        } catch (cause: Throwable) {
            Log.e(TAG, "Could not read the kernel status", cause)
            emptyMap()
        }

    private fun statusValue(name: String): Int =
        ServiceBridge.host
            ?.moduleStatus()
            ?.get(name)
            ?.toIntOrNull() ?: 0

    /**
     * Read from the file once, then held in memory: after the start the file is written, not read.
     *
     * Three answers are kept apart on purpose. A file that is not there, in a directory that can be
     * read, is a configuration that has never been written, and an empty one is the truth about it. A
     * directory that cannot be read at all is not an empty configuration but the absence of one, and
     * that is null: nothing is cached from it, so the next ask -- once the storage is readable --
     * reads the file that was there all along.
     */
    @Volatile
    private var heldConfig: String? = null

    val configOrNull: String?
        get() {
            heldConfig?.let { return it }

            /*
             * The service is the side that holds the configuration: it has it before the user's
             * storage is readable, and it is the side that writes it back. Nothing here reads a file.
             */
            val fromService =
                try {
                    ServiceBridge.host
                        ?.configJson()
                } catch (cause: Throwable) {
                    Log.e(TAG, "Could not read the configuration from the service", cause)

                    return null
                }

            if (fromService != null) heldConfig = fromService

            return fromService
        }

    /** Pushes configuration to durable service state. A negative ack never updates the UI cache. */
    fun setConfig(text: String): Boolean {
        android.util.Log.i(
            "AsyaManager",
            "pushing configuration: " + text.length + " chars, " +
                (
                    runCatching {
                        JsonConfig
                            .parse(text)
                            .scope.size
                    }.getOrNull()
                ) +
                " caller(s)",
        )

        // Handed over, not written: the service keeps it beside the module.
        val saved =
            runCatching {
                ServiceBridge.host
                    ?.setConfig(text) == true
            }.onFailure { Log.e(TAG, "Configuration save failed", it) }
                .getOrDefault(false)
        if (saved) {
            heldConfig = text
        } else {
            Log.e(TAG, "Configuration was not acknowledged by persistent storage")
        }
        return saved
    }

    val logs: String
        get() =
            try {
                synchronized(logLines) { logLines.toString() }
            } catch (_: Throwable) {
                ""
            }

    val logFileLocation: String get() = "(kept by the service)"

    fun clearLogs() {
        synchronized(logLines) { logLines.setLength(0) }
    }

    fun log(
        level: Int,
        tag: String,
        message: String,
    ) {
        synchronized(logLines) {
            logLines
                .append(level)
                .append('\t')
                .append(tag)
                .append('\t')
                .append(message)
                .append('\n')
        }
    }

    // ---------------------------------------------------------------- what the service reports

    val serviceVersion: Int get() = BuildConfig.VERSION_CODE

    val serviceVersionName: String get() = BuildConfig.VERSION_NAME

    /** 0 loading, 1 working, 2 sick, 3 no hooks: what the service last wrote. */
    val managerWorkMode: Int get() = statusValue("workMode")

    /** The total this side has added up, plus the increments the service has not handed over yet. */
    val filterCount: Int get() =
        runCatching {
            ServiceBridge.host
                ?.filterCount()
        }.getOrNull()
            ?: counts.totalCount

    /** The per-caller breakdown of what has been counted, as the totals this side keeps. */
    val detailedFilterStats: String get() {
        val snapshot =
            runCatching {
                ServiceBridge.host
                    ?.filterStatsJson()
            }.getOrNull()
        if (snapshot != null) runCatching { counts = FilterHolder.parse(snapshot) }
        return counts.toString()
    }

    fun clearFilterStats() {
        val host = ServiceBridge.host ?: error("Service is unavailable")
        host.clearFilterStats()
        counts = FilterHolder()
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

    /**
     * Hands the configuration over again, for a caller that has just come back. What this side holds is
     * what is sent: when nothing has been read yet, the file is read once here rather than an empty
     * document being sent in its place.
     */
    fun repushConfig() {
        pushConfig()
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
