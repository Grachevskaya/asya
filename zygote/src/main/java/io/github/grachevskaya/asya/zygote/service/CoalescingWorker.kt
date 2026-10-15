package io.github.grachevskaya.asya.zygote.service

/** At most one pass runs; a request during a pass always causes a later pass. */
internal class CoalescingWorker(
    private val name: String,
    private val failure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var pending: (() -> Unit)? = null

    fun submit(work: () -> Unit) {
        synchronized(lock) {
            pending = work
            if (running) return
            running = true
        }
        Thread({
            while (true) {
                val next =
                    synchronized(lock) {
                        val task = pending
                        pending = null
                        if (task == null) running = false
                        task
                    } ?: break
                try {
                    next()
                } catch (cause: Throwable) {
                    failure(cause)
                }
            }
        }, name).apply { isDaemon = true }.start()
    }
}
