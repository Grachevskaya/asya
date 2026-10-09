package io.github.grachevskaya.asya.zygote.service

import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logE
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The service's end of the kernel half's wire.
 *
 * What goes over it is a policy: a flat array of u32 uids, caller then target, caller then
 * target. The kernel module takes it in pages -- a header naming the whole blob and its crc,
 * then pages that must arrive in order, then a commit that applies nothing unless every byte is
 * there and the crc matches. A half-applied policy is worse than none, which is what the crc and
 * the ordered pages are for.
 *
 * Everything here is best effort. The hiding does not depend on the kernel half being loaded,
 * and a device where the upload fails keeps working exactly as before -- with a line in the log
 * saying so, because silence is what makes these things hard to place later.
 */
object KernelPolicy {
    private const val TAG = "KernelPolicy"

    private const val FAMILY_NAME = "tosya"
    private const val FAMILY_VERSION = 5

    // Streaming (kaux.h): BEGIN opens a stream, BATCH carries records, END makes it current.
    private const val CMD_BEGIN = 2
    private const val CMD_BATCH = 3
    private const val CMD_END = 4
    private const val CMD_STATUS = 5

    private const val KAUX_OP_ADD = 0
    private const val KAUX_OP_DEL = 1

    private const val ATTR_BLOB = 1
    private const val ATTR_STATUS = 2
    private const val STATUS_MAGIC = 0x7875616b
    private const val KIND_POLICY = 0
    private const val KIND_APKS = 1

    /*
     * Matches the kernel side: the table holds apks and extracted native libraries, and
     * 24 MiB is the headroom under the 65536 entry cap.
     */
    private const val PAGE_BYTES = 32 * 1024
    private const val MAX_BLOB_BYTES = 24 * 1024 * 1024

    private const val NETLINK_GENERIC = 16
    private const val GENL_ID_CTRL = 16
    private const val CTRL_CMD_GETFAMILY = 3
    private const val CTRL_ATTR_FAMILY_ID = 1
    private const val CTRL_ATTR_FAMILY_NAME = 2

    private const val NLM_F_REQUEST = 0x01
    private const val NLM_F_ACK = 0x04
    private const val NLMSG_ERROR = 2
    private const val NLMSG_DONE = 3

    private const val NLMSG_HEADER = 16
    private const val GENL_HEADER = 4

    private val sequence = AtomicInteger(1)

