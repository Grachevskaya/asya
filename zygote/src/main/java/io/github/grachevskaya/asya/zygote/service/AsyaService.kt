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
import io.github.grachevskaya.asya.zygote.util.Logcat.logE
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.Logcat.logWithLevel
import io.github.grachevskaya.asya.zygote.util.PackageManagerUtils.isConflictingModuleInstalled
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.ensureFileIsRW
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

    /**
     * Everything resolved from the configuration, dropped so the next pass resolves it again: the
     * uid map, the webview provider and the default browser all answer questions a new config can
     * change.
     */
    private fun invalidateConfigCaches() {
        dataHolder.clearUidCache()
        webviewProviderCache = null
        browserCache.clear()
    }

    /**
     * Everything resolved from the package list, dropped so the next pass resolves it again: a
     * package that was installed, replaced or removed has a different owner than the last pass
     * thought.
     */
    private fun invalidatePackageCaches() {
        invalidateConfigCaches()
        PackageModelReader.cachedPackageModel = null
    }

    /** The configuration this service holds, answered from memory: the file is for the root side. */
    override fun configJson(): String? = runCatching { config.toString() }.getOrNull()

    /** The manager pushed a configuration: it is applied here and written to disk for the next boot. */
    override fun setConfig(json: String): Boolean =
        runCatching {
            // The file is for the root side; the service itself runs on what was pushed.
            writeConfig(json)
            applyPushedConfig(json)
        }
            .onFailure { logE(TAG, it) { "Could not apply the pushed configuration" } }
            .isSuccess


    val hooker = BulkHooker()
    val dataHolder = AsyaServiceDataHolder()

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

        if (!File(ConfigStore.dataDir).isDirectory) {
            waitForHostDirectory()
        }

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

    private fun waitForHostDirectory() {
        thread(isDaemon = true, name = "asya-service-init") {
            repeat(150) {
                Thread.sleep(2_000)

                val dir =
                    File(HOST_DATA_ROOT)
                        .listFiles { file -> file.isDirectory && file.name.startsWith(DIR_PREFIX) }
                        ?.firstOrNull() ?: return@repeat

                runCatching { searchDataDir() }
                runCatching { saveModuleStatus() }

                logI(TAG) { "The host's directory appeared: $ConfigStore.dataDir" }
                return@thread
            }

            logE(TAG) { "The host never made its directory; the manager will show no status" }
        }
    }

    /**
     * The configuration as the manager pushed it. Nothing here is read from disk: the host hands the
     * document over on the bridge, and the file is written only for the root side to read.
     */
    private fun applyPushedConfig(json: String) {
        val loading =
            runCatching { JsonConfig.parse(json) }
                .getOrElse {
                    logW(TAG, it) { "Could not parse the pushed configuration" }
                    return
                }

        if (loading.configVersion != BuildConfig.CONFIG_VERSION) {
            logW(TAG) { "Config version mismatch, need to reload" }
            return
        }

        loading.cleanRemnantsFromConfig()
        config = loading

        // The configuration is what the caches answer for, and the pass that follows is what the
        // kernel hears: a pushed configuration is applied here and nowhere else.
        invalidateConfigCaches()
        pushPolicyToKernel()
        logI(TAG) { "Config loaded" }
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
             * manager query: asking per pair is five hundred targets times every caller, twice.
             * Cached per user, cleared whenever the configuration is reloaded.
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
     * The install transaction is the install event: it is the one that knows what was installed.
     * A package that already has a config keeps it, and an upgrade is not a new package.
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

    fun getDetailedFilterStats() = dataHolder.filterHolder.toString()

    fun clearFilterStats() {
        synchronized(ConfigStore.configLock) {
            dataHolder.filterHolder.filterCounts.clear()
        }

        writeFilterCount(true)
    }

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
}
