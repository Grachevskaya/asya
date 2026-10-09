package io.github.grachevskaya.asya.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Binder
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import io.github.grachevskaya.asya.BuildConfig
import io.github.grachevskaya.asya.MyApp.Companion.asyaApp
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.util.ThemeUtils.asDrawable
import io.github.grachevskaya.asya.ui.util.asComponentName
import io.github.grachevskaya.asya.ui.util.get
import io.github.grachevskaya.asya.util.ConfigUtils.getLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

object PackageHelper {
    const val TAG = "PackageHelper"

    class PackageCache(
        val info: PackageInfo,
        val label: String,
        val userIds: HashSet<Int>,
    )

    object Comparators {
        val byLabel =
            Comparator<String> { o1, o2 ->
                try {
                    val locale = getLocale()
                    val n1 = loadAppLabel(o1).lowercase(locale)
                    val n2 = loadAppLabel(o2).lowercase(locale)
                    Collator.getInstance(locale).compare(n1, n2)
                } catch (_: Throwable) {
                    byPackageName.compare(o1, o2)
                }
            }
        val byPackageName =
            Comparator<String> { o1, o2 ->
                val locale = getLocale()
                val n1 = o1.lowercase(locale)
                val n2 = o2.lowercase(locale)
                Collator.getInstance(locale).compare(n1, n2)
            }
        val byInstallTime =
            Comparator<String> { o1, o2 ->
                try {
                    val n1 = loadPackageInfo(o1).firstInstallTime
                    val n2 = loadPackageInfo(o2).firstInstallTime
                    n2.compareTo(n1)
                } catch (_: Throwable) {
                    byPackageName.compare(o1, o2)
                }
            }
        val byUpdateTime =
            Comparator<String> { o1, o2 ->
                try {
                    val n1 = loadPackageInfo(o1).lastUpdateTime
                    val n2 = loadPackageInfo(o2).lastUpdateTime
                    n2.compareTo(n1)
                } catch (_: Throwable) {
                    byPackageName.compare(o1, o2)
                }
            }
    }

    private val packageCache = MutableSharedFlow<Map<String, PackageCache>>(replay = 1)
    val appList = MutableSharedFlow<List<String>>(replay = 1)

    val isRefreshing = MutableSharedFlow<Boolean>(replay = 1)

    val refreshing get() = isRefreshing.replayCache.isEmpty() || isRefreshing.get()

    init {
        invalidateCache()
    }

    fun invalidateCache(onFinished: ((Throwable?) -> Unit)? = null) {
        asyaApp.globalScope
            .launch {
                isRefreshing.emit(true)
                val cache =
                    withContext(Dispatchers.IO) {
                        val pm = asyaApp.packageManager
                        val profiles =
                            ServiceClient.userProfiles
                                ?: intArrayOf(Binder.getCallingUserHandle().hashCode())

                        mutableMapOf<String, PackageCache>().also { cacheMap ->
                            for (userProfile: Int in profiles) {
                                val packages = ServiceClient.getPackageNames(userProfile) ?: arrayOf<String>()
                                for (packageName in packages) {
                                    if (packageName in Constants.packagesShouldNotHide) continue
                                    val packageInfo =
                                        try {
                                            ServiceClient.getPackageInfo(packageName, userProfile)!!
                                        } catch (e: Throwable) {
                                            ServiceClient.log(
                                                Log.DEBUG,
                                                TAG,
                                                "Cannot get package details for $packageName\n${e.stackTraceToString()}",
                                            )

                                            continue
                                        }
                                    packageInfo.applicationInfo?.let { appInfo ->
                                        val label = pm.getApplicationLabel(appInfo).toString()
                                        if (cacheMap.containsKey(packageName)) {
                                            cacheMap[packageName]?.userIds?.add(userProfile)
                                        } else {
                                            cacheMap[packageName] =
                                                PackageCache(
                                                    packageInfo,
                                                    label,
                                                    HashSet<Int>().apply { add(userProfile) },
                                                )
                                        }
                                    }
                                }
                            }
                        }
                    }
                snapshot = cache
                packageCache.emit(cache)
                appList.emit(cache.keys.toList())
                isRefreshing.emit(false)
                prefetchIcons()
            }.apply {
                if (onFinished != null) {
                    invokeOnCompletion(onFinished)
                }
            }
    }

    suspend fun sortList(firstComparator: Comparator<String>) {
        var comparator =
            when (PrefManager.appFilter_sortMethod) {
                PrefManager.SortMethod.BY_LABEL -> Comparators.byLabel
                PrefManager.SortMethod.BY_PACKAGE_NAME -> Comparators.byPackageName
                PrefManager.SortMethod.BY_INSTALL_TIME -> Comparators.byInstallTime
                PrefManager.SortMethod.BY_UPDATE_TIME -> Comparators.byUpdateTime
            }
        if (PrefManager.appFilter_reverseOrder) comparator = comparator.reversed()
        val list = appList.first().sortedWith(firstComparator.then(comparator))
        appList.emit(list)
    }

