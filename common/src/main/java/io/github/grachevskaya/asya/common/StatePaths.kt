package io.github.grachevskaya.asya.common

/**
 * Where the two sides keep what they share, named once.
 *
 * The side that owns the disk is the manager: the service hands its data over the bridge and keeps
 * none of it. The directory is the host's own data directory, because that is the one place both
 * already reach -- the host because the directory is its own, the service because it may work in an
 * app's data -- and the prefix is neutral, so the name says nothing about this module.
 */
object StatePaths {
    const val HOST_DATA_ROOT = "/data/data/com.android.systemui"

    const val DIR_PREFIX = ".cache_"

    /** The directory the manager keeps its own files in, named by the manager itself. */
    const val MANAGER_DIR = "manager"

    /** The totals of filtered queries, added up as the service hands them over. */
    const val FILTER_COUNTS = "filter_counts.json"

    /** The lines the service sent, appended by the manager. */
    const val LOG_NAME = "runtime.log"
}
