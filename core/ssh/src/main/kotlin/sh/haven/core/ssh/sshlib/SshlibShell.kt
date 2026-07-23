package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.runBlocking
import org.connectbot.sshlib.SshSession
import org.connectbot.sshlib.SshClient as SshlibClient
import sh.haven.core.ssh.ShellChannel
import sh.haven.core.ssh.SshIoException

/**
 * Opens an interactive shell [ShellChannel] over a connected sshlib client
 * (#58, phase 5): a session channel with a PTY and a shell request, its
 * `stdout` bridged to a blocking [ReceiveChannelInputStream] and stdin to a
 * [SuspendWriteOutputStream] — the same stream shape
 * [sh.haven.core.ssh.TerminalSession] already consumes from JSch.
 */
internal object SshlibShell {

    fun open(
        client: SshlibClient,
        session: SshSession,
        term: String,
        cols: Int,
        rows: Int,
        /**
         * Whether closing this channel also drops the SSH connection. True for
         * a dedicated shell dial (the connection exists only for this shell);
         * false when the shell rides a shared whole-connection
         * ([SshlibConnection]), where closing one terminal tab must not take
         * the profile's other channels and forwards with it.
         */
        ownsClient: Boolean = true,
    ): ShellChannel {
        val input = ReceiveChannelInputStream(session.stdout)
        val output = SuspendWriteOutputStream { session.write(it) }
        return ShellChannel(
            input = input,
            output = output,
            resizeFn = { c, r ->
                runBlocking { session.resizeTerminal(c, r, 0, 0) }
            },
            disconnectFn = {
                runCatching { session.close() }
                if (ownsClient) runCatching { runBlocking { client.disconnect() } }
            },
            connectedProbe = { session.isOpen },
            closedProbe = { !session.isOpen },
            // Real exit status since sshlib 0.4.0 (Haven's cbssh#232). The probe
            // is synchronous, so read the Deferred only once it has completed —
            // before the server sends exit-status this reports -1 ("unknown"),
            // exactly as JSch does.
            exitStatusProbe = { completedExitStatus(session) },
        )
    }

    /**
     * The session's exit status if the server has already reported one, else
     * -1. Non-suspending by design: [ShellChannel]'s probe is synchronous, so
     * an in-flight session must answer "unknown" rather than block a caller.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun completedExitStatus(session: SshSession): Int {
        val exit = session.exitInfo
        if (!exit.isCompleted) return -1
        return when (val value = runCatching { exit.getCompleted() }.getOrNull()) {
            is org.connectbot.sshlib.SessionExit.Status -> value.code.toInt()
            // Signal-terminated carries no numeric status — see SshlibExec.
            else -> -1
        }
    }

    /** Open a session channel, request a PTY, and start a shell. */
    suspend fun requestShell(
        session: SshSession,
        term: String,
        cols: Int,
        rows: Int,
    ) {
        if (!session.requestPty(term, cols, rows)) {
            throw SshIoException("sshlib: server rejected PTY request")
        }
        if (!session.requestShell()) {
            throw SshIoException("sshlib: server rejected shell request")
        }
    }
}
