package io.github.grachevskaya.asya.zygote.service

import android.net.LocalSocket
import android.net.LocalSocketAddress
import io.github.grachevskaya.asya.zygote.util.Logcat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** One connection per request, just like StateDaemon. No callbacks or shared streams. */
internal object StateClient {
    const val STATE_CONFIG = 0
    const val STATE_LIBRARIES = 1
    const val STATE_COUNTS = 2

    /** The hidden package names, for the cleanup the kernel runs and the route the zygote takes. */

    @Volatile private var name: String? = null

    fun open(candidate: String) {
        name = candidate
    }

    private fun <T> request(
        which: Int,
        operation: (DataInputStream, DataOutputStream) -> T,
    ): T {
        require(which in 0..2)
        val address = name ?: throw IOException("State daemon name is not available yet")
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(address, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 5_000
            check(socket.peerCredentials.uid == 0) { "State peer is not root" }
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)
            output.writeInt(which)
            return operation(input, output)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Null is a transport/storage failure; an empty array is a successfully read empty file. */
    fun read(which: Int): ByteArray? =
        runCatching {
            request(which) { input, output ->
                output.writeInt(StateWire.READ)
                output.flush()
                StateWire.readBytes(input)
            }
        }.onFailure { Logcat.logE("StateClient", it) { "State read failed: file=$which" } }.getOrNull()

    fun write(
        which: Int,
        bytes: ByteArray,
    ): Boolean =
        runCatching {
            require(bytes.size <= StateWire.MAX_BYTES)
            request(which) { input, output ->
                output.writeInt(StateWire.WRITE)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
                if (input.readInt() != StateWire.OK) throw IOException("Daemon rejected state write: file=$which")
                true
            }
        }.onFailure { Logcat.logE("StateClient", it) { "State write failed: file=$which" } }.getOrDefault(false)

    fun readText(which: Int): String? = read(which)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
}
