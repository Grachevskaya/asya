package io.github.grachevskaya.asya.zygote.host

import android.app.ActivityThread
import io.github.grachevskaya.asya.zygote.service.BulkHooker
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.ZLUtils.thisObject
import com.v7878.zygisk.ZygoteLoader

/**
 * The way in, installed where the framework will pass it.
 *
 * Sui does this with a native hook on a binder transaction and then calls its Java side. This
 * module already carries an ART method hook engine -- the service half uses it for the package
 * manager -- so the same moment can be taken from here: the call to handleBindApplication is the
 * one the framework makes for the process that is coming up, and a hook *after* it is exactly
 * Sui's postBindApplication: the application is bound, the instrumentation has been initialised,
 * and no activity has been created yet.
 *
 * Nothing is guessed and nothing is polled: the hook runs inside the framework's own call, with
 * the ActivityThread it was made on.
 */
object HostHook {

    private const val TAG = "HostHook"

    private const val ACTIVITY_THREAD_CLASS = "android.app.ActivityThread"
    private const val BIND_APPLICATION_METHOD = "handleBindApplication"

    fun install() {
        if (HostConstants.HOST_PACKAGE != thisProcess()) return

        val hooker = BulkHooker()

        hooker.hookAfter(ACTIVITY_THREAD_CLASS, BIND_APPLICATION_METHOD) { name, frame, _ ->
            val thread = frame.thisObject as? ActivityThread
            if (thread == null) {
                logI(TAG) { "$name was called without an activity thread" }
                return@hookAfter
            }

            HostProcess.onApplicationBound(thread)
        }

        logI(TAG) { "The bind hook is in place" }
    }

    private fun thisProcess(): String? = try {
        ZygoteLoader.getPackageName()
    } catch (cause: Throwable) {
        null
    }
}
