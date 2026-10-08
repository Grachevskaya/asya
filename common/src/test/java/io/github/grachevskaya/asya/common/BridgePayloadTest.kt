package io.github.grachevskaya.asya.common

import io.github.grachevskaya.asya.common.ServiceBridge.PARCEL_LIMIT
import io.github.grachevskaya.asya.common.ServiceBridge.PAYLOAD_BUNDLE
import io.github.grachevskaya.asya.common.ServiceBridge.PAYLOAD_SHARED_MEMORY
import io.github.grachevskaya.asya.common.ServiceBridge.payloadKind
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Which way a payload travels is the one thing the manager and the service have to agree on: a size
 * that one side sends in the parcel and the other reads as shared memory is a configuration read as
 * nothing, and nothing fails. The boundary is checked here, either side of it, because it is a
 * function of the size and of nothing else.
 */
class BridgePayloadTest {
    @Test
    fun aSmallPayloadTravelsInTheParcel() {
        assertEquals(PAYLOAD_BUNDLE, payloadKind(0))
        assertEquals(PAYLOAD_BUNDLE, payloadKind(1))
        assertEquals(PAYLOAD_BUNDLE, payloadKind(4096))
    }

    @Test
    fun theLimitItselfStillTravelsInTheParcel() {
        assertEquals(PAYLOAD_BUNDLE, payloadKind(PARCEL_LIMIT))
    }

    @Test
    fun oneByteMoreGoesThroughSharedMemory() {
        assertEquals(PAYLOAD_SHARED_MEMORY, payloadKind(PARCEL_LIMIT + 1))
    }

    @Test
    fun aWholeDocumentGoesThroughSharedMemory() {
        assertEquals(PAYLOAD_SHARED_MEMORY, payloadKind(PARCEL_LIMIT * 8))
    }

    @Test
    fun theTwoKindsAreDifferent() {
        // The markers are the protocol: if they ever collide, every large payload is misread.
        assertEquals(false, PAYLOAD_BUNDLE == PAYLOAD_SHARED_MEMORY)
    }
}
