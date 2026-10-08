package io.github.grachevskaya.asya.zygote.service

import java.io.File
import java.io.RandomAccessFile

/**
 * ELF dependency closure: file header, program headers, dynamic section and string table
 * only -- no mmap, no symbol tables.
 *
 * A declared native library is often a shell: uidscan declares libnatsvc.so, while the
 * isolated child opens its dependency libscan.so. Hooking only the declared one misses
 * the child; hooking all of them pulls Chrome/WebView's hundreds of libraries into the
 * table. Walking DT_NEEDED avoids both.
 *
 * Any unreadable step returns null and the caller falls back to every .so.
 */
object ElfDeps {
    /** Closure of names, resolved inside dir only. null when unreadable. */
    fun closure(
        names: Collection<String>,
        dir: File,
    ): Set<String>? {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue.addAll(names)
        var steps = 0
        while (queue.isNotEmpty()) {
            if (++steps > 1024) return null
            val name = queue.removeFirst()
            if (!out.add(name)) continue
            val file = File(dir, name)
            if (!file.isFile) continue
            val needed = needed(file) ?: continue
            for (dep in needed) {
                if (dep !in out && File(dir, dep).isFile) queue.add(dep)
            }
        }
        return if (out.isEmpty()) null else out
    }

    private fun rd16(
        b: ByteArray,
        o: Int,
    ): Long = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8)

    private fun rd32(
        b: ByteArray,
        o: Int,
    ): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun rd64(
        b: ByteArray,
        o: Int,
    ): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private const val PT_LOAD = 1L
    private const val PT_DYNAMIC = 2L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L

    /** DT_NEEDED of one .so; null on anything unreadable. */
    private fun needed(file: File): List<String>? =
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 64) return@use null
                val eh = ByteArray(64)
                raf.readFully(eh)
                if (eh[0].toInt() != 0x7F ||
                    eh[1].toInt() != 'E'.code ||
                    eh[2].toInt() != 'L'.code ||
                    eh[3].toInt() != 'F'.code
                ) {
                    return@use null
                }

                val is64 = eh[4].toInt() == 2
                val phOff = if (is64) rd64(eh, 32) else rd32(eh, 28)
                val phEnt = if (is64) rd16(eh, 54) else rd16(eh, 42)
                val phNum = if (is64) rd16(eh, 56) else rd16(eh, 44)
                if (phNum <= 0L || phEnt <= 0L || phNum > 128L) return@use null

                val loads = ArrayList<Pair<Long, Long>>() // offset, vaddr
                var dynOff = -1L
                val hdr = ByteArray(phEnt.toInt())
                for (i in 0 until phNum.toInt()) {
                    raf.seek(phOff + i * phEnt)
                    raf.readFully(hdr)
                    val type = rd32(hdr, 0)
                    val off = if (is64) rd64(hdr, 8) else rd32(hdr, 4)
                    val va = if (is64) rd64(hdr, 16) else rd32(hdr, 8)
                    if (type == PT_LOAD) loads.add(off to va)
                    if (type == PT_DYNAMIC) dynOff = off
                }
                if (dynOff < 0L || loads.isEmpty()) return@use null

                fun vaddrToOffset(va: Long): Long? {
                    for ((off, base) in loads) {
                        if (va >= base && va - base < (1L shl 30)) return off + (va - base)
                    }
                    return null
                }

                val dynEnt = if (is64) 16L else 8L
                val buf = ByteArray(dynEnt.toInt())
                val ents = ArrayList<Pair<Long, Long>>()
                var p = dynOff
                while (ents.size < 4096) {
                    raf.seek(p)
                    if (raf.read(buf) < buf.size) break
                    val tag = if (is64) rd64(buf, 0) else rd32(buf, 0)
                    val value = if (is64) rd64(buf, 8) else rd32(buf, 4)
                    if (tag == 0L) break
                    ents.add(tag to value)
                    p += dynEnt
                }
                val strTabVa = ents.firstOrNull { it.first == DT_STRTAB }?.second ?: return@use null
                val strTabOff = vaddrToOffset(strTabVa) ?: return@use null

                val out = ArrayList<String>()
                for ((tag, value) in ents) {
                    if (tag != DT_NEEDED) continue
                    raf.seek(strTabOff + value)
                    val sb = StringBuilder()
                    var c = raf.read()
                    var guard = 0
                    while (c > 0 && guard < 512) {
                        sb.append(c.toChar())
                        c = raf.read()
                        guard++
                    }
                    if (sb.isNotEmpty()) out.add(sb.toString())
                }
                out
            }
        }.getOrNull()
}
