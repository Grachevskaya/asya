package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.FilterHolder
import io.github.grachevskaya.asya.zygote.hook.HideName
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLongArray

/*
 * What the hooks remember between calls.
 *
 * The hide table is read on every package query -- it is the path a query takes when a target asks
 * whether another package exists -- so it is one object, replaced whole and never edited in place: a
 * query takes the reference once and walks a table nothing can change under it. Inside that table a
 * caller's hidden packages are uids in a sorted array, so the answer is a binary search, and a miss
 * costs what a hit costs.
 *
 * The counters are the other half of the same path, with the opposite pattern: written on every
 * filtered query, read once every two seconds. So a query adds to a per-caller accumulator -- no
 * lock, no binder, no write -- and the periodic flush is what lands the accumulation.
 */
class AsyaServiceDataHolder(
    /**
     * Turns the package name a query named into the integer the table is keyed by. The service hands
     * over the parsed package table's own answer; null -- a name that table does not hold, or a table
     * that cannot be read -- is not an error, it is a query that is answered by name instead.
     */
    private val packageUidResolver: (String) -> Int? = { null },
) {
    private companion object {
        /** One counter per [[FilterHolder.FilterType]]: the accumulator is indexed by its ordinal. */
        val PENDING_KINDS = FilterHolder.FilterType.values().size
    }

    /**
     * One caller and what it may not see: the uids, sorted so a query is a binary search, and the
     * names of the packages whose uid could not be resolved, so that a pair written before its uid
     * was known is still answered -- whichever of the two a query can be resolved to now.
     */
    private class HiddenTargets(
        val caller: String,
        val uids: IntArray,
        val packages: Set<String>,
    ) {
        fun hides(uid: Int) = uids.binarySearch(uid) >= 0

        /** This entry with one more uid, or null when it already had it. */
        fun withUid(uid: Int): HiddenTargets? {
            val found = uids.binarySearch(uid)
            if (found >= 0) return null

            val slot = -found - 1
            val grown = IntArray(uids.size + 1)
            System.arraycopy(uids, 0, grown, 0, slot)
            grown[slot] = uid
            System.arraycopy(uids, slot, grown, slot + 1, uids.size - slot)
            return HiddenTargets(caller, grown, packages)
        }

        /** This entry with one more name, or null when it already had it. */
        fun withPackage(name: String): HiddenTargets? {
            if (name in packages) return null
            return HiddenTargets(caller, uids, packages + name)
        }
    }

    /**
     * The whole table: every caller, and therefore every answer. Nothing in it is mutated after it is
     * published, so a query that reads it holds a consistent answer without taking a lock.
     */
    private class HideTable(
        val byCaller: Map<Int, HiddenTargets>,
    )

    /** Serialises writers only; readers take the published reference and hold nothing. */
    private val tableLock = Any()

    @Volatile private var hideTable = HideTable(emptyMap())

    /**
     * The packages a uid runs. Resolving them is a query to the package manager, and the hooks ask
     * for them on every call, so the answer is kept until the configuration changes and the table
     * this answers for is dropped with it. What the answer decides -- whether any of those packages
     * is hooked -- is asked again on every call.
     */
    private val uidPackages = ConcurrentHashMap<Int, Array<String>>()

    /**
     * Package name to uid, resolved once. A name the resolver cannot place is remembered as such:
     * one attempt per name rather than one per query, and the answer stays exact because such a pair
     * is kept by name.
     */
    private val packageUids = ConcurrentHashMap<String, Int>()

    private val packagesWithoutUid: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun findCallerByUid(uid: Int) = hideTable.byCaller[uid]?.caller

    /**
     * Whether [uid] may see [query]. The signature is the one the hooks call -- they hold a caller uid
     * and a package name -- and the table is keyed by the uid behind that name.
     */

    /**
     * The pairs a push just handed to the kernel, kept here as well so the query path is a lookup and
     * nothing else: the same two reads whether the name is hidden or absent, with no template walk and
     * no binder call in between -- a difference in work is a difference a caller can time.
     */

    /** The absent name of every hidden target, computed once when the policy is published, so a
     * hidden name and a name that is not both cost one map read here and nothing else. */

    /**
     * Publishes the whole policy at once: the uid answers and the name answers are one object, so a
     * caller can never see one pass's targets with another pass's names.
     */
    fun publishPolicy(
        pairs: IntArray,
        tables: Map<Int, HideTables>,
    ) {
        val byCaller = HashMap<Int, MutableList<Int>>()
        var at = 0
        while (at + 1 < pairs.size) {
            byCaller.getOrPut(pairs[at]) { ArrayList() }.add(pairs[at + 1])
            at += 2
        }
        val built = HashMap<Int, IntArray>(byCaller.size)
        for ((caller, targets) in byCaller) {
            val sorted = targets.toIntArray()
            sorted.sort()
            built[caller] = sorted
        }
        if (built.isEmpty() && tables.isEmpty()) return
        snapshot = HideSnapshot(built, tables)
    }

    /** One policy pass, published whole: both answers a caller can ask for come from this object. */
    class HideSnapshot(
        val targets: Map<Int, IntArray>,
        val tables: Map<Int, HideTables>,
    )

    @Volatile
    private var snapshot = HideSnapshot(emptyMap(), emptyMap())

    /**
     * The table a query reads, and the only one it reads: for every caller, the two sets that answer
     * it -- the names that caller may not see, and the names that are installed at all. Both are
     * decided when the policy is published, so a query is two set reads and nothing else, and a name
     * that is hidden and a name that is not installed are answered from the same pair of sets.
     *
     * Replaced whole and never edited in place, like the table above: a query takes the reference
     * once and walks a table nothing can change under it.
     */
    fun publishHideTables(tables: Map<Int, HideTables>) {
        if (tables.isEmpty()) return
        val current = snapshot
        snapshot = HideSnapshot(current.targets, tables)
    }

    /**
     * What [callerUid] may see, or null when no policy answered for it: one map read, and the same
     * read whether that caller has a table or not.
     */
    fun hideTablesFor(callerUid: Int): HideTables? = snapshot.tables[callerUid]

    /**
     * Whether [callerUid] may see the packages behind [targetUid]. The uid channel asks this: a
     * package's uid and its name are two ways to the same answer, and both read the table the push
     * published, so neither can be timed against the other.
     */

    /** Whether [callerUid] may see the package named [name]. */
    fun isNameHidden(
        callerUid: Int,
        name: String,
    ): Boolean = snapshot.tables[callerUid]?.hidden?.contains(name) == true

    /** Whether [name] is hidden from any caller: the change history is not per caller. */
    fun isHiddenFromAnybody(name: String): Boolean = snapshot.tables.values.any { name in it.hidden }

    fun isTargetUidHidden(
        callerUid: Int,
        targetUid: Int,
    ): Boolean {
        // A package is never hidden from itself: answering "no such uid" for its own uid would take
        // its identity away from it.
        if (callerUid == targetUid) return false
        val targets = snapshot.targets[callerUid] ?: return false
        return targets.binarySearch(targetUid) >= 0
    }

    fun shouldHideFromUid(
        uid: Int,
        query: String?,
    ): Boolean? {
        if (query == null) return null

        val byCaller = snapshot.targets
        if (byCaller.isNotEmpty()) {
            val targets = byCaller[uid] ?: return false
            // -1 when the name is not a package this device has: the search still runs, so a hidden
            // name and an absent one cost the same read.
            return targets.binarySearch(uidOf(query) ?: -1) >= 0
        }
        if (query == null) return null

        val targets = hideTable.byCaller[uid] ?: return false
        val targetUid = uidOf(query)

        // Both halves are asked, and that is what keeps one answer: a pair written while its uid was
        // unknown sits in the package set, and a resolver that learns that uid later must not make
        // the pair unfindable -- so the name is still checked when the uid is known.
        return (targetUid != null && targets.hides(targetUid)) || query in targets.packages
    }

    /**
     * The salt every absent name is computed from. Drawn once, when the service starts, and never
     * handed out: a name is the asked one's bytes mixed with this value, so the query path computes
     * a name and remembers nothing, one package keeps its name for as long as this object lives, and
     * the name cannot be read back as the package it was computed from.
     *
     * It is drawn here rather than in [HideName] because this object is the one the service makes
     * once and hands to the hooks: the salt lives exactly as long as the service does, a cleared
     * table keeps it, and [HideName] stays a computation the tests can run with a salt they pick.
     */
    private val salt = ThreadLocalRandom.current().nextInt()

    /** The name a hidden lookup carries: one computation, no cache and no draw on the query path. */
    fun absentNameFor(asked: String): String = absentNames.getOrPut(asked) { HideName.absent(asked, salt) }

    private val absentNames = ConcurrentHashMap<String, String>()

    /**
     * Records that [uid], which runs [caller], may not see [query]. The table is rebuilt around the
     * one entry that changed and published; a query that reads the old one gets the answer it would
     * have got a moment earlier, which is what it would have got anyway.
     */
    fun putShouldHideUidCache(
        uid: Int,
        caller: String,
        query: String,
    ) {
        val targetUid = uidOf(query)

        synchronized(tableLock) {
            val targets = hideTable.byCaller[uid]
            val merged =
                when {
                    targets == null && targetUid != null -> HiddenTargets(caller, intArrayOf(targetUid), emptySet())
                    targets == null -> HiddenTargets(caller, IntArray(0), setOf(query))
                    targetUid != null -> targets.withUid(targetUid)
                    else -> targets.withPackage(query)
                } ?: return

            hideTable = HideTable(HashMap(hideTable.byCaller).apply { put(uid, merged) })
        }
    }

    /**
     * The uid behind a package name. Two packages that are the same application -- one uid, several
     * names -- are one row here, which is what the kernel's own policy pairs are: it hides a uid, not
     * a name.
     */
    private fun uidOf(packageName: String): Int? {
        packageUids[packageName]?.let { return it }
        if (packageName in packagesWithoutUid) return null

        val uid = packageUidResolver(packageName)?.takeIf { it > 0 }
        if (uid == null) {
            packagesWithoutUid.add(packageName)
            return null
        }

        packageUids.putIfAbsent(packageName, uid)
        return uid
    }

    fun callingAppsOf(
        uid: Int,
        resolve: () -> Array<String>?,
    ): Array<String>? {
        uidPackages[uid]?.let { return it }

        // A read that failed is not an answer: caching it would turn one bad lookup into "this uid
        // runs nothing" until the package list changes.
        val resolved = resolve() ?: return null

        return uidPackages.putIfAbsent(uid, resolved) ?: resolved
    }

    /*
     * The counters. A filtered query adds to one accumulator and is done; the service folds them into
     * the ledger it persists and hands to the manager, and empties them, every two seconds.
     */

    private val pendingCounts = ConcurrentHashMap<String, AtomicLongArray>()

    /** Guards the ledger the accumulators are folded into, and the two snapshots of it. */
    private val filterCountLock = Any()

    private var filterHolder = FilterHolder()

    private fun pendingOf(caller: String) = pendingCounts.computeIfAbsent(caller) { AtomicLongArray(PENDING_KINDS) }

    private fun pendingTotal(): Long = pendingCounts.values.sumOf { it.get(0) + it.get(1) }

    /** The total as it stands: what the last flush landed plus what has been counted since. */
    fun filterCountSnapshot(): Int = synchronized(filterCountLock) { filterHolder.totalCount + pendingTotal() }.toInt()

    /** The ledger as it stands, with the counts not yet flushed included: a reader sees every count. */
    fun filterCountsSnapshot(): String =
        synchronized(filterCountLock) {
            val merged = FilterHolder(filterHolder.filterCounts.mapValuesTo(mutableMapOf()) { it.value.copy() })

            pendingCounts.forEach { (caller, deltas) ->
                val count = merged.filterCounts.getOrPut(caller) { FilterHolder.FilterCount() }
                count.packageManagerCount += deltas.get(0).toInt()
                count.activityLaunchCount += deltas.get(1).toInt()
            }

            merged.toString()
        }

    /**
     * Empties the ledger a reader is shown, and the accumulators with it: an increment that lands
     * after this is a count made after the clear, and the ones that landed before it are the counts
     * being dropped.
     */
    fun clearFilterCounts() {
        pendingCounts.values.forEach {
            it.set(0, 0L)
            it.set(1, 0L)
        }

        synchronized(filterCountLock) {
            filterHolder = FilterHolder()
        }
    }

    /** Merge persisted totals once during bootstrap, preserving increments already seen this boot. */
    fun mergeFilterCounts(taken: FilterHolder) {
        synchronized(filterCountLock) {
            taken.filterCounts.forEach { (caller, counts) ->
                val kept = filterHolder.filterCounts.getOrPut(caller) { FilterHolder.FilterCount() }
                kept.packageManagerCount += counts.packageManagerCount
                kept.activityLaunchCount += counts.activityLaunchCount
            }
        }
    }

    /**
     * Lands the counts made since the last call in the ledger and empties the accumulators that
     * carried them, so that each count is written, sent and persisted once. Returns whether anything
     * landed.
     *
     * A caller's accumulator is taken with getAndSet, so an increment that arrives while the flush
     * runs is either in this delta or in the next one, and never in neither. The slot itself is left
     * in place: removing it would race an increment that already holds the array, and there is one
     * slot per caller rather than one per query.
     */
    fun flushPendingCounts(): Boolean {
        var landed = false

        pendingCounts.forEach { (caller, deltas) ->
            val packageManager = deltas.getAndSet(0, 0L)
            val activityLaunch = deltas.getAndSet(1, 0L)
            if (packageManager == 0L && activityLaunch == 0L) return@forEach

            synchronized(filterCountLock) {
                val count = filterHolder.filterCounts.getOrPut(caller) { FilterHolder.FilterCount() }
                count.packageManagerCount += packageManager.toInt()
                count.activityLaunchCount += activityLaunch.toInt()
            }

            landed = true
        }

        return landed
    }

    /**
     * Drops everything resolved from the configuration: the hide table and the uids those names
     * resolved to. Only a configuration change calls this, and the next query builds the table again
     * lazily; a package change does not, because dropping the table there makes hiding intermittent
     * for as long as the table takes to be rebuilt.
     */
    fun clearUidCache() {
        // Neither published table is emptied here: the next push replaces them whole, and a table that
        // is emptied between two pushes hides nothing until that push arrives -- which a scan sees.
        synchronized(tableLock) {
            hideTable = HideTable(emptyMap())
        }

        packageUids.clear()
        packagesWithoutUid.clear()
        uidPackages.clear()
    }

    fun increaseFilterCount(
        uid: Int?,
        amount: Int = 1,
        filterType: FilterHolder.FilterType,
    ) {
        if (uid == null || amount < 0) return

        val caller = findCallerByUid(uid) ?: return

        count(caller, amount, filterType)
    }

    fun increaseFilterCount(
        caller: String?,
        amount: Int = 1,
        filterType: FilterHolder.FilterType,
    ) {
        if (caller == null || amount < 0) return

        count(caller, amount, filterType)
    }

    /** The whole of a filtered query's bookkeeping: one map read and one add. */
    private fun count(
        caller: String,
        amount: Int,
        filterType: FilterHolder.FilterType,
    ) {
        pendingOf(caller).addAndGet(filterType.ordinal, amount.toLong())
    }
}

/**
 * What one caller is answered with: the names it may not see, and the names that are installed at all.
 *
 * Both sets are read by the query path and neither is changed after it is published, so a query holds
 * the same two answers however many pushes land while it runs. A name in neither set is a name nothing
 * has, and such a name is given the same form a hidden one is given -- see xorName, where the hook
 * writes it -- so the two answers cannot be told apart.
 */
data class HideTables(
    val hidden: Set<String>,
    val installed: Set<String>,
)
