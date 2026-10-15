package io.github.grachevskaya.asya.zygote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ApkRecordsTest {
    private val old = ApkIdentity(10123, 64820, 42)
    private val fresh = old.copy(ino = 0x12345678abcdef01L)

    @Test fun replacingAPathDropsOldIdentityNotNew() {
        assertEquals(listOf(old), ApkRecords.obsolete(mapOf("base.apk" to old), mapOf("base.apk" to fresh), true))
    }

    @Test fun incrementalCommitDoesNotDropOtherPackages() {
        assertTrue(ApkRecords.obsolete(mapOf("old.apk" to old), mapOf("new.apk" to fresh), false).isEmpty())
    }

    @Test fun fullEmptyTableRemovesEverything() {
        assertEquals(listOf(old), ApkRecords.obsolete(mapOf("old.apk" to old), emptyMap(), true))
    }

    @Test fun retainedAliasKeepsItsInode() {
        assertTrue(ApkRecords.obsolete(mapOf("old.apk" to old), mapOf("alias.apk" to old), true).isEmpty())
    }

    @Test fun addCarriesStatDevAndBothInodeWords() {
        val bytes = ApkRecords.add("/base.apk", fresh)
        val wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, wire.int)
        assertEquals(fresh.uid, wire.int)
        assertEquals(64820, wire.int)
        assertEquals(fresh.ino.toInt(), wire.int)
        assertEquals((fresh.ino ushr 32).toInt(), wire.int)
        assertEquals(10, wire.int)
        assertEquals(0, bytes.size % 4)
        assertEquals(0, bytes[33].toInt())
    }

    @Test fun deleteCarriesNoPathAndZeroInodeIsRejected() {
        val wire = ByteBuffer.wrap(ApkRecords.drop(fresh)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, wire.capacity())
        assertEquals(1, wire.int)
        assertEquals(0, wire.int)
        assertEquals(64820, wire.int)
        assertEquals(fresh.ino.toInt(), wire.int)
        assertEquals((fresh.ino ushr 32).toInt(), wire.int)
        assertEquals(0, wire.int)
        assertThrows(IllegalArgumentException::class.java) { ApkRecords.add("/base.apk", fresh.copy(ino = 0)) }
    }
}
