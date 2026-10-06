package io.github.grachevskaya.asya.zygote.util

import android.app.ActivityThread

object ContextUtils {
    val application get() = ActivityThread.currentActivityThread().application!!

    val packageManager get() = application.packageManager!!

    val contentResolver get() = application.contentResolver!!
}
