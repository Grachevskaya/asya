package io.github.grachevskaya.asya.zygote.service

import android.content.pm.IPackageManager
import io.github.grachevskaya.asya.common.BuildConfig
import io.github.grachevskaya.asya.zygote.util.Logcat.logI

/**
 * The service, for the hooks to reach, and the one place the system side is started.
 *
 * Upstream also watches for the manager app's uid to come up and hands it a binder through the app's
 * own content provider. This fork has no installed app to hand anything to -- the manager reads the
 * same files this service writes -- so what is left of that arrangement is the registration itself,
 * which is the name the system side calls.
 */
object UserService {
    private const val TAG = "Asya-UserService"

    var service: AsyaService? = null

    fun register(
        pms: IPackageManager,
        pmn: Any?,
    ) {
        assert(service == null) { "You cannot register the service more than once" }

        logI(TAG) { "Initialize AsyaService - Version ${BuildConfig.APP_VERSION_NAME}" }

        val created = AsyaService(pms, pmn)
        service = created
        /*
         * Publish to the in-process bridge. The manager runs inside this very process (the
         * host loads its Activity), so it can read live values instead of the last snapshot
         * written to a file. The files stay: they outlive a restart and the root side reads
         * them too.
         */
        io.github.grachevskaya.asya.common.ServiceBridge.host = created
    }
}
