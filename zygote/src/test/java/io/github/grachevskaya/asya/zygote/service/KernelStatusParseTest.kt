package io.github.grachevskaya.asya.zygote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The status record is a struct the kernel writes and this side reads field by field. The offsets are
 * the whole contract between the two halves, and a mistake in one of them is not a crash: it is a
 * number read from the wrong place and shown to the manager as if it were the right one. So the
 * layout is pinned down here, byte by byte, against a buffer written the way the kernel writes it.
 */
class KernelStatusParseTest {
    private val magic = 0x7875616bL

    /** One record, in the order the kernel writes it, with every field a value of its own. */
    private fun status(overrides: Map<Int, Long> = emptyMap()): ByteArray {
        val bytes = ByteArray(128)

        writeI32(bytes, 0, magic)

        // version, flags, then the native and compat pairs, each a u32 of its own.
        (1L..13L).forEach { field ->
            writeI32(bytes, 4 + field.toInt() * 4, field)
        }

        // The two tier names sit at 64 and 96, NUL-terminated.
        writeString(bytes, 64, "uid-tier")
        writeString(bytes, 96, "setuid-tier")

        overrides.forEach { (at, value) -> writeI32(bytes, at, value) }

        return bytes
    }

    private fun writeI32(
        bytes: ByteArray,
        at: Int,
        value: Long,
    ) {
        for (i in 0 until 4) bytes[at + i] = ((value shr (8 * i)) and 0xff).toByte()
    }

    private fun writeString(
        bytes: ByteArray,
        at: Int,
        text: String,
    ) {
        text.toByteArray(Charsets.UTF_8).copyInto(bytes, at)
    }

    private fun parse(record: ByteArray): Map<String, String> = KernelPolicy.parseStatus(record, 0, record.size)

    @Test
    fun aRecordShorterThanTheFixedPartIsNotReadAsOne() {
        val short = ByteArray(KernelConstants.MIN_STATUS_LENGTH - 1)

        val status = parse(short)

        assertEquals("state", status.keys.single())
        assertTrue("a short record has to say it was short", status.getValue("state").contains("short"))
    }

    @Test
    fun aRecordWithTheWrongMagicIsNotReadAsOne() {
        val record = status()
        writeI32(record, 0, magic + 1L)

        val status = parse(record)

        assertEquals("wrong magic", status["state"])
    }

    @Test
    fun everyFieldIsReadFromItsOwnOffset() {
        val status = parse(status())

        assertEquals("loaded", status["state"])
        assertEquals("1", status["version"])
        assertEquals("2", status["flags"])
        assertEquals("3/4", status["native"])
        assertEquals("5/6", status["compat"])
        assertEquals("7", status["apkInodes"])
        assertEquals("8", status["apkOffered"])
        assertEquals("9", status["apkFailed"])
        assertEquals("10", status["apkFailedTotal"])
        assertEquals("11", status["lastError"])
        assertEquals("12", status["apkUpdates"])
        assertEquals("13", status["vaBits"])
    }

    @Test
    fun aSignedErrorIsReadAsTheNegativeItIs() {
        // lastError is the one field the kernel writes as a signed value, and a failure is negative.
        val status = parse(status(mapOf(48 to -13L)))

        assertEquals("-13", status["lastError"])
    }

    @Test
    fun theTierNamesAreReadUpToTheirTerminator() {
        val status = parse(status())

        assertEquals("uid-tier", status["uidTier"])
        assertEquals("setuid-tier", status["setuidTier"])
    }

    @Test
    fun aFieldIsReadFromWhereTheRecordStartsNotFromZero() {
        // The kernel's answer is read inside a netlink frame, so the record has an offset.
        val record = status()
        val framed = ByteArray(record.size + 16)
        record.copyInto(framed, 16)

        val status = KernelPolicy.parseStatus(framed, 16, record.size)

        assertEquals("loaded", status["state"])
        assertEquals("7", status["apkInodes"])
    }
}
