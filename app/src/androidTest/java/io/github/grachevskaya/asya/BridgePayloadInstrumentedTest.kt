package io.github.grachevskaya.asya

import android.os.Parcel
import io.github.grachevskaya.asya.common.ServiceBridge
import io.github.grachevskaya.asya.common.ServiceBridge.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/*
 * The bridge carries the whole config document and the status of the module, and a payload above
 * 64 KiB travels in shared memory while the parcel holds only a handle. Both halves of that encoding
 * need a Parcel, so the test is instrumented: it hands a real ServiceBinder to a real ManagerBinder
 * and drives the traffic the manager would drive, in both directions, on both sides of the boundary.
 */
class BridgePayloadInstrumentedTest {
    /** A service that keeps what it was given and answers with what it was told to answer. */
    private class FakeService : Host {
        var config: String? = null

        /** The binder the manager handed over on the handshake, kept so the test can prove it arrived. */
        var managerInbox: android.os.IBinder? = null

        var resets = 0

        override fun moduleStatus(): Map<String, String> = mapOf("workMode" to "1")

        override fun kernelStatus(): Map<String, String> = mapOf("state" to "loaded")

        override fun configJson(): String? = config

        override fun setConfig(json: String): Boolean {
            config = json

            return true
        }

        override fun filterCount(): Int = 0

        override fun filterStatsJson(): String? = null

        override fun clearFilterStats() {
            resets++
        }

        override fun setManagerBinder(binder: android.os.IBinder) {
            managerInbox = binder
        }

        override fun onPackagesChanged() = Unit

        override val isAlive: Boolean get() = true
    }

    private fun connected(): Pair<Host, FakeService> {
        val fake = FakeService()
        val service = ServiceBridge.ServiceBinder(fake)
        val manager = ServiceBridge.ManagerBinder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        try {
            data.writeStrongBinder(service)
            // The handshake code, written out because it is internal to the bridge: 0x00A50001.
            manager.transact(0x00A50001, data, reply, 0)
        } finally {
            reply.recycle()
            data.recycle()
        }

        val host = ServiceBridge.host ?: error("the handshake did not set the host")

        // The handshake is two moves, not one: the manager answers with its own plain binder, and the
        // service is told to answer on it. A service that never received it has no way back.
        assertNotNull("the handshake left the service without a binder to answer on", fake.managerInbox)

        return host to fake
    }

    private fun payloads(): List<String> =
        listOf(
            "",
            "plain",
            "a".repeat(4096),
            "b".repeat(64 * 1024 - 1),
            "c".repeat(64 * 1024),
            "d".repeat(64 * 1024 + 1),
            "e".repeat(200 * 1024),
            "汉字".repeat(22000),
            "🙂".repeat(20000),
        )

    @Test
    fun aPushedConfigArrivesWhole() {
        val (host, fake) = connected()

        payloads().forEach { payload ->
            assertEquals(true, host.setConfig(payload))
            assertEquals("the service received a different document", payload, fake.config)
        }
    }

    @Test
    fun anAnsweredConfigArrivesWhole() {
        val (host, fake) = connected()

        payloads().forEach { payload ->
            fake.config = payload
            assertEquals("the manager received a different document", payload, host.configJson())
        }
    }

    @Test
    fun theStatusAnswersAreDistinguishableFromAMissingOne() {
        val (host, _) = connected()

        assertNotNull(host.kernelStatus())
        assertEquals(mapOf("state" to "loaded"), host.kernelStatus())
    }

    @Test
    fun aResetReachesTheServiceOnce() {
        val (host, fake) = connected()

        assertEquals(0, fake.resets)
        host.clearFilterStats()
        assertEquals("the reset did not reach the service", 1, fake.resets)
    }
}
