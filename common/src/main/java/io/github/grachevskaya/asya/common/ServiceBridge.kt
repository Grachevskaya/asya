package io.github.grachevskaya.asya.common

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_CONFIG_CHANGED
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_CONFIG_JSON
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_CONFIG_SET
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_FILTER_CLEAR
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_FILTER_COUNT
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_FILTER_DELTA
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_FILTER_STATS
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_HELLO
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_KERNEL_STATUS
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_LOG_LINES
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_MANAGER_BINDER
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_MODULE_STATUS
import io.github.grachevskaya.asya.common.BridgeProtocol.CODE_PACKAGES_CHANGED
import io.github.grachevskaya.asya.common.BridgeProtocol.DESCRIPTOR_OF_OURS
import io.github.grachevskaya.asya.common.BridgeProtocol.DISPATCH_INTERFACE
import io.github.grachevskaya.asya.common.BridgeProtocol.DISPATCH_UNKNOWN
import io.github.grachevskaya.asya.common.BridgeProtocol.PAYLOAD_BUNDLE
import io.github.grachevskaya.asya.common.BridgeProtocol.PAYLOAD_SHARED_MEMORY
import io.github.grachevskaya.asya.common.BridgeProtocol.managerDispatch
import io.github.grachevskaya.asya.common.BridgeProtocol.payloadKind
import io.github.grachevskaya.asya.common.BridgeProtocol.serviceHandles

/*
 * The channel between the service and the manager, which run in different processes.
 *
 * The handshake rides on an ordinary process-observer registration: the manager registers an object
 * that answers this module's codes, the service's hook recognises it by uid and by its interface
 * descriptor, and the service answers on that same object with a binder of its own. The manager then
 * hands over a plain binder of its own, and every later message goes there.
 *
 * The codes themselves, and the two rules that keep the ends from drifting apart, are in
 * [BridgeProtocol].
 */
object ServiceBridge {
    /** What the service can answer while it is running. */
    interface Host {
        fun moduleStatus(): Map<String, String>?

        fun kernelStatus(): Map<String, String>?

        fun configJson(): String?

        fun setConfig(json: String): Boolean

        fun filterCount(): Int?

        fun filterStatsJson(): String?

        fun clearFilterStats()

        /** The binder the manager wants this module to answer on, sent once the handshake is done. */
        fun setManagerBinder(binder: android.os.IBinder)

        fun onPackagesChanged()

        val isAlive: Boolean
    }

    /**
     * The service, seen from the manager's process. Set by [ManagerBinder] when the handshake
     * arrives; null until then, and null forever in a process the service is not in.
     */
    @Volatile
    var host: Host? = null

    /** Where a batch of log lines goes: one document, appended verbatim. */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    /** Where the batches of counts go: the manager adds them to the total it keeps. */
    @Volatile
    var filterSink: ((String) -> Unit)? = null

    /** Where a configuration the service changed itself goes: the manager keeps the file. */
    @Volatile
    var configSink: ((String) -> Unit)? = null

    /**
     * Called once the handshake is done. The manager keeps the configuration, so this is the moment
     * it hands one over: the service starts empty and would otherwise hide nothing until next save.
     */
    @Volatile
    var onServiceReady: (() -> Unit)? = null

    /** Set once the handshake is done, so a late caller knows the service is already there. */
    @Volatile
    var serviceReady = false

    /** Held for as long as it is registered: a binder with no reference would be collected. */
    @Volatile
    private var managerObserver: ManagerBinder? = null

    @Volatile
    private var managerRegistered = false

    /**
     * Puts the bridge in place from the manager's side.
     *
     * It is the manager's own copy of these classes that has to run this: the copy that registers is
     * the copy that answers the handshake, and the service's binder, the sinks and the configuration
     * all live in that copy's statics. The module calls this in the manager's class loader for that
     * reason -- see HostBridge -- and this module's own copy is only used where the manager's cannot
     * be reached at all.
     */
    fun registerManagerObserver() {
        if (managerRegistered) return

        managerRegistered = true

        // Built here and not as a field: the shape this extends is a stub the test runtime does not
        // carry, and a field would be constructed while this class is initialised.
        val observer = ManagerBinder()
        managerObserver = observer

        runCatching {
            val activity = android.os.ServiceManager.getService("activity")

            android.app.IActivityManager.Stub
                .asInterface(activity)
                ?.registerProcessObserver(observer)
        }
    }

    /** What the manager does when the service says a transaction is over: set by the manager. */
    @Volatile
    var onServiceEvent: (() -> Unit)? = null

