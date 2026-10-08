package io.github.grachevskaya.asya.zygote.hook

import android.os.Build
import com.v7878.unsafe.invoke.EmulatedStackFrame
import io.github.grachevskaya.asya.common.CollectionUtils.lastOrNullWithType
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.isAppDataIsolationEnabled
import io.github.grachevskaya.asya.zygote.util.ZLUtils.argTypes
import io.github.grachevskaya.asya.zygote.util.ZLUtils.args
import io.github.grachevskaya.asya.zygote.util.ZLUtils.setArgument
import io.github.grachevskaya.asya.zygote.util.ZLUtils.shortyEquals
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.NATIVE_ZYGOTE_PROCESS_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.ZYGOTE_PROCESS_CLASS
import java.util.concurrent.atomic.AtomicReference

class ZygoteHook : IFrameworkHook {
    override val TAG = "ZygoteHook"

    private val lastForceMountedApp: AtomicReference<String?> = AtomicReference(null)

    private val forceMountData get() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            config.forceMountData &&
            isAppDataIsolationEnabled(config)

    override fun load() {
        hooker.apply {
            hookBefore(
                ZYGOTE_PROCESS_CLASS,
                "start",
            ) { _, frame, _ ->
                hookIntoZygoteProcess(frame)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                hookBefore(
                    NATIVE_ZYGOTE_PROCESS_CLASS,
                    "start",
                ) { _, frame, _ ->
                    hookIntoZygoteProcess(frame)
                }
            }
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun hookIntoZygoteProcess(frame: EmulatedStackFrame) {
        logD(TAG) { "@startZygoteProcess: Starting ${frame.args.contentToString()}" }

        val caller = frame.args.lastOrNullWithType<String>() ?: return
        val isHookEnabled = service.isHookEnabled(caller)
        if (!isHookEnabled) return

        // another plan for PlatformCompatHook
        val pair = getForceMountArgs(frame, caller)
        if (pair.first) {
            val lastMapIndex =
                frame.argTypes.indexOfLast {
                    it == java.util.Map::class.java
                }
            if (lastMapIndex >= 0) {
                // enable bindMountAppsData after checks
                val bindMountAppsDataIndex = lastMapIndex + 1
                if (frame.shortyEquals(bindMountAppsDataIndex, 'Z')) {
                    val last = lastForceMountedApp.getAndSet(caller)
                    if (last != caller) logI(TAG) { "@startZygoteProcess: force mountAppsData for $caller" }
                    frame.setArgument(bindMountAppsDataIndex, true)
                    logD(TAG) { "@startZygoteProcess: mountAppsData argument overridden for $caller" }
                }
            }
        }
    }

    fun getForceMountArgs(
        frame: EmulatedStackFrame,
        caller: String,
    ): Pair<Boolean, Int> {
        var gIDsVarIndex = -1
        for ((i, clazz) in frame.argTypes.withIndex()) {
            if (clazz == IntArray::class.java) {
                gIDsVarIndex = i
                continue
            }

            if (gIDsVarIndex < 0) continue

            if (!forceMountData || systemApps.contains(caller)) {
                return Pair(false, gIDsVarIndex)
            }

            if (clazz == String::class.java) {
                val targetSDKVar = frame.args[i - 1]
                if (targetSDKVar is Int && targetSDKVar >= 30) {
                    return Pair(false, gIDsVarIndex)
                }
            }

            if (clazz == LongArray::class.java) {
                val isTopAppIndex = i - 1
                return Pair(frame.args[isTopAppIndex] == true, gIDsVarIndex)
            }
        }

        return Pair(false, gIDsVarIndex)
    }
}
