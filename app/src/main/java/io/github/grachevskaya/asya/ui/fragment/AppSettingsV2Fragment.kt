package io.github.grachevskaya.asya.ui.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.clearFragmentResultListener
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.CollectionUtils.sync
import io.github.grachevskaya.asya.common.JsonConfig
import io.github.grachevskaya.asya.common.OSUtils
import io.github.grachevskaya.asya.data.AppConstants
import io.github.grachevskaya.asya.databinding.FragmentSettingsBinding
import io.github.grachevskaya.asya.databinding.LayoutListEmptyBinding
import io.github.grachevskaya.asya.service.ConfigManager
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.fragment.ScopeFragmentArgs
import io.github.grachevskaya.asya.ui.util.ThemeUtils.asDrawable
import io.github.grachevskaya.asya.ui.util.enabledString
import io.github.grachevskaya.asya.ui.util.navController
import io.github.grachevskaya.asya.ui.util.navigate
import io.github.grachevskaya.asya.ui.util.registerOnBackCallback
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.ui.util.showToast
import io.github.grachevskaya.asya.ui.util.stylePreferenceRows
import io.github.grachevskaya.asya.ui.util.withAnimations
import io.github.grachevskaya.asya.ui.viewmodel.AppSettingsViewModel
import io.github.grachevskaya.asya.util.PackageHelper

class AppSettingsV2Fragment : Fragment(R.layout.fragment_settings) {
    companion object {
        private const val TAG = "AppSettingsV2Fragment"
    }

    private var argsOverride: AppSettingsV2FragmentArgs? = null

    private val binding by viewBinding(FragmentSettingsBinding::bind)
    private val viewModel by viewModels<AppSettingsViewModel> {
        var args =
            if (argsOverride != null) {
                argsOverride!!
            } else {
                val safeArgs by navArgs<AppSettingsV2FragmentArgs>()

                safeArgs
            }

        val cfg: JsonConfig.AppConfig? =
            if (args.mode != AppConstants.APP_CONFIG_MODE_SINGLE) {
                if (args.inputConfig != null) {
                    JsonConfig.AppConfig.parse(args.inputConfig)
                } else {
                    null
                }
            } else {
                ConfigManager.getAppConfig(args.packageName)
            }

        val pack =
            AppSettingsViewModel.Pack(
                app = args.packageName,
                enabled = cfg != null,
                mode = args.mode,
                config = cfg ?: JsonConfig.AppConfig(),
                customSubtitle = args.customSubtitle,
            )
        AppSettingsViewModel.Factory(pack)
    }

    private fun saveConfig() {
        when (viewModel.pack.mode) {
            AppConstants.APP_CONFIG_MODE_SINGLE -> {
                ConfigManager.setAppConfig(
                    viewModel.pack.app,
                    if (viewModel.pack.enabled) viewModel.pack.config else null,
                )
            }

            AppConstants.APP_CONFIG_MODE_BULK_CONFIG -> {
                setFragmentResult(
                    "bulk_app_settings",
                    Bundle().apply {
                        putString(
                            "appConfig",
                            if (viewModel.pack.enabled) viewModel.pack.config.toString() else null,
                        )
                    },
                )
            }

            AppConstants.APP_CONFIG_MODE_DEFAULT_CONFIG -> {
                ConfigManager.defaultConfig = if (viewModel.pack.enabled) viewModel.pack.config else null
            }

            else -> {
                throw UnsupportedOperationException("Invalid mode: ${viewModel.pack.mode}")
            }
        }
    }

    private fun onBack() {
        if (!parentFragmentManager.popBackStackImmediate()) {
            saveConfig()
            navController.navigateUp()
        }
    }

    override fun onPause() {
        super.onPause()
        saveConfig()
    }

