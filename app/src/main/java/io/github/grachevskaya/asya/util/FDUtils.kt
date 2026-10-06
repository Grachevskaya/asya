package io.github.grachevskaya.asya.util

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.net.toUri
import io.github.grachevskaya.asya.common.Constants.PROVIDER_AUTHORITY
import io.github.grachevskaya.asya.service.ServiceClient.log
import io.github.grachevskaya.asya.service.ServiceProvider

object FDUtils {
    private const val TAG = "FDUtils"

    fun readFromPipe(descriptor: ParcelFileDescriptor) =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    fun writeIntoPipe(provider: ServiceProvider, args: String): ParcelFileDescriptor {
        val uri = "content://${PROVIDER_AUTHORITY}".toUri()

        return provider.openPipeHelper(
            uri, "text/plain", null, args) { output, _, _, _, args ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output)
                    .bufferedWriter(Charsets.UTF_8)
                    .apply {
                        write(args)
                        flush()
                    }
            } catch (cause: Throwable) {
                log(Log.ERROR, TAG, cause.stackTraceToString())
            }
        }
    }
}
