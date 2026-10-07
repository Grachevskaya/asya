package io.github.grachevskaya.asya.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.grachevskaya.asya.service.ServiceClient
import io.github.grachevskaya.asya.ui.util.StatusRefresh
import io.github.grachevskaya.asya.util.PackageHelper

class AppChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppChangeReceiver"

        private val actions = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED
        )

        fun register(context: Context) {
            val filter = IntentFilter().apply {
                actions.forEach(::addAction)
                addDataScheme("package")
            }
            context.registerReceiver(AppChangeReceiver(), filter)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in actions) {
            ServiceClient.log(Log.INFO, TAG, "Received intent: $intent")
            PackageHelper.invalidateCache()

            // The bridge is the authority on when a transaction is over, and it is what the manager
            // refreshes on. This is the way out when it is not there -- a build whose hook the
            // framework does not have, or a process the host has not offered it from yet: the
            // broadcast is early, so the cards may read the old shape, and they read it again.
            StatusRefresh.trigger()
        }
    }
}