    /**
     * Begins a blob: BEGIN{kind}, BATCH{count} + records, END{}. The generation counts from 1, since
     * 0 means "no stream" in the kernel.
     */
    private fun beginBlob(kind: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(kind)
            .array()

    private fun endBlob(): ByteArray = ByteArray(0)

    private fun batchBlob(
        count: Int,
        records: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(count)
            .array() + records

    fun push(pairs: IntArray): Boolean {
        val blob = ByteBuffer.allocate(pairs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        pairs.forEach { blob.putInt(it) }
        val bytes = blob.array()

        if (bytes.isEmpty()) {
            logD(TAG) { "Nothing to hand over" }
            return true
        }

        if (bytes.size > MAX_BLOB_BYTES) {
            logE(TAG) { "The policy is ${'$'}{bytes.size} bytes, over the ${'$'}MAX_BLOB_BYTES the kernel takes" }
            return false
        }

        return try {
            val fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_RAW, NETLINK_GENERIC)
            try {
                Os.setsockoptTimeval(
                    fd,
                    OsConstants.SOL_SOCKET,
                    OsConstants.SO_RCVTIMEO,
                    StructTimeval.fromMillis(2_000),
                )

                val family = resolveFamily(fd)
                if (family <= 0) {
                    logI(TAG) { "The kernel half is not loaded (no $FAMILY_NAME family)" }
                    return false
                }

                if (!call(fd, family, CMD_BEGIN, beginBlob(KIND_POLICY))) {
                    logE(TAG) { "The kernel refused the beginning of the policy" }
                    return false
                }

                /*
                 * 16 bytes per record: action, caller, target, flags. A policy is replaced
                 * whole, so every record is an ADD and END swaps the set in.
                 */
                val total = pairs.size / 2
                var at = 0
                while (at < total) {
                    // The 8 byte batch header counts against the kernel's per-message cap.
                    val n = minOf((PAGE_BYTES - 8) / 16, total - at)
                    val rec = ByteBuffer.allocate(n * 16).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) {
                        rec
                            .putInt(KAUX_OP_ADD)
                            .putInt(pairs[(at + i) * 2])
                            .putInt(pairs[(at + i) * 2 + 1])
                            .putInt(0)
                    }

                    if (!call(fd, family, CMD_BATCH, batchBlob(n, rec.array()))) {
                        logE(TAG) { "The kernel refused $n policy record(s) at $at" }
                        return false
                    }

                    at += n
                }

                if (!call(fd, family, CMD_END, endBlob())) {
                    logE(TAG) { "The kernel refused the end of the policy stream" }
                    return false
                }

                logI(TAG) { "Handed ${bytes.size / 8} pair(s) to the kernel half" }
                true
            } finally {
                Os.close(fd)
            }
        } catch (cause: Throwable) {
            logE(TAG, cause) { "Could not reach the kernel half" }
            false
        }
    }

    /**
     * Hands over the apps whose apk the kernel has to watch.
     *
     * This is the second of the two tables and the one this side never sent: the kernel replaces the
     * ->open of each base.apk named here, and the first file of its own code that an isolated child
     * opens is what gives it the app it belongs to. With no table there is nothing to watch and every
     * isolated process stays a bare number.
     *
     * The layout is the kernel's (inode_hook.c, tosya_apk_apply): a u32 count, then four u32 per
     * entry -- action, uid, the byte offset of the path inside this same blob, and the path's size
     * including its trailing NUL. The kernel resolves the path itself, so no inode is sent.
     */

    /** A file the kernel was told about: who it belongs to, and what it was when it was added. */
    private data class Held(
        val uid: Int,
        val dev: Long,
        val ino: Long,
    )

    /** What the kernel holds, as this side last handed it over. */
    private val held = HashMap<String, Held>()

    /** How much this side believes the kernel holds. */
    fun heldCount(): Int = held.size

    /**
     * Forgets everything, for the one case where the kernel's own ledger is known to be empty: a
     * module that has just been loaded holds nothing, and a difference computed against what it no
     * longer remembers would offer it nothing at all.
     */
    fun forgetHeld() = held.clear()

    fun pushApks(entries: List<Pair<Int, String>>): Boolean {
        val current = LinkedHashMap<String, Int>()
        for ((uid, path) in entries) current.putIfAbsent(path, uid)

        /*
         * Every push carries the whole set, not a difference: the kernel parks whatever the newest
         * generation did not mention. Sending the set is a few dozen records; the ledger is only
         * kept to name files that are gone.
         */
        val adds = ArrayList<Triple<Int, String, Held>>()
        val identityOf = LinkedHashMap<String, Held>()
        for ((path, uid) in current) {
            val stat =
                try {
                    Os.stat(path)
                } catch (cause: Throwable) {
                    logD(TAG) { "No identity for " + path + ": " + cause.message }
                    continue
                }

            val identity = Held(uid, stat.st_dev, stat.st_ino)
            adds.add(Triple(uid, path, identity))
            identityOf[path] = identity
        }

        /*
         * The kernel keys its ledger by (dev, ino), so a file replaced in place under the same path
         * needs the old identity dropped before the path can be added again -- the file system may
         * have handed that number to the new file.
         */
        val replaced =
            identityOf
                .filter { (path, identity) ->
                    val before = held[path]
                    before != null && (before.dev != identity.dev || before.ino != identity.ino)
                }.values

        /*
         * A file that is gone has to be named for the kernel to let go of it, and the path it was
         * added under is gone with it: the only name left is the identity the file had then, which
         * is why it is remembered here. The kernel keys its own ledger the same way.
         */
        val drops = held.filterKeys { it !in current }

        if (adds.isEmpty() && drops.isEmpty()) {
            logD(TAG) { "The kernel already holds every one of " + current.size + " apk(s)" }
            return true
        }

        /*
         * One record per file (kaux.h kaux_apk_rec): a 24 byte head plus the path, NUL
         * terminated and padded to four bytes so the next record stays aligned. An add
         * leaves dev/ino 0 -- the kernel resolves the path and stamps the entry with it.
         */
        val addRecords =
            adds.map { (uid, path, _) ->
                val bytes = path.toByteArray(Charsets.UTF_8)
                ByteBuffer
                    .allocate(24 + ((bytes.size + 4) and 3.inv()))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(KAUX_OP_ADD)
                    .putInt(uid)
                    .putInt(0)
                    .putInt(0)
                    .putInt(0)
                    .putInt(bytes.size + 1)
                    .put(bytes)
                    .put(0)
                    .array()
            }

        /*
         * A drop carries identity only -- shadow_drop_id(dev, (hi << 32) | lo) -- with no
         * path, because the file is gone and the module's ledger is what still knows which
         * one it was. Writing the uid into the dev field made every drop match nothing and
         * still count as success, so the table grew by one per install instead of holding.
         */
        val delRecords =
            (drops.values + replaced).distinctBy { it.dev to it.ino }.map { gone ->
                ByteBuffer
                    .allocate(24)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(KAUX_OP_DEL)
                    .putInt(0)
                    .putInt((gone.dev and 0xffffffffL).toInt())
                    .putInt((gone.ino and 0xffffffffL).toInt())
                    .putInt(((gone.ino ushr 32) and 0xffffffffL).toInt())
                    .putInt(0)
                    .array()
            }

        return try {
            val fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_RAW, NETLINK_GENERIC)
            try {
                Os.setsockoptTimeval(
                    fd,
                    OsConstants.SOL_SOCKET,
                    OsConstants.SO_RCVTIMEO,
                    StructTimeval.fromMillis(2_000),
                )

                val family = resolveFamily(fd)
                if (family <= 0) {
                    logI(TAG) { "The kernel half is not loaded (no $FAMILY_NAME family)" }
                    return false
                }

                if (!call(fd, family, CMD_BEGIN, beginBlob(KIND_APKS))) {
                    logE(TAG) { "The kernel refused the beginning of the apk stream" }
                    return false
                }

                logI(TAG) {
                    "Streaming the apk table: " + addRecords.size + " add(s), " +
                        delRecords.size + " drop(s), " + entries.size + " entr(ies) offered"
                }

                var batch = ArrayList<ByteArray>()
                var bytes = 0

                fun flush(): Boolean {
                    if (batch.isEmpty()) {
                        return true
                    }
                    val records = ByteArray(batch.sumOf { it.size })
                    var at = 0
                    for (one in batch) {
                        System.arraycopy(one, 0, records, at, one.size)
                        at += one.size
                    }
                    val ok = call(fd, family, CMD_BATCH, batchBlob(batch.size, records))
                    if (!ok) {
                        logE(TAG) { "The kernel refused ${batch.size} apk record(s)" }
                    }
                    batch = ArrayList()
                    bytes = 0
                    return ok
                }

                /*
                 * Sent in slices. This runs in system_server, and pushing thousands of records
                 * in one go keeps the framework from running for seconds -- most visible just
                 * after the boot animation, when the lock screen comes up.
                 */
                var sinceYield = 0
                for (record in addRecords + delRecords) {
                    if (bytes + record.size > PAGE_BYTES - 8 && !flush()) {
                        return false
                    }
                    batch.add(record)
                    bytes += record.size
                    if (++sinceYield >= 512) {
                        sinceYield = 0
                        if (!flush()) {
                            return false
                        }
                        Thread.yield()
                    }
                }
                if (!flush()) {
                    return false
                }

                if (!call(fd, family, CMD_END, endBlob())) {
                    logE(TAG) { "The kernel refused the end of the apk stream" }
                    return false
                }

                for ((_, path, identity) in adds) held[path] = identity
                for (path in drops.keys) held.remove(path)

                logI(TAG) {
                    "Handed the kernel half " + adds.size + " new apk(s) and " + delRecords.size +
                        " removal(s) (" + replaced.size + " replaced); it holds " + held.size + " now"
                }
                true
            } finally {
                Os.close(fd)
            }
        } catch (cause: Throwable) {
            logE(TAG, cause) { "Could not reach the kernel half with the apk table" }
            false
        }
    }

