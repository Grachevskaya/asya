package io.github.grachevskaya.asya.ui.fragment

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.BuildConfig
import io.github.grachevskaya.asya.MyApp.Companion.asyaApp
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.common.JsonConfig
import io.github.grachevskaya.asya.common.OSUtils
import io.github.grachevskaya.asya.common.PropertyUtils
import io.github.grachevskaya.asya.data.AppConstants
import io.github.grachevskaya.asya.databinding.FragmentSettingsBinding
import io.github.grachevskaya.asya.service.ConfigManager
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.activity.MainActivity
import io.github.grachevskaya.asya.ui.fragment.AppSettingsV2FragmentArgs
import io.github.grachevskaya.asya.ui.util.ThemeUtils.themeColor
import io.github.grachevskaya.asya.ui.util.dpToPx
import io.github.grachevskaya.asya.ui.util.enabledString
import io.github.grachevskaya.asya.ui.util.navigate
import io.github.grachevskaya.asya.ui.util.recreateMainActivity
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.ui.util.showNeedRebootToast
import io.github.grachevskaya.asya.ui.util.showToast
import io.github.grachevskaya.asya.ui.util.stylePreferenceRows
import io.github.grachevskaya.asya.ui.util.withAnimations
import io.github.grachevskaya.asya.ui.util.withDisableButton
import io.github.grachevskaya.asya.util.ConfigUtils.getLocale
import io.github.grachevskaya.asya.util.SuUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

