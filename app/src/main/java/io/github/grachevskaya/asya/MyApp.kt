package io.github.grachevskaya.asya

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import io.github.grachevskaya.asya.common.ServiceBridge
import io.github.grachevskaya.asya.service.ConfigManager
import io.github.grachevskaya.asya.service.PrefManager
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.util.StatusRefresh
import io.github.grachevskaya.asya.util.PackageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class MyApp : Application() {
    companion object {
        lateinit var asyaApp: MyApp
    }

    val globalScope = CoroutineScope(Dispatchers.Default)

    fun loadConfiguration() {
        ConfigManager.init()
    }

    fun loadPreferences() {
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)

        /*
         * The language is not applied here: both updateConfiguration on these resources and AppCompat
         * application locales write the configuration of the process, and in a hosted manager the
         * process is the host's. It is applied to the manager's own context instead, where the apk's
         * resources live (MainActivity.getLocaleAppliedContext, HostContext).
         */
    }

    override fun onCreate() {
        super.onCreate()
        asyaApp = this

        loadPreferences()
        loadConfiguration()

        // A package transaction is over: the tables and the lists may be read again.
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
