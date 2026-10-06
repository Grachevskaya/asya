package io.github.grachevskaya.asya.zygote.service

import io.github.grachevskaya.asya.common.FilterHolder
import io.github.grachevskaya.asya.common.PresetCache
import io.github.grachevskaya.asya.common.RiskyPackageUtils

class AsyaServiceDataHolder {
    private val filterCountLock = Any()

    private val uidHideCache = mutableListOf<Triple<Int, String, MutableList<String>>>()

    var filterHolder = FilterHolder()
        internal set

    fun findCallerByUid(uid: Int) = uidHideCache.firstOrNull { it.first == uid }?.second

    fun shouldHideFromUid(uid: Int, query: String?): Boolean? {
        if (query == null) return null

        return uidHideCache.firstOrNull { it.first == uid && it.third.contains(query) } != null
    }

    fun putShouldHideUidCache(uid: Int, caller: String, query: String) {
        val findList = uidHideCache.firstOrNull { it.first == uid }
        if (findList != null) {
            findList.third.add(query)
        } else {
            uidHideCache.add(Triple(uid, caller, mutableListOf(query)))
        }
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
            if (!filterHolder.filterCounts.containsKey(caller)) {
                filterHolder.filterCounts[caller] = FilterHolder.FilterCount()
            }

            val filterCount = filterHolder.filterCounts[caller]!!
            when (filterType) {
                FilterHolder.FilterType.PACKAGE_MANAGER -> filterCount.packageManagerCount += amount
                FilterHolder.FilterType.ACTIVITY_LAUNCH -> filterCount.activityLaunchCount += amount
                FilterHolder.FilterType.INSTALLER -> filterCount.installerCount += amount
                FilterHolder.FilterType.SETTINGS -> filterCount.settingsCount += amount
                FilterHolder.FilterType.OTHERS -> filterCount.othersCount += amount
            }
        }

        writeFilterCount()
    }
}
