package io.github.grachevskaya.asya.zygote.service

import android.content.pm.IPackageManager
import io.github.grachevskaya.asya.common.BuildConfig
import io.github.grachevskaya.asya.zygote.util.Logcat.logI

/**
 * The service, for the hooks to reach, and the one place the system side is started.
 *
 * The manager is not an installed app: it reads the same files this service writes, so the
 * registration itself is what the system side calls by name.
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
