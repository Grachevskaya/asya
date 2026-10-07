package io.github.grachevskaya.asya

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import io.github.grachevskaya.asya.common.ServiceBridge
import io.github.grachevskaya.asya.receiver.AppChangeReceiver
import io.github.grachevskaya.asya.service.ConfigManager
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.util.StatusRefresh
import io.github.grachevskaya.asya.util.ConfigUtils.getLocale
import io.github.grachevskaya.asya.util.PackageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Locale

class MyApp : Application() {
    companion object {
        lateinit var asyaApp: MyApp
    }

    val globalScope = CoroutineScope(Dispatchers.Default)
    fun loadConfiguration() {
        if (ServiceClient.serviceVersion > 0) {
            ConfigManager.init()
        }
    }

    fun loadPreferences() {
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)

        /*
         * The language is not applied here. Both ways of doing it -- updateConfiguration on these
         * resources, and AppCompat application locales -- write the configuration of the process,
         * and in a hosted manager the process is the host: the host changed language with the
         * manager. The choice is applied to the manager own context instead, where the resources
         * of the apk live: MainActivity.getLocaleAppliedContext and HostContext.
         */
    }

    override fun onCreate() {
        super.onCreate()
        asyaApp = this
        loadPreferences()
        AppChangeReceiver.register(this)

        // The service's word, over the bridge the host registered: a transaction is over, so the
        // tables may be read again. The package broadcasts above arrive earlier than that.
        ServiceBridge.onServiceEvent = {
            PackageHelper.invalidateCache()
            StatusRefresh.trigger()
        }

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            ServiceClient.log(Log.ERROR, t.name, e.stackTraceToString())
            handler?.uncaughtException(t, e)
        }
    }
}
