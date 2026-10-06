package io.github.grachevskaya.asya.zygote.hook

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ParceledListSlice
import io.github.grachevskaya.asya.common.settings_presets.AccessibilityPreset
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.ServiceUtils
import io.github.grachevskaya.asya.zygote.util.ZLUtils.returnType
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.ACCESSIBILITY_SERVICE_CLASS

class AccessibilityHook : IFrameworkHook {
    override val TAG = "AccessibilityHook"

    override fun load() {
        hooker.apply {
            hookBefore(
                ACCESSIBILITY_SERVICE_CLASS,
                "getEnabledAccessibilityServiceList",
            ) { methodName, frame, returnValue ->
                val callingApps = ServiceUtils.getCallingApps(pms)
                if (callingApps.isEmpty()) return@hookBefore

                val caller = callingApps.firstOrNull { callerIsSpoofed(it) }
                if (caller != null) {
                    logD(TAG) { "@$methodName returning empty list for ${callingApps.contentToString()}" }

                    val returnedList = java.util.ArrayList<AccessibilityServiceInfo>()
                    returnValue.result = if ("Parcel" in frame.returnType.simpleName) {
                        ParceledListSlice(returnedList)
                    } else {
                        returnedList
                    }
                }
            }

            hookBefore(
                ACCESSIBILITY_SERVICE_CLASS,
                "addClient",
            ) { _, _, returnValue ->
                val callingApps = ServiceUtils.getCallingApps(pms)
                if (callingApps.isEmpty()) return@hookBefore

                val caller = callingApps.firstOrNull { callerIsSpoofed(it) }
                if (caller != null) {
                    returnValue.result = 0L
                }
            }
        }
    }

    private fun callerIsSpoofed(caller: String) =
        service.getEnabledSettingsPresets(caller).contains(AccessibilityPreset.NAME)
}
