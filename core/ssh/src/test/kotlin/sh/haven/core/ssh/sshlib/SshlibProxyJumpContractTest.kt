package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.session.Session as MinaSession
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HavenProxy
import sh.haven.core.ssh.ProxyJump
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshConnection
import java.nio.file.Files
import java.util.Collections

/**
 * Jump-host and proxy dials on the sshlib engine (#58 phase 7).
 *
 * Two real MINA sshd instances: a jump host that permits direct-tcpip, and a
 * target. A Haven JSch [SshClient] connects to the jump, its session becomes a
 * JSch [ProxyJump], and the sshlib engine dials the TARGET through it — the same
 * [HavenProxy] shape `SshSessionManager.createProxyJump` produces in the app,
 * carried by [JschProxyTransportFactory].
 *
 * **The jump server records every forward it is asked for**, so the assertion is
 * that the target port was reached VIA the jump, not merely that a connection
 * happened: dialing direct would leave that list empty. `dialingDirect_...`
 * is the control — same target, no proxy, and the jump sees nothing.
 *
 * MINA sshd runs on the host JVM only, so this lives in `src/test`.
 */
class SshlibProxyJumpContractTest {

    private lateinit var jumpServer: SshServer
    private lateinit var targetServer: SshServer

    /** Destinations the jump host was asked to open a direct-tcpip channel to. */
    private val forwardRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val jumpClient = SshClient()

    @After
    fun tearDown() {
        jumpClient.disconnect()
        if (::jumpServer.isInitialized) jumpServer.stop(true)
        if (::targetServer.isInitialized) targetServer.stop(true)
    }

    @Test
    fun sshlibDialsTargetThroughJumpHost() {
        startServers()
        val connection: SshConnection = SshlibConnection()
        try {
            val hostKey = runBlocking {
                connection.connect(
                    config = targetConfig(),
                    connectTimeoutMs = 10_000,
                    proxy = jumpProxy(),
                    keyboardInteractivePrompter = null,
                    totpCodeProvider = null,
                    confirmOtp = false,
                    preConnect = null,
                    trustedHostCaKeys = emptyList(),
                )
            }
            assertNotNull("the TARGET's host key must come back for TOFU", hostKey)
            assertTrue("sshlib must reach the target through the jump", connection.isConnected)
            assertTrue("connectedViaProxy must be real, not a constant", connection.connectedViaProxy)
            assertEquals(
                "the jump host must have been asked to forward to the target",
                listOf("127.0.0.1:${targetServer.port}"),
                forwardRequests.toList(),
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Control for the assertion above: without a proxy the same target connects
     * fine and the jump host is not involved at all. Without this, a
     * forward-request list that silently stopped being populated would look
     * like a passing test.
     */
    @Test
    fun dialingDirectDoesNotTouchTheJumpHost() {
        startServers()
        val connection: SshConnection = SshlibConnection()
        try {
            runBlocking {
                connection.connect(
                    config = targetConfig(),
                    connectTimeoutMs = 10_000,
                    proxy = null,
                    keyboardInteractivePrompter = null,
                    totpCodeProvider = null,
                    confirmOtp = false,
                    preConnect = null,
                    trustedHostCaKeys = emptyList(),
                )
            }
            assertTrue(connection.isConnected)
            assertFalse("a direct dial must not report connectedViaProxy", connection.connectedViaProxy)
            assertEquals("a direct dial must not involve the jump host", emptyList<String>(), forwardRequests.toList())
        } finally {
            connection.disconnect()
        }
    }

    /** The connection must be usable through the jump, not just established. */
    @Test
    fun execWorksOverTheJumpedConnection() {
        startServers()
        val connection: SshConnection = SshlibConnection()
        try {
            runBlocking {
                connection.connect(
                    config = targetConfig(),
                    connectTimeoutMs = 10_000,
                    proxy = jumpProxy(),
                    keyboardInteractivePrompter = null,
                    totpCodeProvider = null,
                    confirmOtp = false,
                    preConnect = null,
                    trustedHostCaKeys = emptyList(),
                )
                val alive = connection.isAlive(5_000)
                assertTrue("a session channel must open over the jumped transport", alive)
            }
            // Without these the test passes on a direct dial too, which is how
            // it behaved when the transport factory was mutated out.
            assertTrue(connection.connectedViaProxy)
            assertEquals(listOf("127.0.0.1:${targetServer.port}"), forwardRequests.toList())
        } finally {
            connection.disconnect()
        }
    }

    private fun targetConfig() = ConnectionConfig(
        host = "127.0.0.1",
        port = targetServer.port,
        username = "alice",
        authMethod = ConnectionConfig.AuthMethod.Password("any"),
    )

    /** A JSch session on the jump host, wrapped exactly as the app wraps it. */
    private fun jumpProxy(): HavenProxy {
        runBlocking {
            jumpClient.connect(
                config = ConnectionConfig(
                    host = "127.0.0.1",
                    port = jumpServer.port,
                    username = "alice",
                    authMethod = ConnectionConfig.AuthMethod.Password("any"),
                ),
                connectTimeoutMs = 10_000,
            )
        }
        val session = requireNotNull(jumpClient.jschSession) { "jump session must be connected" }
        return HavenProxy(ProxyJump(session))
    }

    private fun startServers() {
        targetServer = buildServer(recordForwards = false).also { it.start() }
        jumpServer = buildServer(recordForwards = true).also { it.start() }
    }

    private fun buildServer(recordForwards: Boolean): SshServer {
        val keyFile = Files.createTempFile("haven-sshlib-jump-hostkey-", ".ser").also {
            Files.deleteIfExists(it)
            it.toFile().deleteOnExit()
        }
        return SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile).apply {
                algorithm = "RSA"
                keySize = 2048
            }
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
            forwardingFilter = if (recordForwards) {
                object : AcceptAllForwardingFilter() {
                    override fun canConnect(
                        type: TcpForwardingFilter.Type,
                        address: SshdSocketAddress,
                        session: MinaSession,
                    ): Boolean {
                        forwardRequests += "${address.hostName}:${address.port}"
                        return super.canConnect(type, address, session)
                    }
                }
            } else {
                AcceptAllForwardingFilter.INSTANCE
            }
        }
    }
}
