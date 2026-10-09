package io.github.grachevskaya.asya.common

import android.os.IBinder

/*
 * The wire the bridge speaks: the transaction codes both ends answer, the marks a payload travels
 * under, and the two questions that keep the ends from drifting apart.
 *
 * The codes live here so that both ends read one list. The service picks the ones it answers with
 * [serviceHandles], the manager picks the ones it takes with [managerDispatch], and a change that
 * would put them out of step has to be made here.
 *
 * A code sent to a framework object must stay outside 1..IBinder.LAST_CALL_TRANSACTION: a code inside
 * that range is taken for a framework call, the interface token is demanded, and the transaction is
 * refused. The framework object the host registers can therefore never carry this module's traffic.
 */
object BridgeProtocol {
    internal const val CODE_HELLO = 0x00A50001
    internal const val CODE_PACKAGES_CHANGED = 0x00A50002

    /** What the module says about itself, and the kernel's last answer as the module read it. */
    internal const val CODE_MODULE_STATUS = 0x00A50010
    internal const val CODE_KERNEL_STATUS = 0x00A50011

    /** The configuration, as the one whole document both ends pass around. */
    internal const val CODE_CONFIG_JSON = 0x00A50012
    internal const val CODE_CONFIG_SET = 0x00A50018

    /** The filter ledger: what it holds, how it is read, and the reset that empties it. */
    internal const val CODE_FILTER_COUNT = 0x00A50013
    internal const val CODE_FILTER_STATS = 0x00A50014
    internal const val CODE_FILTER_DELTA = 0x00A5001A
    internal const val CODE_FILTER_CLEAR = 0x00A5001C

    /** Log lines, batched: one transaction per batch, never one per line. */
    internal const val CODE_LOG_LINES = 0x00A50019

    /** A configuration this side changed itself, sent so the manager can keep the file. */
    internal const val CODE_CONFIG_CHANGED = 0x00A5001B

    /** The manager's own binder: the object it registers with the framework refuses these codes. */
    internal const val CODE_MANAGER_BINDER = 0x00A5001D

    /** The name the manager's object answers to, which no framework observer shares. */
    const val DESCRIPTOR_OF_OURS = "asya.bridge.manager"

    /** How many lines travel in one batch, and how long the oldest waits for company. */
    const val LOG_BATCH_LINES = 32

    const val LOG_FLUSH_PERIOD_MS = 250L

    /** Markers for a payload that travels in shared memory rather than in the parcel itself. */
    internal const val PAYLOAD_BUNDLE = 0
    internal const val PAYLOAD_SHARED_MEMORY = 1

    /**
     * Above this the bytes go through shared memory. The threshold is close to what a transaction can
     * carry on purpose: the shared-memory mapping is released only on finalization, so it is kept for
     * documents a parcel cannot hold.
     */
    internal const val PARCEL_LIMIT = 256 * 1024

    internal const val DISPATCH_UNKNOWN = 0
    internal const val DISPATCH_HANDLED = 1
    internal const val DISPATCH_INTERFACE = 2

    /** Which of the two marks a document of this size travels under. */
    internal fun payloadKind(size: Int): Int = if (size <= PARCEL_LIMIT) PAYLOAD_BUNDLE else PAYLOAD_SHARED_MEMORY

    /**
     * How the manager answers a code: its own two, the interface cases it must not forward (the
     * framework calls the observer through this same object), or neither. The decision is a
     * function of the code, so the set of codes and their meaning can be checked without a device.
     */
    internal fun managerDispatch(code: Int): Int =
        when {
            code == CODE_HELLO ||
                code == CODE_PACKAGES_CHANGED ||
                code == CODE_LOG_LINES ||
                code == CODE_FILTER_DELTA ||
                code == CODE_CONFIG_CHANGED -> DISPATCH_HANDLED
            code in 1..IBinder.LAST_CALL_TRANSACTION -> DISPATCH_INTERFACE
            else -> DISPATCH_UNKNOWN
        }

    /** The codes the service end answers, and nothing else: it is a plain binder of this module. */
    internal fun serviceHandles(code: Int): Boolean =
        code == CODE_MODULE_STATUS ||
            code == CODE_KERNEL_STATUS ||
            code == CODE_CONFIG_JSON ||
            code == CODE_CONFIG_SET ||
            code == CODE_FILTER_COUNT ||
            code == CODE_FILTER_STATS ||
            code == CODE_FILTER_CLEAR ||
            code == CODE_MANAGER_BINDER
}
