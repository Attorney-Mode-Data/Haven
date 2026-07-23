package sh.haven.core.ssh.sshlib

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.ExecContractTest
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import org.connectbot.sshlib.SshClient as SshlibClient

/**
 * [ExecContractTest] on the sshlib engine (#58 phase 3) — the same five cases
 * the JSch leg pins (stdout/zero exit, stderr/non-zero exit, independent
 * streams, large stdout, timeout shape), over sshlib's session channel.
 *
 * This is the subclass [ExecContractTest]'s header said would "land when a
 * sshlib release carries exit-status support": that arrived in 0.4.0 via
 * Haven's connectbot/cbssh#232, so `exitStatus` is now the server's real
 * RFC 4254 §6.10 value rather than something this engine had to fake.
 */
class SshlibExecContractTest : ExecContractTest() {

    private var client: SshlibClient? = null

    override fun exec(command: String, timeoutMs: Long?): ExecResult = runBlocking {
        val connected = client ?: SshlibSftpConnector.dialAndAuth(
            ConnectionConfig(
                host = "127.0.0.1",
                port = serverPort,
                username = "tester",
                authMethod = ConnectionConfig.AuthMethod.Password("secret"),
            ),
            trustingVerifier(),
        ).also { client = it }
        SshlibExec.exec(connected, command, timeoutMs)
    }

    @After
    fun closeClient() {
        runCatching { runBlocking { client?.disconnect() } }
        client = null
    }

    /** Haven verifier that trusts the test server's ephemeral host key. */
    private fun trustingVerifier(): HostKeyVerifier = mockk {
        coEvery { verify(any()) } returns HostKeyResult.Trusted
    }
}
