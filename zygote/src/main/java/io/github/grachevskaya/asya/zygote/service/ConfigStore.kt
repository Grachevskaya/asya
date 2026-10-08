package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.BuildConfig
import io.github.grachevskaya.asya.common.CollectionUtils.removeIf
import io.github.grachevskaya.asya.common.JsonConfig
import io.github.grachevskaya.asya.common.Utils.cleanRemnantsFromConfig
import io.github.grachevskaya.asya.common.Utils.generateRandomString
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logE
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.ensureFileIsRW
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path

/*
 * Where the configuration lives and how it reaches the disk: the state directory is found once,
 * and a config is written beside its file and moved onto it, so a stop in between leaves the old
 * one whole.
 */

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

internal fun AsyaService.searchDataDir() {
    val root = File(HOST_DATA_ROOT)
    val dir =
        root
            .listFiles { file -> file.isDirectory && file.name.startsWith(DIR_PREFIX) }
            ?.firstOrNull()
            ?: File(root, DIR_PREFIX + generateRandomString(16, ('a'..'z').toList())).apply {
                mkdirs()
                logI(TAG) { "Made the state directory: $path" }
            }

    ConfigStore.dataDir = dir.path

    val logDir = File(ConfigStore.dataDir, "log")
    if (!logDir.isDirectory) logDir.mkdirs()
    ConfigStore.configFile = File(ConfigStore.dataDir, "config.json")
    ConfigStore.filterCountFile = File(ConfigStore.dataDir, "filter_count.json")
    ModuleLogger.logFile = File(logDir, "runtime.log")
    ModuleLogger.oldLogFile = File(logDir, "old.log")

    clearLogs()

    ModuleLogger.logcatAvailable = true
    logI(TAG) { "Data dir: $ConfigStore.dataDir" }

    try {
        // make the map issues easier to debug
        Files.copy(
            Path("/proc/self/maps"),
            Path(ConfigStore.dataDir, "maps_module_thread.txt"),
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (cause: Throwable) {
        logE(TAG, cause) { "An error occurred while copying the map file" }
    }
}

internal fun AsyaService.writeConfig(json: String) {
    if (!ensureManagerWorkModeOK()) return

    synchronized(ConfigStore.configLock) {
        try {
            val newConfig = JsonConfig.parse(json)
            newConfig.cleanRemnantsFromConfig()
            if (newConfig.configVersion != BuildConfig.CONFIG_VERSION) {
                logW(TAG) { "Sync config: version mismatch, need reboot" }
                return
            }
            config = newConfig

            // Written beside the file and moved onto it: a stop between the two leaves the old
            // config whole, and the watcher sees one MOVED_TO rather than a half written file.
            val pending = File(ConfigStore.configFile.parentFile, ConfigStore.configFile.name + ".new")
            ensureFileIsRW(pending, true)
            pending.writeText(json)
            Files.move(
                pending.toPath(),
                ConfigStore.configFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
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

/*
 * The state this part owns: where the state directory is, the config file inside it, the file the
 * filter counts are kept in, and the lock the writes to them are serialised by.
 */
internal object ConfigStore {
    internal var lastFilterCountWrite = 0L
    internal lateinit var configFile: File

    internal lateinit var dataDir: String

    internal lateinit var filterCountFile: File

    internal val configLock = Any()
}

internal fun AsyaService.writeFilterCount(force: Boolean = false) {
    if (!ensureManagerWorkModeOK()) return

    synchronized(ConfigStore.configLock) {
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
            now - ConfigStore.lastFilterCountWrite < 2_000L
        ) {
            return
        }
        ConfigStore.lastFilterCountWrite = now

        try {
            ensureFileIsRW(ConfigStore.filterCountFile, true)
            ConfigStore.filterCountFile.writeText(getDetailedFilterStats())
            logD(TAG) { "Filter count synced" }
        } catch (cause: Throwable) {
            logE(TAG, cause) { "An error occurred while writing filter count" }
        }
    }
}