    val subtitle: String by lazy {
        if (viewModel.pack.mode != AppConstants.APP_CONFIG_MODE_SINGLE) {
            viewModel.pack.customSubtitle
                ?.takeIf { it.isNotEmpty() }
                ?.let { return@lazy it }
        }

        return@lazy PackageHelper.loadAppLabel(viewModel.pack.app)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        registerOnBackCallback { onBack() }

        setupToolbar(
            toolbar = binding.pageBar.toolbar,
            title = getString(R.string.title_app_settings),
            subtitle = subtitle,
            navigationIcon = R.drawable.baseline_arrow_back_24,
            navigationOnClick = { onBack() },
        )
        binding.pageBar.collapsing.title = getString(R.string.title_app_settings)
        binding.pageBar.toolbar.title = null

        if (childFragmentManager.findFragmentById(R.id.settings_container) == null) {
            childFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, AppPreferenceFragment())
                .commit()
        }

        setEdge2EdgeFlags(binding.root)
    }

    class AppPreferenceDataStore(
        private val pack: AppSettingsViewModel.Pack,
    ) : PreferenceDataStore() {
        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean =
            when (key) {
                "enableHide" -> pack.enabled
                "excludeSystemApps" -> pack.config.excludeSystemApps
                "hideInstallationSource" -> pack.config.hideInstallationSource
                "hideSystemInstallationSource" -> pack.config.hideSystemInstallationSource
                "excludeTargetInstallationSource" -> pack.config.excludeTargetInstallationSource
                "invertActivityLaunchProtection" -> pack.config.invertActivityLaunchProtection
                "excludeVoldIsolation" -> pack.config.excludeVoldIsolation
                else -> throw IllegalArgumentException("Invalid key: $key")
            }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) {
            when (key) {
                "enableHide" -> pack.enabled = value
                "excludeSystemApps" -> pack.config.excludeSystemApps = value
                "hideInstallationSource" -> pack.config.hideInstallationSource = value
                "hideSystemInstallationSource" -> pack.config.hideSystemInstallationSource = value
                "excludeTargetInstallationSource" -> pack.config.excludeTargetInstallationSource = value
                "invertActivityLaunchProtection" -> pack.config.invertActivityLaunchProtection = value
                "excludeVoldIsolation" -> pack.config.excludeVoldIsolation = value
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun getString(
            key: String,
            defValue: String?,
        ): String =
            when (key) {
                "useWhiteList" -> if (pack.config.useWhitelist) "1" else "0"
                else -> throw IllegalArgumentException("Invalid key: $key")
            }

        override fun putString(
            key: String,
            value: String?,
        ) {
            when (key) {
                "useWhiteList" -> pack.config.useWhitelist = value == "1"
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }
    }

    abstract class BaseAppSettingsPreferenceFragment : PreferenceFragmentCompat() {
        override fun onViewCreated(
            view: View,
            savedInstanceState: Bundle?,
        ) {
            super.onViewCreated(view, savedInstanceState)

            /*
             * The app's own settings are a settings screen like any other: without this they were the
             * only ones still drawn in the old list with its dividers.
             */
            stylePreferenceRows()
        }

        internal val parent get() = requireParentFragment() as AppSettingsV2Fragment
        internal val pack get() = parent.viewModel.pack
    }

    class AppPreferenceFragment : BaseAppSettingsPreferenceFragment() {
        private fun startMainActivity(
            userId: Int,
            forceStop: Boolean,
        ) {
            val packageName = pack.app

            if (forceStop) ServiceClient.forceStop(packageName, userId)

            try {
                ServiceClient.startMainActivityAsUser(packageName, userId)
            } catch (e: Throwable) {
                showToast(R.string.app_launch_failed)
                ServiceClient.log(Log.ERROR, TAG, e.stackTraceToString())
            }
        }

        @SuppressLint("DiscouragedApi")
        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?,
        ) {
            preferenceManager.preferenceDataStore = AppPreferenceDataStore(pack)
            setPreferencesFromResource(R.xml.app_settings_v2, rootKey)
            findPreference<Preference>("appInfo")?.let {
                if (pack.mode != AppConstants.APP_CONFIG_MODE_SINGLE) {
                    it.icon = R.drawable.outline_storage_24.asDrawable(requireContext())
                    it.title = parent.subtitle
                } else {
                    it.icon = PackageHelper.loadAppIcon(pack.app)
                    it.title = PackageHelper.loadAppLabel(pack.app)
                    it.summary = pack.app
                    it.setOnPreferenceClickListener { pref ->
                        MaterialAlertDialogBuilder(pref.context)
                            .apply {
                                setTitle(it.title)
                                setItems(
                                    R.array.app_action_texts,
                                ) { _, which ->
                                    parent.saveConfig()
                                    val userIds = PackageHelper.loadUserIds(pack.app)
                                    val forceStop = which == 0

                                    if (userIds.size == 1) {
                                        startMainActivity(userIds.first(), forceStop)
                                    } else if (userIds.size > 1) {
                                        MaterialAlertDialogBuilder(pref.context)
                                            .apply {
                                                setItems(
                                                    userIds.map { id -> id.toString() }.toTypedArray(),
                                                ) { _, userId ->
                                                    startMainActivity(userId, forceStop)
                                                }
                                            }.show()
                                    }
                                }
                            }.show()

                        true
                    }
                }
            }
            findPreference<Preference>("templateConfig")?.setOnPreferenceClickListener { _ ->
                parentFragmentManager
                    .beginTransaction()
                    .withAnimations()
                    .replace(
                        R.id.settings_container,
                        TemplateConfigPreferenceFragment(
                            preferenceManager.preferenceDataStore!!,
                        ),
                    ).addToBackStack(null)
                    .commit()

                true
            }
            findPreference<Preference>("categoryVoldAppDataIsolation")?.let {
                it.isVisible = !OSUtils.isSamsung()

                if (it.isVisible) {
                    findPreference<SwitchPreferenceCompat>("excludeVoldIsolation")?.let {
                        it.isEnabled = ConfigManager.altVoldAppDataIsolation
                    }
                }
            }
            findPreference<SwitchPreferenceCompat>("invertActivityLaunchProtection")?.let {
                it.summary = getString(R.string.app_invert_activity_launch_protection_desc) + "\n\n" +
                    getString(
                        R.string.app_global_activity_launch_protection_state,
                        (!ConfigManager.disableActivityLaunchProtection).enabledString(resources),
                    )
            }
        }
    }

    class TemplateConfigPreferenceFragment(
        private val preferenceDataStore: PreferenceDataStore,
    ) : BaseAppSettingsPreferenceFragment() {
        private fun updateApplyTemplates() {
            findPreference<Preference>("applyTemplates")?.apply {
                title = getString(R.string.app_template_using, pack.config.applyTemplates.size)
                summary =
                    pack.config.applyTemplates
                        .joinToString(", ")
                        .ifEmpty { null }
            }
        }

        private fun updateExtraAppList(useWhiteList: Boolean) {
            findPreference<Preference>("extraAppList")?.title =
                if (useWhiteList) {
                    getString(R.string.app_extra_apps_visible_count, pack.config.extraAppList.size)
                } else {
                    getString(R.string.app_extra_apps_invisible_count, pack.config.extraAppList.size)
                }
        }

        private fun updateExtraOppositeAppList(useWhiteList: Boolean) {
            findPreference<Preference>("extraOppositeAppList")?.title =
                if (!useWhiteList) {
                    getString(R.string.app_extra_apps_visible_count, pack.config.extraOppositeAppList.size)
                } else {
                    getString(R.string.app_extra_apps_invisible_count, pack.config.extraOppositeAppList.size)
                }
        }

        @SuppressLint("DiscouragedApi")
        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?,
        ) {
            preferenceManager.preferenceDataStore = preferenceDataStore
            setPreferencesFromResource(R.xml.app_settings_template_config_v2, rootKey)

            findPreference<ListPreference>("useWhiteList")?.apply {
                val excludeSystemApps = findPreference<SwitchPreferenceCompat>("excludeSystemApps")

                entries =
                    arrayOf(
                        getString(R.string.blacklist),
                        getString(R.string.whitelist),
                    )
                entryValues = arrayOf("0", "1")

                excludeSystemApps?.isEnabled = value == "1"

                setOnPreferenceChangeListener { _, newValue ->
                    val useWhitelist = newValue == "1"

                    pack.config.applyTemplates.clear()

                    // swap extra app lists when the work mode was changed
                    val extraAppList = pack.config.extraAppList.toList()
                    pack.config.extraAppList.sync(pack.config.extraOppositeAppList)
                    pack.config.extraOppositeAppList.sync(extraAppList)

                    updateApplyTemplates()
                    updateExtraAppList(useWhitelist)
                    updateExtraOppositeAppList(useWhitelist)

                    excludeSystemApps?.isEnabled = useWhitelist
                    true
                }
            }
            findPreference<Preference>("applyTemplates")?.setOnPreferenceClickListener {
                val templates =
                    ConfigManager.getTemplateList().mapNotNull {
                        if (it.isWhiteList == pack.config.useWhitelist) it.name else null
                    }

                val dialog =
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.app_choose_template)
                        .setNegativeButton(android.R.string.cancel, null)

                if (templates.isNotEmpty()) {
                    val container = layoutInflater.inflate(R.layout.dialog_choose_template, null)
                    val list = container.findViewById<LinearLayout>(R.id.choice_list)
                    val boxes =
                        templates.map { name ->
                            val row = layoutInflater.inflate(R.layout.row_choice, list, false)
                            val label = row.findViewById<TextView>(R.id.row_text)
                            val box =
                                MaterialCheckBox(row.context).apply {
                                    isClickable = false
                                    isFocusable = false
                                }
                            row.findViewById<FrameLayout>(R.id.row_control).addView(box)

                            label.text = name
                            box.isChecked = pack.config.applyTemplates.contains(name)
                            row.contentDescription = name
                            row.setOnClickListener { box.toggle() }

                            list.addView(row)
                            box
                        }
                    dialog.setView(container)
                    dialog.setPositiveButton(android.R.string.ok) { _, _ ->
                        pack.config.applyTemplates =
                            templates.filterIndexedTo(mutableSetOf()) { index, _ -> boxes[index].isChecked }
                        updateApplyTemplates()
                    }
                } else {
                    val emptyView = LayoutListEmptyBinding.inflate(layoutInflater)
                    emptyView.root.isVisible = true
                    emptyView.listEmptyIcon.setImageResource(R.drawable.sentiment_very_dissatisfied_24px)
                    emptyView.listEmptyText.text = getString(R.string.title_template_manage)
                    dialog.setView(emptyView.root)
                }

                dialog.show()

                true
            }
            findPreference<Preference>("extraAppList")?.setOnPreferenceClickListener {
                parent.setFragmentResultListener("app_select") { _, bundle ->
                    pack.config.extraAppList = bundle.getStringArrayList("checked")!!.toMutableSet()
                    updateExtraAppList(pack.config.useWhitelist)
                    parent.clearFragmentResultListener("app_select")
                }

                val args =
                    ScopeFragmentArgs(
                        filterOnlyEnabled = false,
                        checked = pack.config.extraAppList.toTypedArray(),
                        hideMyself = false,
                    )
                navigate(R.id.nav_scope, args.toBundle())
                true
            }
            findPreference<Preference>("extraOppositeAppList")?.setOnPreferenceClickListener {
                parent.setFragmentResultListener("app_opposite_select") { _, bundle ->
                    pack.config.extraOppositeAppList = bundle.getStringArrayList("checked")!!.toMutableSet()
                    updateExtraOppositeAppList(pack.config.useWhitelist)
                    parent.clearFragmentResultListener("app_opposite_select")
                }

                val args =
                    ScopeFragmentArgs(
                        filterOnlyEnabled = false,
                        isOpposite = true,
                        checked = pack.config.extraOppositeAppList.toTypedArray(),
                        hideMyself = false,
                    )
                navigate(R.id.nav_scope, args.toBundle())
                true
            }
            updateApplyTemplates()
            updateExtraAppList(pack.config.useWhitelist)
            updateExtraOppositeAppList(pack.config.useWhitelist)
        }
    }
}
