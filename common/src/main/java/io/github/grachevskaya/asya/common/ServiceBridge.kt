package io.github.grachevskaya.asya.common

/**
 * The in-process channel between the service and the manager.
 *
 * Both halves run inside the same host process: the module's service is started there, and the
 * manager's Activity is loaded into it. That makes a static reference the cheapest and most
 * accurate way for the manager to ask for the current state -- no binder, no IPC, no file
 * snapshot that lags behind.
 *
 * Files stay for what they are good at: surviving a restart (persistence) and being read by the
 * root side, which cannot see a Java object. Anything the manager can ask for live should go
 * through here first and fall back to a file only when this is null.
 */
object ServiceBridge {

    /** What the service can answer while it is running in this process. */
    interface Host {
        /** The kernel half's own report, the same map the kernel.json file carries. */
        fun kernelStatus(): Map<String, String>

        /** The configuration json the manager edits. */
        fun configJson(): String?

        /**
         * A package was installed, removed or replaced: recompute and hand the kernel the
         * difference. The ledger turns it into add/drop records, and END parks whatever the
         * new generation did not mention, so a removal needs no special case.
         */
        fun onPackagesChanged()

        /** Whether this host answers; null from the bridge means "not in process". */
        val isAlive: Boolean
    }

    /** Set by AsyaService when it starts, cleared by nobody: a dead process is a dead reference. */
    @Volatile
    var host: Host? = null
}
