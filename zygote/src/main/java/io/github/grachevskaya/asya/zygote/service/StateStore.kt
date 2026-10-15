package io.github.grachevskaya.asya.zygote.service

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.PosixFilePermissions

/** A failed write never truncates the last committed document. Existing file permissions survive. */
internal class StateStore(
    private val root: File,
    private val syncDirectory: (Path) -> Unit = { dir -> FileChannel.open(dir, READ).use { it.force(true) } },
    private val commit: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
        Unit
    },
) {
    private val names = arrayOf("config.json", "native_libs.json", "filter_counts.json")

    private fun path(which: Int): Path {
        require(which in names.indices)
        return File(root, names[which]).toPath()
    }

    fun read(which: Int): ByteArray {
        val path = path(which)
        return try {
            require(Files.size(path) <= StateWire.MAX_BYTES)
            Files.readAllBytes(path).also { require(it.size <= StateWire.MAX_BYTES) }
        } catch (_: java.nio.file.NoSuchFileException) {
            ByteArray(0)
        }
    }

    @Synchronized
    fun write(
        which: Int,
        bytes: ByteArray,
    ) {
        require(bytes.size <= StateWire.MAX_BYTES)
        val target = path(which)
        val permissions = if (Files.exists(target)) Files.getPosixFilePermissions(target) else PosixFilePermissions.fromString("rw-------")
        val staging = Files.createTempFile(root.toPath(), ".state-", ".new", PosixFilePermissions.asFileAttribute(permissions))
        try {
            FileOutputStream(staging.toFile()).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            commit(staging, target)
            syncDirectory(root.toPath())
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}
