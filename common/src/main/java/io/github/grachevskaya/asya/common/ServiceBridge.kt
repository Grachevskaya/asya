package io.github.grachevskaya.asya.common

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

/**
 * The channel between the service and the manager.
 *
 * The two halves are not in one process: the service is created in system_server, where the package
 * manager and the transaction hooks are (SystemServerHook -> UserService.register), and the
 * manager's Activity is loaded into the host, com.android.systemui. A static is per process, so what
 * they need is a binder -- and a way to exchange one that nothing else can see.
 *
 * The way is an ordinary registration. The host registers a process observer with the framework,
 * which is something a system process is entitled to do and which says nothing about this module;
 * the service holds a hook on the accepting method, recognises the host by uid, keeps the object it
 * was handed and answers *on that same object* with a binder of its own, under a transaction code
 * the object's own interface never uses. No service name, no broadcast and no new interface has to
 * exist for the handshake, and nothing enumerable is added to the system.
 *
 * From then on it is two binders and nothing else: the manager asks the service for live values
 * instead of reading the files it writes, and the service tells the manager when a package
 * transaction is over. The files stay as the fallback for a process with no binder -- a plain
 * install, where the service is not running at all.
 */
object ServiceBridge {
    /** This module owns both objects, and the range with them: well clear of any AIDL's own codes. */
    private const val CODE_HELLO = 0x00A50001
    private const val CODE_PACKAGES_CHANGED = 0x00A50002
    private const val CODE_KERNEL_STATUS = 0x00A50011
    private const val CODE_CONFIG_JSON = 0x00A50012
    private const val CODE_FILTER_COUNT = 0x00A50013
    private const val CODE_FILTER_STATS = 0x00A50014

    /** What the service can answer while it is running. */
    interface Host {
        fun kernelStatus(): Map<String, String>

        fun configJson(): String?

        fun filterCount(): Int

        fun filterStatsJson(): String?

        fun onPackagesChanged()

        val isAlive: Boolean
    }

    /**
     * The service, seen from the manager's process. Set by [ManagerBinder] when the handshake
     * arrives; null until then, and null forever in a process the service is not in.
     */
    @Volatile
    var host: Host? = null

    /** What the manager does when the service says a transaction is over: set by the manager. */
    @Volatile
    var onServiceEvent: (() -> Unit)? = null

    /** The service's end, written into the handshake and called by the manager afterwards. */
    class ServiceBinder(
        private val service: Host,
    ) : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (reply == null) return super.onTransact(code, data, reply, flags)

            when (code) {
                CODE_KERNEL_STATUS ->
                    reply.writeBundle(
                        Bundle().apply {
                            service.kernelStatus().forEach { (key, value) -> putString(key, value) }
                        },
                    )

                CODE_CONFIG_JSON -> reply.writeBundle(Bundle().apply { putString("json", service.configJson()) })

                CODE_FILTER_COUNT -> reply.writeBundle(Bundle().apply { putInt("count", service.filterCount()) })

                CODE_FILTER_STATS -> reply.writeBundle(Bundle().apply { putString("json", service.filterStatsJson()) })

                else -> return super.onTransact(code, data, reply, flags)
            }

            return true
        }
    }

    /**
     * The manager's end: the object the framework is given, and the object the service keeps.
     *
     * It is a process observer so that registering it is an ordinary thing to do, and it is a
     * carrier for everything else. The interface's own codes are answered here without a word to
     * the interface: what the framework would call is not the module's business, and answering
     * them where they arrive means a platform that adds one cannot turn the carrier into a crash.
     */
    class ManagerBinder : android.app.IProcessObserver.Stub() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            when (code) {
                CODE_HELLO -> {
                    data.readStrongBinder()?.let { host = ServiceProxy(it) }

                    return true
                }

                CODE_PACKAGES_CHANGED -> {
                    onServiceEvent?.invoke()

                    return true
                }
            }

            if (code in 1..IBinder.LAST_CALL_TRANSACTION) return true

            return super.onTransact(code, data, reply, flags)
        }

        override fun onForegroundActivitiesChanged(
            pid: Int,
            uid: Int,
            foregroundActivities: Boolean,
        ) = Unit

        override fun onForegroundServicesChanged(
            pid: Int,
            uid: Int,
            serviceTypes: Int,
        ) = Unit

        override fun onProcessDied(
            pid: Int,
            uid: Int,
        ) = Unit
    }

    /** The manager's view of the service's binder. Every call is one transaction and one reply. */
    private class ServiceProxy(
        private val binder: IBinder,
    ) : Host {
        override val isAlive: Boolean get() = runCatching { binder.isBinderAlive }.getOrDefault(false)

        override fun kernelStatus(): Map<String, String> {
            val bundle = call(CODE_KERNEL_STATUS) ?: return emptyMap()
            val values = HashMap<String, String>()

            bundle.keySet().forEach { key ->
                bundle.getString(key)?.let { values[key] = it }
            }

            return values
        }

        override fun configJson(): String? = call(CODE_CONFIG_JSON)?.getString("json")

        override fun filterCount(): Int = call(CODE_FILTER_COUNT)?.getInt("count") ?: 0

        override fun filterStatsJson(): String? = call(CODE_FILTER_STATS)?.getString("json")

        override fun onPackagesChanged() = Unit

        private fun call(code: Int): Bundle? =
            runCatching {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()

                try {
                    if (binder.transact(code, data, reply, 0)) {
                        reply.readBundle(ServiceProxy::class.java.classLoader)
                    } else {
                        null
                    }
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }.getOrNull()
    }

    /** Called on the service's side: hand this service's binder to the manager's binder. */
    fun shakeHands(
        manager: IBinder,
        service: Host,
    ) {
        runCatching {
            val data = Parcel.obtain()

            try {
                data.writeStrongBinder(ServiceBinder(service))
                manager.transact(CODE_HELLO, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        }
    }

    /** Called on the service's side: tell the manager that a transaction is over. */
    fun notifyPackagesChanged(manager: IBinder) {
        val data = Parcel.obtain()

        try {
            manager.transact(CODE_PACKAGES_CHANGED, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }
}
