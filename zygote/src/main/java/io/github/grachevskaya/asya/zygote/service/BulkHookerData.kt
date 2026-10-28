package io.github.grachevskaya.asya.zygote.service

import android.util.Pair
import com.v7878.vmtools.HookTransformer
import java.lang.reflect.Executable

class ReturnValue(
    initialValue: Any? = null,
) {
    var replace: Boolean = false
        private set

    var result: Any? = initialValue
        set(newValue) {
            field = newValue
            replace = true
        }

    var throwable: Throwable? = null

    /**
     * Nothing left of an earlier call. The value a hook decides with is the same object on every call
     * of its thread, so a "replace" or a result that survived the call before it would answer this
     * call with the last one's answer.
     */
    fun reset(): ReturnValue {
        // The result first: its setter raises replace, and this is what lowers it again.
        result = null
        throwable = null
        replace = false
        return this
    }
}

/**
 * The [ReturnValue] instances one thread reuses, one per depth.
 *
 * Depth is what the slots are for: a hook runs on the thread that made the call, and the original
 * method it wraps can itself call a hooked method on that same thread. A single slot would be reset
 * by the inner call while the outer one is still deciding, and the outer answer would be the inner
 * one's. So a call takes a cleared slot and gives it back when it is done -- the common case is one
 * object, made once and never again.
 */
internal class ReturnValuePool {
    private companion object {
        /** Slots kept per thread; a hook nesting deeper than this allocates instead of pooling. */
        const val MAX_SLOTS = 4
    }

    private val free = ArrayList<ReturnValue>(MAX_SLOTS)

    fun acquire(): ReturnValue {
        if (free.isEmpty()) return ReturnValue()
        return free.removeAt(free.size - 1).reset()
    }

    /**
     * Back in the pool, cleared: a pooled value must not hold what the framework handed it, or a
     * long-lived thread would keep the last package it hid for as long as it lived.
     */
    fun release(value: ReturnValue) {
        value.reset()
        if (free.size < MAX_SLOTS) free.add(value)
    }
}

/**
 * The argument a rewritten call took away, per thread and per depth.
 *
 * A rewritten method can, inside its own body, make another query this module rewrites -- on Android
 * 12 a body resolves its package setting through a method that is rewritten too -- so the slot the
 * restore step takes must be the one its own call put there. Every rewritten call puts a slot in
 * place, empty when it took nothing away, so the two halves stay paired and no asked name outlives
 * the call that asked it.
 */
internal object RewrittenArguments {
    private val slots = ThreadLocal.withInitial { ArrayDeque<Any?>() }

    fun remember(replaced: Any?) {
        slots.get().addLast(replaced)
    }

    /** What this call took away, or null when it took nothing. */
    fun take(): Any? {
        val stack = slots.get()
        return if (stack.isEmpty()) null else stack.removeLast()
    }
}

data class HookElement(
    val impl: HookTransformer,
    val methodName: String,
    var method: Executable? = null,
    var memoryAddresses: Pair<Long, Long>? = null,
    var hookFinished: Boolean = false,
    val argumentCount: Int = -1,
)
