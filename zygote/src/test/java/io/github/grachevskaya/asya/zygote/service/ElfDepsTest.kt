package io.github.grachevskaya.asya.zygote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The closure decides which files a native app hands the kernel: the declared library, the libraries
 * it says it needs, and theirs in turn. Read the walk as "every .so in the directory" and a WebView
 * app offers the kernel hundreds of files; read it as "the declared one only" and the isolated child
 * that opens the real library is never hidden. Both mistakes are silent, so the walk is driven here
 * against real ELF images written byte by byte: a dependency that exists, one that does not, a cycle,
 * and a file that is not an ELF at all.
 */
class ElfDepsTest {
    @get:Rule
    val dir = TemporaryFolder()

    /** A 64-bit little-endian ELF shared object whose DT_NEEDED entries are the names it is given. */
    private fun sharedObject(
        needs: List<String>,
        name: String,
    ): File {
        val strings = StringBuilder("\u0000")
        val offsets = LinkedHashMap<String, Int>()

        for (need in needs) {
            offsets[need] = strings.length
            strings.append(need).append('\u0000')
        }

        val strTab = strings.toString().toByteArray(Charsets.US_ASCII)
        val phOff = 64
        val phEnt = 56
        val phNum = 2
        val dynOff = phOff + phEnt * phNum
        val dynEnt = 16
        val dynNum = needs.size + 2
        val strOff = dynOff + dynEnt * dynNum

        val image = ByteBuffer.allocate(strOff + strTab.size).order(ByteOrder.LITTLE_ENDIAN)

        // The ELF header: magic, 64-bit, little-endian, and where the program headers are.
        image.put(0x7F)
        image.put('E'.code.toByte())
        image.put('L'.code.toByte())
        image.put('F'.code.toByte())
        image.put(2)
        image.put(1)
        image.put(1)
        image.put(ByteArray(9))
        image.position(32)
        image.putLong(phOff.toLong())
        image.position(54)
        image.putShort(phEnt.toShort())
        image.putShort(phNum.toShort())

        // Two program headers: one PT_LOAD covering the whole image at virtual address zero, so a
        // virtual address is its file offset, and one PT_DYNAMIC pointing at the dynamic section.
        image.position(phOff)
        image.putInt(1)
        image.putInt(0)
        image.putLong(0)
        image.putLong(0)
        image.putLong(0)
        image.putLong(image.capacity().toLong())
        image.putLong(image.capacity().toLong())
        image.putLong(0)

        image.putInt(2)
        image.putInt(0)
        image.putLong(dynOff.toLong())
        image.putLong(dynOff.toLong())
        image.putLong(0)
        image.putLong((dynEnt * dynNum).toLong())
        image.putLong((dynEnt * dynNum).toLong())
        image.putLong(0)

        // The dynamic section: the string table's address, one DT_NEEDED per name, then the terminator.
        image.position(dynOff)
        image.putLong(5L)
        image.putLong(strOff.toLong())

        for ((_, at) in offsets) {
            image.putLong(1L)
            image.putLong(at.toLong())
        }

        image.putLong(0L)
        image.putLong(0L)

        image.position(strOff)
        image.put(strTab)

        val file = File(dir.root, name)
        file.writeBytes(image.array())

        return file
    }

    @Test
    fun theNeededLibrariesOfANamedFileAreFollowed() {
        val root = dir.root
        sharedObject(listOf("libinner.so"), "libouter.so")
        sharedObject(listOf("libleaf.so"), "libinner.so")
        sharedObject(emptyList(), "libleaf.so")

        val closure = ElfDeps.closure(listOf("libouter.so"), root)

        assertEquals(setOf("libouter.so", "libinner.so", "libleaf.so"), closure)
    }

    @Test
    fun aDependencyThatIsNotThereIsNotInvented() {
        val root = dir.root
        sharedObject(listOf("libmissing.so"), "libouter.so")

        val closure = ElfDeps.closure(listOf("libouter.so"), root)

        assertEquals(setOf("libouter.so"), closure)
    }

    @Test
    fun aCycleBetweenTwoLibrariesEnds() {
        val root = dir.root
        sharedObject(listOf("libb.so"), "liba.so")
        sharedObject(listOf("liba.so"), "libb.so")

        val closure = ElfDeps.closure(listOf("liba.so"), root)

        assertEquals(setOf("liba.so", "libb.so"), closure)
    }

    @Test
    fun aFileThatIsNotAnElfRequestsConservativeFallback() {
        val root = dir.root
        File(root, "libjunk.so").writeText("not an elf at all, not even close")

        val closure = ElfDeps.closure(listOf("libjunk.so"), root)

        assertNull(closure)
    }

    @Test
    fun theSameNameOfferedTwiceIsOfferedOnce() {
        val root = dir.root
        sharedObject(listOf("libb.so"), "liba.so")
        sharedObject(emptyList(), "libb.so")

        val closure = ElfDeps.closure(listOf("liba.so", "liba.so", "libb.so"), root)

        assertEquals(setOf("liba.so", "libb.so"), closure)
    }

    @Test
    fun nothingOfferedIsNothingAnswered() {
        assertNull(ElfDeps.closure(emptyList(), dir.root))
    }

    @Test
    fun aMissingFileIsStillListed() {
        // The table is built from the names the package declares, and a name with no file behind it
        // is the caller's business: dropping it here would hide the app's own entry point.
        val closure = ElfDeps.closure(listOf("libabsent.so"), dir.root)

        assertEquals(setOf("libabsent.so"), closure)
    }

    @Test
    fun aTruncatedFileIsNotWalkedInto() {
        val root = dir.root
        val file = File(root, "libshort.so")
        file.writeBytes(ByteArray(32))

        val closure = ElfDeps.closure(listOf("libshort.so"), root)

        assertNull(closure)
    }
}
