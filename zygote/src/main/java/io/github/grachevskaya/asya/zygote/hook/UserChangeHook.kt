package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.zygote.util.Logcat.logI

/**
 * A user being added or removed, taken as an event.
 *
 * The apk table is built from every user's packages, so a new user -- a work profile, a clone, a
 * second space -- changes that table as surely as an install does. It does not arrive as a package
 * broadcast: adding a user installs their copy of everything, and removing one takes it away, but
 * the signal for the user itself is this pair of methods, which the platform calls on
 * UserManagerService for exactly this reason. Watching them is watching the event; nothing here has
 * to be polled, and nothing else in the system has to be hooked.
 */
class UserChangeHook : IFrameworkHook {
    override val TAG = "UserChangeHook"

    override fun load() {
        logI(TAG) { "Load hook" }

        hooker.apply {
            for (method in METHODS) {
                if (!isHookAvailable(USER_MANAGER_SERVICE, method)) continue

                hookBefore(USER_MANAGER_SERVICE, method) { _, _, _ -> service.onUsersChanged() }
            }
        }
    }

    private companion object {
        const val USER_MANAGER_SERVICE = "com.android.server.pm.UserManagerService"

        val METHODS = arrayOf("onUserAdded", "onUserRemoved")
    }
}
