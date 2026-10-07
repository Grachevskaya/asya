package io.github.grachevskaya.asya.zygote.util

import android.app.IActivityManager
import android.content.Context
import android.os.RemoteException
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.waitForService


object ActivityManagerUtils {
    private val activityService by lazy { waitForService(Context.ACTIVITY_SERVICE) as? IActivityManager }

    @Throws(RemoteException::class)
    fun forceStopPackage(packageName: String, userId: Int) {
        activityService?.forceStopPackage(packageName, userId)
    }
}
