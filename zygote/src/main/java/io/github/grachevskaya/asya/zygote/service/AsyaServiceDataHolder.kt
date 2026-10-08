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

    fun clearUidCache() = uidHideCache.clear()

    fun increaseFilterCount(
        uid: Int?,
        amount: Int = 1,
        filterType: FilterHolder.FilterType,
        writeFilterCount: () -> Unit,
    ) {
        if (uid == null || amount < 1) return

        val caller = findCallerByUid(uid) ?: return

        return increaseFilterCount(caller, amount, filterType, writeFilterCount)
    }

    fun increaseFilterCount(
        caller: String?,
        amount: Int = 1,
        filterType: FilterHolder.FilterType,
        writeFilterCount: () -> Unit,
    ) {
        if (caller == null || amount < 1) return

        synchronized(filterCountLock) {
            val filterCount = filterHolder.filterCounts.getOrPut(caller) { FilterHolder.FilterCount() }

            when (filterType) {
                FilterHolder.FilterType.PACKAGE_MANAGER -> filterCount.packageManagerCount += amount
                FilterHolder.FilterType.ACTIVITY_LAUNCH -> filterCount.activityLaunchCount += amount
            }
        }

        writeFilterCount()
    }
}