    /** The service's end, written into the handshake and called by the manager afterwards. */

    /**
     * A string that may not fit in a parcel. Small ones travel inside it; the others are put in
     * shared memory and the parcel carries the handle, which has no size limit of its own.
     */
    private fun Parcel.writeLargeString(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)

        if (payloadKind(bytes.size) == PAYLOAD_BUNDLE) {
            writeInt(PAYLOAD_BUNDLE)
            writeBundle(Bundle().apply { putString("json", text) })

            return
        }

        val memory = android.os.SharedMemory.create("asya-bridge", bytes.size)

        memory.mapReadWrite().put(bytes)
        writeInt(PAYLOAD_SHARED_MEMORY)
        memory.writeToParcel(this, 0)
        unmapWhereReachable(memory)
        memory.close()
    }

    /**
     * Releases a mapping before the object is finalized, where the platform lets it be called.
     *
     * The method is hidden, so this is done reflectively and a build that refuses it falls back to
     * the collector: the mapping then lives until the next collection rather than until the process
     * ends, which is what the shared memory path has always done.
     */
    private fun unmapWhereReachable(memory: android.os.SharedMemory) {
        runCatching {
            android.os.SharedMemory::class.java.getMethod("unmap").invoke(memory)
        }
    }

    private fun Parcel.readLargeString(): String? =
        runCatching {
            when (readInt()) {
                PAYLOAD_SHARED_MEMORY -> {
                    val memory =
                        android.os.SharedMemory.CREATOR
                            .createFromParcel(this)
                    val text = Charsets.UTF_8.decode(memory.mapReadOnly()).toString()

                    unmapWhereReachable(memory)
                    memory.close()
                    text
                }

                else -> {
                    val bundle = readBundle(javaClass.classLoader)

                    bundle?.getString("json")
                }
            }
        }.getOrNull()

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

            if (!serviceHandles(code)) return super.onTransact(code, data, reply, flags)

            when (code) {
                CODE_MODULE_STATUS ->
                    reply.writeBundle(
                        Bundle().apply {
                            service.moduleStatus()?.forEach { (key, value) -> putString(key, value) }
                        },
                    )

                CODE_KERNEL_STATUS ->
                    reply.writeBundle(
                        Bundle().apply {
                            service.kernelStatus()?.forEach { (key, value) -> putString(key, value) }
                        },
                    )

                CODE_CONFIG_JSON -> reply.writeLargeString(service.configJson() ?: "")

                CODE_CONFIG_SET -> {
                    service.setConfig(data.readLargeString() ?: "")
                    reply.writeInt(PAYLOAD_BUNDLE)
                }

                CODE_FILTER_COUNT ->
                    reply.writeBundle(Bundle().apply { service.filterCount()?.let { putInt("count", it) } })

                CODE_FILTER_STATS -> reply.writeBundle(Bundle().apply { putString("json", service.filterStatsJson()) })

                CODE_FILTER_CLEAR -> {
                    service.clearFilterStats()
                    reply.writeInt(0)
                }

                CODE_MANAGER_BINDER -> {
                    data.readStrongBinder()?.let { service.setManagerBinder(it) }
                    reply.writeInt(0)
                }

                else -> return super.onTransact(code, data, reply, flags)
            }

            return true
        }
    }

    /**
     * The manager's inbox: a plain binder of this module that answers its codes.
     *
     * The object the host registers with the framework cannot be used for them: a code in the binder's
     * own range is taken for a framework call, the observer's token is then demanded, and the
     * transaction is refused. This one carries no framework interface at all.
     */
    class ManagerInbox : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            when (managerDispatch(code)) {
                DISPATCH_UNKNOWN -> return super.onTransact(code, data, reply, flags)
                DISPATCH_INTERFACE -> return true
            }

            when (code) {
                CODE_PACKAGES_CHANGED -> {
                    onServiceEvent?.invoke()

                    return true
                }

                CODE_FILTER_DELTA -> {
                    val sink = filterSink
                    val counts = data.readLargeString()
                    val taken = sink != null && !counts.isNullOrEmpty()

                    if (taken) sink?.invoke(counts ?: "")

                    // The answer is the manager's word on whether the document was kept.
                    reply?.writeInt(if (taken) 1 else 0)

                    return true
                }

                CODE_CONFIG_CHANGED -> {
                    val sink = configSink
                    val json = data.readLargeString()

                    if (sink != null && !json.isNullOrEmpty()) sink(json)

                    return true
                }

                CODE_LOG_LINES -> {
                    val sink = logSink
                    val batch = data.readLargeString()

                    if (sink != null && !batch.isNullOrEmpty()) sink(batch)

                    return true
                }
            }

            return super.onTransact(code, data, reply, flags)
        }
    }

