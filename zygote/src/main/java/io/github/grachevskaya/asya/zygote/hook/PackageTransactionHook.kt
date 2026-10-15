package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logW
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.DELETE_PACKAGE_HELPER_CLASS
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.INSTALL_PACKAGE_HELPER_CLASS
import io.github.grachevskaya.asya.zygote.service.KernelPolicy
import io.github.grachevskaya.asya.zygote.service.precomputeNativeLibs
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.PACKAGE_MANAGER_SERVICE_CLASS

/**
 * The install and removal transactions, and the rebuild that follows each of them.
 *
 * Each transaction is hooked once, on the method that carries it out, and the rebuild follows the
 * one that committed. The frame's index 0 is the receiver, not the first parameter, so the install
 * request list is index 1.
 */
class PackageTransactionHook : IFrameworkHook {
    override val TAG = "PackageTransactionHook"

    override fun load() {
        logI(TAG) { "Load hook" }

        hookFirstAvailableInstall(
            INSTALL_PACKAGE_HELPER_CLASS to "installPackagesLI",
            INSTALL_PACKAGE_HELPER_CLASS to "installPackagesTracedLI",
            INSTALL_PACKAGE_HELPER_CLASS to "installPackagesTraced",
            PACKAGE_MANAGER_SERVICE_CLASS to "installPackagesTracedLI",
            PACKAGE_MANAGER_SERVICE_CLASS to "installPackagesTraced",
        )

        hookRemove(DELETE_PACKAGE_HELPER_CLASS, "deletePackageX")

        /*
         * The commit point of an install: the apk's inode exists and the package is not yet
         * published, so replacing its file operations here leaves no window in which someone
         * could open it first -- and nothing the package manager can see is changed, because a
         * record with the add op only does that replacement.
         */
        hookCommit("commitReconciledScanResultLocked")
    }

    /**
     * The implementation sits deeper on newer builds: InstallPackageHelper holds it and the names on
     * PackageManagerService forward to it, so the deepest name that exists is taken and the others
     * are left alone. Hooking a forwarder together with its target rebuilds the table
     * twice for one install.
     */
    private fun hookFirstAvailableInstall(vararg candidates: Pair<String, String>) {
        for ((className, methodName) in candidates) {
            if (!hooker.isHookAvailable(className, methodName)) continue
            hookInstall(className, methodName)
            return
        }

        logW(TAG) { "No install method on this build" }
    }

    private fun hookInstall(
        className: String,
        methodName: String,
    ) {
        hooker.hookAfter(className, methodName) { _, frame, _ ->
            onInstalled(frame.getArgument(1))
        }
        if (!hooker.isHookAvailable(className, methodName)) {
            logI(TAG) { "Not on this build: " + className + "." + methodName }
        }
    }

    /**
     * Off while the commit-point push is under suspicion: a synchronous kernel call inside the
     * package manager's transaction blocks system_server, and that is a system-wide restart.
     */
    private val ARM_AT_COMMIT = false

    private fun hookCommit(methodName: String) {
        for (className in arrayOf(INSTALL_PACKAGE_HELPER_CLASS, PACKAGE_MANAGER_SERVICE_CLASS)) {
            hooker.hookAfter(className, methodName) { _, _, returnValue ->
                onCommitted(returnValue.result)
            }
            if (hooker.isHookAvailable(className, methodName)) return
            logI(TAG) { "Not on this build: " + className + "." + methodName }
        }
    }

    /**
     * The package just committed. Its apk is named to the kernel now, so the replacement is in
     * place before the transaction publishes it; the full table still follows on the usual pass.
     */
    private fun onCommitted(committed: Any?) {
        if (!ARM_AT_COMMIT || committed == null) return
        val path =
            runCatching {
                committed.javaClass.getMethod("getBaseApkPath").invoke(committed) as? String
            }.getOrNull()
        val uid =
            runCatching {
                committed.javaClass.getMethod("getUid").invoke(committed) as? Int
            }.getOrNull()
        if (path.isNullOrEmpty() || uid == null) return
        runCatching { KernelPolicy.pushApks(listOf(uid to path)) }
            .onFailure { logW(TAG, it) { "Could not name the committed apk" } }
        /*
         * The libraries this app can load are worked out here, while the install that made it is
         * still running: reading the app's directory afterwards is what something watching that
         * directory would see, and the pushes that follow only read what is remembered.
         */
        val name =
            runCatching {
                committed.javaClass.getMethod("getPackageName").invoke(committed) as? String
            }.getOrNull()
        if (!name.isNullOrEmpty()) service.precomputeNativeLibs(name, 0)
    }

    private fun hookRemove(
        className: String,
        methodName: String,
    ) {
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
            val value =
                runCatching {
                    request.javaClass.getMethod(method).invoke(request) as? String
                }.getOrNull()
            if (!value.isNullOrEmpty()) return value
        }
        val field =
            runCatching {
                request.javaClass
                    .getDeclaredField("mName")
                    .apply { isAccessible = true }
                    .get(request) as? String
            }.getOrNull()
        if (!field.isNullOrEmpty()) return field
        logW(TAG) { "No package name readable from " + request.javaClass.name + "; its default config is skipped" }
        return null
    }

    private fun isReplacement(request: Any): Boolean {
        val direct =
            runCatching {
                request.javaClass.getMethod("isInstallReplace").invoke(request) as? Boolean
            }.getOrNull()
        if (direct != null) return direct
        val flags =
            runCatching {
                request.javaClass.getMethod("getInstallFlags").invoke(request) as? Int
            }.getOrNull()
        // PackageManager.INSTALL_REPLACE_EXISTING
        return flags != null && (flags and 0x00000002) != 0
    }
}
