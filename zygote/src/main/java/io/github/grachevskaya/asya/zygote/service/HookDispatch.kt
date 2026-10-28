package io.github.grachevskaya.asya.zygote.service

/*
 * What happens around one hooked call, kept free of the hooking library's types so it can be run on
 * this machine: the original runs unless the hook said its answer is final, frames of this module are
 * taken out of anything thrown, and the answer is written back only when it is final.
 */
internal object HookDispatch {
    inline fun before(
        value: ReturnValue,
        hook: () -> Unit,
        invokeOriginal: () -> Unit,
        onHookError: (Throwable) -> Unit,
        onOriginalError: (Throwable) -> Unit,
        writeResult: () -> Unit,
        clearFrames: (Throwable) -> Unit,
        restore: () -> Unit = {},
    ) {
        try {
            hook()
        } catch (it: Throwable) {
            onHookError(it)
        }

        if (!value.replace) {
            try {
                invokeOriginal()
            } catch (it: Throwable) {
                onOriginalError(it)
                value.throwable = it
            }
        }

        // The call is over, whatever it answered: this is where the argument a hook wrote over is
        // put back, so nothing a hook changed outlives the call that asked for it -- and a call that
        // threw leaves nothing rewritten behind it either.
        try {
            restore()
        } catch (it: Throwable) {
            onHookError(it)
        }

        value.throwable?.let {
            clearFrames(it)
            throw it
        }

        if (value.replace) {
            writeResult()
        }
    }

    inline fun after(
        value: ReturnValue,
        invokeOriginal: () -> Unit,
        readResult: () -> Unit,
        hook: () -> Unit,
        onHookError: (Throwable) -> Unit,
        onOriginalError: (Throwable) -> Unit,
        handleAfterThrows: Boolean,
        writeResult: () -> Unit,
        clearFrames: (Throwable) -> Unit,
    ) {
        try {
            invokeOriginal()
        } catch (it: Throwable) {
            onOriginalError(it)
            value.throwable = it
        }

        if (value.throwable == null) {
            readResult()
        }

        if (handleAfterThrows || value.throwable == null) {
            try {
                hook()
            } catch (it: Throwable) {
                onHookError(it)
            }
        }

        value.throwable?.let {
            clearFrames(it)
            throw it
        }

        writeResult()
    }
}
