package io.github.grachevskaya.asya.zygote.hook

import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.getCallingApps
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.APPS_FILTER_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.PMS_COMPUTER_ENGINE_CLASS

@RequiresApi(Build.VERSION_CODES.S)
class PmsHookTarget31 : PmsHookTargetBase() {
    override val TAG = "PmsHookTarget31"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            // Android 12 answers the permission checks in front of the body, so the rewrite is here:
            // the body the name reaches is the one the framework checks first, and it then resolves
            // a name no package carries exactly as it resolves one that is not installed.
            hookBefore(
                PMS_COMPUTER_ENGINE_CLASS,
                "getPackageInfoInternalBody",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applyNameRewrite(frame, 1, callerUidOf(frame, 4))
            }

            // The flag adjustment the body runs in front of: it reads the name to decide which
            // flags apply, so it is rewritten here as well, and the body it then calls sees no more
            // of the real name than it does for a package that is not installed.
            hookBefore(
                PMS_COMPUTER_ENGINE_CLASS,
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
                PMS_COMPUTER_ENGINE_CLASS,
                "getApplicationInfoInternal",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applyNameRewrite(frame, 1, callerUidOf(frame, 3))
            }

            // Android 12 keeps the record in the package manager itself and fills it from a package
            // setting; the same rule applies, so a hidden name is never written into it.
            hookBefore(
                PMS_COMPUTER_ENGINE_CLASS,
                "updateSequenceNumberLP",
            ) { _, frame, returnValue ->
                skipChangeRecordForSetting(returnValue, frame.getArgument(1))
            }

            // The uid channel: a package's uid answers for the package, so a caller that may not see
            // it gets the answer a uid nothing ever had gets.
            hookBefore(
                PMS_COMPUTER_ENGINE_CLASS,
                "getNameForUid",
                restore = { frame -> restoreUid(frame, 1) },
            ) { _, frame, _ ->
                applyUidRewrite(frame, 1, Binder.getCallingUid())
            }

            hookBefore(
                PMS_COMPUTER_ENGINE_CLASS,
                "getPackagesForUid",
                restore = { frame -> restoreUid(frame, 1) },
            ) { _, frame, _ ->
                applyUidRewrite(frame, 1, Binder.getCallingUid())
            }

            hookBefore(
                PMS_COMPUTER_ENGINE_CLASS,
                "getNamesForUids",
                restore = { frame -> restoreUidArray(frame, 1) },
            ) { _, frame, _ ->
                applyUidArrayRewrite(frame, 1, Binder.getCallingUid())
            }

            hookBefore(
                PMS_COMPUTER_ENGINE_CLASS,
                "getApplicationInfoInternalBody",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applyNameRewrite(frame, 1, callerUidOf(frame, 3))
            }

            // The setting the outer lookup and every caller behind it resolve through: the name is
            // taken away here too, so the miss happens where the framework resolves names.
            hookBefore(
                PMS_COMPUTER_ENGINE_CLASS,
                "getPackageSettingInternal",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applyNameRewrite(frame, 1, callerUidOf(frame, 2))
            }

            // The generator's first line answers a null setting with a null result: handing it
            // nothing is the framework's own miss, not a result this module made up.
            hookBefore(
                PMS_COMPUTER_ENGINE_CLASS,
                "generatePackageInfo",
                restore = { frame -> putBackArgument(frame, 1) },
            ) { _, frame, _ ->
                applySettingNull(frame, 1, Binder.getCallingUid(), ::getCallingApps)
            }
            // A caller that cannot see a package and a caller that cannot see one that is not
            // installed get the same answer here; the filter itself checks no permissions.
            hookBefore(
                APPS_FILTER_CLASS,
                "shouldFilterApplication",
            ) { _, frame, returnValue ->
                applyFilterHiding(
                    returnValue,
                    callerUidOf(frame, 1),
                    getPackageNameFromPackageSettings(frame.getArgument(3)) as? String,
                    ::getCallingApps,
                )
            }
        }
    }
}
