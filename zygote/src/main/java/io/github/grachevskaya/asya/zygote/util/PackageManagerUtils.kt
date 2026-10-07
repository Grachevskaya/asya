package io.github.grachevskaya.asya.zygote.util

import android.content.pm.IPackageManager
import io.github.grachevskaya.asya.common.Utils.conflictedModules

object PackageManagerUtils {
    fun IPackageManager.isConflictingModuleInstalled(): Boolean {
        // we shouldn't apply hooks when the HMA/HMAL detected
        return conflictedModules.any { isPackageAvailable(it, 0) }
    }
}
