package sh.haven.core.ssh.sshlib

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import org.connectbot.sshlib.SshClient as SshlibClient

/**
 * Local stress probe for the sshlib output-loss race (#448, connectbot/cbssh#245).
 *
 * NOT a gate — it measures a rate rather than asserting a contract, and it is
 * excluded from CI with the rest of `sshlib/`. It exists to answer two things
 * the contract tests cannot: how often the race actually fires, and whether it
 * can be closed from Haven's side at all.
 */
class SshlibExecLossStressTest {

    private lateinit var server: SshServer
    private var serverPort = 0
    private var client: SshlibClient? = null

    @Before
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("stress-hostkey", ".ser"))
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
            commandFactory = CommandFactory { _, command -> Scripted(command) }
        }
        server.start()
        serverPort = server.port
    }

    @After
    fun tearDown() {
        runCatching { runBlocking { client?.disconnect() } }
        client = null
        if (::server.isInitialized) server.stop(true)
    }

    @Ignore(
        "Measures a rate, so it has no pass/fail answer to assert and would be " +
            "either slow or meaningless as a gate — at the observed ~4% it needs " +
            "hundreds of runs before the shape is clear. Run it by hand when " +
            "checking a candidate sshlib: STRESS_RUNS=1000 ./gradlew " +
            ":core:ssh:testDebugUnitTest --tests '*SshlibExecLossStressTest*' " +
            "--rerun, with the machine kept busy.",
    )
    @Test
    fun `measure the loss rate`() = runBlocking {
        val runs = (System.getenv("STRESS_RUNS") ?: "200").toInt()
        // The contract test closes its client after every case, so each of its
        // execs rides a freshly-dialled connection. Reusing one client here
        // measured a different shape entirely and found nothing.
        val reconnectEach = System.getenv("STRESS_REUSE") != "1"
        var lostStdout = 0
        var lostStderr = 0
        var truncatedBig = 0
        var goodBoth = 0
        var goodBig = 0
        var errors = 0

        repeat(runs) { i ->
            try {
                val both = exec("both")
                if (both.stdout != "to-stdout\n") lostStdout++
                if (both.stderr != "to-stderr\n") lostStderr++
                if (both.stdout == "to-stdout\n" && both.stderr == "to-stderr\n") goodBoth++

                if (i % 5 == 0) {
                    val big = exec("big")
                    if (big.stdout.length != BIG_BYTES) truncatedBig++ else goodBig++
                }
                if (reconnectEach) {
                    runCatching { client?.disconnect() }
                    client = null
                }
            } catch (e: Exception) {
                errors++
                if (errors <= 3) println("stress: exception on run $i: ${e.message}")
            }
        }

        println(
            "STRESS runs=$runs both-ok=$goodBoth lostStdout=$lostStdout lostStderr=$lostStderr " +
                "big-ok=$goodBig big-truncated=$truncatedBig errors=$errors",
        )
        // Non-vacuity: if nothing succeeded the probe measured nothing.
        require(goodBoth > 0) { "no successful exec at all — probe is not exercising the path" }
    }

    private suspend fun exec(command: String) = SshlibExec.exec(connect(), command, null)

    private suspend fun connect(): SshlibClient = client ?: SshlibSftpConnector.dialAndAuth(
        ConnectionConfig(
            host = "127.0.0.1",
            port = serverPort,
            username = "tester",
            authMethod = ConnectionConfig.AuthMethod.Password("secret"),
        ),
        mockk<HostKeyVerifier> { coEvery { verify(any()) } returns HostKeyResult.Trusted },
    ).also { client = it }

    private companion object {
        const val BIG_LINE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde\n"
        const val BIG_BYTES = 64 * 16_384

        private class Scripted(private val command: String) : Command {
            private var out: OutputStream? = null
            private var err: OutputStream? = null
            private var exit: ExitCallback? = null

            override fun setInputStream(value: InputStream?) {}
            override fun setOutputStream(value: OutputStream?) { out = value }
            override fun setErrorStream(value: OutputStream?) { err = value }
            override fun setExitCallback(value: ExitCallback?) { exit = value }

            override fun start(channel: ChannelSession?, env: Environment?) {
                Thread({
                    try {
                        when (command) {
                            "both" -> {
                                out!!.write("to-stdout\n".toByteArray()); out!!.flush()
                                err!!.write("to-stderr\n".toByteArray()); err!!.flush()
                                exit!!.onExit(0)
                            }
                            "big" -> {
                                val line = BIG_LINE.toByteArray()
                                repeat(BIG_BYTES / line.size) { out!!.write(line) }
                                out!!.flush()
                                exit!!.onExit(0)
                            }
                            else -> exit!!.onExit(127)
                        }
                    } catch (_: Exception) {
                        // torn down under us — the race being measured
                    }
                }, "scripted-stress").apply { isDaemon = true; start() }
            }

            override fun destroy(channel: ChannelSession?) {}
        }
    }
}
