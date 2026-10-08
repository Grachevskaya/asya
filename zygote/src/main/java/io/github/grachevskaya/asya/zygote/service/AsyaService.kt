package io.github.grachevskaya.asya.zygote.service

import android.content.pm.IPackageManager
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import io.github.grachevskaya.asya.common.BuildConfig
import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.common.FilterHolder
import io.github.grachevskaya.asya.common.JsonConfig
import io.github.grachevskaya.asya.common.RiskyPackageUtils
import io.github.grachevskaya.asya.common.Utils.binderLocalScope
import io.github.grachevskaya.asya.common.Utils.cleanRemnantsFromConfig
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
import io.github.grachevskaya.asya.zygote.util.ActivityManagerUtils
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
import java.io.File
import kotlin.concurrent.thread

class AsyaService(
    val pms: IPackageManager,
    val pmn: Any?,
) : io.github.grachevskaya.asya.common.ServiceBridge.Host {
    // ---- ServiceBridge.Host: the in-process answers the manager asks for ----

    override val isAlive: Boolean get() = true

    /** Live from the kernel, not the last file write. One netlink round trip, cheap. */
    override fun moduleStatus(): Map<String, String> = StatusReporter.moduleStatus

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
        runCatching {
            io.github.grachevskaya.asya.common.ServiceBridge
                .notifyPackagesChanged(binder)
        }.onFailure { logW(TAG) { "Could not announce the package change: $it" } }
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
        // The uid map is resolved from the same package list, so it goes with the model: a package
        // that was installed, replaced or removed has a different owner than the last pass thought.
        dataHolder.clearUidCache()
        PackageModelReader.cachedPackageModel = null
        webviewProviderCache = null
        browserCache.clear()
    }

    override fun configJson(): String? = runCatching { ConfigStore.configFile.takeIf { it.isFile }?.readText() }.getOrNull()

    companion object {
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
    }

    val hooker = BulkHooker()
    val dataHolder = AsyaServiceDataHolder()

    private var configObserver: FileObserver? = null

    private val loggerLock = Any()
    val systemApps = mutableSetOf<String>()
    private val frameworkHooks = mutableSetOf<IFrameworkHook>()

    /** Built once: the count path is walked on every filtered query and must not allocate. */
    private val filterCountWriter: () -> Unit = { writeFilterCount() }

    var config = JsonConfig().apply { detailLog = true }
        internal set

    init {
        StatusReporter.managerWorkMode =
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

        if (!File(ConfigStore.dataDir).isDirectory) {
            waitForHostDirectory()
        }

        runCatching { pollKernelStatus() }.onFailure { logD(TAG) { "No kernel status poll: ${it.message}" } }

        if (StatusReporter.managerWorkMode != Constants.MANAGER_WORK_MODE_NO_HOOKS) {
            installHooks()

            if (hooker.hooksWasCrashed) {
                StatusReporter.managerWorkMode = Constants.MANAGER_WORK_MODE_CRASHED
            } else {
                StatusReporter.managerWorkMode = Constants.MANAGER_WORK_MODE_OK
            }

            saveModuleStatus()
        }

        logI(TAG) { "Asya service initialized in mode ${StatusReporter.managerWorkMode}" }
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
     *
     */

    /**
     *
     *
     */

    /**
     *
     *
     *
     */

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

                logI(TAG) { "The host's directory appeared: $ConfigStore.dataDir" }
                return@thread
            }

            logE(TAG) { "The host never made its directory; the manager will show no status" }
        }
    }

    private fun watchConfigFile() {
        val dir = ConfigStore.configFile.parentFile ?: return
        val name = ConfigStore.configFile.name

        val observer =
            object : FileObserver(
                dir,
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
        File("$ConfigStore.dataDir/filter_count").also {
            try {
                if (it.exists()) it.delete()
            } catch (cause: Throwable) {
                logW(TAG, cause) { "Failed to delete filter count, skip it" }
            }
        }

        if (!ConfigStore.configFile.exists()) {
            logI(TAG) { "Config file not found" }
            return
        }

        val loading =
            try {
                val json = ConfigStore.configFile.readText()
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
        if (!ConfigStore.filterCountFile.exists()) {
            logI(TAG) { "Filter count file not found" }
            return
        }
        val loading =
            runCatching {
                val json = ConfigStore.filterCountFile.readText()
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
        filterCountWriter,
    )

    fun increasePMFilterCount(
        caller: String?,
        amount: Int = 1,
    ) = dataHolder.increaseFilterCount(
        caller,
        amount,
        FilterHolder.FilterType.PACKAGE_MANAGER,
        filterCountWriter,
    )

    fun increaseALFilterCount(
        caller: String?,
        amount: Int = 1,
    ) = dataHolder.increaseFilterCount(
        caller,
        amount,
        FilterHolder.FilterType.ACTIVITY_LAUNCH,
        filterCountWriter,
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
        if (StatusReporter.managerWorkMode == Constants.MANAGER_WORK_MODE_NO_HOOKS) {
            if (!silent) logW(TAG) { "Cannot write while in no hooks mode" }
            return false
        }

        return true
    }

    fun addLog(parsedMsg: String) {
        if (!ensureManagerWorkModeOK(true)) return

        synchronized(loggerLock) {
            if (!ModuleLogger.logcatAvailable) return

            /*
             * A log line is never worth a crash, and this runs on a pool thread: an exception here
             * takes system_server with it. The directory the file lives in is deleted by the
             * manager's own clean-up -- "Remove system service cache in /data/misc/asya_*" -- and
             * the service keeps running while it is gone, which is exactly the FileNotFoundException
             * that was seen in the crash record. One retry rebuilds the file and the directory;
             * anything after that is swallowed, because there is nowhere left to say it.
             */
            runCatching {
                if (ModuleLogger.logFile.length() / 1024 > config.maxLogSize) clearLogs()
                ModuleLogger.logFile.appendText(parsedMsg)
            }.onFailure {
                runCatching {
                    ensureFileIsRW(ModuleLogger.logFile, true)
                    ModuleLogger.logFile.appendText(parsedMsg)
                }
            }
        }
    }

    /**
     * Everything kept behind a throttle, written once more. Called from the shutdown hook, where
     * the alternative is losing the tail of the session: the file is the only copy that outlives
     * this process. The log is not here because it is not buffered.
     */
    fun flushToDisk() {
        runCatching { this.writeFilterCount(force = true) }
            .onFailure { logE(TAG, it) { "Could not flush the filter count on shutdown" } }
        logI(TAG) { "State flushed for shutdown: " + dataHolder.filterHolder.totalCount + " filtered" }
    }

    fun getServiceVersion() = BuildConfig.SERVICE_VERSION

    fun getFilterCount() = dataHolder.filterHolder.totalCount

    fun clearLogs() {
        if (!ensureManagerWorkModeOK()) return

        synchronized(loggerLock) {
            ModuleLogger.oldLogFile.delete()
            ModuleLogger.logFile.renameTo(ModuleLogger.oldLogFile)
            ModuleLogger.logFile.createNewFile()
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

    fun getLogFileLocation(): String = ModuleLogger.logFile.absolutePath

    fun getDetailedFilterStats() = dataHolder.filterHolder.toString()

    fun clearFilterStats() {
        synchronized(ConfigStore.configLock) {
            dataHolder.filterHolder.filterCounts.clear()
        }

        writeFilterCount(true)
    }

    fun getServiceVersionName() = BuildConfig.APP_VERSION_NAME

    fun reloadConfigFromFile() {
        invalidatePackageCaches()

        val loading =
            runCatching {
                assert(ConfigStore.configFile.exists())
                val json = ConfigStore.configFile.readText()
                JsonConfig.parse(json)
            }.getOrElse {
                logE(TAG, it) { "Failed to parse config.json" }
                return
            }

        config = loading
    }

    fun getUserProfiles() = binderLocalScope { UserManagerUtils.userIds }
}