class SettingsFragment :
    Fragment(R.layout.fragment_settings),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private val binding by viewBinding(FragmentSettingsBinding::bind)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        with(binding.toolbar) {
            setupToolbar(
                toolbar = this,
                title = getString(R.string.title_settings),
            )
            title = null
        }
        binding.collapsing.title = getString(R.string.title_settings)

        if (childFragmentManager.findFragmentById(R.id.settings_container) == null) {
            childFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsPreferenceFragment())
                .commit()
        }

        setEdge2EdgeFlags(binding.root)
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference,
    ): Boolean {
        val fragment = childFragmentManager.fragmentFactory.instantiate(requireContext().classLoader, pref.fragment!!)
        fragment.arguments = pref.extras
        childFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    class SettingsPreferenceDataStore : PreferenceDataStore() {
        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean =
            when (key) {
                "followSystemAccent" -> PrefManager.followSystemAccent
                "detailLog" -> ConfigManager.detailLog
                "errorOnlyLog" -> ConfigManager.errorOnlyLog
                "bypassRiskyPackageWarning" -> PrefManager.bypassRiskyPackageWarning
                "appDataIsolation" -> ConfigManager.altAppDataIsolation
                "voldAppDataIsolation" -> ConfigManager.altVoldAppDataIsolation
                "skipSystemAppDataIsolation" -> ConfigManager.skipSystemAppDataIsolation
                "disableActivityLaunchProtection" -> ConfigManager.disableActivityLaunchProtection
                "forceMountData" -> ConfigManager.forceMountData
                "webViewProtection" -> ConfigManager.webViewProtection
                else -> throw IllegalArgumentException("Invalid key: $key")
            }

        override fun getString(
            key: String,
            defValue: String?,
        ): String =
            when (key) {
                "language" -> PrefManager.locale
                "themeColor" -> PrefManager.themeColor
                "darkTheme" -> PrefManager.darkTheme.toString()
                "maxLogSize" -> ConfigManager.maxLogSize.toString()
                else -> throw IllegalArgumentException("Invalid key: $key")
            }

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int =
            when (key) {
                else -> throw IllegalArgumentException("Invalid key: $key")
            }

        override fun getStringSet(
            key: String?,
            defValues: Set<String>?,
        ): Set<String> =
            when (key) {
                else -> throw IllegalArgumentException("Invalid key: $key")
            }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) {
            when (key) {
                "followSystemAccent" -> PrefManager.followSystemAccent = value
                "detailLog" -> ConfigManager.detailLog = value
                "errorOnlyLog" -> ConfigManager.errorOnlyLog = value
                "forceMountData" -> ConfigManager.forceMountData = value
                "bypassRiskyPackageWarning" -> PrefManager.bypassRiskyPackageWarning = value
                "disableActivityLaunchProtection" -> ConfigManager.disableActivityLaunchProtection = value
                "appDataIsolation" -> ConfigManager.altAppDataIsolation = value
                "voldAppDataIsolation" -> ConfigManager.altVoldAppDataIsolation = value
                "skipSystemAppDataIsolation" -> ConfigManager.skipSystemAppDataIsolation = value
                "webViewProtection" -> ConfigManager.webViewProtection = value
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putString(
            key: String,
            value: String?,
        ) {
            when (key) {
                "language" -> PrefManager.locale = value!!
                "themeColor" -> PrefManager.themeColor = value!!
                "darkTheme" -> PrefManager.darkTheme = value!!.toInt()
                "maxLogSize" -> ConfigManager.maxLogSize = value!!.toInt()
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putInt(
            key: String,
            value: Int,
        ) {
            when (key) {
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putStringSet(
            key: String,
            values: Set<String>?,
        ) {
            when (key) {
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }
    }

    class DataIsolationPreferenceFragment(
        private val preferenceDataStore: PreferenceDataStore,
    ) : PreferenceFragmentCompat() {
        override fun onViewCreated(
            view: View,
            savedInstanceState: Bundle?,
        ) {
            super.onViewCreated(view, savedInstanceState)
            stylePreferenceRows()
        }

        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?,
        ) {
            preferenceManager.preferenceDataStore = preferenceDataStore
            setPreferencesFromResource(R.xml.settings_data_isolation, rootKey)

            findPreference<SwitchPreferenceCompat>("appDataIsolation")?.let {
                it.isEnabled = !PropertyUtils.isAppDataIsolationEnabled

                it.summary = getString(R.string.settings_need_reboot) + "\n\n" +
                    getString(
                        R.string.settings_default_value,
                        PropertyUtils.isAppDataIsolationEnabled.enabledString(resources),
                    )

                it.setOnPreferenceChangeListener { _, _ ->
                    showNeedRebootToast()

                    true
                }
            }

            findPreference<SwitchPreferenceCompat>("voldAppDataIsolation")?.let {
                it.isEnabled = !PropertyUtils.isVoldAppDataIsolationEnabled

                it.summary = getString(R.string.settings_need_reboot) + "\n\n" +
                    getString(
                        R.string.settings_default_value,
                        PropertyUtils.isVoldAppDataIsolationEnabled.enabledString(resources),
                    )

                it.setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    if (enabled) {
                        MaterialAlertDialogBuilder(
                            requireContext(),
                            R.style.ThemeOverlay_MaterialAlertDialog_Centered_FullWidthButtons,
                        )
                            .setIcon(R.drawable.outline_info_24)
                            .setTitle(R.string.settings_warning)
                            .setMessage(R.string.settings_vold_warning)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                showNeedRebootToast()

                                it.isChecked = true
                            }.setNegativeButton(android.R.string.cancel) { _, _ ->
                                it.isChecked = false
                            }.setCancelable(false)
                            .create()
                            .withDisableButton()
                            .show()
                    }

                    (!enabled).apply {
                        if (this) {
                            showNeedRebootToast()
                        }
                    }
                }
            }

            findPreference<Preference>("categoryVoldAppDataIsolation")?.let {
                it.isVisible = !OSUtils.isSamsung()
            }
        }
    }

    class SettingsPreferenceFragment : PreferenceFragmentCompat() {
        private fun configureDataIsolation() {
            findPreference<Preference>("dataIsolation")?.let {
                it.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                it.summary =
                    when {
                        it.isEnabled -> {
                            getString(
                                R.string.settings_data_isolation_summary,
                                if (ConfigManager.altAppDataIsolation) {
                                    getString(R.string.settings_overwritten)
                                } else {
                                    PropertyUtils.isAppDataIsolationEnabled.enabledString(resources)
                                },
                                if (ConfigManager.altVoldAppDataIsolation) {
                                    getString(R.string.settings_overwritten)
                                } else {
                                    PropertyUtils.isVoldAppDataIsolationEnabled.enabledString(resources)
                                },
                                ConfigManager.forceMountData.enabledString(resources),
                            )
                        }

                        else -> {
                            getString(R.string.settings_data_isolation_unsupported)
                        }
                    }
                it.setOnPreferenceClickListener { _ ->
                    parentFragmentManager
                        .beginTransaction()
                        .withAnimations()
                        .replace(
                            R.id.settings_container,
                            DataIsolationPreferenceFragment(
                                preferenceManager.preferenceDataStore!!,
                            ),
                        ).addToBackStack(null)
                        .commit()

                    true
                }
            }
        }

        override fun onViewCreated(
            view: View,
            savedInstanceState: Bundle?,
        ) {
            super.onViewCreated(view, savedInstanceState)
            stylePreferenceRows()
        }

        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?,
        ) {
            preferenceManager.preferenceDataStore = SettingsPreferenceDataStore()
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<ListPreference>("language")?.let {
                val userLocale = getLocale()
                val entries =
                    buildList {
                        for (lang in BuildConfig.SUPPORTED_LOCALES) {
                            if (lang == "SYSTEM") {
                                add(getString(R.string.follow_system))
                            } else {
                                val locale = Locale.forLanguageTag(lang)
                                add(locale.getDisplayName(locale))
                            }
                        }
                    }
                it.entries = entries.toTypedArray()
                it.entryValues = BuildConfig.SUPPORTED_LOCALES
                if (it.value == "SYSTEM") {
                    it.summary = getString(R.string.follow_system)
                } else {
                    val locale = Locale.forLanguageTag(it.value)
                    it.summary =
                        if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(userLocale) else locale.getDisplayName(userLocale)
                }
                it.setOnPreferenceChangeListener { _, newValue ->
                    // Rebuilt the way the theme is, and for the same reason: the strings are read
                    // while views are inflated. prepareRecreate is what keeps that rebuild from being
                    // a window swap anyone can see -- it remembers the page and suppresses the host
                    // window transition.
                    view?.post { (activity as? MainActivity)?.applyLanguageInPlace() }
                    true
                }
            }

            findPreference<SwitchPreferenceCompat>("followSystemAccent")?.also {
                it.isVisible = DynamicColors.isDynamicColorAvailable()

                // Run after the preference store commits the new value so the recreated Activity
                // reads the selected theme.

                it.setOnPreferenceChangeListener { _, _ ->
                    view?.post { (activity as? MainActivity)?.applyThemeInPlace() }
                    true
                }
            }

            findPreference<ListPreference>("themeColor")?.also {
                if (!DynamicColors.isDynamicColorAvailable()) it.dependency = null

                // Run after the preference store commits the new value so the recreated Activity
                // reads the selected theme.

                it.setOnPreferenceChangeListener { _, _ ->
                    view?.post { (activity as? MainActivity)?.applyThemeInPlace() }
                    true
                }
            }

            findPreference<ListPreference>("darkTheme")?.setOnPreferenceChangeListener { _, newValue ->
                val mode = (newValue as String).toInt()

                view?.post { (activity as? MainActivity)?.applyNightMode(mode) }
                true
            }

            val detailLog = findPreference<SwitchPreferenceCompat>("detailLog")
            val errorOnlyLog = findPreference<SwitchPreferenceCompat>("errorOnlyLog")

            detailLog?.apply {
                isEnabled = !(errorOnlyLog?.isChecked ?: false)

                setOnPreferenceChangeListener { _, value ->
                    errorOnlyLog?.isEnabled = !(value as Boolean)

                    true
                }
            }
            errorOnlyLog?.apply {
                isEnabled = !(detailLog?.isChecked ?: false)

                setOnPreferenceChangeListener { _, value ->
                    detailLog?.isEnabled = !(value as Boolean)

                    true
                }
            }

            findPreference<Preference>("backupConfig")?.apply {
                setOnPreferenceClickListener {
                    navigate(R.id.nav_backup_restore, BackupRestoreFragmentArgs(true).toBundle())
                    true
                }
            }

            findPreference<Preference>("restoreConfig")?.apply {
                setOnPreferenceClickListener {
                    navigate(R.id.nav_backup_restore, BackupRestoreFragmentArgs(false).toBundle())
                    true
                }
            }

            findPreference<Preference>("about")?.apply {
                setOnPreferenceClickListener {
                    navigate(R.id.nav_about)
                    true
                }
            }

            findPreference<Preference>("clearUninstalledPackageConfigs")?.apply {
                setOnPreferenceClickListener {
                    MaterialAlertDialogBuilder(
                        requireContext(),
                        R.style.ThemeOverlay_MaterialAlertDialog_Centered_FullWidthButtons,
                    )
                        .setIcon(R.drawable.outline_cleaning_services_24)
                        .setTitle(R.string.settings_clear_uninstalled_app_configs)
                        .setMessage(R.string.settings_no_undone_warning)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val progressDialog =
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(R.string.settings_clear_uninstalled_app_configs)
                                    .setView(R.layout.dialog_loading)
                                    .setCancelable(false)
                                    .create()

                            progressDialog.show()

                            ConfigManager.clearUninstalledAppConfigs { isSuccess ->
                                lifecycleScope.launch {
                                    progressDialog.dismiss()

                                    if (isSuccess) {
                                        showToast(android.R.string.ok)
                                    }
                                }
                            }
                        }.setNegativeButton(android.R.string.cancel, null)
                        .setCancelable(false)
                        .show()

                    true
                }
            }

            findPreference<Preference>("defaultConfig")?.apply {
                setOnPreferenceClickListener {
                    val args =
                        AppSettingsV2FragmentArgs(
                            packageName = "bulk_config",
                            inputConfig = ConfigManager.defaultConfig?.toString(),
                            mode = AppConstants.APP_CONFIG_MODE_DEFAULT_CONFIG,
                            customSubtitle = getString(R.string.settings_default_config),
                        )
                    navigate(R.id.nav_app_settings, args.toBundle())

                    true
                }
            }

            configureDataIsolation()

            findPreference<Preference>("resetDefault")?.setOnPreferenceClickListener {
                if (ServiceClient.serviceVersion != 0) {
                    MaterialAlertDialogBuilder(
                        requireContext(),
                        R.style.ThemeOverlay_MaterialAlertDialog_Centered_FullWidthButtons,
                    )
                        .setIcon(R.drawable.outline_cleaning_services_24)
                        .setTitle(R.string.settings_reset_default)
                        .setMessage(R.string.settings_irreversible_confirmation)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            ConfigManager.resetConfig()
                            showToast(android.R.string.ok)
                        }.setNegativeButton(R.string.no, null)
                        .show()
                } else {
                    showToast(R.string.home_xposed_service_off)
                }
                true
            }

            findPreference<Preference>("forceCleanEnv")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(
                    requireActivity(),
                    R.style.ThemeOverlay_MaterialAlertDialog_Centered_FullWidthButtons,
                )
                    .setIcon(R.drawable.outline_cleaning_services_24)
                    .setTitle(R.string.settings_force_clean_env)
                    .setMessage(R.string.settings_is_clean_env_summary)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val result = SuUtils.execPrivileged("rm -rf /data/misc/asya*")
                        if (result) {
                            showToast(R.string.settings_force_clean_env_toast_successful)
                        } else {
                            showToast(R.string.settings_permission_denied)
                        }
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            configureDataIsolation()
        }
    }
}
