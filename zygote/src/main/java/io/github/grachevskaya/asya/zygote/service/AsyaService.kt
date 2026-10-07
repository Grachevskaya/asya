package io.github.grachevskaya.asya.zygote.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.IPackageManager
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.provider.Settings
import io.github.grachevskaya.asya.common.BuildConfig
import io.github.grachevskaya.asya.common.CollectionUtils.removeIf
import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.common.FilterHolder
import io.github.grachevskaya.asya.common.JsonConfig
import io.github.grachevskaya.asya.common.RiskyPackageUtils
import io.github.grachevskaya.asya.common.Utils.binderLocalScope
import io.github.grachevskaya.asya.common.Utils.cleanRemnantsFromConfig
import io.github.grachevskaya.asya.common.Utils.conflictedModules
import io.github.grachevskaya.asya.common.Utils.encoder
import io.github.grachevskaya.asya.common.Utils.generateRandomString
import io.github.grachevskaya.asya.common.Utils.getInstalledApplicationsCompat
import io.github.grachevskaya.asya.common.Utils.getPackageInfoCompat
import io.github.grachevskaya.asya.common.Utils.isSystemApp
import io.github.grachevskaya.asya.zygote.hook.ActivityHook
import io.github.grachevskaya.asya.zygote.hook.AppDataIsolationHook
import io.github.grachevskaya.asya.zygote.hook.BridgeHook
import io.github.grachevskaya.asya.zygote.hook.BroadcastHook
import io.github.grachevskaya.asya.zygote.hook.IFrameworkHook
import io.github.grachevskaya.asya.zygote.hook.PackageTransactionHook
import io.github.grachevskaya.asya.zygote.hook.PmsHookTarget29
import io.github.grachevskaya.asya.zygote.hook.PmsHookTarget30
import io.github.grachevskaya.asya.zygote.hook.PmsHookTarget31
import io.github.grachevskaya.asya.zygote.hook.PmsHookTarget33
import io.github.grachevskaya.asya.zygote.hook.PmsHookTarget34
import io.github.grachevskaya.asya.zygote.hook.ShutdownHook
import io.github.grachevskaya.asya.zygote.hook.UserChangeHook
import io.github.grachevskaya.asya.zygote.hook.ZygoteHook
import io.github.grachevskaya.asya.zygote.util.BrowserUtils.getDefaultBrowser
import io.github.grachevskaya.asya.zygote.util.BrowserUtils.getWebviewProvider
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logE
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.Logcat.logWithLevel
import io.github.grachevskaya.asya.zygote.util.PackageManagerUtils.isConflictingModuleInstalled
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.ensureFileIsRW
import io.github.grachevskaya.asya.zygote.util.UserManagerUtils
import io.github.grachevskaya.asya.zygote.util.ActivityManagerUtils
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.Path

