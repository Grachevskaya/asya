package io.github.grachevskaya.asya.zygote.hook

import android.content.pm.PackageInstaller
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.grachevskaya.asya.common.Constants.VENDING_PACKAGE_NAME
import io.github.grachevskaya.asya.zygote.util.ZLUtils.findConstructor

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class InstallerHookTarget34 : InstallerHookTarget33() {
    override val TAG = "InstallerHookTarget34"

    override val fakeSystemPackageInstallSourceInfo: Any by lazy {
        findConstructor(
            "android.content.pm.InstallSourceInfo",
            6,
        )!!.newInstance(
            null,
            null,
            null,
            null,
            null,
            PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED,
        )
    }

    override val fakeUserPackageInstallSourceInfo: Any by lazy {
        findConstructor(
            "android.content.pm.InstallSourceInfo",
            6,
        )!!.newInstance(
            VENDING_PACKAGE_NAME,
            psPackageInfo?.signingInfo,
            VENDING_PACKAGE_NAME,
            VENDING_PACKAGE_NAME,
            VENDING_PACKAGE_NAME,
            PackageInstaller.PACKAGE_SOURCE_STORE,
        )
    }
}
