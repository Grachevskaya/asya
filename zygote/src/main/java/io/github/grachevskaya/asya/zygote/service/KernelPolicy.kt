package io.github.grachevskaya.asya.zygote.service

import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import io.github.grachevskaya.asya.zygote.service.KernelConstants.MIN_STATUS_LENGTH
import io.github.grachevskaya.asya.zygote.service.KernelConstants.YIELD_BATCH_SIZE
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logE
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/*
 * Policy tables replace at END; APK records apply incrementally and END reports any refusal.
 * Only a successful END advances the userspace ledger. Separate processes share the kernel
 * channel, whose BEGIN/BATCH/END owner is the netlink port, not this object monitor.
 */
object KernelPolicy {
    private const val TAG = "KernelPolicy"

    private const val FAMILY_NAME = "tosya"

    /**
     * The family version, which the kernel compares with its own before it reads anything
     * (kaux.h: KAUX_FAMILY_VERSION, and netlink.c: kaux_version()). Every command carries it in the
     * generic netlink header, and BEGIN reports a mismatch as -EINVAL, so the two numbers have to be
     * the same one.
     */
    internal const val FAMILY_VERSION = 7

    // Streaming (kaux.h): BEGIN opens a stream, BATCH carries records, END makes it current.
    private const val CMD_BEGIN = 2
    private const val CMD_BATCH = 3
    private const val CMD_END = 4
    private const val CMD_STATUS = 5
    private const val CMD_REFRESH = 6

    private const val KAUX_OP_ADD = 0
    private const val KAUX_OP_DEL = 1

    private const val ATTR_BLOB = 1
    private const val ATTR_STATUS = 2
    private const val STATUS_MAGIC = 0x7875616b
    private const val KIND_POLICY = 0
    private const val KIND_APKS = 1

    /**
     * The names a caller may not see, in a stream of their own: a stream carries one kind, and the
     * kernel replaces the whole set of that kind at END (kaux.h: KAUX_KIND_NAMES; netlink.c:
     * kaux_batch_names()). A name record carries the caller it is hidden from, 0 meaning every one.
     */
    internal const val KIND_NAMES = 5

    /** Sixteen bytes: the name both halves use. */
    private const val KIND_TOKEN = 4

    // kaux_name_rec: op, caller, name_len, then the name zero padded to a four byte boundary.
    private const val NAME_REC_WORDS = 3

    /** TOSYA_NAME_MAX: the kernel refuses an empty name and one longer than this. */
    internal const val NAME_MAX_BYTES = 255

    // The caller range the kernel maps to an app id: TOSYA_APP_MIN/TOSYA_APP_SPAN of uid % 100000.
    private const val APP_ID_MIN = 10_000
    private const val APP_ID_SPAN = 10_000
    private const val APP_ID_MODULUS = 100_000

    // Matches the kernel side: 24 MiB of headroom under the 65536 entry cap.
    private const val PAGE_BYTES = 32 * 1024
    private const val MAX_BLOB_BYTES = 24 * 1024 * 1024

    /** KAUX_BATCH_BYTES: the largest single payload the kernel takes, header included. */
    private const val BATCH_BYTES = 32 * 1024

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

    /** BEGIN{kind}: the one word struct kaux_begin, and the kind the kernel dispatches the stream on. */
    internal fun beginBlob(kind: Int): ByteArray =
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

    /**
     * One BATCH of the policy stream: the count, then 16 byte kaux_policy_rec records of
     * op, caller, target, flags. The policy is replaced whole, so every record is an ADD.
     */
    internal fun policyBatchBlob(
        pairs: IntArray,
        from: Int = 0,
        count: Int = pairs.size / 2,
    ): ByteArray {
        require(pairs.size % 2 == 0)
        require(from >= 0 && count >= 0 && from + count <= pairs.size / 2)
        val records = ByteBuffer.allocate(count * 16).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            records
                .putInt(KAUX_OP_ADD)
                .putInt(pairs[(from + i) * 2])
                .putInt(pairs[(from + i) * 2 + 1])
                .putInt(0)
        }

