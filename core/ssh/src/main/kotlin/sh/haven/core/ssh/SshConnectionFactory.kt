package sh.haven.core.ssh

/**
 * The single place an [SshConnection] is constructed for a profile (#58,
 * phase 4). Centralising it here means the whole-connection engine choice
 * lands in one branch when sshlib gains shell/exec/forwarding (phase 5+),
 * instead of being scattered across the connect and reconnect paths.
 */
object SshConnectionFactory {

    fun create(engine: SshEngine): SshConnection = when (engine) {
        // The default for every profile that has not opted in.
        SshEngine.JSCH -> SshClient()
        // EXPERIMENTAL, opt-in per profile via the `HavenSshEngine sshlib`
        // sshOptions directive. Now a real whole-connection sshlib engine
        // (shell, exec, SFTP and port forwarding over one sshlib transport)
        // rather than the JSch stand-in it used to return — exec was the last
        // missing block and landed with sshlib 0.4.0 (connectbot/cbssh#232).
        //
        // This is NOT the default flip: JSch stays the default until the
        // experimental engine has real-world mileage. SshlibConnection refuses
        // what it cannot do (jump/proxy, FIDO2, OpenSSH certs, MFA chains)
        // instead of falling back silently, so an opted-in profile fails loudly
        // and the user can switch it back.
        SshEngine.SSHLIB -> sh.haven.core.ssh.sshlib.SshlibConnection()
    }

    fun create(config: ConnectionConfig): SshConnection = create(config.sshEngine)
}
