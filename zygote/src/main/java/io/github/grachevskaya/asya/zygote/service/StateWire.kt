package io.github.grachevskaya.asya.zygote.service

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** DataInput/OutputStream's big endian framing, independent of the little endian netlink ABI. */
internal object StateWire {
    const val READ = 0
    const val WRITE = 1
    const val OK = 1
    const val ERROR = -1
    const val MAX_BYTES = 16 * 1024 * 1024

    fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        if (size !in 0..MAX_BYTES) throw IOException("Invalid state payload length: $size")
        return ByteArray(size).also { input.readFully(it) }
    }

    fun serve(
        input: DataInputStream,
        output: DataOutputStream,
        read: (Int) -> ByteArray,
        write: (Int, ByteArray) -> Unit,
    ) {
        val which = input.readInt()
        val op = input.readInt()
        try {
            require(which in 0..2 && (op == READ || op == WRITE)) { "Invalid state request: $which/$op" }
            if (op == READ) {
                val bytes = read(which)
                require(bytes.size <= MAX_BYTES)
                output.writeInt(bytes.size)
                output.write(bytes)
            } else {
                write(which, readBytes(input))
                output.writeInt(OK)
            }
        } catch (cause: Exception) {
            output.writeInt(ERROR)
            output.flush()
            throw cause
        }
        output.flush()
    }
}