        return batchBlob(count, records.array())
    }

    /**
     * One kaux_name_rec and the name it carries: three words of head, then the bytes zero padded to a
     * four byte boundary. The kernel walks a batch by `KAUX_NAME_REC_WORDS + (name_len + 3) / 4`
     * words, so the padding is part of the record and not slack between records.
     */
    internal fun nameRecord(
        caller: Int,
        name: ByteArray,
    ): ByteArray {
        require(name.size in 1..NAME_MAX_BYTES)
        val padded = (name.size + 3) and 3.inv()

        return ByteBuffer
            .allocate(NAME_REC_WORDS * 4 + padded)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(KAUX_OP_ADD)
            .putInt(caller)
            .putInt(name.size)
            .put(name)
            .array()
    }

    /**
     * The BATCH blobs of one names stream: one record per (caller, hidden name), cut so one blob stays
     * inside the kernel's per-message cap. A caller the kernel cannot map to an app id, and a name it
     * would refuse, are left out here rather than sent to be counted as refusals on the other side.
     */
    internal fun nameBatches(tables: Map<Int, HideTables>): List<ByteArray> {
        val batches = ArrayList<ByteArray>()
        var records = ArrayList<ByteArray>()
        var bytes = 4 // the batch's own count word

        fun flush() {
            if (records.isEmpty()) return
            batches.add(batchBlob(records.size, concat(records)))
            records = ArrayList()
            bytes = 4
        }

        for ((caller, table) in tables) {
            if (!callerIsApp(caller)) continue
            for (name in table.hidden) {
                val encoded = name.toByteArray(Charsets.UTF_8)
                if (encoded.size !in 1..NAME_MAX_BYTES) continue
                val record = nameRecord(caller, encoded)
                if (bytes + record.size > BATCH_BYTES) flush()
                records.add(record)
                bytes += record.size
            }
        }
        flush()

        return batches
    }

    /** The caller a name record names: 0 is every caller, anything else an app uid (dir_caller_app_of). */
    private fun callerIsApp(caller: Int): Boolean {
        val app = caller % APP_ID_MODULUS

        return app >= APP_ID_MIN && app < APP_ID_MIN + APP_ID_SPAN
    }

    private fun concat(records: List<ByteArray>): ByteArray {
        val out = ByteArray(records.sumOf { it.size })
        var at = 0
        for (record in records) {
            record.copyInto(out, at)
            at += record.size
        }

        return out
    }

    /**
     * Asks the kernel to look for the protected directories again. A user that was added since the module
     * loaded has a tree nothing was registered on, and its names would be answered by the filesystem.
     */
    fun refresh(): Boolean {
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
                call(fd, family, CMD_REFRESH, ByteArray(0))
            } finally {
                Os.close(fd)
            }
        } catch (cause: Throwable) {
            logE(TAG) { "The kernel refused the directory refresh: $cause" }
            false
        }
    }

    @Synchronized
    fun push(pairs: IntArray): Boolean {
        val blob = ByteBuffer.allocate(pairs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        pairs.forEach { blob.putInt(it) }
        val bytes = blob.array()

        require(pairs.size % 2 == 0)
        // Empty is an explicit policy replacement too; it must clear the previous rules.

        if (bytes.size > MAX_BLOB_BYTES) {
            logE(TAG) { "The policy is ${bytes.size} bytes, over the ${MAX_BLOB_BYTES} the kernel takes" }
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

                // 16 bytes per record: action, caller, target, flags. The policy is replaced whole,
                // so every record is an ADD and END swaps the set in.
                val total = pairs.size / 2
                var at = 0
                while (at < total) {
                    // The 8 byte batch header counts against the kernel's per-message cap.
                    val n = minOf((PAGE_BYTES - 8) / 16, total - at)

                    if (!call(fd, family, CMD_BATCH, policyBatchBlob(pairs, at, n))) {
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
     * Hands over the files the kernel has to watch: the kernel replaces the ->open of each one, and
     * the first file of its own code that an isolated child opens gives it the app it belongs to.
     *
     * ApkRecords encodes the 24-byte header including stat-encoded dev and 64-bit ino.
     * The kernel never resolves the diagnostic path.
     */

    /** A file the kernel was told about: who it belongs to, and what it was when it was added. */

    /** What the kernel holds, as this side last handed it over. */
    private val held = HashMap<String, ApkIdentity>()

    /** How much this side believes the kernel holds. */
    @Synchronized fun heldCount(): Int = held.size

    /** A module that has just been loaded holds nothing: the two ledgers start level again. */
    @Synchronized fun forgetHeld() = held.clear()

    /** The name both halves use, handed to the kernel so the other half can learn it too. */
    @Synchronized
    fun pushToken(words: IntArray): Boolean {
        require(words.size == 4)
        try {
            val fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_RAW, NETLINK_GENERIC)
            try {
                Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(2_000))
                val family = resolveFamily(fd)
                if (family <= 0) return false

                val bytes = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                words.forEach { bytes.putInt(it) }
                return call(fd, family, CMD_BEGIN, beginBlob(KIND_TOKEN)) &&
                    call(fd, family, CMD_BATCH, batchBlob(words.size, bytes.array())) &&
                    call(fd, family, CMD_END, endBlob())
            } finally {
                runCatching { Os.close(fd) }
            }
        } catch (cause: Throwable) {
            logW(TAG, cause) { "Could not hand the name to the kernel half" }

            return false
        }
    }

    /**
     * Hands over the names each caller may not see, as a stream of its own. A stream is the whole set
     * of its kind: the kernel keeps nothing of the previous one, so an empty set is a stream with no
     * BATCH and that is what clears it.
     */
    @Synchronized
    fun pushHiddenNames(tables: Map<Int, HideTables>): Boolean {
        val batches = nameBatches(tables)

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

                if (!call(fd, family, CMD_BEGIN, beginBlob(KIND_NAMES))) {
                    logE(TAG) { "The kernel refused the beginning of the hidden-name stream" }
                    return false
                }

                for (batch in batches) {
                    if (!call(fd, family, CMD_BATCH, batch)) {
                        logE(TAG) { "The kernel refused a hidden-name batch of ${batch.size - 4} byte(s)" }
                        return false
                    }
                }

                if (!call(fd, family, CMD_END, endBlob())) {
                    logE(TAG) { "The kernel refused the end of the hidden-name stream" }
                    return false
                }

                logI(TAG) { "Handed the kernel half ${batches.size} hidden-name batch(es) for ${tables.size} caller(s)" }
                true
            } finally {
                Os.close(fd)
            }
        } catch (cause: Throwable) {
            logE(TAG, cause) { "Could not reach the kernel half with the hidden names" }
            false
        }
    }

    @Synchronized
    fun pushApks(
        entries: List<Pair<Int, String>>,
        fullSnapshot: Boolean = true,
    ): Boolean {
        val current = LinkedHashMap<String, Int>()
        for ((uid, path) in entries) current.putIfAbsent(path, uid)

        // Reoffer every current identity; explicit DEL records remove absent/replaced identities.
        val adds = ArrayList<Triple<Int, String, ApkIdentity>>()
        var missing = 0
        val identityOf = LinkedHashMap<String, ApkIdentity>()
        for ((path, uid) in current) {
            val stat =
                try {
                    Os.stat(path)
                } catch (cause: Throwable) {
                    logW(TAG, cause) { "No identity for $path; offering all other APKs and retrying later" }
                    missing++
                    continue
                }

            val identity = ApkIdentity(uid, stat.st_dev, stat.st_ino)
            adds.add(Triple(uid, path, identity))
            identityOf[path] = identity
        }

        // Missing stat for one path must neither suppress valid records nor drop its old identity.
        val protectedCurrent = identityOf.toMutableMap()
        for (path in current.keys) if (path !in protectedCurrent) held[path]?.let { protectedCurrent[path] = it }
        // A cold native-lib cache cannot justify removing libraries already attached.
        val previous = held.filterKeys { it.endsWith(".apk") || it in identityOf }
        val drops = previous.filterKeys { fullSnapshot && it !in current }
        val removals = ApkRecords.obsolete(previous, protectedCurrent, fullSnapshot)
        val addRecords = adds.map { (_, path, identity) -> ApkRecords.add(path, identity) }
        val delRecords = removals.map { ApkRecords.drop(it) }

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
                    val ok = call(fd, family, CMD_BATCH, batchBlob(batch.size, concat(batch)))
                    if (!ok) {
                        logE(TAG) { "The kernel refused ${batch.size} apk record(s)" }
                    }
                    batch = ArrayList()
                    bytes = 0
                    return ok
                }

                // Sent in slices: this runs in system_server, and thousands of records in one go keep
                // the framework from running for seconds.
                var sinceYield = 0
                for (record in delRecords + addRecords) {
                    if (bytes + record.size > PAGE_BYTES - 8 && !flush()) {
                        return false
                    }
                    batch.add(record)
                    bytes += record.size
                    if (++sinceYield >= YIELD_BATCH_SIZE) {
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
                    "Handed the kernel half " + adds.size + " record(s) and " + delRecords.size +
                        " removal(s); missing=" + missing + "; it holds " + held.size + " now"
                }
                missing == 0
            } finally {
                Os.close(fd)
            }
        } catch (cause: Throwable) {
            logE(TAG, cause) { "Could not reach the kernel half with the apk table" }
            false
        }
    }

    /**
     * The only command that answers with data. The struct carries its own magic, so a reply from
     * another build is refused rather than misread.
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

    /**
     * The fixed structure, field by field, in the order the kernel writes it.
     *
     * Internal rather than private: it is a function of the bytes and of nothing else, and the field
     * offsets are the one thing about this wire that a test on the host can pin down.
     */
    internal fun parseStatus(
        reply: ByteArray,
        offset: Int,
        length: Int,
    ): Map<String, String> {
        if (offset < 0 ||
            length < MIN_STATUS_LENGTH ||
            offset > reply.size ||
            length > reply.size - offset
        ) {
            return linkedMapOf("state" to "short status, $length bytes")
        }
        if (i32(reply, offset) != STATUS_MAGIC) return linkedMapOf("state" to "wrong magic")
        // The record says how long it is and which family wrote it, and both are checked: a kernel
        // whose structure differs still carries the magic, and reading its fields by this offset
        // table would report one field as another without anything looking wrong.
        if (u32(reply, offset + 4) != MIN_STATUS_LENGTH.toLong()) {
            return linkedMapOf("state" to "status of ${u32(reply, offset + 4)} bytes, not $MIN_STATUS_LENGTH")
        }
        // The one field that says which half wrote this: a kernel of another family version writes the
        // record at another size and its fields elsewhere, so both numbers are named here.
        val family = u32(reply, offset + 8)
        if (family != FAMILY_VERSION.toLong()) {
            return linkedMapOf("state" to "kernel family version $family, this half speaks $FAMILY_VERSION")
        }

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
            // The directory guard: how many names the last set carried, how many parents are watched,
            // and how many records the kernel refused (kaux.h: hidden_names, hidden_guards, hidden_failed).
            "hiddenNames" to u32(reply, offset + 56).toString(),
            "hiddenGuards" to u32(reply, offset + 60).toString(),
            "hiddenFailed" to u32(reply, offset + 64).toString(),
            "vaBits" to u32(reply, offset + 68).toString(),
            "pageShift" to u32(reply, offset + 72).toString(),
            "uidTier" to cString(reply, offset + 76, 32),
            "setuidTier" to cString(reply, offset + 108, 32),
            "token0" to u32(reply, offset + 140).toString(),
            "token1" to u32(reply, offset + 144).toString(),
            "token2" to u32(reply, offset + 148).toString(),
            "token3" to u32(reply, offset + 152).toString(),
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

    /** The family id has no fixed number: the generic netlink controller answers by name. */
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

        // Attributes begin after nlmsghdr (16) and genlmsghdr (4); anywhere else walks the chain from
        // the middle of an attribute.
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
                    // The version this half put in the header is what the kernel compared first, so a
                    // refusal names it: -EINVAL from BEGIN is what a family version mismatch looks like.
                    logE(TAG) { "The kernel refused cmd $cmd with $errno (this half speaks family version $FAMILY_VERSION)" }
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
