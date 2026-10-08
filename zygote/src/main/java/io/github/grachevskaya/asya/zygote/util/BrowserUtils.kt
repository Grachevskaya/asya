package io.github.grachevskaya.asya.zygote.util

import android.os.Build
import android.os.ServiceManager
import android.provider.Settings
import android.webkit.IWebViewUpdateService
import com.android.server.pm.PackageManagerService
import io.github.grachevskaya.asya.common.Utils.binderLocalScope
import io.github.grachevskaya.asya.zygote.util.ContextUtils.contentResolver
import io.github.grachevskaya.asya.zygote.util.ContextUtils.packageManager
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.ZLUtils.callMethodWithTypes
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getObjectField
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.WEBVIEW_PROVIDER_KEY
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.WEBVIEW_UPDATE_SERVICE

object BrowserUtils {
    const val TAG = "BrowserUtils"

    @Volatile
    private var useAltMethodForBrowserCheck = false

    fun getDefaultBrowser(
        pmn: Any?,
        userId: Int,
    ): String? {
        if (!useAltMethodForBrowserCheck) {
            val pmnMethod = getDefaultBrowserPMN(pmn, userId)

            return if (useAltMethodForBrowserCheck) {
                getDefaultBrowserPM(userId)
            } else {
                pmnMethod
            }
        }

        return getDefaultBrowserPM(userId)
    }

    fun getWebviewProvider(): String? =
        binderLocalScope {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    webViewService.currentWebViewPackage?.packageName
                } else {
                    webViewService.currentWebViewPackageName
                }
            } catch (_: Throwable) {
                Settings.Global.getString(contentResolver, WEBVIEW_PROVIDER_KEY)
            }
        }

    /**
     * This method is mainly called on non-Samsung devices
     */
    private fun getDefaultBrowserPMN(
        pmn: Any?,
        userId: Int,
    ): String? {
        return try {
            val pms =
                getObjectField(
                    pmn ?: return null,
                    "mPm",
                ) as? PackageManagerService ?: return null

            when (Build.VERSION.SDK_INT) {
                Build.VERSION_CODES.Q -> pms.getDefaultBrowserPackageName(userId)
                Build.VERSION_CODES.R -> pms.getPermissionManagerServiceInternal().getDefaultBrowser(userId)
                else -> pms.defaultAppProvider.getDefaultBrowser(userId)
            }
        } catch (e: Throwable) {
            logD(TAG, e) { "Getting default browser failed through PMN" }

            useAltMethodForBrowserCheck = true

            null
        }
    }

    /**
     * This method is mainly called on Samsung devices
     */
    private fun getDefaultBrowserPM(userId: Int): String? =
        try {
            callMethodWithTypes(
                packageManager,
                "getDefaultBrowserPackageNameAsUser",
                arrayOf(Int::class.javaPrimitiveType!!),
                arrayOf(userId),
            ) as? String
        } catch (x: Throwable) {
            logD(TAG, x) { "Getting default browser failed through PM" }

            null
        }

    private val webViewService by lazy {
        IWebViewUpdateService.Stub.asInterface(
            ServiceManager.getService(WEBVIEW_UPDATE_SERVICE),
        )
    }
}
