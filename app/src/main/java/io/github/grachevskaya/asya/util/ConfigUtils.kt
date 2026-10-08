package io.github.grachevskaya.asya.util

import android.content.res.Resources
import io.github.grachevskaya.asya.service.PrefManager
import java.util.Locale

object ConfigUtils {
    fun getSystemLocale(): Locale =
        Resources
            .getSystem()
            .configuration.locales
            .get(0)

    fun getLocale(tag: String = PrefManager.locale): Locale =
        if (tag == "SYSTEM") {
            getSystemLocale()
        } else {
            Locale.forLanguageTag(tag)
        }
}
