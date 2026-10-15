package io.github.grachevskaya.asya.zygote.service

/** Exactly the same 16-byte secret goes into netlink and the abstract socket name. */
internal object StateToken {
    fun words(bytes: ByteArray): IntArray {
        require(bytes.size >= 16)
        return IntArray(4) { word ->
            (0..3).fold(0) { bits, byte ->
                bits or ((bytes[4 * word + byte].toInt() and 0xff) shl (8 * byte))
            }
        }
    }

    fun name(words: IntArray): String {
        require(words.size == 4)
        return words.joinToString("") { word ->
            (0..3).joinToString("") { byte -> "%02x".format((word ushr (8 * byte)) and 0xff) }
        }
    }
}
