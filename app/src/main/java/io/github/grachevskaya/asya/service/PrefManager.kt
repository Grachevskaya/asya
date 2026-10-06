package io.github.grachevskaya.asya.service

import android.content.ComponentName
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import io.github.grachevskaya.asya.MyApp.Companion.hmaApp
import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.data.AppConstants
import io.github.grachevskaya.asya.ui.util.get
import io.github.grachevskaya.asya.util.PackageHelper.findEnabledAppComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

object PrefManager {

    private const val PREF_LOCALE = "language"

    private const val PREF_DARK_THEME = "dark_theme"
    private const val PREF_BLACK_DARK_THEME = "black_dark_theme"
    private const val PREF_FOLLOW_SYSTEM_ACCENT = "follow_system_accent"
    private const val PREF_THEME_COLOR = "theme_color"

    private const val PREF_BYPASS_RISKY_PACKAGE_WARNING = "bypass_risky_package_warning"

    private const val PREF_ENABLE_INTERNET = "enable_internet"
    private const val PREF_DISABLE_UPDATE = "disable_update"

    private const val PREF_APP_FILTER_SHOW_SYSTEM = "app_filter_show_system"
    private const val PREF_APP_FILTER_SORT_METHOD = "app_filter_sort_method"
    private const val PREF_APP_FILTER_REVERSE_ORDER = "app_filter_reverse_order"
    private const val PREF_LOG_FILTER_LEVEL = "log_filter_level"
    private const val PREF_LOG_FILTER_REVERSE_ORDER = "log_filter_reverse_order"

    enum class SortMethod {
        BY_LABEL, BY_PACKAGE_NAME, BY_INSTALL_TIME, BY_UPDATE_TIME
    }

    private val pref = asyaApp.getSharedPreferences("settings", MODE_PRIVATE)

    var locale: String
        get() = pref.getString(PREF_LOCALE, "SYSTEM")!!
        set(value) = pref.edit { putString(PREF_LOCALE, value) }

    var darkTheme: Int
        get() = pref.getInt(PREF_DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit { putInt(PREF_DARK_THEME, value) }

    var blackDarkTheme: Boolean
        get() = pref.getBoolean(PREF_BLACK_DARK_THEME, false)
        set(value) = pref.edit { putBoolean(PREF_BLACK_DARK_THEME, value) }

    var followSystemAccent: Boolean
        get() = pref.getBoolean(PREF_FOLLOW_SYSTEM_ACCENT, true)
        set(value) = pref.edit { putBoolean(PREF_FOLLOW_SYSTEM_ACCENT, value) }

    var themeColor: String
        get() = pref.getString(PREF_THEME_COLOR, "MATERIAL_BLUE")!!
        set(value) = pref.edit { putString(PREF_THEME_COLOR, value) }

    var bypassRiskyPackageWarning: Boolean
        get() = pref.getBoolean(PREF_BYPASS_RISKY_PACKAGE_WARNING, false)
        set(value) = pref.edit { putBoolean(PREF_BYPASS_RISKY_PACKAGE_WARNING, value) }

    /**
     * Enable/disable the Internet connection in the manager app
     *
     * States:
     * - ENABLE_INTERNET_UNKNOWN - The user didn't pass the dialog yet
     * - ENABLE_INTERNET_ON - The user granted the Internet permission
     * - ENABLE_INTERNET_OFF - The user rejected the Internet permission
     */
    var enableInternet: Int
        get() = pref.getInt(PREF_ENABLE_INTERNET, Constants.ENABLE_INTERNET_UNKNOWN)
        set(value) = pref.edit { putInt(PREF_ENABLE_INTERNET, value) }

    fun setEnableInternet(value: Boolean) {
            enableInternet = if (value) {
                    Constants.ENABLE_INTERNET_ON
                } else {
                    Constants.ENABLE_INTERNET_OFF
                }
        }

    var disableUpdate: Boolean
        get() = pref.getBoolean(PREF_DISABLE_UPDATE, false)
        set(value) = pref.edit { putBoolean(PREF_DISABLE_UPDATE, value) }

    var appFilter_showSystem: Boolean
        get() = pref.getBoolean(PREF_APP_FILTER_SHOW_SYSTEM, false)
        set(value) = pref.edit { putBoolean(PREF_APP_FILTER_SHOW_SYSTEM, value) }

    var appFilter_sortMethod: SortMethod
        get() = SortMethod.entries[pref.getInt(PREF_APP_FILTER_SORT_METHOD, SortMethod.BY_LABEL.ordinal)]
        set(value) = pref.edit { putInt(PREF_APP_FILTER_SORT_METHOD, value.ordinal) }

    var appFilter_reverseOrder: Boolean
        get() = pref.getBoolean(PREF_APP_FILTER_REVERSE_ORDER, false)
        set(value) = pref.edit { putBoolean(PREF_APP_FILTER_REVERSE_ORDER, value) }

    var logFilter_level: Int
        get() = pref.getInt(PREF_LOG_FILTER_LEVEL, 0)
        set(value) = pref.edit { putInt(PREF_LOG_FILTER_LEVEL, value) }

    var logFilter_reverseOrder: Boolean
        get() = pref.getBoolean(PREF_LOG_FILTER_REVERSE_ORDER, false)
        set(value) = pref.edit { putBoolean(PREF_LOG_FILTER_REVERSE_ORDER, value) }
}