    /**
     * What the kernel half says about itself, as the pairs the manager shows.
     *
     * This is the only command that answers with data: which registrations are in place, how many
     * entries were hooked where, and what the apk side is holding. The struct carries its own magic and its own size, so a
     * reply from another build is refused rather than misread.
     */
    fun requestStatus(): Map<String, String> {
        val fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_RAW, NETLINK_GENERIC)
        try {
            Os.setsockoptTimeval(
                fd,
                OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(2_000),
            )

            val family = resolveFamily(fd)
            if (family <= 0) return linkedMapOf("state" to "not loaded")

            val seq = sequence.getAndIncrement()
            val request = message(family, CMD_STATUS, seq, ByteArray(0), NLM_F_REQUEST)
            Os.write(fd, request, 0, request.size)

            val reply = receive(fd) ?: return linkedMapOf("state" to "no answer")

            var at = NLMSG_HEADER + GENL_HEADER
            while (at + 4 <= reply.size) {
                val length = u16(reply, at)
                val type = u16(reply, at + 2) and 0x3fff
                if (length < 4 || at + length > reply.size) break
                if (type == ATTR_STATUS) return parseStatus(reply, at + 4, length - 4)
                at += (length + 3) and 3.inv()
            }

            return linkedMapOf("state" to "no status in the reply")
        } catch (cause: Throwable) {
            logD(TAG) { "Could not ask the kernel half: ${cause.message}" }
            return linkedMapOf("state" to "unreachable")
        } finally {
            Os.close(fd)
        }
    }

    /** The fixed structure, field by field, in the order the kernel writes it. */
    private fun parseStatus(
        reply: ByteArray,
        offset: Int,
        length: Int,
    ): Map<String, String> {
        if (length < 128) return linkedMapOf("state" to "short status, $length bytes")
        if (i32(reply, offset) != STATUS_MAGIC) return linkedMapOf("state" to "wrong magic")

        return linkedMapOf(
            "state" to "loaded",
            "version" to u32(reply, offset + 8).toString(),
            "flags" to u32(reply, offset + 12).toString(),
            "native" to "${u32(reply, offset + 16)}/${u32(reply, offset + 20)}",
            "compat" to "${u32(reply, offset + 24)}/${u32(reply, offset + 28)}",
            "apkInodes" to u32(reply, offset + 32).toString(),
            "apkOffered" to u32(reply, offset + 36).toString(),
            "apkFailed" to u32(reply, offset + 40).toString(),
            "apkFailedTotal" to u32(reply, offset + 44).toString(),
            "lastError" to i32(reply, offset + 48).toString(),
            "apkUpdates" to u32(reply, offset + 52).toString(),
            "vaBits" to u32(reply, offset + 56).toString(),
            "pageShift" to u32(reply, offset + 60).toString(),
            "uidTier" to cString(reply, offset + 64, 32),
            "setuidTier" to cString(reply, offset + 96, 32),
        )
    }

    private fun u32(
        bytes: ByteArray,
        at: Int,
    ): Long =
        (bytes[at].toLong() and 0xff) or
            ((bytes[at + 1].toLong() and 0xff) shl 8) or
            ((bytes[at + 2].toLong() and 0xff) shl 16) or
            ((bytes[at + 3].toLong() and 0xff) shl 24)

    private fun cString(
        bytes: ByteArray,
        at: Int,
        max: Int,
    ): String {
        var end = at
        while (end < at + max && end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, at, end - at, Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- generic netlink

    /**
     * The family id has no fixed number: it has to be asked for by name, which is what the
     * generic netlink controller is for. The reply carries it as one attribute.
     */
    private fun resolveFamily(fd: FileDescriptor): Int {
        val name = FAMILY_NAME.toByteArray(Charsets.UTF_8) + 0
        val payload = attribute(CTRL_ATTR_FAMILY_NAME, name)
        val seq = sequence.getAndIncrement()
        val message = message(GENL_ID_CTRL, CTRL_CMD_GETFAMILY, seq, payload, NLM_F_REQUEST)

        Os.write(fd, message, 0, message.size)

        val reply =
            receive(fd)
                ?: run {
                    logE(TAG) { "No answer when asking for the $FAMILY_NAME family" }
                    return 0
                }

        // The attributes begin right after the two headers: nlmsghdr (16) and genlmsghdr (4).
        // Starting anywhere else walks the chain from the middle of an attribute and reads a length
        // and a type out of a payload.
        var at = NLMSG_HEADER + GENL_HEADER
        while (at + 4 <= reply.size) {
            val length = u16(reply, at)
            val type = u16(reply, at + 2) and 0x3fff
            if (length < 4 || at + length > reply.size) break
            if (type == CTRL_ATTR_FAMILY_ID) return u16(reply, at + 4)
            at += (length + 3) and 3.inv()
        }

        return 0
    }

    /** One command, one reply: a netlink error of zero is the acknowledgment. */
    private fun call(
        fd: FileDescriptor,
        family: Int,
        cmd: Int,
        payload: ByteArray,
    ): Boolean {
        val body = attribute(ATTR_BLOB, payload)
        val seq = sequence.getAndIncrement()
        val message = message(family, cmd, seq, body, NLM_F_REQUEST or NLM_F_ACK)

        Os.write(fd, message, 0, message.size)

        val reply = receive(fd) ?: return false
        val type = u16(reply, 4)

        return when (type) {
            NLMSG_ERROR -> {
                val errno = i32(reply, NLMSG_HEADER)
                if (errno != 0) {
                    logE(TAG) { "The kernel refused cmd $cmd with $errno" }
                    false
                } else {
                    true
                }
            }

            NLMSG_DONE -> true
            else -> true
        }
    }

    private fun receive(fd: FileDescriptor): ByteArray? =
        try {
            val buffer = ByteArray(64 * 1024)
            val read = Os.read(fd, buffer, 0, buffer.size)
            if (read <= 0) null else buffer.copyOf(read)
        } catch (cause: Throwable) {
            logD(TAG) { "Nothing to read from the kernel half: ${'$'}{cause.message}" }
            null
        }

    private fun message(
        family: Int,
        cmd: Int,
        seq: Int,
        payload: ByteArray,
        flags: Int,
    ): ByteArray {
        val length = NLMSG_HEADER + GENL_HEADER + payload.size
        val out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(length)
        out.putShort(family.toShort())
        out.putShort(flags.toShort())
        out.putInt(seq)
        out.putInt(0)
        out.put(cmd.toByte())
        out.put(FAMILY_VERSION.toByte())
        out.putShort(0)
        out.put(payload)
        return out.array()
    }

    private fun attribute(
        type: Int,
        data: ByteArray,
    ): ByteArray {
        val padded = (data.size + 3) and 3.inv()
        val out = ByteBuffer.allocate(4 + padded).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort((4 + data.size).toShort())
        out.putShort(type.toShort())
        out.put(data)
        return out.array()
    }

    private fun u16(
        bytes: ByteArray,
        at: Int,
    ): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

    private fun i32(
        bytes: ByteArray,
        at: Int,
    ): Int =
        (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)
}
