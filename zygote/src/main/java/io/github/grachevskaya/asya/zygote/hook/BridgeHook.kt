package io.github.grachevskaya.asya.zygote.hook

import android.os.Binder
import io.github.grachevskaya.asya.common.ServiceBridge
import io.github.grachevskaya.asya.common.Utils.getPackageUidCompat
import io.github.grachevskaya.asya.zygote.host.HostConstants
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument

/**
 * The hook that turns the host's own process observer registration into the bridge.
 *
 * There is no counterpart to the host's call on this side of the wall and nothing to poll: the
 * registration arrives as an ordinary binder call, this hook is inside the method that accepts it,
 * and the answer travels back on the very object that was passed in. The caller is checked by uid,
 * so a registration from anyone else is left alone, and nothing the module adds can be listed.
 */
class BridgeHook : IFrameworkHook {
    override val TAG = "BridgeHook"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.hookAfter(ACTIVITY_MANAGER_SERVICE_CLASS, REGISTER_PROCESS_OBSERVER) { _, frame, _ ->
            val observer = frame.getArgument(1) as? android.os.IInterface ?: return@hookAfter
            if (Binder.getCallingUid() != hostUid()) return@hookAfter

            val binder = observer.asBinder()

            service.onManagerBinder(binder)
            ServiceBridge.shakeHands(binder, service)
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
