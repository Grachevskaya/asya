package io.github.grachevskaya.asya.zygote.hook

import android.os.Binder
import io.github.grachevskaya.asya.common.ServiceBridge
import io.github.grachevskaya.asya.common.Utils.getPackageUidCompat
import io.github.grachevskaya.asya.zygote.host.HostConstants
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument

/**
 * The hook that turns the host's own process observer registration into the bridge.
 *
 * The manager registers an observer of its own when it starts; the registration arrives as an
 * ordinary binder call, this hook sits inside the method that accepts it, and the handshake travels
 * back on the very object that was passed in. The caller is checked by uid, so a registration from
 * any other process is left alone.
 */
class BridgeHook : IFrameworkHook {
    override val TAG = "BridgeHook"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.hookAfter(ACTIVITY_MANAGER_SERVICE_CLASS, REGISTER_PROCESS_OBSERVER) { _, frame, _ ->
            val observer = frame.getArgument(1) as? android.os.IInterface ?: return@hookAfter
            if (Binder.getCallingUid() != hostUid()) return@hookAfter

            val binder = observer.asBinder()

            // The host's own observers are registered from the same uid as this module's manager, so the
            // object itself has to say who it belongs to: only the manager's answers under this name.
            val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
            if (descriptor != ServiceBridge.DESCRIPTOR_OF_OURS) return@hookAfter

            logI(TAG) { "A registration of this module: " + descriptor }

            // The registration that matters is this module’s own, made by the manager when it starts.
            // Anything else that lands here belongs to the host and is left alone: it has no handshake
            // to answer, and treating it as one would be a lie rather than a fallback.
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
