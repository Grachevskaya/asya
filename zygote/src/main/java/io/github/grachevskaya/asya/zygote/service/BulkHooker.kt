package io.github.grachevskaya.asya.zygote.service

import android.os.Build
import com.v7878.unsafe.ArtMethodUtils
import com.v7878.unsafe.Reflection
import com.v7878.unsafe.invoke.EmulatedStackFrame
import com.v7878.unsafe.invoke.EmulatedStackFrame.RETURN_VALUE_IDX
import com.v7878.unsafe.invoke.Transformers
import com.v7878.vmtools.HookTransformer
import com.v7878.vmtools.Hooks
import io.github.grachevskaya.asya.zygote.ZygoteEntry
import io.github.grachevskaya.asya.zygote.service.UserService.service
import io.github.grachevskaya.asya.zygote.util.Logcat.logD
import io.github.grachevskaya.asya.zygote.util.Logcat.logE
import io.github.grachevskaya.asya.zygote.util.Logcat.logI
import io.github.grachevskaya.asya.zygote.util.Logcat.logV
import io.github.grachevskaya.asya.zygote.util.ServiceUtils
import io.github.grachevskaya.asya.zygote.util.ZLUtils.dumpArgs
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZLUtils.setReturnValue
import io.github.grachevskaya.asya.zygote.util.ZygoteConstants.CONSTRUCTOR_METHOD_NAME
import java.lang.invoke.MethodHandle
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class BulkHooker {
    internal companion object {
        const val PARAMETER_COUNT_UNKNOWN = -1

        /**
         * The value a hook call decides with, reused per thread: a hook runs on the thread that made
         * the call, hundreds of times a second, and a fresh object for each of them is garbage in a
         * process whose collections are the framework's pauses.
         */
        val threadReturnValues = ThreadLocal.withInitial { ReturnValuePool() }
    }

    /** A value with nothing of the call before it, for the call about to run. */
    private fun acquireReturnValue() = threadReturnValues.get().acquire()

    private fun releaseReturnValue(value: ReturnValue) = threadReturnValues.get().release(value)

    var hooksWasCrashed = false

    internal val hooks = ConcurrentHashMap<String, CopyOnWriteArrayList<HookElement>>()

    internal fun isHookAvailable(
        clazz: String,
        method: String,
    ) = findHookElement(clazz, method) != null

    private fun findHookElement(
        clazz: String,
        method: String,
    ) = hooks[clazz]?.firstOrNull { it.methodName == method }

    private fun addHook(
        clazz: String,
        methodName: String,
        argumentCount: Int,
        impl: HookTransformer,
    ) {
        val isConstructorHook = methodName == CONSTRUCTOR_METHOD_NAME
        if (isConstructorHook && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            logI(ZygoteEntry.TAG) { "Constructor hook removed for Android 12-: $clazz -> $methodName($argumentCount)" }
        }

        val element =
            HookElement(
                impl = impl,
                methodName = methodName,
                argumentCount = argumentCount,
            )

        if (applyHook(clazz, element)) {
            hooks.computeIfAbsent(clazz) { CopyOnWriteArrayList() }.add(element)
        } else if (!hooksWasCrashed) {
            logI(ZygoteEntry.TAG) { "Invalid hook removed: $clazz -> $methodName($argumentCount)" }
        }
    }

    /**
     * A hook that runs before the original, with the half that runs after it.
     *
     * [restore] is the other half of a rewrite of the arguments: it runs once the original is done,
     * whatever the original answered, and before the answer is written back. It is here rather than
     * in a second registration because the hooking library puts one transformer in front of a method
     * -- a second registration on the same method replaces this one instead of nesting inside it --
     * so the two halves of one call are one hook.
     */
    internal fun hookBefore(
        clazz: String,
        methodName: String,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        restore: ((frame: EmulatedStackFrame) -> Unit)? = null,
        hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = addHook(clazz, methodName, argumentCount) { original, frame ->
        val value = acquireReturnValue()

        try {
            HookDispatch.before(
                value = value,
                hook = { hook(methodName, frame, value) },
                invokeOriginal = { invokeExactCompat(clazz, methodName, original, frame, value) },
                restore = { restore?.invoke(frame) },
                // Message only, never the throwable: a stack logged here names this module, its
                // hooking library and its obfuscation maps, and any reader of the log sees it.
                onHookError = { logE(ZygoteEntry.TAG) { it.message ?: "Unknown error on hook" } },
                onOriginalError = { logD(ZygoteEntry.TAG) { it.message ?: "Unknown error" } },
                writeResult = { frame.setReturnValue(value.result) },
                clearFrames = ServiceUtils::clearStackTraces,
            )
        } finally {
            releaseReturnValue(value)
        }
    }

    internal fun hookAfter(
        clazz: String,
        methodName: String,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        handleAfterThrows: Boolean = false,
        hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = addHook(clazz, methodName, argumentCount) { original, frame ->
        val value = acquireReturnValue()

        try {
            HookDispatch.after(
                value = value,
                invokeOriginal = { invokeExactCompat(clazz, methodName, original, frame, value) },
                readResult = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        value.result = frame.accessor().getValue(RETURN_VALUE_IDX)
                    }
                },
                hook = { hook(methodName, frame, value) },
                // Message only, never the throwable: a stack logged here names this module, its
                // hooking library and its obfuscation maps, and any reader of the log sees it.
                onHookError = { logE(ZygoteEntry.TAG) { it.message ?: "Unknown error on hook" } },
                onOriginalError = { logD(ZygoteEntry.TAG) { it.message ?: "Unknown error" } },
                handleAfterThrows = handleAfterThrows,
                writeResult = { frame.setReturnValue(value.result) },
                clearFrames = ServiceUtils::clearStackTraces,
            )
        } finally {
            releaseReturnValue(value)
        }
    }

    private fun applyHook(
        clazz: String,
        element: HookElement,
        loader: ClassLoader? = SystemServerHook.classLoader,
    ): Boolean {
        // do not apply next hooks when the previous one was crashed
        if (hooksWasCrashed) {
            return false
        }

        var curClazz =
            try {
                Class.forName(clazz, true, loader)
            } catch (ex: ClassNotFoundException) {
                logE(ZygoteEntry.TAG, ex) { "Class $clazz not found" }
                return false
            }

        while (
            !element.hookFinished &&
            curClazz != null &&
            curClazz.javaClass.simpleName != "Object"
        ) {
            resolveExecutable(curClazz, element.methodName, element.argumentCount)?.let { executable ->
                logD(ZygoteEntry.TAG) { "Hooked constructor: $executable" }

                val memoryAddresses =
                    try {
                        Hooks.hook(
                            executable,
                            Hooks.EntryPointType.DIRECT,
                            element.impl,
                            Hooks.EntryPointType.DIRECT,
                        )
                    } catch (e: Throwable) {
                        logE(ZygoteEntry.TAG, e) {
                            "Hook $clazz -> ${element.methodName}(${element.argumentCount}) crashed!"
                        }

                        hooksWasCrashed = true

                        return false
                    }

                logV(ZygoteEntry.TAG) { "Memory address map: $memoryAddresses" }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    element.memoryAddresses = memoryAddresses
                    element.method = executable
                }

                element.hookFinished = true
            }

            curClazz = curClazz.superclass
        }

        return element.hookFinished
    }

    private fun invokeExactCompat(
        clazz: String,
        methodName: String,
        original: MethodHandle,
        frame: EmulatedStackFrame,
        value: ReturnValue,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val element = findHookElement(clazz, methodName)!!

            ArtMethodUtils.setExecutableEntryPoint(
                element.method!!,
                element.memoryAddresses?.second!!,
            )

            val thisObject = frame.getArgument(0)
            val args = frame.dumpArgs(true)

            // The concrete type is what is passed: a Constructor<*> here breaks Tango.
            value.result = (element.method as Method).invoke(thisObject, *args)

            ArtMethodUtils.setExecutableEntryPoint(
                element.method!!,
                element.memoryAddresses?.first!!,
            )
        } else {
            Transformers.invokeExactNoChecks(original, frame)
        }
    }

    private fun resolveExecutable(
        clazz: Class<*>?,
        methodName: String,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
    ): Executable? {
        val isConstructorHook = methodName == CONSTRUCTOR_METHOD_NAME

        return if (isConstructorHook) {
            Reflection
                .getHiddenConstructors(clazz)
                .let { constructors ->
                    if (argumentCount >= 0) {
                        constructors
                            .filter {
                                argumentCount == it.parameterCount
                            }.toTypedArray()
                    } else {
                        constructors
                    }
                }.firstOrNull()
        } else {
            Reflection
                .getHiddenExecutables(clazz)
                .filter { executable ->
                    if (methodName == executable.name) {
                        if (argumentCount >= 0) {
                            return@filter argumentCount == executable.parameterCount
                        }

                        return@filter true
                    }

                    return@filter false
                }.firstOrNull()
        }
    }
}
