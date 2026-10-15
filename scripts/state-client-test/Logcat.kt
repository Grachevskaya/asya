package io.github.grachevskaya.asya.zygote.util
object Logcat {
    fun logE(tag: String, cause: Throwable, msg: () -> String) { println("expected/diagnostic: ${msg()}: ${cause.javaClass.simpleName}") }
}
