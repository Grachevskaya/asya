package io.github.grachevskaya.asya.common

import android.content.pm.ApplicationInfo
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.Build
import io.github.grachevskaya.asya.common.CollectionUtils.removeIf
import kotlinx.serialization.json.Json

object Utils {
    /**
     * A random lower case hexadecimal string: a name that neither repeats nor looks made up, for the
     * queries this module answers by pointing them at something that does not exist.
     */
    fun generateRandomHex(length: Int): String =
        buildString(length) {
            repeat(length) { append("0123456789abcdef".random()) }
        }

    fun generateRandomString(
        length: Int,
        allowedChars: List<Char>,
    ): String =
        (0 until length)
            .map { allowedChars.random() }
            .joinToString("")

    fun <T> binderLocalScope(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        val result = block()
        Binder.restoreCallingIdentity(identity)
        return result
    }

    fun IPackageManager.getInstalledApplicationsCompat(
        flags: Long,
        userId: Int,
    ): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.getInstalledApplications(flags, userId)
        } else {
            this.getInstalledApplications(flags.toInt(), userId)
        }.list

    fun IPackageManager.getPackageUidCompat(
        packageName: String,
        flags: Long,
        userId: Int,
    ): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.getPackageUid(packageName, flags, userId)
        } else {
            this.getPackageUid(packageName, flags.toInt(), userId)
        }

    fun IPackageManager.getPackageInfoCompat(
        packageName: String,
        flags: Long,
        userId: Int,
    ): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.getPackageInfo(packageName, flags, userId)
        } else {
            this.getPackageInfo(packageName, flags.toInt(), userId)
        }

    fun String?.startsWithMultiple(vararg targets: String): Boolean {
        if (isNullOrEmpty() || targets.isEmpty()) return false

        return targets.any { startsWith(it) }
    }

    fun String?.endsWithMultiple(vararg targets: String): Boolean {
        if (isNullOrEmpty() || targets.isEmpty()) return false

        return targets.any { endsWith(it) }
    }

    fun String?.containsMultiple(vararg targets: String): Boolean {
        if (isNullOrEmpty() || targets.isEmpty()) return false

        return targets.any { contains(it) }
    }

    fun ResolveInfo.getPackageName(): String =
        resolvePackageName
            ?: activityInfo?.packageName
            ?: serviceInfo?.packageName
            ?: providerInfo!!.packageName

    fun JsonConfig.cleanRemnantsFromConfig() {
        // STEP 1: Remove empty app templates
        templates.removeIf { _, template -> template.appList.isEmpty() }

        // STEP 2: Remove mismatching items
        for (app in scope.values) {
            app.applyTemplates.removeIf { !templates.containsKey(it) }
        }
    }

    fun getUserFromCallingUid(uid: Int) = uid / 100000

    fun PackageManager.isPackageAvailable(packageName: String) =
        try {
            getPackageUid(packageName, 0) >= 0
        } catch (_: Throwable) {
            false
        }

    fun ApplicationInfo.isSystemApp() =
        flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

    val conflictedModules =
        arrayOf(
            "com.tsng.hidemyapplist",
            "com.google.android.hmal",
        )

    val encoder =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
}
