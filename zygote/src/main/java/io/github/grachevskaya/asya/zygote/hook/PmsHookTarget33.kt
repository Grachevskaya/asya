package io.github.grachevskaya.asya.zygote.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.grachevskaya.asya.common.CollectionUtils.firstOrNullWithType
import io.github.grachevskaya.asya.common.OSUtils
import io.github.grachevskaya.asya.common.Utils
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.getCallingApps
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import io.github.grachevskaya.asya.zygote.util.ZLUtils.args
import io.github.grachevskaya.asya.zygote.util.ZLUtils.findMethod
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.APPS_FILTER_IMPL_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.COMPUTER_ENGINE_CLASS

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
open class PmsHookTarget33 : PmsHookTargetBase() {
    override val TAG = "PmsHookTarget33"

    protected open val getPackagesForUidMethod by lazy {
        findMethod(
            "com.android.server.pm.Computer",
            "getPackagesForUid",
            isDeclared = false,
            systemClassLoader = true,
            Int::class.java,
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            // Samsung related fix
            if (OSUtils.isSamsung()) {
                hookAfter(
                    COMPUTER_ENGINE_CLASS,
                    "generatePackageInfo",
                ) { methodName, frame, returnValue ->
                    applyPackageHiding(
                        methodName,
                        returnValue,
                        { Binder.getCallingUid() },
                        { getPackageNameFromPackageSettings(frame.getArgument(1)) },
                        ::getCallingApps,
                        null,
                    )
                }
            } else {
                hookBefore(
                    COMPUTER_ENGINE_CLASS,
                    "addPackageHoldingPermissions",
                ) { methodName, frame, returnValue ->
                    applyPackageHiding(
                        methodName,
                        returnValue,
                        { Binder.getCallingUid() },
                        { getPackageNameFromPackageSettings(frame.getArgument(2)) },
                        ::getCallingApps,
                        null,
                    )
                }
            }

            hookAfter(
                COMPUTER_ENGINE_CLASS,
                "getPackageInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.args.firstOrNullWithType() },
                    { frame.args.firstOrNullWithType() },
                    ::getCallingApps,
                    null,
                )
            }

            hookAfter(
                COMPUTER_ENGINE_CLASS,
                "getApplicationInfoInternal",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.args.firstOrNullWithType() },
                    { frame.args.firstOrNullWithType() },
                    ::getCallingApps,
                    null,
                )
            }

            hookBefore(
                APPS_FILTER_IMPL_CLASS,
                "shouldFilterApplication",
            ) { methodName, frame, returnValue ->
                applyPackageHiding(
                    methodName,
                    returnValue,
                    { frame.getArgument(2) as? Int },
                    { getPackageNameFromPackageSettings(frame.getArgument(4)) },
                    { _, it ->
                        Utils.binderLocalScope {
                            getPackagesForUidMethod.invoke(frame.getArgument(1), it) as? Array<String>
                        }
                    },
                    true,
                )
            }
        }
    }
}
