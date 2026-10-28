package io.github.grachevskaya.asya.zygote.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PmsHookTarget34 : PmsHookTarget33() {
    override val TAG = "PmsHookTarget34"

    override fun load() {
        super.load()

        hooker.apply {
            // AOSP exploit - https://github.com/aosp-mirror/platform_frameworks_base/commit/5bc482bd99ea18fe0b4064d486b29d5ae2d65139
            // Only 14 QPR2+ has the first name, and Samsung calls it getArchivedPackage: the name a
            // release does not carry is dropped by the hooker, as every other hook for a method a
            // release may not have. Neither name has a caller uid to read, so the binder identity is
            // the caller -- and the method's own cross-user check runs behind the rewrite, on the
            // absent name, which is what a query about an uninstalled package pays there too.
            hookBefore(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getArchivedPackageInternal",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applyNameRewrite(frame, 1, Binder.getCallingUid())
            }

            hookBefore(
                PACKAGE_MANAGER_SERVICE_CLASS,
                "getArchivedPackage",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applyNameRewrite(frame, 1, Binder.getCallingUid())
            }
        }
    }
}