    /**
     * The last build, for the readers.
     *
     * A bind asks for a label, a package info or a user list and gets it from here: no coroutine is
     * started to answer a row, which is what a list of five hundred rows was doing -- five hundred
     * waits on the main thread, and the list that came of it.
     */
    @Volatile
    private var snapshot: Map<String, PackageCache> = emptyMap()

    fun exists(packageName: String) = snapshot.containsKey(packageName)

    fun loadPackageInfo(packageName: String): PackageInfo = snapshot[packageName]!!.info

    fun loadAppLabel(packageName: String): String = snapshot[packageName]?.label ?: packageName

    fun loadUserIds(packageName: String): Set<Int> = snapshot[packageName]?.userIds ?: setOf()

    fun isSystem(packageName: String): Boolean =
        snapshot[packageName]
            ?.info
            ?.applicationInfo
            ?.flags
            ?.and(ApplicationInfo.FLAG_SYSTEM) != 0

    /*
     * Icons are not part of that build. Loading all of them is loading five hundred bitmaps nothing
     * is looking at, and it is what made a refresh take as long as it did. A row asks for the icon it
     * is about to draw: what has been drawn is kept in a small cache, and what has not is loaded off
     * the main thread and handed back when it is ready.
     */
    private val iconCache = android.util.LruCache<String, Drawable>(600)

    /** The icon if it is already in hand, and the system's stand-in if it is not. */
    fun loadAppIcon(packageName: String): Drawable = iconCache.get(packageName) ?: standInIcon()

    private fun standInIcon(): Drawable =
        try {
            android.R.drawable.sym_def_app_icon
                .asDrawable(asyaApp)
        } catch (_: Throwable) {
            asyaApp.getDrawable(android.R.drawable.sym_def_app_icon)!!
        }

    /** Loads the icon if it is not in hand, and calls back with it once it is. */
    fun loadAppIconAsync(
        packageName: String,
        onReady: (Drawable) -> Unit,
    ) {
        iconCache.get(packageName)?.let {
            onReady(it)
            return
        }

        asyaApp.globalScope.launch {
            val icon = withContext(Dispatchers.IO) { loadAppIconNow(packageName) }
            iconCache.put(packageName, icon)
            withContext(Dispatchers.Main) { onReady(icon) }
        }
    }

    /**
     * Loads the icons for everything that is installed, in the background, as the manager opens.
     *
     * The list itself is drawn from whatever is in hand and asks for what is not, but the first list
     * anyone opens should not be the thing that discovers five hundred icons: by the time it is open,
     * they are here. It is a prefetch and not a requirement -- a row still loads what it needs.
     */
    fun prefetchIcons() {
        asyaApp.globalScope.launch {
            withContext(Dispatchers.IO) {
                for (packageName in snapshot.keys) {
                    if (iconCache.get(packageName) != null) continue
                    iconCache.put(packageName, loadAppIconNow(packageName))
                }
            }
        }
    }

    private fun loadAppIconNow(packageName: String): Drawable =
        try {
            val appInfo = snapshot[packageName]?.info?.applicationInfo
            if (appInfo != null) {
                loadAppIconFromAppInfo(appInfo)
            } else {
                asyaApp.packageManager.getApplicationIcon(packageName)
            }
        } catch (cause: Throwable) {
            ServiceClient.log(Log.WARN, TAG, "No icon for $packageName: ${cause.message}")
            android.R.drawable.sym_def_app_icon
                .asDrawable(asyaApp)
        }

    fun loadAppIconFromAppInfo(appInfo: ApplicationInfo): Drawable {
        // The manager's own icon is not looked up anywhere: the manager is not an installed app, so
        // the package manager has no record of it, and asking for one produced a name-not-found and an
        // activity-manager line about a package that does not exist. The apk's own resource is its
        // icon, and this branch is the one that means "the manager itself".
        return if (appInfo.packageName == BuildConfig.APPLICATION_ID) {
            R.mipmap.ic_launcher.asDrawable(asyaApp)
        } else {
            try {
                appInfo.loadIcon(asyaApp.packageManager)
            } catch (x: Throwable) {
                ServiceClient.log(Log.ERROR, TAG, x.stackTraceToString())

                return ResourcesCompat.getDrawable(
                    asyaApp.resources,
                    android.R.drawable.sym_def_app_icon,
                    asyaApp.theme,
                )!!
            }
        }
    }


}
