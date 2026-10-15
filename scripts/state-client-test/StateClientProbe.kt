package io.github.grachevskaya.asya.zygote.service
import java.net.ServerSocket
import java.io.*
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    val root = Files.createTempDirectory(java.nio.file.Paths.get(args[0]), "state-probe-").toFile()
    val store = StateStore(root)
    val listener = ServerSocket(0)
    val failWrites = AtomicBoolean()
    val completed = AtomicInteger()
    val server = thread(isDaemon = true) {
        while (!listener.isClosed) {
            val client = try { listener.accept() } catch (_: IOException) { break }
            client.use {
                client.soTimeout = 3000
                try {
                    StateWire.serve(DataInputStream(client.getInputStream()), DataOutputStream(client.getOutputStream()), store::read) { which, bytes ->
                        if (failWrites.get()) throw IOException("injected disk full")
                        store.write(which, bytes)
                    }
                    completed.incrementAndGet()
                } catch (_: Exception) { /* negative-ack and untrusted-peer probes */ }
            }
        }
    }
    val workers = Executors.newFixedThreadPool(6)
    try {
        check(StateClient.read(0) == null) // not named is NOT an empty file
        StateClient.open(listener.localPort.toString())
        for (which in 0..2) {
            check(StateClient.read(which)!!.isEmpty())
            val body = "{\"kind\":$which,\"unicode\":\"汉字🙂\"}".toByteArray()
            check(StateClient.write(which, body))
            check(StateClient.read(which)!!.contentEquals(body))
        }
        val futures = (0 until 24).map { n -> workers.submit<Boolean> {
            val which = n % 3
            check(StateClient.write(which, "{\"value\":$n}".toByteArray()))
            check(StateClient.read(which)!!.toString(Charsets.UTF_8).startsWith("{\"value\":"))
            true
        } }
        futures.forEach { check(it.get(10, TimeUnit.SECONDS)) }
        val before = store.read(0)
        failWrites.set(true)
        check(!StateClient.write(0, "should not land".toByteArray()))
        check(StateClient.read(0)!!.contentEquals(before))
        failWrites.set(false)
        val unavailable = ServerSocket(0).let { val port = it.localPort; it.close(); port }
        StateClient.open(unavailable.toString())
        check(!StateClient.write(0, "offline".toByteArray()))
        StateClient.open(listener.localPort.toString())
        check(StateClient.write(0, "recovered".toByteArray()))
        check(StateStore(root).read(0).contentEquals("recovered".toByteArray()))
        android.net.LocalSocket.peerUid = 2000
        check(StateClient.read(0) == null)
        android.net.LocalSocket.peerUid = 0
        check(StateClient.read(0)!!.contentEquals("recovered".toByteArray()))
        println("PASS actual StateClient + StateWire + StateStore: three files, ${completed.get()} successful requests, parallel callers, negative ACK, old-data retention, reconnect, root-peer check, restart readback")
    } finally {
        workers.shutdownNow(); listener.close(); server.join(4000); root.deleteRecursively()
    }
}
