package io.github.grachevskaya.asya.zygote.hook

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemProperties
import androidx.annotation.RequiresApi
import io.github.grachevskaya.asya.common.OSUtils
import io.github.grachevskaya.asya.common.PropertyUtils
import io.github.grachevskaya.asya.zygote.service.SystemServerHook
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logE
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.getCallingApps
import io.github.grachevskaya.asya.zygote.util.ZLUtils.args
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getBooleanField
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getIntField
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getObjectField
import io.github.grachevskaya.asya.zygote.util.ZLUtils.setBooleanField
import io.github.grachevskaya.asya.zygote.util.ZLUtils.thisObject
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.PROCESS_LIST_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.PROCESS_RECORD_INTERNAL_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.STORAGE_MANAGER_SERVICE_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.STORAGE_MANAGER_SERVICE_LIFECYCLE_CLASS
import java.util.Map

@SuppressLint("PrivateApi")
@RequiresApi(Build.VERSION_CODES.R)
class AppDataIsolationHook : IFrameworkHook {
    override val TAG = "AppDataIsolationHook"

    companion object {
        private const val APPDATA_ISOLATION_ENABLED = "mAppDataIsolationEnabled"
        private const val VOLD_APPDATA_ISOLATION_ENABLED = "mVoldAppDataIsolationEnabled"
        private const val FUSE_PROP = "persist.sys.fuse"
    }

    private var voldHookSkipped = false

    private val processRecordIntClass: Class<*> by lazy {
        Class.forName(
            PROCESS_RECORD_INTERNAL_CLASS,
            true,
            SystemServerHook.classLoader,
        )
    }

    private val isAltIsolationEnabled get() =
        config.let {
            (!PropertyUtils.isAppDataIsolationEnabled && it.altAppDataIsolation) ||
                (!PropertyUtils.isVoldAppDataIsolationEnabled && it.altVoldAppDataIsolation)
        }

