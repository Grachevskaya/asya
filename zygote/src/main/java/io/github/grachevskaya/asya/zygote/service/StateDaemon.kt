package io.github.grachevskaya.asya.zygote.service

import android.net.LocalServerSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Started as root by app_process; no file descriptors or heap state are inherited by app zygotes. */
object StateDaemon {
    private const val ROOT = "/data/adb/asya"

    /** Where the zygote reads the hashed list: the module directory is the path it can open. */
    private val store =
        StateStore(File(ROOT), syncDirectory = { path ->
            val fd = android.system.Os.open(path.toString(), android.system.OsConstants.O_RDONLY, 0)
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        })

    private fun say(what: String) {
        runCatching { File(ROOT, "daemon.log").appendText("${java.util.Date()} $what\n") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runCatching { Class.forName("android.os.Process").getMethod("setArgV0", String::class.java).invoke(null, "") }
        val secret = runCatching { File(ROOT, ".salt").readBytes().takeIf { it.size >= 16 }?.copyOf(16) }.getOrNull()
        if (secret == null) {
            say("no secret beside the module")
            return
        }
        val words = StateToken.words(secret)
        val name = StateToken.name(words)
        val server =
            try {
                LocalServerSocket(name)
            } catch (cause: Throwable) {
                say("the state socket could not be made: $cause")
                return
            }
        say("the state socket is up")
        // Publish only after listen. Retry if the module was not ready; the app-start sockets are
        // recognised by the kernel from the names init binds them at, so nothing here refreshes them.
        Thread({
            var wasReady = false
            var lastToken = false
            var first = true
            while (true) {
                val announced = runCatching { KernelPolicy.pushToken(words) }.getOrDefault(false)
                if (!announced && (first || lastToken != announced)) {
                    say("rendezvous pending: token=$announced")
                } else if (announced && !wasReady) {
                    say("rendezvous ready: the name is published")
                }
                lastToken = announced
                first = false
                wasReady = announced
                Thread.sleep(if (announced) 30_000L else 2_000L)
            }
        }, "state-rendezvous").apply { isDaemon = true }.start()
        while (true) {
            val client =
                try {
                    server.accept()
                } catch (cause: Throwable) {
                    say("state accept failed: $cause")
                    return
                }
            try {
                client.soTimeout = 5_000
                val peer = client.peerCredentials
                if (peer.uid != 1000) {
                    say("state peer rejected: uid=${peer.uid}")
                    continue
                }
                StateWire.serve(
                    DataInputStream(client.inputStream),
                    DataOutputStream(client.outputStream),
                    store::read,
                ) { which, bytes ->
                    store.write(which, bytes)
                }
            } catch (cause: Throwable) {
                say("state request failed: $cause")
            } finally {
                runCatching { client.close() }
            }
        }
    }
}
