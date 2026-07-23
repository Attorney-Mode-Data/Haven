package sh.haven.core.ssh.sshlib

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.ShellChannel
import sh.haven.core.ssh.ShellChannelContractTest

/**
 * [ShellChannelContractTest] on the sshlib engine (#58 phase 5): the same
 * banner/echo/resize/exit suite over the neutralised ShellChannel + the
 * ReceiveChannel/suspend-write stream adapters, proving byte-for-byte parity
 * with JSch before any factory flip.
 */
class SshlibShellChannelContractTest : ShellChannelContractTest() {

    override fun openShell(host: String, port: Int, username: String, password: String): ShellChannel = runBlocking {
        val config = ConnectionConfig(
            host = host,
            port = port,
            username = username,
            authMethod = ConnectionConfig.AuthMethod.Password(password),
        )
        val verifier = mockk<HostKeyVerifier> { coEvery { verify(any()) } returns HostKeyResult.Trusted }
        val client = SshlibSftpConnector.dialAndAuth(config, verifier)
        val session = client.openSession()
            ?: error("sshlib: failed to open session channel")
        SshlibShell.requestShell(session, term = "xterm", cols = 80, rows = 24)
        SshlibShell.open(client, session, term = "xterm", cols = 80, rows = 24)
    }
}
