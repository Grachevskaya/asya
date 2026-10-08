package io.github.grachevskaya.asya.zygote.util

import android.content.pm.IPackageManager
import io.github.grachevskaya.asya.common.Utils.conflictedModules

object PackageManagerUtils {
    fun IPackageManager.isConflictingModuleInstalled(): Boolean {
        // No hooks are applied when HMA or HMAL is detected.
        return conflictedModules.any { isPackageAvailable(it, 0) }
    }
}
