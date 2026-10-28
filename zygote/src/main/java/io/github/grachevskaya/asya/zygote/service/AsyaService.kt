package io.github.grachevskaya.asya.zygote.service

import android.content.pm.IPackageManager
import android.os.Build
import android.os.IBinder
import io.github.grachevskaya.asya.common.BridgeProtocol
import io.github.grachevskaya.asya.common.BuildConfig
import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.common.FilterHolder
import io.github.grachevskaya.asya.common.JsonConfig
import io.github.grachevskaya.asya.common.RiskyPackageUtils
import io.github.grachevskaya.asya.common.ServiceBridge
import io.github.grachevskaya.asya.common.Utils.binderLocalScope
import io.github.grachevskaya.asya.common.Utils.getPackageInfoCompat
import io.github.grachevskaya.asya.common.Utils.isSystemApp
import io.github.grachevskaya.asya.zygote.hook.ActivityHook
import io.github.grachevskaya.asya.zygote.hook.AppDataIsolationHook
import io.github.grachevskaya.asya.zygote.hook.BridgeHook
import io.github.grachevskaya.asya.zygote.hook.BroadcastHook
import io.github.grachevskaya.asya.zygote.hook.IFrameworkHook
import io.github.grachevskaya.asya.zygote.hook.PackageTransactionHook
import io.github.grachevskaya.asya.zygote.hook.PmsHookTarget31
import io.github.grachevskaya.asya.zygote.hook.PmsHookTarget33
import io.github.grachevskaya.asya.zygote.hook.PmsHookTarget34
import io.github.grachevskaya.asya.zygote.hook.ShutdownHook
import io.github.grachevskaya.asya.zygote.hook.UserChangeHook
import io.github.grachevskaya.asya.zygote.hook.ZygoteHook
import io.github.grachevskaya.asya.zygote.service.precomputeAllNativeLibs
import io.github.grachevskaya.asya.zygote.util.ActivityManagerUtils
import io.github.grachevskaya.asya.zygote.util.BrowserUtils.getDefaultBrowser
import io.github.grachevskaya.asya.zygote.util.BrowserUtils.getWebviewProvider
import io.github.grachevskaya.asya.zygote.util.Logcat.logE
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.Logcat.logWithLevel
import io.github.grachevskaya.asya.zygote.util.PackageManagerUtils.isConflictingModuleInstalled

