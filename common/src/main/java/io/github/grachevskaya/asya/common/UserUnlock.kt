package io.github.grachevskaya.asya.common

import android.content.Context
import android.os.UserManager

/*
 * The one place the unlock is asked about and waited for.
 *
 * The user is named by number: the context a caller holds may belong to a different user -- the
 * system context is the SYSTEM user's -- and the no-argument form answers about that one.
 *
 * Nothing here waits on a broadcast: a receiver that is never called cannot be told apart from a
 * device that never unlocked, and the contexts this module works with belong to other users and
 * other processes. There is no deadline either: nothing is built or pushed before the storage is
 * readable, and the unlock is the event being waited for.
 */

/** The user the manager and its files live in: the host runs in user 0. */
private const val MANAGER_USER = 0

/**
 * Whether the storage this module's work needs can be read yet.
 *
 * Two questions, and which applies depends on who asks. Where the caller may ask about user 0 -- the
 * service, which holds the permissions -- UserManager's answer is final: a false there is a locked
 * user, and the kernel tables must not be built. Where it may not ask -- the manager, in the host --
 * the question is whether its own directory can be listed, which is all its files need.
 */
fun storageIsUnlocked(context: Context?): Boolean {
    unlockedFor(MANAGER_USER, context)?.let { return it }

    if (ceAvailable()) return true

    return stateDirReadable()
}

/** The manager's own directory, listed: its configuration, log and totals are files in there. */
private fun stateDirReadable(): Boolean =
    runCatching { StatePaths.stateDir().list() != null }.getOrDefault(false)

/** The answer for one user, or null if the runtime will not be asked that question at all. */
private fun unlockedFor(
    user: Int,
    context: Context?,
): Boolean? =
    runCatching {
        val manager = (context ?: appContext())?.getSystemService(UserManager::class.java) ?: return@runCatching null
        val method = UserManager::class.java.getMethod("isUserUnlocked", Int::class.javaPrimitiveType)

        method.invoke(manager, user) as? Boolean
    }.getOrNull()

/** The framework's own signal for user 0's credential-encrypted storage. */
private fun ceAvailable(): Boolean =
    runCatching {
        val type = Class.forName("android.os.SystemProperties")
        val get = type.getMethod("get", String::class.java)
        val value = get.invoke(null, "sys.user.0.ce_available") as? String

        value == "1" || value.equals("true", ignoreCase = true)
    }.getOrDefault(false)

/** Blocking, with no deadline: for a thread with nothing else to do until it returns. */
fun awaitUserUnlocked(context: Context?) {
    while (!storageIsUnlocked(context)) {
        try {
            Thread.sleep(POLL_MS)
        } catch (_: InterruptedException) {
            return
        }
    }
}

/** Not blocking: for a caller that must not be held, such as the host's main thread. */
fun whenUserUnlocked(
    context: Context?,
    action: () -> Unit,
) {
    if (storageIsUnlocked(context)) {
        action()

        return
    }

    Thread(
        {
            awaitUserUnlocked(context)
            action()
        },
        "asya-wait-unlock",
    ).apply { isDaemon = true }.start()
}

private const val POLL_MS = 250L

/** For a caller with no context of its own, which is the service side. */
private fun appContext(): Context? =
    runCatching {
        Class
            .forName("android.app.AppGlobals")
            .getMethod("getInitialApplication")
            .invoke(null) as? Context
    }.getOrNull()
