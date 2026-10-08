package io.github.grachevskaya.asya.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.clearFragmentResultListener
import androidx.fragment.app.setFragmentResultListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import io.github.grachevskaya.asya.R
import io.github.grachevskaya.asya.common.JsonConfig
import io.github.grachevskaya.asya.databinding.FragmentTemplateManageBinding
import io.github.grachevskaya.asya.service.ConfigManager
import io.github.grachevskaya.asya.ui.adapter.TemplateAdapter
import io.github.grachevskaya.asya.ui.util.RowGroup
import io.github.grachevskaya.asya.ui.util.groupedRowBackground
import io.github.grachevskaya.asya.ui.util.navigate
import io.github.grachevskaya.asya.ui.util.setEdge2EdgeFlags
import io.github.grachevskaya.asya.ui.util.setupToolbar

class TemplateManageFragment : Fragment(R.layout.fragment_template_manage) {
    private val binding by viewBinding(FragmentTemplateManageBinding::bind)
    private val adapter = TemplateAdapter(this::navigateToTemplateSettings)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        // The two "new template" rows are one group: the shape system rounds the ends.
        binding.newBlacklistTemplate.background = requireContext().groupedRowBackground(RowGroup.Top)
        binding.newWhitelistTemplate.background = requireContext().groupedRowBackground(RowGroup.Bottom)

        setupToolbar(
            toolbar = binding.pageBar.toolbar,
            title = getString(R.string.title_template_manage),
            menuRes = R.menu.menu_template_manage,
            onMenuOptionSelected = {
                when (it.itemId) {
                    R.id.menu_info -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setIcon(R.drawable.outline_info_24)
                            .setTitle(R.string.title_template_manage)
                            .setMessage(R.string.template_usage_hint)
                            .setNegativeButton(android.R.string.ok, null)
                            .show()
                    }
                }
            },
        )

        binding.pageBar.collapsing.title = getString(R.string.title_template_manage)
        binding.pageBar.toolbar.title = null

        binding.newBlacklistTemplate.setOnClickListener {
            navigateToTemplateSettings(ConfigManager.TemplateInfo(null, ConfigManager.PTType.APP, false))
        }
        binding.newWhitelistTemplate.setOnClickListener {
            navigateToTemplateSettings(ConfigManager.TemplateInfo(null, ConfigManager.PTType.APP, true))
        }
        binding.templateList.layoutManager = LinearLayoutManager(context)
        binding.templateList.adapter = adapter

        setEdge2EdgeFlags(binding.root)
    }

    private fun navigateToTemplateSettings(info: ConfigManager.TemplateInfo) {
        setFragmentResultListener("template_settings") { _, bundle ->
            val name = bundle.getString("name")
            val appliedList = bundle.getStringArrayList("appliedList")!!
            val targetList = bundle.getStringArrayList("targetList")!!

            if (info.name == null) {
                if (!name.isNullOrEmpty()) {
                    ConfigManager.updateTemplate(
                        name,
                        JsonConfig.Template(info.isWhiteList, targetList.toSet()),
                    )
                    ConfigManager.updateTemplateAppliedApps(name, appliedList)
                }
            } else {
                if (name == null) {
                    ConfigManager.deleteTemplate(info.name)
                } else {
                    val finalName = name.ifEmpty { info.name }
                    if (finalName != info.name) ConfigManager.renameTemplate(info.name, finalName)
                    ConfigManager.updateTemplate(
                        finalName,
                        JsonConfig.Template(info.isWhiteList, targetList.toSet()),
                    )
                    ConfigManager.updateTemplateAppliedApps(finalName, appliedList)
                }
            }

            adapter.updateList()
            clearFragmentResultListener("template_settings")
        }

        val args = TemplateSettingsFragmentArgs(info.name, info.isWhiteList)
        navigate(R.id.nav_template_settings, args.toBundle())
    }
}
