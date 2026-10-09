package io.github.grachevskaya.asya.ui.fragment

import android.annotation.SuppressLint
import android.icu.text.SimpleDateFormat
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.CollectionUtils.removeIf
import io.github.grachevskaya.asya.common.CollectionUtils.sync
import io.github.grachevskaya.asya.common.Constants.CONFIG_VERSION_NO_SETTINGS
import io.github.grachevskaya.asya.common.JsonConfig
import io.github.grachevskaya.asya.common.Utils.cleanRemnantsFromConfig
import io.github.grachevskaya.asya.databinding.FragmentBackupRestoreBinding
import io.github.grachevskaya.asya.databinding.LayoutListEmptyBinding
import io.github.grachevskaya.asya.service.ConfigManager
import io.github.grachevskaya.asya.ui.util.contentResolver
import io.github.grachevskaya.asya.ui.util.navController
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.ui.util.setupToolbar
import io.github.grachevskaya.asya.ui.util.showToast
import io.github.grachevskaya.asya.ui.util.stylePreferenceRows
import io.github.grachevskaya.asya.util.PackageHelper.loadAppLabel
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

/** The two answers a restore row can carry. */
private const val OVERWRITE = "overwrite"
private const val APPEND = "append"

/*
 * Backing up and restoring the configuration.
 *
 * The two directions are two pages, not one page with a mode: what is being chosen and what is being
 * done to the destination are different questions, and a page that asks both has to explain itself
 * twice. This class holds what the two pages share; the two pages themselves are the classes the
 * navigation graph names.
 *
 * They are preferences screens, like the other sub pages of the settings: the rows, their group
 * surfaces and the switches come from the same theme the rest of the manager uses, which is where the
 * shape of a row lives rather than in this file.
 */
abstract class BackupRestoreFragment : PreferenceFragmentCompat() {
    protected abstract val isBackupMode: Boolean

    protected abstract val preferencesRes: Int

    private val binding by viewBinding(FragmentBackupRestoreBinding::bind)

    private lateinit var importedConfig: JsonConfig

    private val includeSettings get() = switch("includeSettings")?.isChecked == true
    private val trimConfig get() = switch("trimConfig")?.isChecked == true

    /** What a restored entry does to one that is already there: the answer inside each row’s card. */
    private val overwriteApps get() = storedAnswer("overwriteApps")
    private val overwriteTemplates get() = storedAnswer("overwriteTemplates")

    private enum class BRCategory {
        APP,
        TEMPLATE,
    }

    private val markedForBackup = BRCategory.entries.associateWith { mutableSetOf<String>() }

    /** The marks of one category: every category has a set, so the map never answers null. */
    private fun marked(category: BRCategory): MutableSet<String> = markedForBackup.getValue(category)

    private fun switch(key: String): SwitchPreferenceCompat? = findPreference(key)

    /**
     * A row that picks what to include. Touching it asks which items; only the restore page’s rows
     * are cards that carry the answer as well, so the typing has to stay kind to a plain row too.
     */

    /** The row the backup page picks its items with. Touching it asks which items to include. */
    private fun wirePicker(
        key: String,
        category: BRCategory,
    ) {
        findPreference<Preference>(key)?.apply {
            title =
                getString(
                    if (category == BRCategory.APP) R.string.backup_restore_apps else R.string.backup_restore_templates,
                )
            setOnPreferenceClickListener {
                showDialogToSelect(category)

                true
            }
        }
    }

    /** A pick of what to restore, and how it arrives: a group of the thing it speaks about. */
    private fun wireContentRow(
        chooseKey: String,
        modeKey: String,
        category: BRCategory,
        answerKey: String,
    ) {
        findPreference<Preference>(chooseKey)?.apply {
            title =
                getString(
                    if (category == BRCategory.APP) R.string.restore_choose_apps else R.string.restore_choose_templates,
                )
            setOnPreferenceClickListener {
                showDialogToSelect(category)

                true
            }
        }

        findPreference<ListPreference>(modeKey)?.apply {
            title = getString(R.string.restore_mode)
            entries =
                arrayOf(
                    getString(R.string.settings_overwrite),
                    getString(R.string.backup_restore_append),
                )
            entryValues = arrayOf(OVERWRITE, APPEND)
            value = if (storedAnswer(answerKey)) OVERWRITE else APPEND
            setOnPreferenceChangeListener { _, chosen ->
                storeAnswer(answerKey, chosen == OVERWRITE)

                true
            }
        }
    }

    /** The answer a card keeps between visits, since the page is rebuilt whenever it comes back. */
    private fun storedAnswer(key: String): Boolean = preferenceManager.sharedPreferences?.getBoolean(key, true) ?: true

