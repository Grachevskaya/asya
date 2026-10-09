package io.github.grachevskaya.asya.zygote.host

import io.github.grachevskaya.asya.common.ServiceBridge
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.ZLUtils

/*
 * The host's end of the bridge.
 *
 * The registered object has to be the manager's own: the apk and this module are minified separately,
 * so the shared classes exist twice in this process and cannot be mixed -- a shared method mentions
 * library types each build renamed on its own. What crosses between the two copies is a binder, which
 * carries no library types at all; registering the manager's object puts the handshake, the service
 * binder, the sinks and the configuration in the copy the manager itself reads.
 */
object HostBridge {
    private const val TAG = "HostBridge"

    private const val SHARED_BRIDGE = "io.github.grachevskaya.asya.common.ServiceBridge"

    private const val REGISTER = "registerManagerObserver"

    private const val ACTIVITY_MANAGER_CLASS = "android.app.ActivityManager"
    private const val GET_SERVICE = "getService"
    private const val REGISTER_PROCESS_OBSERVER = "registerProcessObserver"

    fun install(managerLoader: ClassLoader?) {
        if (byManager(managerLoader)) {
            logI(TAG) { "The manager answers on the bridge" }

            return
        }

        logW(TAG) { "The manager's classes are not reachable; this module answers on the bridge" }

        runCatching {
            val manager = ZLUtils.callStaticMethod(Class.forName(ACTIVITY_MANAGER_CLASS), GET_SERVICE) ?: return

            ZLUtils.callMethodWithTypes(
                manager,
                REGISTER_PROCESS_OBSERVER,
                arrayOf(Class.forName("android.app.IProcessObserver")),
                arrayOf<Any>(ServiceBridge.ManagerBinder()),
            )

            logI(TAG) { "The bridge is offered to the service" }
        }.onFailure { logW(TAG) { "Could not offer the bridge: " + it } }
    }

    /** Registers the manager's own observer, inside the manager's own class loader. */
    private fun byManager(managerLoader: ClassLoader?): Boolean {
        if (managerLoader == null) return false

        return runCatching {
            val bridge = Class.forName(SHARED_BRIDGE, true, managerLoader)
            val instance = bridge.getField("INSTANCE").get(null)

            bridge.getMethod(REGISTER).invoke(instance)
        }.isSuccess
    }
}
