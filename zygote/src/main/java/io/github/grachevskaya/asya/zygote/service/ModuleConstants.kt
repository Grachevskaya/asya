package io.github.grachevskaya.asya.zygote.service

/*
 * What the whole package shares: the name the module logs under, the classes the kernel is told an
 * app falls in, and the host directory whose state this module keeps together with the prefix that
 * directory is found by.
 */

internal const val TAG = "Asya-Service"

internal const val ISOLATION_NONE = 0

internal const val ISOLATION_APP = 1

internal const val ISOLATION_NATIVE = 2

/**
 * Where the state lives: a random name under /data/misc, whose tail is random so that the name
 * is not a fixed thing to look for.
 *
 * The manager cannot read it: it is an activity of the host process, whose domain is denied
 * the system_data_file type that /data/misc carries. So the status is mirrored to one file in
 * the host's own private directory, which the host can read because the directory is its own
 * -- and whether this service can write there is answered by the log line that says so.
 */
internal const val HOST_DATA_ROOT = "/data/data/com.android.systemui"

internal const val DIR_PREFIX = ".cache_"

/** The file only the host's own plugin directory holds, which tells it apart from the state one. */
internal const val MANAGER_APK_NAME = "manager.apk"
