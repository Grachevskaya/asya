package io.github.grachevskaya.asya.zygote.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class StatePipelineTest {
    @get:Rule val dir = TemporaryFolder()

    private fun frame(
        which: Int,
        op: Int,
        bytes: ByteArray? = null,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { raw ->
                DataOutputStream(raw).use { out ->
                    out.writeInt(which)
                    out.writeInt(op)
                    bytes?.let {
                        out.writeInt(it.size)
                        out.write(it)
                    }
                }
            }.toByteArray()

    private fun exchange(
        store: StateStore,
        frame: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { answer ->
                StateWire.serve(DataInputStream(ByteArrayInputStream(frame)), DataOutputStream(answer), store::read, store::write)
            }.toByteArray()

    @Test fun threeFilesSurviveIndependentRequestsAndRestart() {
        val store = StateStore(dir.root)
        for (which in 0..2) {
            val bytes = "{\"file\":$which,\"data\":\"汉字🙂\"}".toByteArray()
            assertEquals(1, DataInputStream(ByteArrayInputStream(exchange(store, frame(which, 1, bytes)))).readInt())
            assertArrayEquals(
                bytes,
                StateWire.readBytes(DataInputStream(ByteArrayInputStream(exchange(StateStore(dir.root), frame(which, 0))))),
            )
        }
    }

    @Test fun emptyReadIsNotTransportFailure() {
        assertArrayEquals(
            ByteArray(0),
            StateWire.readBytes(DataInputStream(ByteArrayInputStream(exchange(StateStore(dir.root), frame(0, 0))))),
        )
    }

    @Test fun failedCommitKeepsOldBytesAndPermissions() {
        val store = StateStore(dir.root)
        store.write(0, "old".toByteArray())
        val file = File(dir.root, "config.json").toPath()
        val permissions = Files.getPosixFilePermissions(file)
        assertEquals(PosixFilePermissions.fromString("rw-------"), permissions)
        val failing = StateStore(dir.root) { _, _ -> throw IOException("injected rename failure") }
        assertThrows(IOException::class.java) { failing.write(0, "new".toByteArray()) }
        assertArrayEquals("old".toByteArray(), store.read(0))
        assertEquals(permissions, Files.getPosixFilePermissions(file))
        assertEquals(listOf("config.json"), dir.root.list()!!.toList())
    }

    @Test fun successfulReplacementPreservesMode() {
        val store = StateStore(dir.root)
        store.write(0, "a".toByteArray())
        val path = File(dir.root, "config.json").toPath()
        val mode = PosixFilePermissions.fromString("rw-r-----")
        Files.setPosixFilePermissions(path, mode)
        store.write(0, "b".toByteArray())
        assertEquals(mode, Files.getPosixFilePermissions(path))
    }

    @Test fun storageFailureIsNegativeAck() {
        val response = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            StateWire.serve(DataInputStream(ByteArrayInputStream(frame(0, 1, byteArrayOf(1)))), DataOutputStream(response), {
                byteArrayOf()
            }, { _, _ -> throw IOException("disk full") })
        }
        assertEquals(-1, DataInputStream(ByteArrayInputStream(response.toByteArray())).readInt())
    }

    @Test fun rejectsNegativeOversizeAndTruncatedBodies() {
        for (size in listOf(-1, StateWire.MAX_BYTES + 1, 8)) {
            val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(size) }.toByteArray()
            assertThrows(IOException::class.java) { StateWire.readBytes(DataInputStream(ByteArrayInputStream(bytes))) }
        }
    }

    @Test fun invalidFileOrOperationCannotWrite() {
        // 4 is past the last file the wire knows (the hidden list is 3); 1 is a read, 9 is no op.
        for ((which, op) in listOf(-1 to 1, 4 to 1, 0 to 9)) {
            assertThrows(IllegalArgumentException::class.java) { exchange(StateStore(dir.root), frame(which, op)) }
        }
        assertTrue(dir.root.list()!!.isEmpty())
    }

    @Test fun truncatedWriteCannotDestroyOldDocument() {
        val store = StateStore(dir.root)
        store.write(0, "old".toByteArray())
        val request = frame(0, 1, "new".toByteArray()).dropLast(1).toByteArray()
        assertThrows(EOFException::class.java) { exchange(store, request) }
        assertArrayEquals("old".toByteArray(), store.read(0))
    }
}
