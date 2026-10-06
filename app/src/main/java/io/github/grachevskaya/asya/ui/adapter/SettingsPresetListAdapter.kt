package io.github.grachevskaya.asya.ui.adapter

import io.github.grachevskaya.asya.common.SettingsPresets
import io.github.grachevskaya.asya.common.settings_presets.ReplacementItem

class SettingsPresetListAdapter(name: String) : BaseSettingsPTAdapter() {
    override val items by lazy {
        SettingsPresets.instance.getPresetByName(name)!!.settingsKVPairs.sortedBy { it.name }
    }

    override fun onItemClick(item: ReplacementItem) {
        // do nothing
    }
}
