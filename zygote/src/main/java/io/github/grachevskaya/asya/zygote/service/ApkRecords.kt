package io.github.grachevskaya.asya.zygote.service

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class ApkIdentity(
    val uid: Int,
    val dev: Long,
    val ino: Long,
)

/** The exact 24-byte kaux_apk_rec ABI. dev is Linux stat/new_encode_dev, not raw kernel dev_t. */
internal object ApkRecords {
    fun obsolete(
        old: Map<String, ApkIdentity>,
        current: Map<String, ApkIdentity>,
        full: Boolean,
    ): List<ApkIdentity> {
        val live = current.values.map { it.dev to it.ino }.toSet()
        return old
            .filter { (path, before) ->
                val after = current[path]
                (after != null && (before.dev != after.dev || before.ino != after.ino)) || (full && after == null)
            }.values
            .filter { (it.dev to it.ino) !in live }
            .distinctBy { it.dev to it.ino }
    }

    fun add(
        path: String,
        identity: ApkIdentity,
    ): ByteArray {
        require(identity.ino != 0L)
        val bytes = path.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && !path.contains('\u0000'))
        return header(0, identity, bytes.size + 1, 24 + ((bytes.size + 4) and 3.inv()))
            .put(bytes)
            .put(0)
            .array()
    }

    fun drop(identity: ApkIdentity): ByteArray = header(1, identity.copy(uid = 0), 0, 24).array()

    private fun header(
        op: Int,
        identity: ApkIdentity,
        length: Int,
        size: Int,
    ): ByteBuffer =
        ByteBuffer
            .allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op)
            .putInt(identity.uid)
            .putInt(identity.dev.toInt())
            .putInt(identity.ino.toInt())
            .putInt((identity.ino ushr 32).toInt())
            .putInt(length)
}
