package android.net
import java.net.Socket
class LocalSocketAddress(val name: String, val namespace: Namespace = Namespace.ABSTRACT) { enum class Namespace { ABSTRACT } }
class Credentials(val uid: Int)
class LocalSocket {
    private var transport: Socket? = null
    var soTimeout: Int = 0
        set(value) { field = value; transport?.soTimeout = value }
    fun connect(address: LocalSocketAddress) { transport = Socket("127.0.0.1", address.name.toInt()).also { it.soTimeout = soTimeout } }
    val peerCredentials get() = Credentials(peerUid)
    val inputStream get() = transport!!.getInputStream()
    val outputStream get() = transport!!.getOutputStream()
    fun close() { transport?.close() }
    companion object { @Volatile var peerUid = 0 }
}
