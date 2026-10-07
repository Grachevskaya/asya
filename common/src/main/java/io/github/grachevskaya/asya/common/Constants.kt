package io.github.grachevskaya.asya.common

import io.github.grachevskaya.asya.common.BuildConfig

object Constants {
    /**
     * The name the service publishes itself under, for the manager that has no app to talk to.
     */
    const val GMS_PACKAGE_NAME = "com.google.android.gms"
    const val GSF_PACKAGE_NAME = "com.google.android.gsf"
    const val VENDING_PACKAGE_NAME = "com.android.vending"

    const val UID_SYSTEM = 1000

    val gmsPackages = arrayOf(GMS_PACKAGE_NAME, GSF_PACKAGE_NAME)
    val riskyPackages = arrayOf(VENDING_PACKAGE_NAME) + gmsPackages



    const val MANAGER_WORK_MODE_UNKNOWN = 0
    const val MANAGER_WORK_MODE_OK = 1
    const val MANAGER_WORK_MODE_NO_HOOKS = 2
    const val MANAGER_WORK_MODE_LOADING = 3
    const val MANAGER_WORK_MODE_CRASHED = 4

    const val CONFIG_VERSION_NO_SETTINGS = -100

    val packagesShouldNotHide =
        setOf(
            "android",
            "android.media",
            "android.uid.system",
            "android.uid.shell",
            "android.uid.systemui",
            "com.android.permissioncontroller",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.providers.settings",
            "com.google.android.providers.media.module",
            "com.google.android.permissioncontroller",
            "com.miui.securitycenter",
        )
}
