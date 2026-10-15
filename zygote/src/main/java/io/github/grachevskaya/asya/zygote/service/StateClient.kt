package io.github.grachevskaya.asya.zygote.service

import android.net.LocalSocket
import android.net.LocalSocketAddress
import io.github.grachevskaya.asya.zygote.util.Logcat
import java.io.DataInputStream
import java.io.DataOutputStream

/*
 * The other end of the state: this side owns nothing, it asks.
 *
 * The daemon serves an abstract unix socket whose name is the installation's own secret, and this
 * side connects to it. Nothing is registered with the service manager: a name that differs on every
 * installation cannot be granted in SELinux, which is exactly what the kernel said when it was tried.
 */
internal object StateClient {
    private const val TAG = "StateClient"
    private const val OP_READ = 0
    private const val OP_WRITE = 1

    const val STATE_CONFIG = 0
    const val STATE_LIBRARIES = 1
    const val STATE_COUNTS = 2

    @Volatile
    private var socket: LocalSocket? = null

    @Volatile
    private var out: DataOutputStream? = null

    @Volatile
    private var input: DataInputStream? = null

    @Volatile
    private var reported = false

    @Volatile
    private var name: String? = null

    /** The name comes from the service, which reads it from the kernel: this side owns nothing. */
    fun open(candidate: String) {
        name = candidate
    }

    @Volatile
    var onReady: (() -> Unit)? = null

    private fun connect(): Boolean {
        if (socket != null) return true
        val name = name ?: return false
        return runCatching {
            val made = LocalSocket()
            made.connect(LocalSocketAddress(name))
            socket = made
            input = DataInputStream(made.inputStream)
            out = DataOutputStream(made.outputStream)
            Logcat.logI(TAG) { "The state socket is open" }
            runCatching { onReady?.invoke() }
            true
        }.getOrElse { cause ->
            if (!reported) {
                reported = true
                Logcat.logW(TAG, cause) { "The state socket could not be opened" }
            }
            socket = null
            false
        }
    }

    fun read(which: Int): ByteArray? {
        if (!connect()) return null
        return runCatching {
            val stream = out ?: return null
            val answer = input ?: return null
            stream.writeInt(which)
            stream.writeInt(OP_READ)
            stream.flush()
            val size = answer.readInt()
            val bytes = ByteArray(size)
            answer.readFully(bytes)
            bytes
        }.getOrElse {
            socket = null
            null
        }
    }

    fun write(which: Int, bytes: ByteArray): Boolean {
        if (!connect()) return false
        return runCatching {
            val stream = out ?: return false
            val answer = input ?: return false
            stream.writeInt(which)
            stream.writeInt(OP_WRITE)
            stream.writeInt(bytes.size)
            stream.write(bytes)
            stream.flush()
            answer.readInt() == 1
        }.getOrElse {
            socket = null
            false
        }
    }

    fun readText(which: Int): String? = read(which)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
}
