package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.DELETE_PACKAGE_HELPER_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.INSTALL_PACKAGE_HELPER_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS

/**
 * The install and removal transactions, and the rebuild that follows each of them.
 *
 * Both are hooked under every name the framework has used: the entry may be a forwarder, and a
 * forwarder is small enough to be inlined away. The frame's index 0 is the receiver, not the first
 * parameter, so the install request list is index 1.
 */
class PackageTransactionHook : IFrameworkHook {
    override val TAG = "PackageTransactionHook"

    override fun load() {
        logI(TAG) { "Load hook" }

        hookInstall(PACKAGE_MANAGER_SERVICE_CLASS, "installPackagesTraced")
        hookInstall(INSTALL_PACKAGE_HELPER_CLASS, "installPackagesTraced")
        hookInstall(INSTALL_PACKAGE_HELPER_CLASS, "installPackagesLI")
        hookInstall(INSTALL_PACKAGE_HELPER_CLASS, "installPackagesTracedLI")
        hookInstall(PACKAGE_MANAGER_SERVICE_CLASS, "installPackagesTracedLI")

        hookRemove(DELETE_PACKAGE_HELPER_CLASS, "deletePackageX")
        hookRemove(PACKAGE_MANAGER_SERVICE_CLASS, "deletePackageX")
    }

    private fun hookInstall(className: String, methodName: String) {
        hooker.hookAfter(className, methodName) { _, frame, _ ->
            onInstalled(frame.getArgument(1))
        }
        if (!hooker.isHookAvailable(className, methodName)) {
            logI(TAG) { "Not on this build: " + className + "." + methodName }
        }
    }

    private fun hookRemove(className: String, methodName: String) {
        hooker.hookAfter(className, methodName) { _, _, _ ->
            service.onPackagesChanged()
        }
        if (!hooker.isHookAvailable(className, methodName)) {
            logI(TAG) { "Not on this build: " + className + "." + methodName }
        }
    }

    private fun onInstalled(requests: Any?) {
        service.applyDefaultConfigTo(namesOf(requests))
        service.onPackagesChanged()
    }

    /**
     * The package names in the transaction's request list.
     *
     * InstallRequest carries getName() and isInstallReplace(); the install flags are the older way of
     * saying the same. A build that has neither is said out loud, because the silent case is a
     * package that does not get its default config.
     */
    private fun namesOf(requests: Any?): List<String> {
        val list = requests as? List<*> ?: return emptyList()
        val names = ArrayList<String>(list.size)
        for (item in list) {
            if (item == null) continue
            if (isReplacement(item)) continue
            val name = readName(item)
            if (name != null) names.add(name)
        }
        return names
    }

    private fun readName(request: Any): String? {
        for (method in arrayOf("getName", "getPackageName")) {
            val value = runCatching {
                request.javaClass.getMethod(method).invoke(request) as? String
            }.getOrNull()
            if (!value.isNullOrEmpty()) return value
        }
        val field = runCatching {
            request.javaClass.getDeclaredField("mName").apply { isAccessible = true }.get(request) as? String
        }.getOrNull()
        if (!field.isNullOrEmpty()) return field
        logW(TAG) { "No package name readable from " + request.javaClass.name + "; its default config is skipped" }
        return null
    }

    private fun isReplacement(request: Any): Boolean {
        val direct = runCatching {
            request.javaClass.getMethod("isInstallReplace").invoke(request) as? Boolean
        }.getOrNull()
        if (direct != null) return direct
        val flags = runCatching {
            request.javaClass.getMethod("getInstallFlags").invoke(request) as? Int
        }.getOrNull()
        // PackageManager.INSTALL_REPLACE_EXISTING
        return flags != null && (flags and 0x00000002) != 0
    }
}
