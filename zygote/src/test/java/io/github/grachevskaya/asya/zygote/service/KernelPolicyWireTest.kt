package io.github.grachevskaya.asya.zygote.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Every BATCH is walked by the kernel byte for byte, so these tests walk it the way the kernel does.
 * The rules are copied from the module -- src/netlink.c, kaux_batch_policy() and kaux_batch_names():
 * word 0 is the batch's count, a record starts at the word after it, and after a record the reader
 * moves on by its head plus (name_len + 3) / 4 words. Reading the blobs back with the kernel's own
 * rules is what keeps a record this side writes and the kernel cannot read from being found only on a
 * device, as a stream that ended with nothing in it.
 */
class KernelPolicyWireTest {
    private val uidA = 10_123
    private val uidB = 10_456
    private val uidC = 11_789

    /**
     * The record stream a set produces, with the uid each name's table was keyed by as its owner: the
     * tests name a table by the caller that hides the names, which is the uid they expect on the wire.
     */

    /** The record stream a table set produces, as the pusher would send it. */
    private fun batches(tables: Map<Int, HideTables>): List<ByteArray> = KernelPolicy.nameBatches(tables)

    @Test
    fun theFamilyVersionIsTheOneTheKernelRegisters() {
        // KAUX_FAMILY_VERSION in the module's kaux.h, and what dmesg says the family registered with.
        assertEquals(7, KernelPolicy.FAMILY_VERSION)
    }

    @Test
    fun theBegunStreamNamesTheKindTheKernelDispatchesOn() {
        // kaux.h: KAUX_KIND_NAMES = 5; kaux_begin() refuses a kind above KAUX_KIND_MAX.
        assertEquals(5, KernelPolicy.KIND_NAMES)

        val begin = KernelPolicy.beginBlob(KernelPolicy.KIND_NAMES)

        assertEquals(4, begin.size) // sizeof(struct kaux_begin)
        assertEquals(5, i32(begin, 0))
    }

    @Test
    fun thePairsWalkBackOutOfTheBatchAsTheyWereHandedOver() {
        val pairs = intArrayOf(uidA, 10_500, uidB, 10_501)

        val blob = KernelPolicy.policyBatchBlob(pairs)

        assertEquals(4 + 2 * 16, blob.size)
        val reader = WordReader(blob)
        assertEquals(2, reader.count)
        assertEquals(Triple(uidA, 10_500, 0), reader.policyRecord())
        assertEquals(Triple(uidB, 10_501, 0), reader.policyRecord())
        assertTrue(reader.done())
    }

    @Test
    fun aSliceOfThePairsIsTheRecordsOfThatSlice() {
        val pairs = intArrayOf(uidA, 10_500, uidB, 10_501, uidC, 10_502)

        val reader = WordReader(KernelPolicy.policyBatchBlob(pairs, from = 1, count = 1))

        assertEquals(1, reader.count)
        assertEquals(Triple(uidB, 10_501, 0), reader.policyRecord())
        assertTrue(reader.done())
    }

    /** No hidden names is a stream with no BATCH, and its END is what replaces the previous set. */
    @Test
    fun theEmptySetIsAStreamWithNoBatches() {
        assertTrue(batches(emptyMap()).isEmpty())
        assertTrue(
            batches(
                mapOf(uidA to HideTables(hidden = emptySet(), installed = setOf("com.visible"))),
            ).isEmpty(),
        )
    }

    @Test
    fun everyHiddenNameOfEveryCallerComesBack() {
        val tables =
            mapOf(
                uidA to HideTables(hidden = setOf("com.target.one", "com.target.two"), installed = emptySet()),
                uidB to HideTables(hidden = setOf("com.other"), installed = emptySet()),
                uidC to HideTables(hidden = setOf("com.third"), installed = emptySet()),
            )

        val records = nameRecords(batches(tables))

        assertEquals(
            setOf(
                uidA to "com.target.one",
                uidA to "com.target.two",
                uidB to "com.other",
                uidC to "com.third",
            ),
            records.toSet(),
        )
    }

    /** "com.a" is five bytes, so the record is a 12 byte head and eight bytes: five and three of pad. */
    @Test
    fun aNameIsPaddedToAFourByteBoundary() {
        val blob =
            batches(
                mapOf(uidA to HideTables(hidden = setOf("com.a"), installed = emptySet())),
            ).single()

        assertEquals(4 + 12 + 8, blob.size)
        assertEquals("com.a", String(blob, 4 + 12, 5, Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0, 0, 0), blob.copyOfRange(4 + 17, 4 + 20))
    }

