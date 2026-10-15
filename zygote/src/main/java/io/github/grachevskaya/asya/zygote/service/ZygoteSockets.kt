package io.github.grachevskaya.asya.zygote.service

import android.system.Os

/*
 * The sockets that say "this task starts apps".
 *
 * The zygote's own server sockets: the child closes them before it is specialized, the pool processes
 * keep theirs and accept on it, and nothing else on the system holds one -- a shell, su or an app
 * that forks does not. A unix socket bound to a name is that name's inode, so the numbers can be read
 * from the paths, which is what the kernel then compares a held descriptor against.
 */
internal object ZygoteSockets {
    private val PATHS =
        arrayOf(
            "/dev/socket/zygote",
            "/dev/socket/zygote_secondary",
            "/dev/socket/usap_pool_primary",
            "/dev/socket/usap_pool_secondary",
            "/dev/socket/webview_zygote",
        )

    fun push() {
        val words = ArrayList<Int>()
        for (path in PATHS) {
            val stat =
                try {
                    Os.stat(path)
                } catch (cause: Throwable) {
                    continue
                }
            words.add(stat.st_dev.toInt())
            words.add((stat.st_ino and 0xffffffffL).toInt())
            words.add((stat.st_ino ushr 32).toInt())
        }
        if (words.isEmpty()) return

        val payload = IntArray(1 + words.size)
        payload[0] = words.size / 3
        for (i in words.indices) payload[i + 1] = words[i]

        KernelPolicy.pushZygoteSockets(payload)
    }
}
