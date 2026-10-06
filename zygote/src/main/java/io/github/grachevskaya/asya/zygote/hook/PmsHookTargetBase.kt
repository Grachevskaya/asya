package io.github.grachevskaya.asya.zygote.hook

import android.content.pm.IPackageManager
import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.common.Utils.getUserFromCallingUid
import io.github.grachevskaya.asya.zygote.service.ReturnValue
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logV
import java.util.concurrent.atomic.AtomicReference

abstract class PmsHookTargetBase : IFrameworkHook {

    @PublishedApi
    internal val lastFilteredApp: AtomicReference<String?> = AtomicReference(null)

    inline fun applyPackageHiding(
        methodName: String,
        returnValue: ReturnValue,
        findCallingUid: () -> Int?,
        findTargetApp: () -> String?,
        findCallingApps: (IPackageManager, Int) -> Array<String>?,
        valueForHiding: Any?,
    ) {
        if (returnValue.throwable != null) return

        val callingUid = findCallingUid()
        if (callingUid == null || callingUid == Constants.UID_SYSTEM) return

        val targetApp = findTargetApp() ?: return
        logV(TAG) { "@$methodName incoming query: $callingUid => $targetApp" }
        if (dataHolder.shouldHideFromUid(callingUid, targetApp) == true) {
            returnValue.result = valueForHiding
            service.increasePMFilterCount(callingUid)
            logD(TAG) { "@$methodName caller cache: $callingUid, target: $targetApp" }
            return
        }
        val callingUserId = getUserFromCallingUid(callingUid)
        val callingApps = findCallingApps(pms, callingUid)
        val caller = callingApps?.firstOrNull { service.shouldHide(it, targetApp, callingUserId) }
        if (caller != null) {
            logD(TAG) { "@$methodName caller: $callingUid $caller, target: $targetApp" }
            returnValue.result = valueForHiding
            val last = lastFilteredApp.getAndSet(caller)
            if (last != caller) logI(TAG) { "@$methodName: query from $caller" }
            dataHolder.putShouldHideUidCache(callingUid, caller, targetApp)
            service.increasePMFilterCount(caller)
        }
    }
}
