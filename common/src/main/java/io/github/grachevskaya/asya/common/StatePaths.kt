package io.github.grachevskaya.asya.common

import android.util.Log
import java.io.File

/*
 * Where the module keeps what it shares with its two halves, named once.
 *
 * The first choice is beside the module, on storage that is decrypted before the user's is: a boot
 * has the rules and the counters from its first moment instead of waiting for the unlock that the
 * host's data directory needs. The service reads and writes there; the manager reaches the same
 * files. The host's directory is the fallback, for a module whose own state directory is not there.
 */
object StatePaths {
    const val MODULE_STATE_ROOT = "/data/adb/asya"

    const val HOST_DATA_ROOT = "/data/data/com.android.systemui"

    const val DIR_PREFIX = ".cache_"

    const val FILTER_COUNTS = "filter_counts.json"

    const val CONFIG_NAME = "config.json"

    const val NATIVE_LIBS_NAME = "native_libs.json"


    const val MANAGER_APK_NAME = "manager.apk"

    const val LOG_NAME = "runtime.log"

    private const val TAG = "StatePaths"

    /** The module's own directory, when it is there. Null is "not now", never "nowhere". */
    fun moduleStateDirOrNull(): File? =
        runCatching { File(MODULE_STATE_ROOT) }.getOrNull()?.let { if (it.isDirectory) it else null }

    /**
     * The state directory, or null while neither place can be used.
     *
     * Null is not "there is no state": before the user's storage is mounted, the directory that holds
     * it does not exist at all, and a name made up in that window -- together with a mkdirs whose
     * failure was never asked about -- is how the manager came to read a configuration out of a place
     * nothing had ever written. Every caller treats null as "not now" and asks again when it is.
     */
    fun stateDirOrNull(): File? {
        moduleStateDirOrNull()?.let { return it }

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
