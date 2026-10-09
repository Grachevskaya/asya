package io.github.grachevskaya.asya.common

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

    const val LOG_NAME = "runtime.log"

    /**
     * The first prefixed directory under the host's data directory, made if there is none. Asked for
     * here and nowhere else: a second opinion about where the state lives is how the manager came to
     * read a file that was never written.
     */
    fun stateDir(): File {
        val root = File(HOST_DATA_ROOT)

        val found =
            runCatching {
                root.listFiles { file: File -> file.isDirectory && file.name.startsWith(DIR_PREFIX) }
            }.getOrNull()

        return found
            ?.sortedBy { it.name }
            ?.firstOrNull()
            ?: File(root, DIR_PREFIX + java.lang.Long.toHexString(System.nanoTime())).apply { mkdirs() }
    }
}