    @Test
    fun theLongestNameTheKernelTakesIsCarriedWhole() {
        // TOSYA_NAME_MAX: 255 bytes, and a name the platform's own rule accepts.
        val name = "a" + ".a".repeat(127)
        assertEquals(255, name.toByteArray(Charsets.UTF_8).size)

        val blob =
            batches(
                mapOf(uidA to HideTables(hidden = setOf(name), installed = emptySet())),
            ).single()

        // Three words of head, then 255 bytes rounded up to 64 words.
        assertEquals(4 + 3 * 4 + 256, blob.size)
        assertEquals(listOf(uidA to name), nameRecords(listOf(blob)))
    }

    @Test
    fun aNameLongerThanTheKernelTakesIsNotSent() {
        val name = "a" + ".a".repeat(128)
        assertEquals(257, name.toByteArray(Charsets.UTF_8).size)

        assertTrue(
            batches(
                mapOf(uidA to HideTables(hidden = setOf(name), installed = emptySet())),
            ).isEmpty(),
        )
    }

    /** TOSYA_APP_MIN is 10000: a caller outside the app range has no app id to be hidden from. */
    @Test
    fun aCallerTheKernelCannotMapIsNotSent() {
        assertTrue(
            batches(
                mapOf(1_000 to HideTables(hidden = setOf("com.target"), installed = emptySet())),
            ).isEmpty(),
        )
    }

    @Test
    fun aBatchStaysInsideTheKernelsPayloadCap() {
        val names = (0 until 1_000).mapTo(HashSet()) { "com.target.package" + it }

        val batches = batches(mapOf(uidA to HideTables(hidden = names, installed = emptySet())))

        assertTrue("one blob cannot hold them all", batches.size > 1)
        for (batch in batches) {
            assertTrue("KAUX_BATCH_BYTES caps one payload", batch.size <= 32 * 1024)
            assertEquals("a record is whole words", 0, batch.size % 4)
        }
        val records = nameRecords(batches)
        assertEquals(names.size, records.size)
        assertEquals(names.size, records.toSet().size)
    }

    /**
     * The same records, written with the module's own structs: a C program that includes kaux.h and
     * fills struct kaux_batch, kaux_name_rec, kaux_policy_rec and kaux_begin writes exactly these
     * bytes. A layout that drifts on this side drifts from this line, whatever it does to the walk.
     */
    @Test
    fun theRecordsAreTheBytesTheKernelsOwnStructsWrite() {
        val nameBlob =
            batches(
                mapOf(uidA to HideTables(hidden = setOf("com.a"), installed = emptySet())),
            ).single()

        assertArrayEquals(hex("01000000000000008b27000005000000636f6d2e61000000"), nameBlob)
        assertArrayEquals(
            hex(
                "02000000" + "00000000" + "8b270000" + "04290000" + "00000000" +
                    "00000000" + "d8280000" + "05290000" + "00000000",
            ),
            KernelPolicy.policyBatchBlob(intArrayOf(uidA, 10_500, uidB, 10_501)),
        )
        assertArrayEquals(hex("05000000"), KernelPolicy.beginBlob(KernelPolicy.KIND_NAMES))
    }

    // --------------------------------------------------------------- the kernel's own walk

    private fun nameRecords(batches: List<ByteArray>): List<Pair<Int, String>> =
        batches.flatMap { blob ->
            val reader = WordReader(blob)

            (0 until reader.count).map { reader.nameRecord() }
        }

    /** src/netlink.c: `words = len / 4`, `at = 1`, and a record moves `at` on by its own words. */
    private class WordReader(
        private val blob: ByteArray,
    ) {
        private val words = blob.size / 4
        val count = i32(blob, 0)
        private var at = 1

        fun done(): Boolean = at == words

        fun policyRecord(): Triple<Int, Int, Int> {
            assertTrue("a policy head has to fit", at + 4 <= words)
            assertEquals(0, i32(blob, at * 4)) // KAUX_OP_ADD
            val caller = i32(blob, (at + 1) * 4)
            val target = i32(blob, (at + 2) * 4)
            val flags = i32(blob, (at + 3) * 4)
            at += 4

            return Triple(caller, target, flags)
        }

        fun nameRecord(): Pair<Int, String> {
            assertTrue("a name head has to fit", at + 3 <= words)
            assertEquals(0, i32(blob, at * 4)) // KAUX_OP_ADD: a names stream carries no drop
            val caller = i32(blob, (at + 1) * 4)
            val length = i32(blob, (at + 2) * 4)
            assertTrue("a name has to fit its record", length <= (words - at - 3) * 4)
            val name = String(blob, (at + 3) * 4, length, Charsets.UTF_8)
            // The name pads the record out to a four byte boundary.
            at += 3 + (length + 3) / 4

            return caller to name
        }
    }
}

private fun i32(
    bytes: ByteArray,
    at: Int,
): Int = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(at)

private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
