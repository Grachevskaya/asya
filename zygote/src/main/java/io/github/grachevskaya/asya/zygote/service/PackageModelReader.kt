package io.github.grachevskaya.asya.zygote.service

import android.content.pm.ApplicationInfo
import android.os.Build
import io.github.grachevskaya.asya.common.Utils.getPackageInfoCompat
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import java.io.File

/*
 * What the module knows about the packages it hides, read out of the framework by reflection: the
 * model behind a package, the native libraries it declares and preloads, the flags of a service and
 * the isolation class an app falls in.
 */

/**
 * The package table, resolved once and kept: resolving it per lookup costs seconds across
 * five hundred packages, and the table does not move while the service runs. It is the parsed model -- AndroidPackage instances -- because that is where a
 * service's declared properties live.
 */

internal fun AsyaService.packageModel(): Map<*, *>? {
    PackageModelReader.cachedPackageModel?.let { return it }
        /*
         * One route, and it is AOSP's own: the object a system process gets from ServiceManager
         * for "package" is PackageManagerService.IPackageManagerImpl, and that is an inner class
         * of the service --
         *   PackageManagerService.java: public class IPackageManagerImpl extends IPackageManagerBase
         *   PackageManagerService.java: final WatchedArrayMap<String, AndroidPackage> mPackages
         * -- so this$0 is the service instance and mPackages is its package table. No vendor
         * class, no alternative path: if this is not it, the module says so and stops guessing.
         */
    val found =
        runCatching {
            val service =
                pms.javaClass
                    .getDeclaredField("this$0")
                    .apply { isAccessible = true }
                    .get(pms)
                    ?: return@runCatching null
            service.javaClass
                .getDeclaredField("mPackages")
                .apply { isAccessible = true }
                .get(service) as? Map<*, *>
        }.getOrNull()
    if (found !=
        null
    ) {
        PackageModelReader.cachedPackageModel = found
    } else {
        logW(TAG) { "No package table: every native library declaration will read as empty" }
    }
    return found
}

/**
 * Native library names this package declares. The parsed model holds them as
 * Map<String, PackageManager.Property> -- key is the property name, the value carries
 * the string -- for the package and for each service. Anything unreadable returns null
 * and the caller falls back to every .so of the app.
 */
internal fun AsyaService.declaredNativeLibNames(packageName: String): Set<String>? =
    runCatching {
        val packages = packageModel() ?: return@runCatching null
        PackageModelReader.packagesFoundForLog = packages.size
        val androidPackage = packages[packageName]
        PackageModelReader.androidPackageForLog = androidPackage ?: return@runCatching null

        if (!PackageModelReader.diagnosedNativeModel) {
            PackageModelReader.diagnosedNativeModel = true
            runCatching {
                val cls = androidPackage.javaClass
                val methods =
                    cls.methods
                        .map { it.name }
                        .filter {
                            it.contains("ropert", true) ||
                                it.contains("ative", true) ||
                                it.contains("ervice", true) ||
                                it.contains("ygote", true)
                        }.sorted()
                        .take(40)
                val fields =
                    cls.declaredFields
                        .map { it.name }
                        .filter {
                            it.contains("ropert", true) ||
                                it.contains("ative", true) ||
                                it.contains("ervice", true) ||
                                it.contains("ygote", true)
                        }.sorted()
                        .take(40)
                logI(TAG) {
                    "Native-model diagnosis: class=" + cls.name +
                        " methods=" + methods +
                        " fields=" + fields
                }
            }.onFailure { logW(TAG, it) { "Native-model diagnosis failed" } }
        }

        val names = LinkedHashSet<String>()
        val wanted =
            setOf(
                "android.app.PROPERTY_NATIVE_SERVICE_LIBRARY_NAME",
                "android:zygotePreloadNativeLib",
                "zygotePreloadNativeLib",
            )

        fun harvest(properties: Any?) {
            val map = properties as? Map<*, *> ?: return
            map.forEach { key, value ->
                val name = key as? String ?: return@forEach
                if (name !in wanted) return@forEach
                val text =
                    runCatching {
                        value?.javaClass?.getMethod("getString")?.invoke(value) as? String
                    }.getOrNull() ?: (value as? String)
                if (PackageModelReader.propertyKeysForLog.size < 12) PackageModelReader.propertyKeysForLog.add(name)
                if (!text.isNullOrEmpty()) names.add(text)
            }
        }

        for (accessor in arrayOf("getProperties", "getPackageProperties", "getManifestProperties")) {
            runCatching {
                harvest(androidPackage.javaClass.getMethod(accessor).invoke(androidPackage))
            }
        }
        runCatching {
            var cls: Class<*>? = androidPackage.javaClass
            var services: Any? = null
            while (cls != null && services == null) {
                services = runCatching { cls.getMethod("getServices").invoke(androidPackage) }.getOrNull()
                cls = cls.superclass
            }
            (services as? Collection<*>)?.forEach { service ->
                if (service == null) return@forEach
                runCatching {
                    harvest(service.javaClass.getMethod("getProperties").invoke(service))
                }
            }
        }
        if (names.isNotEmpty()) {
            logI(TAG) { "Declared native libs for " + packageName + ": " + names }
            return@runCatching names
        }

        if (!PackageModelReader.declaredLogOnce) {
            PackageModelReader.declaredLogOnce = true
            // Not a failure of the read: the read worked, and this package declares no native
            // library to preload. Whoever is named here is only the first package that was asked.
            logI(TAG) {
                "No declared native lib on " + packageName +
                    ", read itself: pmsClass=" + pms.javaClass.name +
                    ", packagesFound=" + (PackageModelReader.packagesFoundForLog) +
                    ", packageFound=" + (PackageModelReader.androidPackageForLog != null) +
                    ", packageKeys=" + (PackageModelReader.propertyKeysForLog.take(6))
            }
        }
        null
    }.getOrNull()

