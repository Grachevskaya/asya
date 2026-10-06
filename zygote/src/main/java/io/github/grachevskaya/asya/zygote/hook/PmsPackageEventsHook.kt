package io.github.grachevskaya.asya.zygote.hook

import android.content.Intent
import android.os.Build
import android.os.Bundle
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.BROADCAST_HELPER_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.PACKAGE_MONITOR_CLASS

class PmsPackageEventsHook : IFrameworkHook {
    override val TAG = "PmsPackageEventsHook"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hookedMethodName = "sendPackageBroadcastAndNotify"

                hookBefore(
                    BROADCAST_HELPER_CLASS,
                    hookedMethodName,
                ) { _, frame, _ ->
                    service.handlePackageEvent(
                        frame.getArgument(1) as? String,
                        frame.getArgument(2) as? String,
                        frame.getArgument(3) as? Bundle,
                    )
                }

                if (!isHookAvailable(BROADCAST_HELPER_CLASS, hookedMethodName)) {
                    hookBefore(
                        PACKAGE_MONITOR_CLASS,
                        "onReceive",
                    ) { _, frame, _ ->
                        val intent = frame.getArgument(2) as? Intent ?: return@hookBefore

                        service.handlePackageEvent(
                            intent.action,
                            intent.data?.encodedSchemeSpecificPart,
                            intent.extras,
                        )
                    }
                }
            } else {
                hookBefore(
                    PACKAGE_MANAGER_SERVICE_CLASS,
                    "sendPackageBroadcast",
                ) { _, frame, _ ->
                    service.handlePackageEvent(
                        frame.getArgument(1) as? String,
                        frame.getArgument(2) as? String,
                        frame.getArgument(3) as? Bundle,
                    )
                }
            }
        }
    }
}
