package io.github.grachevskaya.asya.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The one place the unlock is asked about and waited for.
 *
 * The user is named by number: the context a caller holds may belong to a different user -- the
 * system context is the SYSTEM user's -- and the no-argument form answers about that one.
 *
 * The wait is the unlock broadcast, not a poll: asking again and again wakes a process that has
 * nothing to do, and SystemUI has better things to do than answer this. The answer is re-checked once
 * when the broadcast arrives, because a receiver that is called for another user's unlock is not an
 * answer about this one.
 */

/** The user the manager and its files live in: the host runs in user 0. */
private const val MANAGER_USER = 0

/**
 * Whether the storage this module's work needs can be read yet.
 *
 * Asked of UserManager, about the user by number, and of nothing else: whether a file happens to be
 * reachable is neither necessary nor sufficient for that answer.
 */
fun storageIsUnlocked(context: Context?): Boolean {
    if (unlockedFor(MANAGER_USER, context) == true) return true

    return ceAvailable()
}

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

/**
 * Blocking, with no deadline: for a thread with nothing else to do until it returns.
 *
 * A device can sit at the lock screen for as long as its owner leaves it there, and the work that
 * waits here is work the unlock is what makes possible: the package model is not complete until the
 * storage is readable, and a table built before then hides the wrong things. So the wait has no end
 * of its own -- its answer is the unlock, however late it comes.
 *
 * The answer is whether the storage can be read. The receiver goes in first and the question is asked
 * again after it, because the broadcast is sent once: an unlock that landed in between is answered by
 * the second question rather than by a broadcast that has already gone by.
 */
fun awaitUserUnlocked(context: Context?): Boolean {
    if (storageIsUnlocked(context)) return true

    val baseContext = context ?: appContext() ?: return false
    val user0Context = unlockContext(baseContext)
    val unlocked = CountDownLatch(1)
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                receiverContext: Context?,
                intent: Intent?,
            ) {
                if (storageIsUnlocked(context)) unlocked.countDown()
            }
        }

    if (runCatching { user0Context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED)) }.isFailure) {
        return storageIsUnlocked(context)
    }

    try {
        // Registered before the question is asked again: an unlock that landed in between was answered
        // by no receiver, and the broadcast is sent once rather than to whoever comes later.
        if (storageIsUnlocked(context)) return true

        unlocked.await()

        return true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()

        return false
    } finally {
        runCatching { user0Context.unregisterReceiver(receiver) }
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

    val baseContext = context ?: appContext() ?: return action()
    val user0Context = unlockContext(baseContext)
    val ran = AtomicBoolean(false)

    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                receiverContext: Context?,
                intent: Intent?,
            ) {
                if (!storageIsUnlocked(context)) return
                if (!ran.compareAndSet(false, true)) return

                runCatching { user0Context.unregisterReceiver(this) }
                action()
            }
        }

    if (runCatching { user0Context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED)) }.isFailure) {
        if (ran.compareAndSet(false, true)) action()

        return
    }

    // Registered before the question is asked again: an unlock that landed in between was answered by
    // no receiver, and the broadcast is sent once rather than to whoever comes later.
    if (storageIsUnlocked(context) && ran.compareAndSet(false, true)) {
        runCatching { user0Context.unregisterReceiver(receiver) }
        action()
    }
}

/**
 * The context the unlock receiver has to be registered on.
 *
 * ACTION_USER_UNLOCKED is sent as the user it is about and only to registered receivers, so a context
 * bound to another user never sees it: the system context is the SYSTEM user's, and a receiver
 * registered there is one this broadcast cannot reach.
 */
private fun unlockContext(context: Context): Context =
    runCatching {
        val handle =
            Class
                .forName("android.os.UserHandle")
                .getMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, MANAGER_USER)

        Context::class.java
            .getMethod(
                "createContextAsUser",
                Class.forName("android.os.UserHandle"),
                Int::class.javaPrimitiveType,
            ).invoke(context, handle, 0) as? Context
    }.getOrNull() ?: context

/** For a caller with no context of its own, which is the service side. */
private fun appContext(): Context? =
    runCatching {
        Class
            .forName("android.app.AppGlobals")
            .getMethod("getInitialApplication")
            .invoke(null) as? Context
    }.getOrNull()