/** The inbox the service is told to answer on, made once and kept for the process's life. */
    val inbox: ManagerInbox by lazy { ManagerInbox() }

/**
     * The manager's end: the object the framework is given, and the object the service keeps.
     *
     * It is a process observer so that registering it is an ordinary thing to do, and it is a
     * carrier for everything else. The interface's own codes are answered here without a word to
     * the interface: what the framework would call is not the module's business, and answering
     * them where they arrive means a platform that adds one cannot turn the carrier into a crash.
     */
    class ManagerBinder : android.app.IProcessObserver.Stub() {
        /**
         * The name this object answers to, and the only thing that tells it apart from the framework's own
         * observers: the system registers those from the same uid, so a caller cannot tell by who it is.
         */
        override fun getInterfaceDescriptor(): String = DESCRIPTOR_OF_OURS

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            when (managerDispatch(code)) {
                DISPATCH_UNKNOWN -> return super.onTransact(code, data, reply, flags)
                DISPATCH_INTERFACE -> return true
            }

            when (code) {
                CODE_HELLO -> {
                    data.readStrongBinder()?.let { service ->
                        host = ServiceProxy(service)
                        runCatching { host?.setManagerBinder(inbox) }
                    }

                    serviceReady = true
                    onServiceReady?.invoke()

                    return true
                }
            }

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

        override fun moduleStatus(): Map<String, String>? = strings(CODE_MODULE_STATUS)

        override fun kernelStatus(): Map<String, String>? = strings(CODE_KERNEL_STATUS)

        /** Null when the call did not come back: an empty map is the service answering "nothing". */
        private fun strings(code: Int): Map<String, String>? =
            call(code)?.let { bundle ->
                val values = HashMap<String, String>()

                bundle.keySet().forEach { key ->
                    bundle.getString(key)?.let { values[key] = it }
                }

                values
            }

        override fun configJson(): String? = callText(CODE_CONFIG_JSON)

        override fun clearFilterStats() {
            call(CODE_FILTER_CLEAR)
        }

        override fun setManagerBinder(inbox: android.os.IBinder) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()

            try {
                data.writeStrongBinder(inbox)
                binder.transact(CODE_MANAGER_BINDER, data, reply, 0)
            } catch (cause: Throwable) {
                // A handshake that fails leaves the bridge as it was, and the lines wait in memory.
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun setConfig(json: String): Boolean =
            runCatching {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()

                try {
                    data.writeLargeString(json)
                    binder.transact(CODE_CONFIG_SET, data, reply, 0)
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }.getOrDefault(false)

        /** One transaction whose answer is a string, in the parcel or in shared memory. */
        private fun callText(code: Int): String? =
            runCatching {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()

                try {
                    if (binder.transact(code, data, reply, 0)) reply.readLargeString() else null
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }.getOrNull()

        override fun filterCount(): Int? = call(CODE_FILTER_COUNT)?.getInt("count")

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

    /** A configuration this side changed itself, on its way to the manager that keeps the file. */
    fun notifyConfigChanged(
        manager: IBinder,
        json: String,
    ) {
        val data = Parcel.obtain()

        try {
            data.writeLargeString(json)
            manager.transact(CODE_CONFIG_CHANGED, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    /** The counts gathered so far, in the holder's own document form. */
    fun notifyFilterDeltas(
        manager: IBinder,
        counts: String,
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        try {
            // Answered rather than one-way: the ledger is cleared only for a batch the manager says it
            // took, so a binder that died or a manager that dropped the document leaves it here.
            data.writeLargeString(counts)
            val sent = manager.transact(CODE_FILTER_DELTA, data, reply, 0)

            return sent && runCatching { reply.readInt() == 1 }.getOrDefault(false)
        } catch (cause: Throwable) {
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Whole batches of log lines, so that the channel is not asked once per line. */
    fun notifyLogLines(
        manager: IBinder,
        lines: Array<String>,
    ) {
        val data = Parcel.obtain()

        try {
            // One document instead of an array: the same payload the configuration travels in, so
            // both ends read a batch the way they already read a config.
            data.writeLargeString(lines.joinToString(""))
            manager.transact(CODE_LOG_LINES, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    fun notifyPackagesChanged(manager: IBinder) {
        val data = Parcel.obtain()

        try {
            manager.transact(CODE_PACKAGES_CHANGED, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }
}
