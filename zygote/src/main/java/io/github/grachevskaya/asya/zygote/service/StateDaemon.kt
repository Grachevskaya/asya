package io.github.grachevskaya.asya.zygote.service

import android.net.LocalServerSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/*
 * The state beside the module, served by a process of its own.
 *
 * The module's boot script starts this as root through app_process on this module's own dex, the way
 * Sui starts its server: a process of its own, sharing nothing with the zygote, so what it reads is
 * on no heap an application inherits.
 *
 * The rendezvous is an abstract unix socket -- a name with nothing in the file system behind it. A
 * name registered with the service manager is refused by SELinux, because a name that is different on
 * every installation cannot have a rule written for it; a socket needs no rule at all. The name is the
 * installation's own secret.
 */
object StateDaemon {
    private const val ROOT = "/data/adb/asya"
    private const val SALT = ".salt"
    private const val SYSTEM_UID = 1000
    private const val OP_READ = 0
    private const val OP_WRITE = 1
    private val NAMES = arrayOf("config.json", "native_libs.json", "filter_counts.json")

    /** Its own file: the shared log needs a system property this process cannot read. */
    private fun say(what: String) {
        runCatching {
            File(ROOT, "daemon.log").appendText(
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " " + what + "\n",
            )
        }
    }

    private fun file(which: Int) = File(ROOT, NAMES[which])

    private fun read(which: Int): ByteArray? =
        runCatching {
            RandomAccessFile(file(which), "r").use { raf ->
                val bytes = ByteArray(raf.length().toInt())
                raf.readFully(bytes)
                bytes
            }
        }.getOrNull()

    private fun write(which: Int, bytes: ByteArray): Boolean =
        runCatching {
            RandomAccessFile(file(which), "rw").use { raf ->
                raf.setLength(0L)
                raf.seek(0L)
                raf.write(bytes)
                raf.fd.sync()
            }
            true
        }.getOrDefault(false)

    /** The name both halves work out: the installation's own secret. */
    private fun token(): String? =
        runCatching {
            val salt = File(ROOT, SALT).readBytes()
            if (salt.size < 16) return null
            salt.copyOf(16).joinToString("") { "%02x".format(it) }
        }.getOrNull()

    /**
     * The secret, to the kernel: the only place the other half can learn it from. Sixteen bytes as
     * four little endian words, which is how that side puts them back into the name.
     */
    private fun announce(): Boolean {
        val bytes =
            runCatching {
                val salt = File(ROOT, SALT).readBytes()
                if (salt.size < 16) null else salt.copyOf(16)
            }.getOrNull() ?: return false

        val words = IntArray(4)
        for (i in 0 until 4) {
            words[i] =
                (bytes[i * 4].toInt() and 0xff) or
                ((bytes[i * 4 + 1].toInt() and 0xff) shl 8) or
                ((bytes[i * 4 + 2].toInt() and 0xff) shl 16) or
                ((bytes[i * 4 + 3].toInt() and 0xff) shl 24)
        }
        return runCatching { KernelPolicy.pushToken(words) }.getOrDefault(false)
    }

    private fun serve(name: String) {
        val server =
            try {
                LocalServerSocket(name)
            } catch (cause: Throwable) {
                say("the state socket could not be made: " + cause.message)
                return
            }
        say("the state socket is up")

        while (true) {
            val client =
                try {
                    server.accept()
                } catch (cause: Throwable) {
                    say("the state socket stopped: " + cause.message)
                    return
                }
            /*
             * An abstract socket has no permissions of its own, so who is on the other end is asked:
             * only the framework's own user is answered. Anything else is closed before it can ask
             * for anything, and said out loud so it is not a silent thing.
             */
            val peer = runCatching { client.peerCredentials }.getOrNull()
            if (peer == null || peer.uid != SYSTEM_UID) {
                say("a stranger asked for the state: uid=" + (peer?.uid ?: -1))
                runCatching { client.close() }
                continue
            }
            try {
                val input = DataInputStream(client.inputStream)
                val output = DataOutputStream(client.outputStream)
                val which = input.readInt()
                val op = input.readInt()
                if (which < 0 || which >= NAMES.size) {
                    output.writeInt(0)
                } else if (op == OP_READ) {
                    val bytes = read(which) ?: ByteArray(0)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                } else {
                    val size = input.readInt()
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    output.writeInt(if (write(which, bytes)) 1 else 0)
                }
                output.flush()
            } catch (cause: Throwable) {
                say("a request went wrong: " + cause.message)
            } finally {
                runCatching { client.close() }
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Nothing of this module is left where a process list would show it.
        runCatching {
            Class.forName("android.os.Process")
                .getMethod("setArgV0", String::class.java)
                .invoke(null, "")
        }

        val name = token()
        if (name == null) {
            say("no secret beside the module")
            return
        }
        say("the state side is up")
        say("the name reached the kernel: " + announce())

        /* Which sockets say "this task starts apps": named to the kernel once, from here. */
        runCatching { ZygoteSockets.push() }
        say("the sockets were named to the kernel")

        serve(name)
    }
}