class AsyaService(
    val pms: IPackageManager,
    val pmn: Any?,
) : ServiceBridge.Host {
    // ---- ServiceBridge.Host: the in-process answers the manager asks for ----

    override val isAlive: Boolean get() = true

    /** Live from the kernel, not the last file write. One netlink round trip, cheap. */
    override fun moduleStatus(): Map<String, String> = StatusReporter.moduleStatus

    /** The kernel's last answer, kept so that a manager query never waits on a netlink round trip. */
    @Volatile
    internal var kernelStatusCache: Map<String, String> = emptyMap()

    // The rendezvous is a secret; it must never travel over the manager bridge.
    override fun kernelStatus(): Map<String, String> = kernelStatusCache.filterKeys { !it.startsWith("token") }

    /** Package install/remove/replace: the table follows through the ordinary push path. */
    override fun onPackagesChanged() {
        invalidatePackageCaches()

        // The push that follows is what moves the kernel's numbers, and they are read on its way back:
        // an install or a removal is one of the moments the table is handed over again. The passes
        // coalesce, so a burst of transactions is one push. The library pass is not asked for here:
        // an install that brings new libraries has them worked out by the transaction that made it,
        // and the one full pass a boot runs is the unlock's work rather than every package change's.
        pushPolicyToKernel()
        announcePackagesChanged()
    }

    /**
     * Sent from the transaction hook rather than left to the package broadcast, which can arrive while
     * the apk directory is still being swapped.
     */
    private fun announcePackagesChanged() {
        val binder = managerBinder ?: return
        runCatching {
            ServiceBridge.notifyPackagesChanged(binder)
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
                    if (managerBinder === binder) {
                        managerBinder = null
                        logW(TAG) { "The manager is gone from the bridge" }
                    }
                },
                0,
            )
        }

        // What kind of object this is decides whether these codes may be sent to it: a framework
        // interface refuses them, and this line is what tells the two apart in the field.
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
        logI(TAG) { "The manager is on the bridge: " + descriptor + " / " + binder.javaClass.name }

        logI(TAG) { "The manager is on the bridge" }
        // Whichever happens last (bootstrap or handshake) sends the restored snapshot.
        val snapshot = synchronized(configLock) { if (stateReady) config.toString() else null }
        snapshot?.let { json ->
            runCatching { ServiceBridge.notifyConfigChanged(binder, json) }
                .onFailure { logW(TAG, it) { "Could not send restored configuration to manager" } }
        }
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
     *
     * The hide table is not dropped here. It answers for the configuration, which a package change
     * does not touch, and clearing it on every install left hidden packages visible until the next
     * query rebuilt it -- the intermittent mismatch this split exists to prevent.
     */
    private fun invalidatePackageCaches() {
        PackageModelReader.cachedPackageModel = null
        clearNativeLibCache()
        webviewProviderCache = null
        browserCache.clear()
    }

    /** The service reads persisted state first; only an acknowledged snapshot is handed to the manager. */
    override fun configJson(): String? = synchronized(configLock) { if (stateReady) config.toString() else null }

    /** A manager update is accepted only after the state daemon has committed it. */
    override fun setConfig(json: String): Boolean =
        runCatching { synchronized(configLock) { stateReady && applyPushedConfig(json) } }
            .onFailure { logE(TAG, it) { "Could not apply the pushed configuration" } }
            .getOrDefault(false)

    val hooker = BulkHooker()

    /**
     * The table the query path reads. Its keys are uids, so it is handed the one place that already
     * knows which uid a package name is: the parsed package table. Asking the package manager from
     * inside a package manager hook would re-enter the hook that is asking.
     */
    val dataHolder = AsyaServiceDataHolder { packageName -> packageUid(packageName) }

    private val loggerLock = Any()

    private val configLock = Any()

    @Volatile private var stateReady = false
    private var savedCounts: String? = null
    private val countFlushQueued =
        java.util.concurrent.atomic
            .AtomicBoolean()
    private val stateExecutor =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "asya-state-flush").apply { isDaemon = true }
        }

    /** Hooks never do disk/socket I/O. The service owns totals; the manager receives snapshots. */
    private fun persistFilterCounts() {
        if (!stateReady) return
        synchronized(statsLock) {
            val text = dataHolder.filterCountsSnapshot()
            if (savedCounts != text && StateClient.write(StateClient.STATE_COUNTS, text.toByteArray(Charsets.UTF_8))) {
                savedCounts = text
            }
        }
    }

    /**
     * Every two seconds, and on the way in and out of the process: the counts the query path has
     * accumulated land in the ledger, which is written and sent once per pass rather than once per
     * query.
     */
    private fun flushFilterCounts() {
        if (!stateReady || !countFlushQueued.compareAndSet(false, true)) return
        stateExecutor.execute {
            try {
                dataHolder.flushPendingCounts()
                persistFilterCounts()
                flushNativeLibCache()
            } finally {
                countFlushQueued.set(false)
            }
        }
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

    private var webviewProviderCache: Pair<Boolean, String?>? = null
    private val browserCache = HashMap<Int, String?>()

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
        // Counts are flushed periodically, including the last increment of a quiet interval.

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

        // APK discovery must not depend on a manager config or a working state daemon. The pairs go
        // out now, because none of them is read from the user's half; the apk table is a full
        // enumeration of that half, so the pass leaves the table to the unlock and the library pass
        // below waits for it on a thread of its own -- which is what keeps this constructor short.
        pushPolicyToKernel()
        precomputeAllNativeLibs()
        startStateBootstrap()
        stateExecutor.scheduleWithFixedDelay({ flushFilterCounts() }, 2L, 2L, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Called by the manager or boot restoration. Never prune installed packages while locked. */
    private fun applyPushedConfig(
        json: String,
        persist: Boolean = true,
    ): Boolean {
        val loading =
            runCatching { JsonConfig.parse(json) }
                .getOrElse {
                    logW(TAG, it) { "Could not parse the pushed configuration" }
                    return false
                }

        if (loading.configVersion != BuildConfig.CONFIG_VERSION) {
            logW(TAG) { "Config version mismatch, need to reload" }
            return false
        }

        // Do not prune packages before unlock. A save is acknowledged only after durable storage.
        if (persist && !saveConfigBesideTheModule(loading.toString())) return false
        config = loading

        // The configuration is what the caches answer for, and the pass that follows is what the
        // kernel hears: a pushed configuration is applied here and nowhere else.
        invalidateConfigCaches()
        pushPolicyToKernel()

        // The asynchronous policy worker sends the matching hidden-name table.

        logI(TAG) {
            "Config loaded: " + json.length + " chars, " + config.scope.size + " caller(s), " +
                "detailLog=" + config.detailLog
        }
        return true
    }

    private fun installHooks() {
        pms.allPackages.filterTo(systemApps) {
            pms.getPackageInfoCompat(it, 0L, 0)?.applicationInfo?.isSystemApp() ?: false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            frameworkHooks.add(PmsHookTarget34())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            frameworkHooks.add(PmsHookTarget33())
        } else {
            // Android 12 is the oldest release this module runs on.
            frameworkHooks.add(PmsHookTarget31())
        }

        frameworkHooks.add(AppDataIsolationHook())

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
            // Both answers are the same for every pair in a pass, so they are cached per user and
            // cleared when the configuration is reloaded.
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

    /** One line for the next batch: a transaction per line would cost the channel more than logging. */
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
                    pendingLogLines.size >= BridgeProtocol.LOG_BATCH_LINES ||
                    now - pendingLogSince >= BridgeProtocol.LOG_FLUSH_PERIOD_MS

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

    /** Whatever is gathered leaves before the process does. */
    fun flushForShutdown() {
        // The process may exit before its scheduled worker runs: land what is counted and persist
        // this snapshot now.
        dataHolder.flushPendingCounts()
        persistFilterCounts()
        logI(TAG) { "Counts flushed for shutdown" }
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
        // A user that was just added has a tree nothing was registered on, so the kernel is asked to look
        // for the protected directories again before anything is answered from it.
        KernelPolicy.refresh()
        pushPolicyToKernel()
        announcePackagesChanged()
    }

    /** For packages that have just been installed; a package that already has a config keeps it. */
    fun applyDefaultConfigTo(packageNames: List<String>) {
        val updated =
            synchronized(configLock) {
                if (!stateReady || packageNames.isEmpty()) return
                val next = JsonConfig.parse(config.toString())
                val default = next.defaultConfig ?: return
                var changed = false
                for (name in packageNames) if (next.scope.putIfAbsent(name, default) == null) changed = true
                if (!changed || !applyPushedConfig(next.toString())) return
                config.toString()
            }
        managerBinder?.let { binder ->
            runCatching { ServiceBridge.notifyConfigChanged(binder, updated) }
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
        synchronized(statsLock) {
            check(stateReady) { "State is not ready" }
            val empty = FilterHolder().toString()
            check(StateClient.write(StateClient.STATE_COUNTS, empty.toByteArray(Charsets.UTF_8))) { "Could not persist cleared counts" }
            dataHolder.clearFilterCounts()
            savedCounts = empty
        }
    }

    /** Persist the configuration through the root daemon; never confuse an I/O failure with success. */
    private fun saveConfigBesideTheModule(text: String): Boolean {
        val saved = StateClient.write(StateClient.STATE_CONFIG, text.toByteArray(Charsets.UTF_8))
        if (saved) {
            logI(TAG) { "Configuration persisted: ${text.length} chars" }
        } else {
            logE(TAG) { "Configuration was not persisted" }
        }
        return saved
    }

    /** The four words the state side's secret arrived as, put back into the name it was made from. */
    private fun tokenFromKernel(): String? {
        val keys = arrayOf("token0", "token1", "token2", "token3")
        val words = keys.map { kernelStatusCache[it]?.toLongOrNull() ?: return null }
        return StateToken.name(words.map { it.toInt() }.toIntArray())
    }

    private fun startStateBootstrap() {
        Thread({
            while (!stateReady) {
                runCatching {
                    refreshKernelStatus()
                    val name = tokenFromKernel()
                    if (name != null && name.any { it != '0' }) {
                        StateClient.open(name)
                        synchronized(configLock) {
                            val bytes = StateClient.read(StateClient.STATE_CONFIG) ?: error("Configuration read failed")
                            val counts = StateClient.read(StateClient.STATE_COUNTS) ?: error("Counts read failed")
                            val restored = if (counts.isEmpty()) FilterHolder() else FilterHolder.parse(counts.toString(Charsets.UTF_8))
                            val text = if (bytes.isEmpty()) config.toString() else bytes.toString(Charsets.UTF_8)
                            check(applyPushedConfig(text, persist = bytes.isEmpty())) { "Stored configuration rejected" }
                            synchronized(statsLock) { dataHolder.mergeFilterCounts(restored) }
                            stateReady = true
                        }
                        logI(TAG) { "Persistent state restored" }
                        managerBinder?.let { binder -> runCatching { ServiceBridge.notifyConfigChanged(binder, config.toString()) } }
                        precomputeAllNativeLibs()
                        flushFilterCounts()
                    }
                }.onFailure { logE(TAG, it) { "State bootstrap pending; retrying" } }
                if (!stateReady) Thread.sleep(2_000L)
            }
        }, "asya-state-bootstrap").apply { isDaemon = true }.start()
    }
}
