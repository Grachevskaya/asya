package io.github.grachevskaya.asya.zygote.hook

/*
 * The name a hidden lookup is given.
 *
 * The name is the asked one's UTF-8 bytes, each mixed with one byte of the salt the service draws
 * once and written as a letter, with a 'z' in front so that the first character is a letter. So the
 * name says nothing about the one it was computed from -- it cannot be read back as a package, and
 * the same query mixes to the same name for as long as the service lives -- and no package that
 * could be installed carries it, so the framework itself answers the rewritten query the way it
 * answers a package that is not installed. Nothing is recognised on the way back: the framework's
 * own answer is handed back as it is.
 */
object HideName {
    /** The letters a mixed byte is written in: one per value of its low nibble. */
    private const val FIRST_LETTER = 'a'
    private const val LETTERS = 16

    /** The letter in front of the mixed bytes: what makes the first character a letter. */
    private const val LEAD = 'z'

    /** How many bytes of the salt are mixed in, one per byte of the asked name, over and over. */
    private const val SALT_BYTES = 4

    fun absent(
        asked: String,
        salt: Int,
    ): String {
        val bytes = asked.toByteArray(Charsets.UTF_8)
        val name = StringBuilder(bytes.size + 1)
        name.append(LEAD)

        for (index in bytes.indices) {
            val saltByte = (salt ushr ((index % SALT_BYTES) * Byte.SIZE_BITS)) and 0xFF
            val mixed = (bytes[index].toInt() xor saltByte) and (LETTERS - 1)
            name.append(FIRST_LETTER + mixed)
        }

        return name.toString()
    }
}
