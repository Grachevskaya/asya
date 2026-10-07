package io.github.grachevskaya.asya.zygote.util

import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.os.ServiceManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.common.JsonConfig
import io.github.grachevskaya.asya.common.PropertyUtils
import io.github.grachevskaya.asya.common.Utils.binderLocalScope
import io.github.grachevskaya.asya.common.Utils.containsMultiple
import io.github.grachevskaya.asya.common.Utils.getPackageInfoCompat
import io.github.grachevskaya.asya.common.BuildConfig
import io.github.grachevskaya.asya.zygote.util.Logcat.logE
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logV
import io.github.grachevskaya.asya.zygote.util.ZLUtils.callMethod
import io.github.grachevskaya.asya.zygote.util.ZLUtils.findField
import java.io.File

object ServiceUtils {
    private const val TAG = "ServiceUtils"
    private val fileFlags = OsConstants.R_OK or OsConstants.W_OK

    @Throws(InterruptedException::class)
    fun waitForService(name: String?): IBinder? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return ServiceManager.waitForService(name)
        }

        var service: IBinder? = null
        var count = 0

        do {
            Thread.sleep(250)
        } while ((ServiceManager.getService(name).also { service = it }) == null && ++count < 100)

        return service
    }

    fun getPackageNameFromPackageSettings(packageSettings: Any?): String? {
        if (packageSettings == null) return null

        return try {
            callMethod(packageSettings, "getPackageName") as? String
        } catch (_: Throwable) {
            runCatching {
                findField(
                    packageSettings::class.java,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "mName" else "name"
                )?.apply { isAccessible = true }?.get(packageSettings) as? String
            }.getOrNull()
        }
    }

    fun getCallingApps(pms: IPackageManager): Array<String> {
        return getCallingApps(pms, Binder.getCallingUid())
    }

    fun getCallingApps(pms: IPackageManager, callingUid: Int): Array<String> {
        if (callingUid == Constants.UID_SYSTEM) return arrayOf()
        return binderLocalScope {
            pms.getPackagesForUid(callingUid)
        } ?: arrayOf()
    }

    /**
     * There is no manager app to verify.
     *
     * Upstream looks the manager up in the package manager and compares its signing certificate with
     * the one compiled into this module, because the service there hands it a binder and has to know
     * who it is talking to. This fork's manager is not an installed app at all -- it is an activity
     * of the host process, reading the same files this service writes -- so there is no identity to
     * check and nothing to compare. The value returned is the one upstream uses for "no manager".
     */

    fun clearStackTraces(throwableIn: Throwable?) {
        var throwable: Throwable? = throwableIn

        while (throwable != null) {
            val newTrace = throwable.stackTrace.filter { item ->
                !item.className.containsMultiple(
                    "BulkHooker",
                    "com.v7878",
                    "MethodHandle",
                ) && !item.fileName.containsMultiple(
                    "r8-map-id-",
                    "dex-id-",
                )
            }

            if (newTrace.size != throwable.stackTrace.size) {
                throwable.stackTrace = newTrace.toTypedArray()
            }

            throwable = throwable.cause
        }
    }

    fun isAppDataIsolationEnabled(config: JsonConfig) =
        PropertyUtils.isAppDataIsolationEnabled || config.altAppDataIsolation

    fun ensureFileIsRW(file: File, skipNoEntry: Boolean) {
        try {
            if (Os.access(file.absolutePath, fileFlags)) return
        } catch (cause: ErrnoException) {
            if (skipNoEntry && cause.errno == OsConstants.ENOENT) return
        } catch (_: Throwable) {}

        throw RemoteException("${file.absolutePath} is not accessible by the current UID")
    }
}
