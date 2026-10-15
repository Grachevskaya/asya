package io.github.grachevskaya.asya.common

import android.util.Log
import java.io.File

/*
 * Where the manager keeps what it shares with the service, named once. The manager is the only side
 * that touches the disk: it runs in the host's process, and the host's data directory is the one
 * place it has. The name is neutral, so it says nothing about this module.
 */
object StatePaths {
    const val HOST_DATA_ROOT = "/data/data/com.android.systemui"

    const val DIR_PREFIX = ".cache_"

    const val FILTER_COUNTS = "filter_counts.json"

    const val CONFIG_NAME = "config.json"
    const val MANAGER_APK_NAME = "manager.apk"

    const val LOG_NAME = "runtime.log"

    private const val TAG = "StatePaths"

    /**
     * The state directory, or null while the host's data directory cannot be read.
     *
     * Null is not "there is no state": before the user's storage is mounted, the directory that holds
     * it does not exist at all, and a name made up in that window -- together with a mkdirs whose
     * failure was never asked about -- is how the manager came to read a configuration out of a place
     * nothing had ever written. Every caller treats null as "not now" and asks again when it is.
     *
     * Nothing here is fixed in the source: a name written down is a name anyone holding this module
     * could look for. It does not have to be, either, because there is only ever one such directory to
     * find, and finding it is the whole of what this does.
     *
     * Asked at the right time -- the wait for the user's unlock is what makes it the right time --
     * there are two outcomes, and null is neither of them: a directory that was found, or one that was
     * made here and now. A null therefore means the wait was not respected, and says so in the log.
     */
    fun stateDirOrNull(): File? {
        val root = File(HOST_DATA_ROOT)

        // A root that cannot be listed is one that is not there yet, or not ours to read. Either way
        // there is nothing here to be decided.
        val entries = runCatching { root.listFiles() }.getOrNull()
        if (entries == null) {
            Log.w(TAG, "The host's data directory cannot be listed; the state is not resolved")

            return null
        }

        val kept =
            entries
                .filter { it.isDirectory && it.name.startsWith(DIR_PREFIX) }
                .sortedBy { it.name }
                .firstOrNull()
        if (kept != null) return kept

        // Nothing is kept yet. The one place is made now, and only if it can be: a path whose mkdirs
        // did not happen is a path nothing will ever be written to, which is exactly what went wrong.
        val made = File(root, DIR_PREFIX + java.lang.Long.toHexString(System.nanoTime()))
        if (made.mkdirs()) return made

        Log.w(TAG, "The state directory could not be made under " + root)

        return null
    }
}