    @SuppressLint("PrivateApi")
    override fun load() {
        if (!isAltIsolationEnabled) return
        logI(TAG) { "Load hook" }

        hooker.apply {
            hookBefore(
                PROCESS_LIST_CLASS,
                "startProcess",
            ) { _, frame, _ ->
                val processListClazz =
                    runCatching {
                        Class.forName(PROCESS_LIST_CLASS, true, SystemServerHook.classLoader)
                    }.getOrNull()

                if (config.altAppDataIsolation) {
                    val isEnabled =
                        getBooleanField(
                            frame.thisObject,
                            APPDATA_ISOLATION_ENABLED,
                            processListClazz,
                        )

                    if (!isEnabled) {
                        setBooleanField(
                            frame.thisObject,
                            APPDATA_ISOLATION_ENABLED,
                            true,
                            processListClazz,
                        )

                        logI(TAG) { "ProcessList - App data isolation is forced" }
                    }
                }

                if (config.altVoldAppDataIsolation && !voldHookSkipped) {
                    val fuseEnabled = SystemProperties.getBoolean(FUSE_PROP, false)

                    if (!fuseEnabled) {
                        voldHookSkipped = true
                        logE(TAG) { "ProcessList - FUSE storage is not enabled, skip vold hook" }
                    } else {
                        val isolationEnabled =
                            getBooleanField(
                                frame.thisObject,
                                VOLD_APPDATA_ISOLATION_ENABLED,
                                processListClazz,
                            )

                        if (!isolationEnabled) {
                            setBooleanField(
                                frame.thisObject,
                                VOLD_APPDATA_ISOLATION_ENABLED,
                                true,
                                processListClazz,
                            )

                            logI(TAG) { "ProcessList - Vold app data isolation is forced" }
                        }
                    }
                }
            }

            if (OSUtils.isSamsung()) {
                hookAfter(
                    STORAGE_MANAGER_SERVICE_LIFECYCLE_CLASS,
                    "onStart",
                ) { _, frame, _ ->
                    if (config.altVoldAppDataIsolation && !voldHookSkipped) {
                        val fuseEnabled = SystemProperties.getBoolean(FUSE_PROP, false)

                        if (!fuseEnabled) {
                            logE(TAG) { "StorageManagerService - FUSE storage is not enabled, skip vold hook" }
                            voldHookSkipped = true
                            return@hookAfter
                        }

                        val storageManagerService =
                            getObjectField(
                                frame.thisObject,
                                "mStorageManagerService",
                            )!!

                        val isolationEnabled =
                            getBooleanField(
                                storageManagerService,
                                VOLD_APPDATA_ISOLATION_ENABLED,
                            )

                        if (!isolationEnabled) {
                            setBooleanField(
                                storageManagerService,
                                VOLD_APPDATA_ISOLATION_ENABLED,
                                true,
                            )

                            logI(TAG) { "StorageManagerService - Vold app data isolation is forced" }
                        }
                    }
                }
            } else {
                hookBefore(
                    STORAGE_MANAGER_SERVICE_CLASS,
                    "onVolumeStateChangedLocked",
                ) { _, frame, _ ->
                    if (config.altVoldAppDataIsolation && !voldHookSkipped) {
                        val fuseEnabled = SystemProperties.getBoolean(FUSE_PROP, false)

                        if (!fuseEnabled) {
                            logE(TAG) { "StorageManagerService - FUSE storage is not enabled, skip vold hook" }
                            voldHookSkipped = true
                            return@hookBefore
                        }

                        val storageManagerService = frame.thisObject

                        val isolationEnabled =
                            getBooleanField(
                                storageManagerService,
                                VOLD_APPDATA_ISOLATION_ENABLED,
                            )

                        if (!isolationEnabled) {
                            setBooleanField(
                                storageManagerService,
                                VOLD_APPDATA_ISOLATION_ENABLED,
                                true,
                            )

                            logI(TAG) { "StorageManagerService - Vold app data isolation is forced" }
                        }
                    }
                }

                hookAfter(
                    PROCESS_LIST_CLASS,
                    "needsStorageDataIsolation",
                ) { _, frame, returnValue ->
                    if (config.altVoldAppDataIsolation) {
                        val app = frame.args.find { it?.javaClass?.simpleName == "ProcessRecord" }!!
                        val uid =
                            runCatching {
                                getIntField(app, "uid")
                            }.getOrElse {
                                getIntField(app, "uid", processRecordIntClass)
                            }

                        val apps = getCallingApps(pms, uid)

                        if (config.detailLog) {
                            val processName =
                                runCatching {
                                    getObjectField(app, "processName")
                                }.getOrElse {
                                    getObjectField(app, "processName", processRecordIntClass)
                                }
                            val mountNode =
                                runCatching {
                                    getIntField(app, "mMountMode")
                                }.getOrDefault(0)
                            val isolated =
                                runCatching {
                                    getBooleanField(app, "isolated")
                                }.getOrElse {
                                    getBooleanField(app, "isolated", processRecordIntClass)
                                }
                            val appZygote =
                                runCatching {
                                    getBooleanField(app, "appZygote")
                                }.getOrElse {
                                    getBooleanField(app, "appZygote", processRecordIntClass)
                                }

                            logD(TAG) {
                                "@needsStorageDataIsolation $uid and ${apps.contentToString()} - $processName value without override: ${returnValue.result}, mount node: $mountNode, isolated: $isolated, appZygote: $appZygote"
                            }
                        }

                        if (apps.any { service.isAppDataIsolationExcluded(it) }) {
                            returnValue.result = false
                            return@hookAfter
                        }

                        if (config.skipSystemAppDataIsolation) {
                            val isSystemApp = systemApps.any { apps.contains(it) }
                            logD(TAG) { "@needsStorageDataIsolation $uid and ${apps.contentToString()} - isSystemApp: $isSystemApp" }

                            if (isSystemApp) {
                                returnValue.result = false
                                return@hookAfter
                            }
                        }
                    }
                }

                hookBefore(
                    STORAGE_MANAGER_SERVICE_CLASS,
                    "remountAppStorageDirs",
                ) { _, frame, _ ->
                    if (!voldHookSkipped && config.altVoldAppDataIsolation && config.skipSystemAppDataIsolation) {
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                        val pidPkgMap = frame.getArgument(1) as Map<*, *>
                        val keysToRemove = mutableSetOf<Any>()

                        for (entry in pidPkgMap.entrySet()) {
                            val pid = entry.key
                            val packageName = entry.value as String

                            if (packageName in systemApps) {
                                logD(TAG) { "@remountAppStorageDirs SYSTEM $pid - $packageName is marked to remove" }
                                keysToRemove += pid
                                break
                            }
                        }

                        keysToRemove.forEach { pidPkgMap.remove(it) }
                    }
                }
            }
        }
    }
}
