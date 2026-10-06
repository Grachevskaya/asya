package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.getCallingApps
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS

class PmsHookTarget29 : PmsHookTargetBase() {
    override val TAG = "PmsHookTarget29"

    @Suppress("UNCHECKED_CAST")
    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            hookBefore(
                service.pms::class.java.name,
                "filterAppAccessLPr",
                argumentCount = 5,
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(2) as? Int },
                    { getPackageNameFromPackageSettings(frame.getArgument(1)) },
                    ::getCallingApps,
                    true,
                )
            }

            hookAfter(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getPackageInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(4) as? Int },
                    { frame.getArgument(1) as? String },
                    ::getCallingApps,
                    null,
                )
            }

            hookAfter(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getApplicationInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(3) as? Int },
                    { frame.getArgument(1) as? String },
                    ::getCallingApps,
                    null,
                )
            }
        }
    }
}