class AsyaService(
    val pms: IPackageManager,
    val pmn: Any?,
) : io.github.grachevskaya.asya.common.ServiceBridge.Host {
    // ---- ServiceBridge.Host: the in-process answers the manager asks for ----

    override val isAlive: Boolean get() = true

    /** Live from the kernel, not the last file write. One netlink round trip, cheap. */
    override fun kernelStatus(): Map<String, String> = runCatching { KernelPolicy.requestStatus() }.getOrDefault(emptyMap())

    /** Package install/remove/replace: the table follows through the ordinary push path. */
    override fun onPackagesChanged() {
        invalidatePackageCaches()
        pushPolicyToKernel()
        announcePackagesChanged()
    }

    /**
     * The manager's notice, and the reason it is a broadcast.
     *
     * The manager runs in the host and this service runs in system_server, so a static reference
     * cannot reach it: what crosses a process boundary here is an intent. It is sent from the
     * transaction hook and not left to the package broadcast, which can arrive while the apk
     * directory is still being swapped -- the read the manager then takes is of the transient
     * state. Addressed at the host and registered-only, so no other app is woken by it.
     */
    private fun announcePackagesChanged() {
        val binder = managerBinder ?: return
        runCatching { io.github.grachevskaya.asya.common.ServiceBridge.notifyPackagesChanged(binder) }
            .onFailure { logW(TAG) { "Could not announce the package change: $it" } }
    }

    /** The manager's binder, from the handshake: the one way into its process from here. */
    @Volatile
    private var managerBinder: android.os.IBinder? = null

    fun onManagerBinder(binder: android.os.IBinder) {
        managerBinder = binder
        logI(TAG) { "The manager is on the bridge" }
    }

    /** The live counter, which is what the manager reads while this service is in the process. */
    override fun filterCount(): Int = dataHolder.filterHolder.totalCount

    override fun filterStatsJson(): String? = getDetailedFilterStats()

    /** Everything resolved from the package list, dropped so the next pass resolves it again. */
    private fun invalidatePackageCaches() {
        cachedPackageModel = null
        webviewProviderCache = null
        browserCache.clear()
    }

    override fun configJson(): String? = runCatching { configFile.takeIf { it.isFile }?.readText() }.getOrNull()

    companion object {
        private const val TAG = "Asya-Service"

        private const val ISOLATION_NONE = 0

        private const val ISOLATION_APP = 1

        private const val ISOLATION_NATIVE = 2

        /**
         * The directory is named with a random tail so that it is not a fixed thing to look for, and
         * the manager finds it by this prefix. Only names with the prefix are adopted: a bare "asya"
         * from an older layout is not this module's to keep, and adopting it would leave the
         * manager unable to find the directory it is supposed to read.
         */
        /**
         * The host's own private directory, and the name this module keeps inside it. Nothing
         * enumerates here: no service to find, no package to list.
         */

        /**
         * Where the state lives: a random name under /data/misc, which is upstream's arrangement and
         * the one that works -- the service has written here since the Zygisk rewrite, and the tail
         * is random so that the name is not a fixed thing to look for.
         *
         * The manager cannot read it: it is an activity of the host process, whose domain is denied
         * the system_data_file type that /data/misc carries. So the status is mirrored to one file in
         * the host's own private directory, which the host can read because the directory is its own
         * -- and whether this service can write there is answered by the log line that says so.
         */
        private const val HOST_DATA_ROOT = "/data/data/com.android.systemui"

        /** Where the same directory lived while the host was Settings, to be carried over once. */
        private const val PREVIOUS_HOST_DATA_ROOT = "/data/data/com.android.settings"
        private const val DIR_PREFIX = ".cache_"
    }

    @Volatile
    private var logcatAvailable = false

    val hooker = BulkHooker()
    val dataHolder = AsyaServiceDataHolder()

    private var managerWorkMode: Int = Constants.MANAGER_WORK_MODE_UNKNOWN

    private lateinit var dataDir: String
    private lateinit var configFile: File
    private var configObserver: FileObserver? = null
    private lateinit var filterCountFile: File
    private lateinit var logFile: File
    private lateinit var oldLogFile: File
    private lateinit var moduleStatusFile: File

    /** What the kernel half reports about itself, for the manager to show. */
    private lateinit var kernelFile: File

    private val configLock = Any()
    private val loggerLock = Any()
    val systemApps = mutableSetOf<String>()
    private val frameworkHooks = mutableSetOf<IFrameworkHook>()

    /** How many times the apk table has been retried; it cannot resolve before /data is readable. */
    private val apkRetries = AtomicInteger()

    var config = JsonConfig().apply { detailLog = true }
        private set

    init {
        managerWorkMode =
            if (pms.isConflictingModuleInstalled()) {
                logE(TAG) { "Conflicting module detected, skipping hook" }
                Constants.MANAGER_WORK_MODE_NO_HOOKS
            } else {
                Constants.MANAGER_WORK_MODE_LOADING
            }

        /*
         * Every step is guarded: this constructor runs inside system_server, and a throw from here
         * takes the service down. A step that fails says so and the rest carries on.
         */
        runCatching { searchDataDir() }.onFailure { logE(TAG, it) { "searchDataDir" } }
        runCatching { saveModuleStatus() }.onFailure { logE(TAG, it) { "saveModuleStatus" } }
        UserService.service = this
        runCatching { loadFilterCount() }.onFailure { logE(TAG, it) { "loadFilterCount" } }
        runCatching { loadConfig() }.onFailure { logE(TAG, it) { "loadConfig" } }
        runCatching { watchConfigFile() }.onFailure { logE(TAG, it) { "watchConfigFile" } }

        if (!File(dataDir).isDirectory) {
            waitForHostDirectory()
        }

        runCatching { pollKernelStatus() }.onFailure { logD(TAG) { "No kernel status poll: ${it.message}" } }

        if (managerWorkMode != Constants.MANAGER_WORK_MODE_NO_HOOKS) {
            installHooks()

            if (hooker.hooksWasCrashed) {
                managerWorkMode = Constants.MANAGER_WORK_MODE_CRASHED
            } else {
                managerWorkMode = Constants.MANAGER_WORK_MODE_OK
            }

            saveModuleStatus()
        }

        logI(TAG) { "Asya service initialized in mode $managerWorkMode" }
    }

    /**
     * Where the state lives, and why it is here and not somewhere else.
     *
     * Not under /data/misc: the manager is not a separate app, it is an activity of the host
     * process, and that process carries the system_app domain -- which SELinux does not let read the
     * system_data_file type that /data/misc is labelled with. Reading it would mean opening a hole
     * in the policy for the host, and a hole is a thing that can be found.
     *
     * The host's own data directory is the one place both sides already reach: the host because the
     * directory is its own, this service because system_server may work in an app's data. Nothing is
     * added to look for -- no service name to resolve, no package to enumerate, just a file inside a
     * private directory that other applications cannot list in the first place.
     */
    private fun searchDataDir() {
        val root = File(HOST_DATA_ROOT)
        val dir =
            root
                .listFiles { file -> file.isDirectory && file.name.startsWith(DIR_PREFIX) }
                ?.firstOrNull()
                ?: carryStateOver(root)
                ?: File(root, DIR_PREFIX + generateRandomString(16, ('a'..'z').toList())).apply {
                    mkdirs()
                    logI(TAG) { "Made the state directory: $path" }
                }

        if (dir == null) {
            logE(TAG) { "The host has not made \$HOST_DATA_ROOT/\$DIR_PREFIX* yet" }
            dataDir = File(root, DIR_PREFIX + "pending").path
        } else {
            dataDir = dir.path
        }

        val logDir = File(dataDir, "log")
        if (!logDir.isDirectory) logDir.mkdirs()
        configFile = File(dataDir, "config.json")
        filterCountFile = File(dataDir, "filter_count.json")
        logFile = File(logDir, "runtime.log")
        oldLogFile = File(logDir, "old.log")
        moduleStatusFile = File(dataDir, "status.json")
        kernelFile = File(dataDir, "kernel.json")

        clearLogs()

        logcatAvailable = true
        logI(TAG) { "Data dir: $dataDir" }

        try {
            // make the map issues easier to debug
            Files.copy(
                Path("/proc/self/maps"),
                Path(dataDir, "maps_module_thread.txt"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (cause: Throwable) {
            logE(TAG, cause) { "An error occurred while copying the map file" }
        }
    }

    fun saveModuleStatus() {
        try {
            val json =
                mapOf(
                    "workMode" to managerWorkMode,
                )

            ensureFileIsRW(moduleStatusFile, true)
            moduleStatusFile.writeText(encoder.encodeToString(json))
        } catch (cause: Throwable) {
            logE(TAG, cause) { "An error occurred while writing the status JSON" }
        }
    }

    /**
     * When the manager runs where this module's app is not installed, there is no provider for this
     * service to
     * hand its binder to, so it also publishes itself under a name. The api, the configuration and
     * the file are the same either way; only the handshake is different.
     */
    /**
     * The manager and this service are not in the same process -- one shows inside a host app,
     * the other is in system_server -- and the manager does not ask this service to do anything:
     * it writes the configuration file. So the file is the message, and this is what delivers it.
     * An inotify watch on the directory rather than a poll: a write, a rename over the file or a
     * delete all arrive here the moment they happen, and none of it costs a binder.
     *
     * This is also the place the kernel half will be told about the new policy, once that is
     * wired: a reload is exactly when the pairs change.
     */

    /**
     * The kernel half wants the same decisions this service makes, as (caller, target) uids: a
     * process whose uid is the caller must not see the target uid at all. The pairs are computed
     * with the very function the hooks use, so the two ends cannot disagree about who hides from
     * whom -- and a template applied to an app unrolls into its pairs here.
     *
     * It runs after every load, which is the moment the configuration changed. Off the calling
     * thread, because it is socket work, and best effort: a kernel half that is not there, or one
     * that refuses the upload, leaves the hiding exactly as it was, in this service.
     */
    /*
     * One rebuild at a time: the kernel keeps its stream state once, not per caller, so two passes
     * at the same time interleave their records. A request that arrives while one runs sets a flag
     * and the running pass takes it once more when it finishes.
     */
    private val policyPushRunning =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val policyPushQueued =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private fun pushPolicyToKernel() {
        if (!policyPushRunning.compareAndSet(false, true)) {
            policyPushQueued.set(true)
            return
        }
        pushPolicyToKernelOnce()
    }

    private fun pushPolicyToKernelOnce() {
        Thread {
            /*
             * Keep the event channel alive. The fifo has to live in the host's own directory
             * (both sides can reach it there) and that directory only exists once this service
             * is running -- so this is the place that can create it. The root side waits for
             * it and refreshes the module description on every byte written to it.
             */
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND,
            )
            try {
                val pairs = buildPolicyPairs()
                logI(TAG) { "Policy for the kernel: ${pairs.size / 2} pair(s)" }
                KernelPolicy.push(pairs)

                saveKernelStatus()

                waitForUserUnlocked(5 * 60_000L)

                val apks = buildApkTable(pairs)
                logIsolationClasses()
                logI(TAG) { "Apks for the kernel: ${apks.size}" }
                publishStatusToKernelSu(
                    pairs.size,
                    apks.size,
                    "none=" + isolationCounts[ISOLATION_NONE] +
                        " app=" + isolationCounts[ISOLATION_APP] +
                        " native=" + isolationCounts[ISOLATION_NATIVE],
                )
                KernelPolicy.pushApks(apks)

                saveKernelStatus()

                /*
                 * The kernel's ledger is as long as the module has been loaded, and this side's is as
                 * long as the service has been running -- so after the module is reloaded the two
                 * disagree: the kernel holds nothing while this side remembers everything, and a
                 * difference computed against a ledger the kernel does not have would offer nothing.
                 * When it says it holds none, this side forgets with it and sends the table again.
                 */
                val status = KernelPolicy.requestStatus()
                if (status["apkInodes"] == "0" && KernelPolicy.heldCount() > 0) {
                    logI(TAG) { "The kernel half holds nothing while this side does; sending the table again" }
                    KernelPolicy.forgetHeld()
                    KernelPolicy.pushApks(apks)
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
                policyPushRunning.set(false)
                if (policyPushQueued.compareAndSet(true, false)) {
                    pushPolicyToKernel()
                }
            }
        }.apply {
            isDaemon = true
            name = "asya-policy-push"
        }.start()
    }

    /**
     * Every (caller, target) the hiding rules say yes to, as uids, across the users this device
     * has. Whatever this service would hide, it says here, and the kernel half answers the same
     * question in the place the lookup is made.
     */
    /**
     * The base.apk of every app the rules name, which is what the kernel watches.
     *
     * An isolated child is named from the first file of its own code that it opens, and the kernel can
     * only watch a file it was told about. The uids come from the very pairs the policy is built from,
     * so the two tables cannot disagree about which apps are in play -- and a template applied to an
     * app puts its uid into those pairs, which is what makes the app's apk watchable.
     */

    /**
     * Tries the apk table again a little later.
     *
     * An app's path only resolves once the storage it lives on is readable, and at boot that is not
     * yet true -- the tool this replaces learned to wait for exactly this and said why in its log
     * ("cannot watch yet; /data may still be encrypted"). Waiting costs nothing and is the difference
     * between an app being watchable and an isolated child of it staying a number forever.
     */
    private fun scheduleApkRetry() {
        if (apkRetries.getAndIncrement() >= 5) {
            logW(TAG) { "Gave up on the apk table after ${apkRetries.get()} tries" }
            return
        }

        Thread {
            try {
                Thread.sleep(15_000)
            } catch (_: InterruptedException) {
                return@Thread
            }

            pushPolicyToKernel()
        }.apply {
            isDaemon = true
            name = "asya-apk-retry"
        }.start()
    }

    /**
     * Asks the kernel half what it is doing, and keeps the answer next to the status.
     *
     * This is the half of the module's description line that was about the kernel: which registrations
     * are in place, how many entries are hooked where, and what the apk side is holding. The manager
     * reads this directory already, so the card that shows it needs no channel of its own -- and the
     * numbers are the kernel's own, not a second opinion about them.
     */
    private fun saveKernelStatus() {
        try {
            val status = KernelPolicy.requestStatus()
            val json = encoder.encodeToString(status)
            ensureFileIsRW(kernelFile, true)
            kernelFile.writeText(json)
        } catch (cause: Throwable) {
            logD(TAG) { "Could not write the kernel status: ${cause.message}" }
        }
    }

    /**
     * The kernel's numbers move as apps come and go, so they are asked for again now and then; the card
     * would otherwise show whatever was true the last time the configuration changed.
     */
    private fun pollKernelStatus() {
        thread(isDaemon = true) {
            while (true) {
                Thread.sleep(30_000)
                runCatching { saveKernelStatus() }
            }
        }
    }

    /**
     *
     */

    /**
     *
     */
    private val isolationCounts = IntArray(3)

    private var diagnosedNativeModel = false

    private var declaredLogOnce = false
    private var packagesFoundForLog = -1
    private var androidPackageForLog: Any? = null
    private val propertyKeysForLog = ArrayList<String>()

    /**
     * Service flags that decide the class. Both values are read off the platform sources:
     * FLAG_ISOLATED_PROCESS = 0x2 (ServiceInfo and ProviderInfo), and FLAG_NATIVE_SERVICE =
     * 0x20, which ServiceInfo documents as "set from the android.R.attr#nativeService
     * attribute" and which exists only from API 37 -- so Android 12-16 always lands on the
     * apk-only class, where the child must open the apk to load its dex anyway.
     */
    private fun isolationClass(
        packageName: String,
        userId: Int,
    ): Int {
        val flags = (0x1 or 0x2 or 0x4 or 0x8).toLong() // GET_ACTIVITIES | RECEIVERS | SERVICES | PROVIDERS ✓
        val info = pms.getPackageInfoCompat(packageName, flags, userId) ?: return ISOLATION_NATIVE

        /*
         *       "Set from the android.R.attr#nativeService attribute."
         *     （frameworks/base/…/content/pm/ServiceInfo.java · android17-release ✓）
         *   "Services declared as android:nativeService=true … may not have the ART loaded
         *    inside their processes … Introduced in API 37."
         *
         */
        val isolated = 0x2 // FLAG_ISOLATED_PROCESS ✓
        val nativeBit = 0x20 // FLAG_NATIVE_SERVICE ✓

        fun isolated(flag: Int) = (flag and isolated) != 0

        fun nativeSvc(flag: Int) = (flag and nativeBit) != 0

        val services = info.services.orEmpty()
        val any =
            services.any { isolated(it.flags) } ||
                info.providers?.any { isolated(it.flags) } == true
        if (!any) {
            isolationCounts[ISOLATION_NONE]++
            return ISOLATION_NONE
        }

        /*
         * Native or regular? The service's own flags answer it: FLAG_ISOLATED_PROCESS (0x2)
         * together with FLAG_NATIVE_SERVICE (0x20, "set from the android.R.attr#nativeService
         * attribute", API 37 and later). When the parsed model cannot be read at all, an app
         * that declares a native library name still counts as native -- the conservative side.
         */
        /*
         * Native if either source says so. The flags are authoritative when the model can be
         * read, but on this device they said no for every app (native=0) while applications
         * that plainly declare a native service library -- uidscan declares libnatsvc.so -- were
         * left out; the declaration is the second source and it is what makes the difference.
         */
        val nativeByFlags =
            serviceFlagsFromModel(packageName)
                ?.any { isolated(it) && nativeSvc(it) } == true
        val nativeByDecl = !declaredNativeLibNames(packageName).isNullOrEmpty()
        val kind = if (nativeByFlags || nativeByDecl) ISOLATION_NATIVE else ISOLATION_APP
        isolationCounts[kind]++
        return kind
    }

    private fun logIsolationClasses() {
        logI(TAG) {
            "Isolation classes: none=" + isolationCounts[ISOLATION_NONE] +
                " app=" + isolationCounts[ISOLATION_APP] +
                " native=" + isolationCounts[ISOLATION_NATIVE] +
                " (SDK=" + Build.VERSION.SDK_INT +
                ", zygote_next=" + File("/system/bin/zygote_next").exists() + ")"
        }
    }

    /**
     *
     *
     */

    /**
     * Native library names this package declares. The parsed model holds them as
     * Map<String, PackageManager.Property> -- key is the property name, the value carries
     * the string -- for the package and for each service. Anything unreadable returns null
     * and the caller falls back to every .so of the app.
     */
    private fun declaredNativeLibNames(packageName: String): Set<String>? =
        runCatching {
            val packages = packageModel() ?: return@runCatching null
            packagesFoundForLog = packages.size
            val androidPackage = packages[packageName]
            androidPackageForLog = androidPackage ?: return@runCatching null

            if (!diagnosedNativeModel) {
                diagnosedNativeModel = true
                runCatching {
                    val cls = androidPackage.javaClass
                    val methods =
                        cls.methods
                            .map { it.name }
                            .filter {
                                it.contains("ropert", true) || it.contains("ative", true) ||
                                    it.contains("ervice", true) || it.contains("ygote", true)
                            }.sorted()
                            .take(40)
                    val fields =
                        cls.declaredFields
                            .map { it.name }
                            .filter {
                                it.contains("ropert", true) || it.contains("ative", true) ||
                                    it.contains("ervice", true) || it.contains("ygote", true)
                            }.sorted()
                            .take(40)
                    logI(TAG) {
                        "Native-model diagnosis: class=" + cls.name +
                            " methods=" + methods +
                            " fields=" + fields
                    }
                }.onFailure { logW(TAG, it) { "Native-model diagnosis failed" } }
            }

            val names = LinkedHashSet<String>()
            val wanted =
                setOf(
                    "android.app.PROPERTY_NATIVE_SERVICE_LIBRARY_NAME",
                    "android:zygotePreloadNativeLib",
                    "zygotePreloadNativeLib",
                )

            fun harvest(properties: Any?) {
                val map = properties as? Map<*, *> ?: return
                map.forEach { key, value ->
                    val name = key as? String ?: return@forEach
                    if (name !in wanted) return@forEach
                    val text =
                        runCatching {
                            value?.javaClass?.getMethod("getString")?.invoke(value) as? String
                        }.getOrNull() ?: (value as? String)
                    if (propertyKeysForLog.size < 12) propertyKeysForLog.add(name)
                    if (!text.isNullOrEmpty()) names.add(text)
                }
            }

            for (accessor in arrayOf("getProperties", "getPackageProperties", "getManifestProperties")) {
                runCatching {
                    harvest(androidPackage.javaClass.getMethod(accessor).invoke(androidPackage))
                }
            }
            runCatching {
                var cls: Class<*>? = androidPackage.javaClass
                var services: Any? = null
                while (cls != null && services == null) {
                    services = runCatching { cls!!.getMethod("getServices").invoke(androidPackage) }.getOrNull()
                    cls = cls.superclass
                }
                (services as? Collection<*>)?.forEach { service ->
                    if (service == null) return@forEach
                    runCatching {
                        harvest(service.javaClass.getMethod("getProperties").invoke(service))
                    }
                }
            }
            if (names.isNotEmpty()) {
                logI(TAG) { "Declared native libs for " + packageName + ": " + names }
                return@runCatching names
            }

            if (!declaredLogOnce) {
                declaredLogOnce = true
                logI(TAG) {
                    "Declared read failed for " + packageName +
                        ": pmsClass=" + pms.javaClass.name +
                        ", packagesFound=" + (packagesFoundForLog) +
                        ", packageFound=" + (androidPackageForLog != null) +
                        ", packageKeys=" + (propertyKeysForLog.take(6))
                }
            }
            null
        }.getOrNull()

    /**
     *
     *
     *
     */
    /**
     *
     */
    private fun appNativePreloadLib(app: ApplicationInfo): String? =
        runCatching {
            val field = ApplicationInfo::class.java.getField("zygotePreloadNativeLib")
            (field.get(app) as? String)?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    /**
     * The package table, resolved once and kept: resolving it per lookup costs seconds across
     * five hundred packages, and the table does not move while the service runs. It is the parsed model -- AndroidPackage instances -- because that is where a
     * service's declared properties live.
     */
    private fun packageModel(): Map<*, *>? {
        cachedPackageModel?.let { return it }
        /*
         * One route, and it is AOSP's own: the object a system process gets from ServiceManager
         * for "package" is PackageManagerService.IPackageManagerImpl, and that is an inner class
         * of the service --
         *   PackageManagerService.java: public class IPackageManagerImpl extends IPackageManagerBase
         *   PackageManagerService.java: final WatchedArrayMap<String, AndroidPackage> mPackages
         * -- so this$0 is the service instance and mPackages is its package table. No vendor
         * class, no alternative path: if this is not it, the module says so and stops guessing.
         */
        val found =
            runCatching {
                val service =
                    pms.javaClass
                        .getDeclaredField("this$0")
                        .apply { isAccessible = true }
                        .get(pms)
                        ?: return@runCatching null
                service.javaClass
                    .getDeclaredField("mPackages")
                    .apply { isAccessible = true }
                    .get(service) as? Map<*, *>
            }.getOrNull()
        if (found !=
            null
        ) {
            cachedPackageModel = found
        } else {
            logW(TAG) { "No package table: every native library declaration will read as empty" }
        }
        return found
    }

    private var cachedPackageModel: Map<*, *>? = null

    private fun serviceFlagsFromModel(packageName: String): List<Int>? =
        runCatching {
            val map = packageModel() ?: return@runCatching null
            var cls: Class<*>?
            val pkg = map[packageName] ?: return@runCatching null

            val list = ArrayList<Int>()
            var services: Any? = null
            cls = pkg.javaClass
            while (cls != null && services == null) {
                services = runCatching { cls!!.getMethod("getServices").invoke(pkg) }.getOrNull()
                cls = cls.superclass
            }
            (services as? Collection<*>)?.forEach { component ->
                if (component == null) return@forEach
                val flags =
                    runCatching {
                        component.javaClass.getMethod("getFlags").invoke(component) as? Int
                    }.getOrNull()
                if (flags != null) list.add(flags)
            }
            if (list.isEmpty()) null else list
        }.getOrNull()

    /**
     */
    private fun waitForUserUnlocked(timeoutMs: Long) {
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

    /**
     *
     */
    private fun publishStatusToKernelSu(
        pairs: Int,
        apks: Int,
        isolated: String,
    ) {
        val kernelUp =
            runCatching {
                jsonDecode(kernelFile.takeIf { it.isFile }?.readText() ?: "")["state"] == "loaded"
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

    private fun jsonDecode(text: String): Map<String, String> =
        runCatching {
            Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
                .findAll(text)
                .associate { it.groupValues[1] to it.groupValues[2] }
        }.getOrDefault(emptyMap())

    private fun buildApkTable(
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
                val apps = pms.getInstalledApplicationsCompat(0L, userId) ?: return@runCatching

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
                                    base?.let { File(it, n) }?.takeIf { f -> f.isFile }?.absolutePath
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

    private fun buildPolicyPairs(): IntArray {
        val pairs = LinkedHashSet<Long>()
        val callerPackages = config.scope.keys.toList()

        var targets = 0

        for (userId in UserManagerUtils.getUsers(false, false, false).map { it.id }.distinct()) {
            val packages = pms.getInstalledApplicationsCompat(0L, userId) ?: continue

            for (app in packages) {
                targets++
                val target = app.packageName

                for (caller in callerPackages) {
                    if (!shouldHide(caller, target, userId)) continue

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

    /**
     * Waits for the host to make the shared directory.
     *
     * The host makes it the first time it starts after a boot, which can be long after this service
     * has started -- and until it does, there is nowhere to keep the status. This looks again every
     * couple of seconds, and when the directory appears it writes the status into it and picks up
     * whatever configuration is there.
     */
    private fun waitForHostDirectory() {
        thread(isDaemon = true) {
            repeat(150) {
                Thread.sleep(2_000)

                val dir =
                    File(HOST_DATA_ROOT)
                        .listFiles { file -> file.isDirectory && file.name.startsWith(DIR_PREFIX) }
                        ?.firstOrNull() ?: return@repeat

                runCatching { searchDataDir() }
                runCatching { loadConfig() }
                runCatching { watchConfigFile() }
                runCatching { saveModuleStatus() }
                runCatching { saveKernelStatus() }

                logI(TAG) { "The host's directory appeared: $dataDir" }
                return@thread
            }

            logE(TAG) { "The host never made its directory; the manager will show no status" }
        }
    }

    /**
     * Brings the state over from where it was, once, when the host changes.
     *
     * The directory is named after the host, so moving the host from Settings to SystemUI would
     * otherwise leave the configuration, the templates and the counts behind in the old process's
     * data. This copies them across the first time the service looks and finds nothing, and leaves the
     * old copy alone: nothing here deletes anything of the user's.
     */
    private fun carryStateOver(root: File): File? {
        try {
            val previous =
                File(PREVIOUS_HOST_DATA_ROOT)
                    .listFiles { file -> file.isDirectory && file.name.startsWith(DIR_PREFIX) }
                    ?.maxByOrNull { it.lastModified() } ?: return null

            val source = File(previous, "config.json")
            if (!source.isFile || source.length() == 0L) return null

            val carried = File(root, DIR_PREFIX + previous.name.removePrefix(DIR_PREFIX))
            if (!carried.isDirectory && !carried.mkdirs()) return null

            for (name in arrayOf("config.json", "filter_count.json", "status.json")) {
                val from = File(previous, name)
                if (from.isFile) from.copyTo(File(carried, name), overwrite = true)
            }

            logI(TAG) { "The state was carried over from ${previous.path}" }
            return carried
        } catch (cause: Throwable) {
            logE(TAG, cause) { "Could not carry the state over" }
            return null
        }
    }

    private fun watchConfigFile() {
        val dir = configFile.parentFile ?: return
        val name = configFile.name

        val observer =
            object : FileObserver(
                dir.absolutePath,
                CREATE or MOVED_TO or CLOSE_WRITE or DELETE or ATTRIB,
            ) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    if (path != name) return

                    logI(TAG) { "The configuration changed on disk, reloading" }
                    try {
                        loadConfig()
                    } catch (cause: Throwable) {
                        logW(TAG, cause) { "Could not reload the configuration" }
                    }
                }
            }

        observer.startWatching()
        configObserver = observer
    }

    private fun loadConfig() {
        // remove the old filter count
        File("$dataDir/filter_count").also {
            try {
                if (it.exists()) it.delete()
            } catch (cause: Throwable) {
                logW(TAG, cause) { "Failed to delete filter count, skip it" }
            }
        }

        if (!configFile.exists()) {
            logI(TAG) { "Config file not found" }
            return
        }

        val loading =
            try {
                val json = configFile.readText()
                JsonConfig.parse(json)
            } catch (cause: Throwable) {
                logW(TAG, cause) { "Failed to parse config json, skip it" }

                config
            }

        if (loading.configVersion != BuildConfig.CONFIG_VERSION) {
            logW(TAG) { "Config version mismatch, need to reload" }
            return
        }

        if (config != loading) {
            loading.cleanRemnantsFromConfig()
            config = loading
        }

        pushPolicyToKernel()

        logI(TAG) { "Config loaded" }
    }

    private fun loadFilterCount() {
        if (!filterCountFile.exists()) {
            logI(TAG) { "Filter count file not found" }
            return
        }
        val loading =
            runCatching {
                val json = filterCountFile.readText()
                FilterHolder.parse(json)
            }.getOrElse {
                logE(TAG, it) { "Failed to parse filter count" }
                return
            }
        dataHolder.filterHolder = loading
        logI(TAG) { "Filter counts loaded" }
    }

    private fun installHooks() {
        pms.allPackages.filterTo(systemApps) {
            pms.getPackageInfoCompat(it, 0L, 0)?.applicationInfo?.isSystemApp() ?: false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            frameworkHooks.add(PmsHookTarget34())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            frameworkHooks.add(PmsHookTarget33())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            frameworkHooks.add(PmsHookTarget31())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frameworkHooks.add(PmsHookTarget30())
        } else {
            frameworkHooks.add(PmsHookTarget29())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frameworkHooks.add(AppDataIsolationHook())
        }

        frameworkHooks.add(ActivityHook())
        frameworkHooks.add(BroadcastHook())
        // The package events are the transactions themselves: an announcement can name a uid the
        // reinstall has already replaced, a transaction cannot.
        frameworkHooks.add(PackageTransactionHook())

        // A user being added or removed is not a package broadcast, and the apk table is built per
        // user -- so this is the event that keeps the kernel's set of apks the set of installed ones.
        frameworkHooks.add(UserChangeHook())
        frameworkHooks.add(ZygoteHook())

        // A power off is the last moment the throttled files can be brought up to date: what the
        // counter has counted since its last write lives in memory until this runs.
        frameworkHooks.add(ShutdownHook())

        // The bridge: the host's process observer registration is what carries it across.
        frameworkHooks.add(BridgeHook())

        frameworkHooks.forEach(IFrameworkHook::load)
        logI(TAG) { "Hooks installed" }
    }

    fun increasePMFilterCount(
        callingUid: Int?,
        amount: Int = 1,
    ) = dataHolder.increaseFilterCount(
        callingUid,
        amount,
        FilterHolder.FilterType.PACKAGE_MANAGER,
        ::writeFilterCount,
    )

    fun increasePMFilterCount(
        caller: String?,
        amount: Int = 1,
    ) = dataHolder.increaseFilterCount(
        caller,
        amount,
        FilterHolder.FilterType.PACKAGE_MANAGER,
        ::writeFilterCount,
    )

    fun increaseALFilterCount(
        caller: String?,
        amount: Int = 1,
    ) = dataHolder.increaseFilterCount(
        caller,
        amount,
        FilterHolder.FilterType.ACTIVITY_LAUNCH,
        ::writeFilterCount,
    )

    fun isHookEnabled(packageName: String?) = config.scope.containsKey(packageName)

    fun isAppDataIsolationExcluded(packageName: String?) = config.scope[packageName]?.excludeVoldIsolation ?: false

    fun isAppInGMSIgnoredPackages(
        caller: String,
        query: String,
    ) = (caller in Constants.gmsPackages) && RiskyPackageUtils.instance.appHasGMSConnection(query)

    fun shouldHide(
        caller: String?,
        query: String?,
        userId: Int,
    ): Boolean {
        if (caller == null || query == null) return false
        if (caller in Constants.packagesShouldNotHide || query in Constants.packagesShouldNotHide) return false
        if (caller == query) return false
        val appConfig = config.scope[caller] ?: return false

        if (config.webViewProtection) {
            /*
             * Both answers are the same for every pair in a pass, and both cost a package
             * manager query. Resolving them here meant 508 targets times every caller, twice,
             * which is the four seconds "Policy computed" was taking. Cached per user, cleared
             * whenever the configuration is reloaded.
             */
            val webviewProvider = cachedWebviewProvider()
            if (webviewProvider == caller || webviewProvider == query) return false

            val currentBrowser = cachedDefaultBrowser(userId)
            if (currentBrowser == caller || currentBrowser == query) return false
        }

        if (query in appConfig.extraAppList) return !appConfig.useWhitelist
        if (query in appConfig.extraOppositeAppList) return appConfig.useWhitelist

        for (tplName in appConfig.applyTemplates) {
            val tpl = config.templates[tplName] ?: continue
            if (query in tpl.appList) {
                if (isAppInGMSIgnoredPackages(caller, query)) return false

                return !appConfig.useWhitelist
            }
        }

        if (appConfig.useWhitelist && appConfig.excludeSystemApps && query in systemApps) return false

        return appConfig.useWhitelist
    }

    private var webviewProviderCache: Pair<Boolean, String?>? = null
    private val browserCache = HashMap<Int, String?>()

    /** The webview provider, resolved once (see shouldHide). */
    private fun cachedWebviewProvider(): String? {
        webviewProviderCache?.let { return it.second }
        val value = runCatching { getWebviewProvider() }.getOrNull()
        webviewProviderCache = true to value
        return value
    }

    /** The default browser for a user, resolved once per user (see shouldHide). */
    private fun cachedDefaultBrowser(userId: Int): String? {
        browserCache[userId]?.let { return it }
        val value = runCatching { getDefaultBrowser(pmn, userId) }.getOrNull()
        browserCache[userId] = value
        return value
    }

    fun shouldHideActivityLaunch(
        caller: String?,
        query: String?,
        userId: Int,
    ): Boolean {
        val appConfig = config.scope[caller]
        if (appConfig != null && shouldHide(caller, query, userId)) {
            return if (appConfig.invertActivityLaunchProtection) {
                config.disableActivityLaunchProtection
            } else {
                !config.disableActivityLaunchProtection
            }
        }

        return false
    }

    fun ensureManagerWorkModeOK(silent: Boolean = false): Boolean {
        if (managerWorkMode == Constants.MANAGER_WORK_MODE_NO_HOOKS) {
            if (!silent) logW(TAG) { "Cannot write while in no hooks mode" }
            return false
        }

        return true
    }

    fun addLog(parsedMsg: String) {
        if (!ensureManagerWorkModeOK(true)) return

        synchronized(loggerLock) {
            if (!logcatAvailable) return
            if (logFile.length() / 1024 > config.maxLogSize) clearLogs()
            logFile.appendText(parsedMsg)
        }
    }

    fun writeConfig(json: String) {
        if (!ensureManagerWorkModeOK()) return

        synchronized(configLock) {
            try {
                val newConfig = JsonConfig.parse(json)
                newConfig.cleanRemnantsFromConfig()
                if (newConfig.configVersion != BuildConfig.CONFIG_VERSION) {
                    logW(TAG) { "Sync config: version mismatch, need reboot" }
                    return
                }
                config = newConfig
                ensureFileIsRW(configFile, true)
                configFile.writeText(json)
                dataHolder.clearUidCache()

                // remove filter counts for apps if they are not in config
                dataHolder.filterHolder
                    .filterCounts
                    .removeIf { key, _ -> !config.scope.containsKey(key) }
                logD(TAG) { "Config synced" }
            } catch (cause: Throwable) {
                logE(TAG, cause) { "An error occurred while writing config" }
            }
        }

        writeFilterCount(true)
    }

    private var lastFilterCountWrite = 0L

    private fun writeFilterCount(force: Boolean = false) {
        if (!ensureManagerWorkModeOK()) return

        synchronized(configLock) {
            val now = System.currentTimeMillis()
            /*
             * The file is the fallback, not the live answer: while the service is in the process
             * the manager reads the counter through ServiceBridge. A write per filtered query is
             * a file write per query, so the file is written on the hundred and otherwise at most
             * once every two seconds -- and never immediately, because the write happens on the
             * increment that follows the interval. The file therefore lags a burst, not a rule.
             */
            if (!force &&
                dataHolder.filterHolder.totalCount % 100 != 0 &&
                now - lastFilterCountWrite < 2_000L
            ) {
                return
            }
            lastFilterCountWrite = now

            try {
                ensureFileIsRW(filterCountFile, true)
                filterCountFile.writeText(getDetailedFilterStats())
                logD(TAG) { "Filter count synced" }
            } catch (cause: Throwable) {
                logE(TAG, cause) { "An error occurred while writing filter count" }
            }
        }
    }

    /**
     * Everything kept behind a throttle, written once more. Called from the shutdown hook, where
     * the alternative is losing the tail of the session: the file is the only copy that outlives
     * this process. The log is not here because it is not buffered.
     */
    fun flushToDisk() {
        runCatching { writeFilterCount(force = true) }
            .onFailure { logE(TAG, it) { "Could not flush the filter count on shutdown" } }
        logI(TAG) { "State flushed for shutdown: " + dataHolder.filterHolder.totalCount + " filtered" }
    }

    fun getServiceVersion() = BuildConfig.SERVICE_VERSION

    fun getFilterCount() = dataHolder.filterHolder.totalCount

    fun clearLogs() {
        if (!ensureManagerWorkModeOK()) return

        synchronized(loggerLock) {
            oldLogFile.delete()
            logFile.renameTo(oldLogFile)
            logFile.createNewFile()
        }
    }

    /**
     * Called by the user hooks: the set of users changed, and with it the apk table.
     *
     * The push computes a difference against what the kernel holds, so a change that turns out to be
     * no change at all costs one comparison.
     */
    fun onUsersChanged() {
        pushPolicyToKernel()
        announcePackagesChanged()
    }

    /**
     * The default config, for packages that have just been installed.
     *
     * This arrived with the install broadcast until the broadcast hook was removed; now the install
     * transaction is the only install event there is, and it is the one that knows what was
     * installed. A package that already has a config keeps it, and an upgrade is not a new package.
     */
    fun applyDefaultConfigTo(packageNames: List<String>) {
        val default = config.defaultConfig ?: return
        if (packageNames.isEmpty()) return
        var changed = false
        for (name in packageNames) {
            if (config.scope.putIfAbsent(name, default) == null) changed = true
        }
        if (changed) writeConfig(config.toString())
    }

    fun forceStop(
        packageName: String?,
        userId: Int,
    ) {
        binderLocalScope {
            try {
                ActivityManagerUtils.forceStopPackage(requireNotNull(packageName), userId)
            } catch (cause: Throwable) {
                logE(TAG, cause) { "An error occurred while force stopping the package" }
            }
        }
    }

    fun log(
        level: Int,
        tag: String,
        message: String,
    ) {
        logWithLevel(level, tag) { message }
    }

    fun getPackageNames(userId: Int) =
        binderLocalScope {
            pms
                .getAllPackages()
                .filter { packageName ->
                    pms.isPackageAvailable(packageName, userId)
                }.toTypedArray()
        }

    fun getPackageInfo(
        packageName: String,
        userId: Int,
    ) = binderLocalScope {
        pms.getPackageInfoCompat(packageName, 0L, userId)
    }

    fun getLogFileLocation(): String = logFile.absolutePath

    fun getDetailedFilterStats() = dataHolder.filterHolder.toString()

    fun clearFilterStats() {
        synchronized(configLock) {
            dataHolder.filterHolder.filterCounts.clear()
        }

        writeFilterCount(true)
    }

    fun getServiceVersionName() = BuildConfig.APP_VERSION_NAME

    fun reloadConfigFromFile() {
        invalidatePackageCaches()

        val loading =
            runCatching {
                assert(configFile.exists())
                val json = configFile.readText()
                JsonConfig.parse(json)
            }.getOrElse {
                logE(TAG, it) { "Failed to parse config.json" }
                return
            }

        config = loading
    }

    fun getUserProfiles() = binderLocalScope { UserManagerUtils.userIds }
}
