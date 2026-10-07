package io.github.grachevskaya.asya.zygote.host

import io.github.grachevskaya.asya.common.ServiceBridge
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.ZLUtils

/**
 * The host's end of the bridge, offered where the manager's application is about to start.
 *
 * The registration is what carries it: an object that is a process observer, so that registering it
 * needs no explanation, and that answers the module's own transaction codes as well -- which is how
 * the service, holding the same object, calls back into this process and hands over a binder of its
 * own. Nothing is added to the system for this: no service name, no provider, no broadcast.
 */
object HostBridge {
    private const val TAG = "HostBridge"

    private const val ACTIVITY_MANAGER_CLASS = "android.app.ActivityManager"
    private const val GET_SERVICE = "getService"
    private const val REGISTER_PROCESS_OBSERVER = "registerProcessObserver"

    fun install() {
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
}
