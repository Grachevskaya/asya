package io.github.grachevskaya.asya.zygote.service

object KernelConstants {
    const val YIELD_BATCH_SIZE = 512

    /** sizeof(struct kaux_status), the fixed record kaux.h defines: 156 bytes, no padding. */
    const val MIN_STATUS_LENGTH = 156
}
