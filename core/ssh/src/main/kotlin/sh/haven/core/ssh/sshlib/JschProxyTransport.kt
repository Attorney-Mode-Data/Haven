package sh.haven.core.ssh.sshlib

import com.jcraft.jsch.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportException
import org.connectbot.sshlib.transport.TransportFactory
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Carries a sshlib connection over a JSch [Proxy] (#58 phase 7).
 *
 * sshlib's [Transport] is four methods over a byte stream, and a JSch [Proxy]
 * *is* a byte stream once connected — so this one adapter covers every proxy
 * shape Haven already builds in `TunnelResolver` and
 * `SshSessionManager.createProxyJump`: SOCKS4, SOCKS5, HTTP CONNECT,
 * Tailscale/WireGuard/Cloudflare Access, and `ProxyJump` over a live jump-host
 * session. No per-proxy-type work, and nothing new to keep in step when a
 * tunnel type is added.
 *
 * The jump case is worth spelling out: `ProxyJump.connect` opens a
 * direct-tcpip channel on the jump session and hands back its streams, so a
 * sshlib target reached through a jump host works today even though the jump
 * session itself is a JSch connection. sshlib's own
 * `SshClient.openDirectTcpipTransport` is the native equivalent and is what a
 * *sshlib* jump host would need — `createProxyJump` still requires a
 * `jschSession`, so a sshlib session cannot yet BE a jump host on either
 * engine.
 */
internal class JschProxyTransportFactory(
    private val proxy: Proxy,
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int,
) : TransportFactory {

    override suspend fun create(): Transport = withContext(Dispatchers.IO) {
        try {
            // socketFactory = null: JSch falls back to its default, which is
            // what the JSch engine passes for every proxy type too.
            proxy.connect(null, host, port, connectTimeoutMs)
        } catch (e: Exception) {
            runCatching { proxy.close() }
            throw TransportException("proxy connect to $host:$port failed: ${e.message}", e)
        }
        JschProxyTransport(proxy, proxy.inputStream, proxy.outputStream)
    }
}

/**
 * The connected half of [JschProxyTransportFactory]. Blocking JSch streams, so
 * every operation hops to [Dispatchers.IO].
 */
private class JschProxyTransport(
    private val proxy: Proxy,
    input: InputStream,
    private val output: OutputStream,
) : Transport {

    private val input = DataInputStream(input)

    @Volatile
    private var open = true

    override val isConnected: Boolean get() = open

    /** [Transport.read] is readFully semantics — exactly [count] bytes or throw. */
    override suspend fun read(count: Int): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArray(count)
        try {
            input.readFully(buffer)
        } catch (e: Exception) {
            open = false
            throw TransportException("proxied transport read failed: ${e.message}", e)
        }
        buffer
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                output.write(data)
                output.flush()
            } catch (e: Exception) {
                open = false
                throw TransportException("proxied transport write failed: ${e.message}", e)
            }
        }
    }

    override suspend fun close() {
        open = false
        withContext(Dispatchers.IO) {
            // Closing the proxy closes the channel/socket and its streams.
            runCatching { proxy.close() }
        }
    }
}
