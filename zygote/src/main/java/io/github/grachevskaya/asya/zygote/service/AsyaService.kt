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
import io.github.grachevskaya.asya.common.ServiceBridge

class AsyaService(
    val pms: IPackageManager,
    val pmn: Any?,
) : io.github.grachevskaya.asya.common.ServiceBridge.Host {
    // ---- ServiceBridge.Host: the in-process answers the manager asks for ----

    override val isAlive: Boolean get() = true

    /** Live from the kernel, not the last file write. One netlink round trip, cheap. */
    override fun moduleStatus(): Map<String, String> = StatusReporter.moduleStatus

    /** The kernel's last answer, kept so that a manager query never waits on a netlink round trip. */
    @Volatile
    internal var kernelStatusCache: Map<String, String> = emptyMap()

    override fun kernelStatus(): Map<String, String> = kernelStatusCache

    /** Package install/remove/replace: the table follows through the ordinary push path. */
    override fun onPackagesChanged() {
        invalidatePackageCaches()

        // The push that follows is what moves the kernel's numbers, and they are read on its way back:
        // an install or a removal is one of the moments the table is handed over again.
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

    override fun setManagerBinder(binder: android.os.IBinder) = onManagerBinder(binder)

    fun onManagerBinder(binder: android.os.IBinder) {
        managerBinder = binder

        // The manager can die while this service lives: without this, batches would be handed to a
        // binder that answers nothing, and the guard that keeps them for a live manager would pass.
        runCatching {
            binder.linkToDeath(
                {
                    managerBinder = null
                    logW(TAG) { "The manager is gone from the bridge" }
                },
                0,
            )
        }

        // What kind of object this is decides whether our own codes may be sent to it: a framework
        // interface refuses them, and this line is what tells the two apart in the field.
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
        logI(TAG) { "The manager is on the bridge: " + descriptor + " / " + binder.javaClass.name }

        logI(TAG) { "The manager is on the bridge" }
    }

    /** The live counter, which is what the manager reads while this service is in the process. */
    override fun filterCount(): Int = dataHolder.filterCountSnapshot()

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
    /** The manager pushed a configuration: the service applies it and keeps it in memory. */
    override fun setConfig(json: String): Boolean =
        runCatching { applyPushedConfig(json) }
            .onFailure { logE(TAG, it) { "Could not apply the pushed configuration" } }
            .isSuccess


    val hooker = BulkHooker()
    val dataHolder = AsyaServiceDataHolder()

    private val loggerLock = Any()

    /** When the counts last left this side: they travel on the log lines' clock. */
    @Volatile
    private var lastCountFlush = 0L

    /** The counts gathered so far leave in one document, and the ledger starts again from nothing. */
    private fun flushFilterCounts() {
        val binder = managerBinder ?: return
        val taken = dataHolder.takeFilterCounts()
        if (taken.totalCount == 0) return

        // The ledger goes back where it came from unless the manager said it took it.
        if (!runCatching { ServiceBridge.notifyFilterDeltas(binder, taken.toString()) }.getOrDefault(false)) {
            dataHolder.mergeFilterCounts(taken)
            logW(TAG) { "The counts were not taken; they wait for the next batch" }
        }
    }

    private fun countGathered() {
        val now = System.currentTimeMillis()
        if (now - lastCountFlush < ServiceBridge.LOG_FLUSH_PERIOD_MS) return

        lastCountFlush = now
        flushFilterCounts()
    }

    /** Guards the counts when they are cleared while a reader walks them. */
    private val statsLock = Any()

    /** The lines waiting for the next batch, and when the oldest of them arrived. */
    private val pendingLogLines = java.util.ArrayDeque<String>()

    private var pendingLogSince = 0L
    val systemApps = mutableSetOf<String>()
    private val frameworkHooks = mutableSetOf<IFrameworkHook>()

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
        runCatching { saveModuleStatus() }.onFailure { logE(TAG, it) { "saveModuleStatus" } }
        UserService.service = this
        dataHolder.onCount = { _, _, _ -> countGathered() }

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

        // Nothing is read here: what the kernel holds becomes true on the way back from a push, and
        // the push is what start-up is for. It happens as soon as the manager's configuration is in,
        // and it waits for the unlock without a deadline -- the apk paths cannot be resolved before
        // that, and the unlock is what the wait is for, not a window it has to fit into.
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
    )

    fun increasePMFilterCount(
        caller: String?,
        amount: Int = 1,
    ) = dataHolder.increaseFilterCount(
        caller,
        amount,
        FilterHolder.FilterType.PACKAGE_MANAGER,
    )

    fun increaseALFilterCount(
        caller: String?,
        amount: Int = 1,
    ) = dataHolder.increaseFilterCount(
        caller,
        amount,
        FilterHolder.FilterType.ACTIVITY_LAUNCH,
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
    /**
     * A line the module wants kept. It reaches logcat at once and waits here for company: whole
     * batches travel to the manager on the bridge, because one transaction per line would cost the
     * channel more than the logging costs.
     */
    fun addLog(
        parsedMsg: String,
        urgent: Boolean = false,
    ) {
        if (!ensureManagerWorkModeOK(true)) return

        val batch: Array<String>?
        synchronized(loggerLock) {
            pendingLogLines.addLast(parsedMsg)
            val now = System.currentTimeMillis()
            if (pendingLogSince == 0L) pendingLogSince = now

            val due =
                urgent ||
                    pendingLogLines.size >= ServiceBridge.LOG_BATCH_LINES ||
                    now - pendingLogSince >= ServiceBridge.LOG_FLUSH_PERIOD_MS

            // Nothing leaves while there is no manager to take it: the lines wait for the handshake
            // instead of being handed to a binder that is not there.
            batch = if (due && managerBinder != null) flushLocked() else null
        }

        batch?.let { sendLogLines(it) }
    }

    private fun flushLocked(): Array<String>? {
        if (pendingLogLines.isEmpty()) return null

        val lines = pendingLogLines.toTypedArray()
        pendingLogLines.clear()
        pendingLogSince = 0L

        return lines
    }

    private fun sendLogLines(lines: Array<String>) {
        val binder = managerBinder ?: return

        runCatching { ServiceBridge.notifyLogLines(binder, lines) }
            .onFailure { logW(TAG) { "Could not hand the log lines over: $it" } }
    }
    /**
     * The shutdown hook's moment: the increments still waiting are handed over, and the total is said
     * out loud. Nothing is written here, because nothing on this side is kept between runs.
     */
    /** The shutdown hook's moment: whatever is gathered leaves before the process does. */
    fun flushForShutdown() {
        flushFilterCounts()

        logI(TAG) { "Flushed for shutdown" }
    }

    /** The log lines are handed to the manager as they are made, so there is nothing to clear here. */
    fun clearLogs() = Unit

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
        // The defaults land in the configuration this side holds, and the manager keeps the file:
        // it is told what changed, since no file here would ever say it.
        if (changed) {
            managerBinder?.let { binder ->
                runCatching { ServiceBridge.notifyConfigChanged(binder, config.toString()) }
            }
            pushPolicyToKernel()
        }
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

    fun getDetailedFilterStats(): String = dataHolder.filterCountsSnapshot()

    override fun clearFilterStats() {
        dataHolder.clearFilterCounts()
    }

    fun reloadConfigFromFile() {
        invalidatePackageCaches()

        val loading =
            runCatching {
                // The configuration arrives over the bridge: this side has no file to read.
                config
                return
            }.getOrElse {
                logE(TAG, it) { "Failed to parse config.json" }
                return
            }

        config = loading
    }
}
