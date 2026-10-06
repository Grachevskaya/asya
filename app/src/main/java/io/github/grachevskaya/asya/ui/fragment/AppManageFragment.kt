package io.github.grachevskaya.asya.ui.fragment

import io.github.grachevskaya.asya.service.ConfigManager
import io.github.grachevskaya.asya.ui.adapter.AppManageAdapter
import io.github.grachevskaya.asya.ui.util.navigate
import io.github.grachevskaya.asya.util.PackageHelper
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.ui.fragment.AppSettingsV2FragmentArgs

class AppManageFragment : AppSelectFragment() {
    override fun getFragmentTitle() = getString(R.string.title_app_manage)

    override val firstComparator: Comparator<String> = Comparator.comparing(ConfigManager::isHideEnabled).reversed()

    override val adapter = AppManageAdapter {
        if (PackageHelper.exists(it)) {
            val args = AppSettingsV2FragmentArgs(it)
            navigate(R.id.nav_app_settings, args.toBundle())
        }
    }
}
