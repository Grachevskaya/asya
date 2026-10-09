package io.github.grachevskaya.asya.zygote.hook

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import com.v7878.unsafe.invoke.EmulatedStackFrame
import io.github.grachevskaya.asya.common.Utils.generateRandomHex
import io.github.grachevskaya.asya.zygote.BuildConfig
import io.github.grachevskaya.asya.zygote.service.ReturnValue
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.ZLUtils.firstReferenceArgument
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getIntField
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getObjectField
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.ACTION_USB_STATE
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.ACTIVITY_MANAGER_SERVICE_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.BROADCAST_CONTROLLER_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.BROADCAST_PROCESS_QUEUE_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.BROADCAST_QUEUE_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.BROADCAST_QUEUE_IMPL_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.USB_FUNCTION_ADB

class BroadcastHook : IFrameworkHook {
    override val TAG = "BroadcastHook"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                hookBefore(
                    BROADCAST_PROCESS_QUEUE_CLASS,
                    "enqueueOutgoingBroadcast",
                    hook = ::enqueueBroadcastLocked,
                )

                hookBefore(
                    BROADCAST_PROCESS_QUEUE_CLASS,
                    "enqueueOrReplaceBroadcast",
                    hook = ::enqueueBroadcastLocked,
                )
            } else {
                val targetClass =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        BROADCAST_QUEUE_CLASS
                    } else {
                        BROADCAST_QUEUE_IMPL_CLASS
                    }

                hookBefore(
                    targetClass,
                    "enqueueParallelBroadcastLocked",
                    hook = ::enqueueBroadcastLocked,
                )

                hookBefore(
                    targetClass,
                    "enqueueOrderedBroadcastLocked",
                    hook = ::enqueueBroadcastLocked,
                )
            }

            // replace USB state receiver
            hookBefore(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    BROADCAST_CONTROLLER_CLASS
                } else {
                    ACTIVITY_MANAGER_SERVICE_CLASS
                },
                "broadcastIntentLocked",
            ) { _, frame, _ ->
                val intent = frame.firstReferenceArgument(Intent::class.java) ?: return@hookBefore
                changeUsbStateBroadcast(intent)
            }
        }
    }

    private fun enqueueBroadcastLocked(
        methodName: String,
        frame: EmulatedStackFrame,
        returnValue: ReturnValue,
    ) {
        val record = frame.getArgument(1) ?: return
        val caller = getObjectField(record, "callerPackage") as? String ?: return
        val component = getObjectField(record, "targetComp") as? ComponentName ?: return
        val targetApp = component.packageName
        val userId = getIntField(record, "userId")

        if (service.shouldHideActivityLaunch(caller, targetApp, userId)) {
            logD(TAG) { "@$methodName: insecure query from $caller, target: $component" }
            // The query is answered by pointing it at something that does not exist, so the caller
            // hears "nothing" from the system itself. Calling back into the caller's IIntentReceiver
            // would answer from this process instead of the system one, which is a fingerprint of its
            // own, and would need the hidden interface to be stubbed out.
            (getObjectField(record, "intent") as Intent).apply {
                this.component = null
                `package` = "${BuildConfig.APP_PACKAGE_NAME}.${generateRandomHex(4)}"
            }

            service.increaseALFilterCount(caller)
        }
    }

    private fun changeUsbStateBroadcast(intent: Intent) {
        if (config.disableActivityLaunchProtection) return

        if (intent.action == ACTION_USB_STATE) {
            intent.removeExtra(USB_FUNCTION_ADB)
        }
    }
}
