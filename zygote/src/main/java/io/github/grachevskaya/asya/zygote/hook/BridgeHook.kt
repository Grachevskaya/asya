package io.github.grachevskaya.asya.zygote.hook

import android.os.Binder
import io.github.grachevskaya.asya.common.ServiceBridge
import io.github.grachevskaya.asya.common.Utils.getPackageUidCompat
import io.github.grachevskaya.asya.zygote.host.HostConstants
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument

/*
 * Turns the manager's process-observer registration into the bridge: the handshake travels back on the
 * object that was passed in, and registrations from other processes are left alone.
 */
class BridgeHook : IFrameworkHook {
    override val TAG = "BridgeHook"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.hookAfter(ACTIVITY_MANAGER_SERVICE_CLASS, REGISTER_PROCESS_OBSERVER) { _, frame, _ ->
            val observer = frame.getArgument(1) as? android.os.IInterface ?: return@hookAfter
            if (Binder.getCallingUid() != hostUid()) return@hookAfter

            val binder = observer.asBinder()

            // The host's own observers come from the same uid, so only the descriptor tells them apart.
            val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
            if (descriptor != ServiceBridge.DESCRIPTOR_OF_OURS) return@hookAfter

            logI(TAG) { "A registration of this module: " + descriptor }

            val answer = runCatching { ServiceBridge.shakeHands(binder, service) }.isSuccess

            if (!answer) {
                logW(TAG) { "A registration that is not this module\u2019s" }

                return@hookAfter
            }

            service.onManagerBinder(binder)
            logI(TAG) { "The manager is on the bridge" }
        }

        if (!hooker.isHookAvailable(ACTIVITY_MANAGER_SERVICE_CLASS, REGISTER_PROCESS_OBSERVER)) {
            logI(TAG) { "Not on this build: " + ACTIVITY_MANAGER_SERVICE_CLASS + "." + REGISTER_PROCESS_OBSERVER }
        }
    }

    /** The host's uid, resolved once: it is the only caller this hook answers. */
    private var hostUidCache = -1

    private fun hostUid(): Int {
        if (hostUidCache < 0) {
            hostUidCache =
                runCatching {
                    pms.getPackageUidCompat(HostConstants.HOST_PACKAGE, 0L, 0)
                }.getOrDefault(-1)

            logI(TAG) { "Host uid resolved to " + hostUidCache }
        }

        return hostUidCache
    }

    private companion object {
        private const val ACTIVITY_MANAGER_SERVICE_CLASS = "com.android.server.am.ActivityManagerService"
        private const val REGISTER_PROCESS_OBSERVER = "registerProcessObserver"
    }
}
