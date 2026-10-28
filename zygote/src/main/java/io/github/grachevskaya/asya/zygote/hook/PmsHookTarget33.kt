package io.github.grachevskaya.asya.zygote.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.getCallingApps
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.APPS_FILTER_IMPL_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.CHANGED_PACKAGES_TRACKER_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.COMPUTER_ENGINE_CLASS

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
open class PmsHookTarget33 : PmsHookTargetBase() {
    override val TAG = "PmsHookTarget33"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            // The body the framework's own permission checks run in front of: the name is rewritten
            // here and nowhere else, so a denied caller keeps its denial and a hidden package is the
            // miss an uninstalled one is.
            hookBefore(
                COMPUTER_ENGINE_CLASS,
                "getPackageInfoInternalBody",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applyNameRewrite(frame, 1, callerUidOf(frame, 4))
            }

            // The flag adjustment the body runs in front of: it reads the name to decide which
            // flags apply, so it is rewritten here as well, and the body it then calls sees no more
            // of the real name than it does for a package that is not installed.
            hookBefore(
                COMPUTER_ENGINE_CLASS,
                "updateFlagsForPackage",
                argumentCount = 3,
                restore = { frame -> putBackArgument(frame, 3) },
            ) { _, frame, _ ->
                applyNameRewrite(frame, 3, callerUidOf(frame, 2))
            }

            // The funnel every ApplicationInfoFlags query takes -- getApplicationInfo and the
            // internal callers alike -- so the flag path is masked before the body picks a branch,
            // and the body site below then sees a masked name and leaves it alone.
            hookBefore(
                COMPUTER_ENGINE_CLASS,
                "getNamesForUids",
                restore = { frame -> restoreUidArray(frame, 1) },
            ) { _, frame, _ ->
                applyUidArrayRewrite(frame, 1, Binder.getCallingUid())
            }

            // The change history has no caller: whatever is written is readable by everyone later, so a
            // hidden name is never written. A release whose tracker is shaped differently simply does not
            // carry this method, and the hook does not install.
            hookBefore(
                CHANGED_PACKAGES_TRACKER_CLASS,
                "addPackageChange",
            ) { _, frame, returnValue ->
                skipChangeRecord(returnValue, frame.getArgument(0) as? String)
            }

            // The uid channel: a package's uid answers for the package, so a caller that may not
            // see it gets the answer a uid nothing ever had gets.
            hookBefore(
                COMPUTER_ENGINE_CLASS,
                "getNameForUid",
                restore = { frame -> restoreUid(frame, 1) },
            ) { _, frame, _ ->
                applyUidRewrite(frame, 1, Binder.getCallingUid())
            }

            hookBefore(
                COMPUTER_ENGINE_CLASS,
                "getPackagesForUid",
                restore = { frame -> restoreUid(frame, 1) },
            ) { _, frame, _ ->
                applyUidRewrite(frame, 1, Binder.getCallingUid())
            }

            hookBefore(
                COMPUTER_ENGINE_CLASS,
                "getApplicationInfoInternal",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applyNameRewrite(frame, 1, callerUidOf(frame, 3))
            }

            hookBefore(
                COMPUTER_ENGINE_CLASS,
                "getApplicationInfoInternalBody",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applyNameRewrite(frame, 1, callerUidOf(frame, 3))
            }

            // A generator that builds a result must run the original: what is taken away is the
            // setting it would build one from, and its own first line answers null with null.
            hookBefore(
                COMPUTER_ENGINE_CLASS,
                "generatePackageInfo",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applySettingNull(frame, 1, Binder.getCallingUid(), ::getCallingApps)
            }
            // A caller that cannot see a package and a caller that cannot see one that is not
            // installed get the same answer here; the filter itself checks no permissions.
            hookBefore(
                APPS_FILTER_IMPL_CLASS,
                "shouldFilterApplication",
            ) { _, frame, returnValue ->
                applyFilterHiding(
                    returnValue,
                    callerUidOf(frame, 2),
                    getPackageNameFromPackageSettings(frame.getArgument(4)) as? String,
                    ::getCallingApps,
                )
            }
        }
    }
}