/** The native library the package asks to be preloaded into zygote, if it declares one. */
internal fun AsyaService.appNativePreloadLib(app: ApplicationInfo): String? =
    runCatching {
        val field = ApplicationInfo::class.java.getField("zygotePreloadNativeLib")
        (field.get(app) as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

internal fun AsyaService.serviceFlagsFromModel(packageName: String): List<Int>? =
    runCatching {
        val map = packageModel() ?: return@runCatching null
        var cls: Class<*>?
        val pkg = map[packageName] ?: return@runCatching null

        val list = ArrayList<Int>()
        var services: Any? = null
        cls = pkg.javaClass
        while (cls != null && services == null) {
            services = runCatching { cls.getMethod("getServices").invoke(pkg) }.getOrNull()
            cls = cls.superclass
        }
        (services as? Collection<*>)?.forEach { component ->
            if (component == null) return@forEach
            val flags =
                runCatching {
                    component.javaClass.getMethod("getFlags").invoke(component) as? Int
                }.getOrNull()
            if (flags != null) list.add(flags)
        }
        if (list.isEmpty()) null else list
    }.getOrNull()

/**
 * Service flags that decide the class. Both values are read off the platform sources:
 * FLAG_ISOLATED_PROCESS = 0x2 (ServiceInfo and ProviderInfo), and FLAG_NATIVE_SERVICE =
 * 0x20, which ServiceInfo documents as "set from the android.R.attr#nativeService
 * attribute" and which exists only from API 37 -- so Android 12-16 always lands on the
 * apk-only class, where the child must open the apk to load its dex anyway.
 */

internal fun AsyaService.isolationClass(
    packageName: String,
    userId: Int,
): Int {
    val flags = (0x1 or 0x2 or 0x4 or 0x8).toLong() // GET_ACTIVITIES | RECEIVERS | SERVICES | PROVIDERS ✓
    val info = pms.getPackageInfoCompat(packageName, flags, userId) ?: return ISOLATION_NATIVE

        /*
         *       "Set from the android.R.attr#nativeService attribute."
         *     （frameworks/base/…/content/pm/ServiceInfo.java · android17-release ✓）
         *   "Services declared as android:nativeService=true … may not have the ART loaded
         *    inside their processes … Introduced in API 37."
         *
         */
    val isolated = 0x2 // FLAG_ISOLATED_PROCESS ✓
    val nativeBit = 0x20 // FLAG_NATIVE_SERVICE ✓

    fun isolated(flag: Int) = (flag and isolated) != 0

    fun nativeSvc(flag: Int) = (flag and nativeBit) != 0

    val services = info.services.orEmpty()
    val any =
        services.any { isolated(it.flags) } ||
            info.providers?.any { isolated(it.flags) } == true
    if (!any) {
        PackageModelReader.isolationCounts[ISOLATION_NONE]++
        return ISOLATION_NONE
    }

        /*
         * Native or regular? The service's own flags answer it: FLAG_ISOLATED_PROCESS (0x2)
         * together with FLAG_NATIVE_SERVICE (0x20, "set from the android.R.attr#nativeService
         * attribute", API 37 and later). When the parsed model cannot be read at all, an app
         * that declares a native library name still counts as native -- the conservative side.
         */

        /*
         * Native if either source says so. The flags are authoritative when the model can be
         * read, but on this device they said no for every app (native=0) while applications
         * that plainly declare a native service library -- uidscan declares libnatsvc.so -- were
         * left out; the declaration is the second source and it is what makes the difference.
         */
    val nativeByFlags =
        serviceFlagsFromModel(packageName)
            ?.any { isolated(it) && nativeSvc(it) } == true
    val nativeByDecl = !declaredNativeLibNames(packageName).isNullOrEmpty()
    val kind = if (nativeByFlags || nativeByDecl) ISOLATION_NATIVE else ISOLATION_APP
    PackageModelReader.isolationCounts[kind]++
    return kind
}

internal fun AsyaService.logIsolationClasses() {
    logI(TAG) {
        "Isolation classes: none=" + PackageModelReader.isolationCounts[ISOLATION_NONE] +
            " app=" + PackageModelReader.isolationCounts[ISOLATION_APP] +
            " native=" + PackageModelReader.isolationCounts[ISOLATION_NATIVE] +
            " (SDK=" + Build.VERSION.SDK_INT +
            ", zygote_next=" + File("/system/bin/zygote_next").exists() + ")"
    }
}

/*
 * The state this part owns: the package model it read, and the counters and caches the diagnosis of
 * an app keeps between calls.
 */
internal object PackageModelReader {
    /**
     *
     */
    internal val isolationCounts = IntArray(3)

    internal var diagnosedNativeModel = false

    internal var declaredLogOnce = false

    internal var packagesFoundForLog = -1

    internal var androidPackageForLog: Any? = null

    internal val propertyKeysForLog = ArrayList<String>()

    internal var cachedPackageModel: Map<*, *>? = null
}