    private fun storeAnswer(
        key: String,
        value: Boolean,
    ) {
        preferenceManager.sharedPreferences
            ?.edit()
            ?.putBoolean(key, value)
            ?.apply()
    }

    /*
     * The page header is this fragment’s, and the preference list is the library’s: it is inflated
     * through the superclass into the container below the header, which is what leaves the library
     * with a list of its own to bind the adapter to.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_backup_restore, container, false)
        val host = root.findViewById<ViewGroup>(R.id.preference_list)

        host.addView(super.onCreateView(inflater, host, savedInstanceState))

        return root
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(preferencesRes, rootKey)
    }

    private val backupSAFLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) backup@{ uri ->
            if (uri == null) return@backup
            val output = contentResolver.openOutputStream(uri)

            if (output == null) {
                showToast(R.string.home_export_failed)
            } else {
                clearNotImportedItems {
                    if (!includeSettings) {
                        val newConfig = JsonConfig(CONFIG_VERSION_NO_SETTINGS)
                        newConfig.templates.putAll(importedConfig.templates)
                        newConfig.scope.putAll(importedConfig.scope)
                        importedConfig = newConfig
                    }

                    showToast(R.string.home_exported)
                    output.write(importedConfig.toString().toByteArray())
                    output.close()

                    navController.navigateUp()
                }
            }
        }

    private val restoreSAFLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) restore@{ uri ->
            if (uri == null) {
                navController.navigateUp()
                return@restore
            }

            try {
                val backupContent =
                    contentResolver
                        .openInputStream(uri)
                        ?.reader()
                        ?.use { it.readText() }
                        ?: error("The backup file could not be opened")
                importedConfig = JsonConfig.parse(backupContent)
                loadScreenContents()
            } catch (cause: Throwable) {
                cause.printStackTrace()
                navController.navigateUp()
                onImportFailed(cause)
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val title =
            getString(
                if (isBackupMode) {
                    R.string.home_backup_config
                } else {
                    R.string.home_restore_config
                },
            )

        binding.pageBar.collapsing.title = title

        setupToolbar(
            binding.pageBar.toolbar,
            title = title,
            navigationIcon = R.drawable.baseline_arrow_back_24,
            navigationOnClick = { navController.navigateUp() },
        )

        stylePreferenceRows()

        if (isBackupMode) {
            importedConfig = ConfigManager.getRawConfig(true)
            loadScreenContents()
        } else {
            restoreSAFLauncher.launch("application/json")
        }

        setEdge2EdgeFlags(binding.root)
    }

    @SuppressLint("DefaultLocale")
    @Suppress("DEPRECATION")
    private fun reloadScreenContents() {
        val apps = getString(R.string.backup_restore_items_count, marked(BRCategory.APP).size)
        val templates = getString(R.string.backup_restore_items_count, marked(BRCategory.TEMPLATE).size)

        // The page that picks its items with the row itself and the one that keeps that pick a row
        // down both say how many items are marked.
        findPreference<Preference>("manageApps")?.summary = apps
        findPreference<Preference>("chooseApps")?.summary = apps
        findPreference<Preference>("manageTemplates")?.summary = templates
        findPreference<Preference>("chooseTemplates")?.summary = templates
    }

    private fun loadScreenContents() {
        marked(BRCategory.APP).addAll(importedConfig.scope.keys)
        marked(BRCategory.TEMPLATE).addAll(importedConfig.templates.keys)

        wirePicker("manageApps", BRCategory.APP)

        wirePicker("manageTemplates", BRCategory.TEMPLATE)

        wireContentRow("chooseApps", "appsMode", BRCategory.APP, "overwriteApps")

        wireContentRow("chooseTemplates", "templatesMode", BRCategory.TEMPLATE, "overwriteTemplates")

        switch("includeSettings")?.apply {
            isChecked = importedConfig.configVersion != CONFIG_VERSION_NO_SETTINGS
            isEnabled = isChecked
        }

        findPreference<Preference>("runBackup")?.setOnPreferenceClickListener {
            onBackup()

            true
        }

        findPreference<Preference>("runRestore")?.setOnPreferenceClickListener {
            onRestore()

            true
        }

        reloadScreenContents()
    }

    private fun showDialogToSelect(category: BRCategory) {
        val items =
            when (category) {
                BRCategory.APP -> importedConfig.scope.keys
                BRCategory.TEMPLATE -> importedConfig.templates.keys
            }

        val title =
            when (category) {
                BRCategory.APP -> getString(R.string.title_app_manage)
                BRCategory.TEMPLATE -> getString(R.string.title_template_manage)
            }

        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setIcon(R.drawable.outline_settings_backup_restore_24)
                .setTitle(title)
                .setNegativeButton(android.R.string.cancel, null)

        if (items.isNotEmpty()) {
            // The rows the rest of the manager chooses with: the whole line answers a touch and the
            // box at its end carries the state, which is read back only when the dialog is answered.
            val container = layoutInflater.inflate(R.layout.dialog_choose_template, null)
            val list = container.findViewById<LinearLayout>(R.id.choice_list)
            val boxes =
                items.map { name ->
                    val row = layoutInflater.inflate(R.layout.row_choice, list, false)
                    val label = row.findViewById<TextView>(R.id.row_text)
                    val box =
                        MaterialCheckBox(row.context).apply {
                            isClickable = false
                            isFocusable = false
                        }
                    row.findViewById<FrameLayout>(R.id.row_control).addView(box)

                    label.text =
                        if (category == BRCategory.APP) {
                            loadAppLabel(name)
                        } else {
                            name
                        }
                    box.isChecked = marked(category).contains(name)
                    row.contentDescription = name
                    row.setOnClickListener { box.toggle() }

                    list.addView(row)
                    box
                }

            dialog.setView(container)
            dialog.setPositiveButton(android.R.string.ok) { _, _ ->
                val marks = marked(category)
                marks.clear()
                marks.addAll(items.filterIndexedTo(mutableSetOf()) { index, _ -> boxes[index].isChecked })

                reloadScreenContents()
            }
        } else {
            val emptyView = LayoutListEmptyBinding.inflate(layoutInflater)
            emptyView.root.isVisible = true
            emptyView.listEmptyIcon.setImageResource(R.drawable.sentiment_very_dissatisfied_24px)
            emptyView.listEmptyText.isVisible = false
            dialog.setView(emptyView.root)
        }

        dialog.show()
    }

    private fun onRestore() =
        try {
            clearNotImportedItems {
                if (!overwriteApps || !overwriteTemplates) {
                    val config = ConfigManager.getRawConfig(false)

                    if (!overwriteApps) {
                        config.scope.map {
                            importedConfig.scope.putIfAbsent(it.key, it.value)
                        }
                    }

                    if (!overwriteTemplates) {
                        config.templates.map {
                            importedConfig.templates.putIfAbsent(it.key, it.value)
                        }
                    }
                }

                if (!includeSettings || importedConfig.configVersion == CONFIG_VERSION_NO_SETTINGS) {
                    val currentConfig = ConfigManager.getRawConfig(true)

                    currentConfig.scope.sync(importedConfig.scope)
                    currentConfig.templates.sync(importedConfig.templates)

                    ConfigManager.importConfig(currentConfig.toString())
                } else {
                    ConfigManager.importConfig(importedConfig.toString())
                }

                showToast(android.R.string.ok)
                navController.navigateUp()
            }
        } catch (cause: Throwable) {
            onImportFailed(cause)
        }

    private fun clearNotImportedItems(onFinish: () -> Unit) {
        importedConfig.scope.removeIf { pkg, _ ->
            !marked(BRCategory.APP).contains(pkg)
        }

        importedConfig.templates.removeIf { template, _ ->
            !marked(BRCategory.TEMPLATE).contains(template)
        }

        if (isBackupMode) {
            importedConfig.cleanRemnantsFromConfig()
        }

        if (trimConfig) {
            val progressDialog =
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_clear_uninstalled_app_configs)
                    .setView(R.layout.dialog_loading)
                    .setCancelable(false)
                    .create()

            progressDialog.show()

            ConfigManager.clearUninstalledAppConfigs(importedConfig) {
                lifecycleScope.launch {
                    progressDialog.dismiss()

                    onFinish()
                }
            }
        } else {
            onFinish()
        }
    }

    private fun onBackup() {
        val date = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.getDefault()).format(Date())
        backupSAFLauncher.launch("asya_config_$date.json")
    }

    private fun onImportFailed(cause: Throwable) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_import_failed)
            .setMessage(cause.message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.show_crash_log) { _, _ ->
                MaterialAlertDialogBuilder(requireActivity())
                    .setCancelable(false)
                    .setTitle(R.string.home_import_failed)
                    .setMessage(cause.stackTraceToString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.show()
    }
}

/** The page that writes a configuration out. */
class BackupFragment : BackupRestoreFragment() {
    override val isBackupMode = true

    override val preferencesRes = R.xml.backup
}

/** The page that reads one in. */
class RestoreFragment : BackupRestoreFragment() {
    override val isBackupMode = false

    override val preferencesRes = R.xml.restore
}
