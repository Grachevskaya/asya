package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.FilterHolder
import java.util.concurrent.ConcurrentHashMap

/*
 * What the hooks remember between calls.
 *
 * The map is read on every package query -- it is the path a query takes when a target asks whether
 * another package exists -- so a lookup is one hash and the queries answered for a uid are hashed as
 * well: a list would be walked for each of them. Both structures are concurrent, because the query
 * that fills an entry and the query that reads it are not the same thread.
 */
class AsyaServiceDataHolder {
    private class Hidden(
        val caller: String,
    ) {
        val queries: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val filterCountLock = Any()
    private val uidHideCache = ConcurrentHashMap<Int, Hidden>()

    /**
     * The packages a uid runs. Resolving them is a query to the package manager, and the hooks ask
     * for them on every call, so the answer is kept until the package list changes: that is when the
     * uid map is dropped, because both are resolved from the same list. What the answer decides --
     * whether any of those packages is hooked -- is asked again on every call.
     */
    private val uidPackages = ConcurrentHashMap<Int, Array<String>>()

    var filterHolder = FilterHolder()
        internal set

    fun findCallerByUid(uid: Int) = uidHideCache[uid]?.caller

    fun shouldHideFromUid(
        uid: Int,
        query: String?,
    ): Boolean? {
        if (query == null) return null

        return uidHideCache[uid]?.queries?.contains(query) == true
    }

    fun putShouldHideUidCache(
        uid: Int,
        caller: String,
        query: String,
    ) {
        uidHideCache.getOrPut(uid) { Hidden(caller) }.queries.add(query)
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

    /**
     * Takes every count gathered so far and empties the ledger: what is handed over is added to the
     * manager's own total, so no count is ever held by both sides.
     */
    fun takeFilterCounts(): FilterHolder {
        synchronized(filterCountLock) {
            val taken = filterHolder
            filterHolder = FilterHolder()

            return taken
        }
    }

    /** The total as it stands, read under the lock the writers take. */
    fun filterCountSnapshot(): Int =
        synchronized(filterCountLock) { filterHolder.totalCount }

    /** The ledger as it stands, read under the same lock. */
    fun filterCountsSnapshot(): String =
        synchronized(filterCountLock) { filterHolder.toString() }

    /** Empties the ledger a reader is shown, under the same lock every other touch takes. */
    fun clearFilterCounts() {
        synchronized(filterCountLock) {
            filterHolder = FilterHolder()
        }
    }

    /** Puts a taken ledger back: the hand that carried it did not reach the manager. */
    fun mergeFilterCounts(taken: FilterHolder) {
        synchronized(filterCountLock) {
            taken.filterCounts.forEach { (caller, counts) ->
                val kept = filterHolder.filterCounts.getOrPut(caller) { FilterHolder.FilterCount() }
                kept.packageManagerCount += counts.packageManagerCount
                kept.activityLaunchCount += counts.activityLaunchCount
            }
        }
    }

    fun clearUidCache() {
        uidHideCache.clear()
        uidPackages.clear()
    }

    fun increaseFilterCount(
        uid: Int?,
        amount: Int = 1,
        filterType: FilterHolder.FilterType,
    ) {
        if (uid == null || amount < 1) return

        val caller = findCallerByUid(uid) ?: return

        return increaseFilterCount(caller, amount, filterType)
    }

    /** Told about every increment, so the manager can keep the running total itself. */
    var onCount: (String, Int, FilterHolder.FilterType) -> Unit = { _, _, _ -> }

    fun increaseFilterCount(
        caller: String?,
        amount: Int = 1,
        filterType: FilterHolder.FilterType,
        pushDelta: (String, Int, FilterHolder.FilterType) -> Unit = { name, count, kind ->
            onCount(name, count, kind)
        },
    ) {
        if (caller == null || amount < 1) return

        synchronized(filterCountLock) {
            val filterCount = filterHolder.filterCounts.getOrPut(caller) { FilterHolder.FilterCount() }

            when (filterType) {
                FilterHolder.FilterType.PACKAGE_MANAGER -> filterCount.packageManagerCount += amount
                FilterHolder.FilterType.ACTIVITY_LAUNCH -> filterCount.activityLaunchCount += amount
            }
        }

        pushDelta(caller, amount, filterType)

    }
}
