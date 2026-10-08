package io.github.grachevskaya.asya.zygote.service

import java.io.File

/*
 * The files a line reaches when it is written, and whether the sink is there at all. The lock that
 * serialises the writes stays in the service, because the sink itself does.
 */
internal object ModuleLogger {
    @Volatile
    internal var logcatAvailable = false

    internal lateinit var logFile: File
    internal lateinit var oldLogFile: File
}
