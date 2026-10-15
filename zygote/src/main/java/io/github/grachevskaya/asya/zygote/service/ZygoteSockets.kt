package io.github.grachevskaya.asya.zygote.service

import android.os.Process
import android.system.Os

/*
 * The sockets that say "this is the task that starts apps".
 *
 * The zygote is given its server sockets by init, as descriptors numbered in the environment: the
 * name in ANDROID_SOCKET_<name> is the descriptor. That is the only place their identities can be
 * read from -- the file system entry and the socket are not the same object -- and it is why this
 * runs inside the zygote itself, which is the process that holds them. The child closes them before
 * it is specialized, so an app never has one; a shell, su or anything else that forks does not
 * either.
 */
internal object ZygoteSockets {
    private val NAMES =
        arrayOf(
            "zygote",
            "zygote_secondary",
            "usap_pool_primary",
            "usap_pool_secondary",
            "webview_zygote",
        )

    fun push() {
        val words = ArrayList<Int>()
        for (name in NAMES) {
            val fd = System.getenv("ANDROID_SOCKET_" + name)?.toIntOrNull() ?: continue
            /*
             * The inode of the socket, read from the descriptor without taking it over: a socket's
             * file system entry and the socket itself are not the same object, so the link target is
             * the only place this number can be read from.
             */
            val target =
                try {
                    Os.readlink("/proc/self/fd/" + fd)
                } catch (cause: Throwable) {
                    continue
                }
            val ino =
                target.substringAfter("socket:[", "").substringBefore(']').toLongOrNull() ?: continue
            /* The device is not known from here; zero says "by inode, among sockets". */
            words.add(0)
            words.add((ino and 0xffffffffL).toInt())
            words.add((ino ushr 32).toInt())
        }
        if (words.isEmpty()) return

        val payload = IntArray(1 + words.size)
        payload[0] = words.size / 3
        for (i in words.indices) payload[i + 1] = words[i]

        KernelPolicy.pushZygoteSockets(payload)
    }

    /** Off the loader's own path: this is a round trip, and premain is not the place for one. */
    fun pushLater() {
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { push() }
        }.apply {
            isDaemon = true
            name = "asya-zygote-sockets"
        }.start()
    }
}
