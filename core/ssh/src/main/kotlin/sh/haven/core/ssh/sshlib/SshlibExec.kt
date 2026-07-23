package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.sshlib.SessionExit
import org.connectbot.sshlib.SshSession
import org.connectbot.sshlib.SshClient as SshlibClient
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.SshIoException
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `exec` on the sshlib engine (#58, phase 3) — the last building block the
 * whole-connection engine was missing.
 *
 * Matches the contract `ExecContractTest` pins on JSch: stdout and stderr
 * drained *concurrently* (a command that emits more than a buffer of stderr
 * before stdout closes must not deadlock), a real exit status, and the
 * documented timeout shape (`exitStatus` -1, `timedOut` true, output possibly
 * truncated because the forced close tears the pipe mid-read).
 *
 * The exit status comes from [SshSession.exitInfo] — Haven upstreamed it as
 * connectbot/cbssh#232 and it shipped in sshlib 0.4.0. Before that the packet
 * loop parsed and then discarded the RFC 4254 §6.10 exit-status message, so an
 * honest `exitStatus` was impossible on this engine and exec had to stay JSch.
 */
internal object SshlibExec {

    /**
     * How long to wait for the exit-status message after both output streams
     * have closed. RFC 4254 §6.10 is a SHOULD, so a server may simply never
     * send one; bound the wait so that degrades to -1 ("unknown" — the same
     * value JSch reports for an unset status) instead of hanging exec forever.
     */
    private const val EXIT_INFO_TIMEOUT_MS = 2_000L

    suspend fun exec(client: SshlibClient, command: String, timeoutMs: Long?): ExecResult {
        val session = client.openSession()
            ?: throw SshIoException(
                "sshlib: server refused a session channel for exec. $SSHLIB_CHANNEL_LIMITATION",
            )
        try {
            if (!session.requestExec(command)) {
                throw SshIoException("sshlib: server rejected exec request: ${command.take(64)}")
            }
            val timedOut = AtomicBoolean(false)
            val (outBytes, errBytes) = coroutineScope {
                val watchdog = timeoutMs?.let {
                    async {
                        delay(it)
                        timedOut.set(true)
                        // Closing the session closes both receive channels,
                        // which is what actually unblocks the drains below.
                        runCatching { session.close() }
                    }
                }
                try {
                    val errDeferred = async { drain(session.stderr) }
                    val out = drain(session.stdout)
                    out to errDeferred.await()
                } finally {
                    watchdog?.cancel()
                }
            }
            return ExecResult(
                exitStatus = if (timedOut.get()) -1 else exitStatusOf(session),
                stdout = outBytes.decodeToString(),
                stderr = errBytes.decodeToString(),
                timedOut = timedOut.get(),
            )
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * Read a chunk channel to completion. A normally-closed channel just ends
     * the loop; a channel torn down by the timeout's [SshSession.close] closes
     * with a cause, and the partial output collected so far is what the
     * contract returns.
     */
    private suspend fun drain(channel: ReceiveChannel<ByteArray>): ByteArray {
        val buf = ByteArrayOutputStream()
        try {
            for (chunk in channel) buf.write(chunk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Forced close mid-read — keep what we got.
        }
        return buf.toByteArray()
    }

    private suspend fun exitStatusOf(session: SshSession): Int =
        when (val exit = withTimeoutOrNull(EXIT_INFO_TIMEOUT_MS) { session.exitInfo.await() }) {
            is SessionExit.Status -> exit.code.toInt()
            // A signal-terminated command carries no numeric status. Report the
            // same -1 JSch surfaces rather than inventing a 128+signum value
            // the server never sent.
            is SessionExit.Signal -> -1
            else -> -1
        }
}
