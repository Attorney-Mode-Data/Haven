package sh.haven.core.ssh

/**
 * Which SSH protocol implementation a connection uses (#58).
 *
 * Selected per-connection via the `HavenSshEngine` directive in the
 * profile's SSH Options (`HavenSshEngine sshlib`) — a Haven-internal
 * directive that [SshOptionsApplier] filters out before JSch config is
 * applied. Absent/unknown values fall back to [JSCH], so a hand-mangled
 * directive can never change engines silently.
 */
enum class SshEngine {
    /** mwiede JSch fork — the default engine for every profile that has not opted in. */
    JSCH,

    /**
     * org.connectbot.sshlib (ssh-proto) — **experimental, opt-in**.
     *
     * Carries a whole connection: terminal (shell and RemoteCommand exec), one-shot
     * exec, SFTP, and local/remote/dynamic port forwarding over one sshlib transport.
     * It deliberately REFUSES what it cannot do — jump-host or proxied dials, FIDO2
     * hardware keys, OpenSSH certificates, and multi-factor chains — so an opted-in
     * profile fails loudly rather than silently behaving differently; move such
     * profiles back to [JSCH].
     */
    SSHLIB,
}

private const val ENGINE_DIRECTIVE = "havensshengine"

/** Engine choice from a parsed SSH Options map; [SshEngine.JSCH] unless valid. */
fun sshEngineFrom(sshOptions: Map<String, String>): SshEngine {
    val value = sshOptions.entries
        .firstOrNull { it.key.trim().lowercase() == ENGINE_DIRECTIVE }
        ?.value?.trim() ?: return SshEngine.JSCH
    return if (value.equals("sshlib", ignoreCase = true)) SshEngine.SSHLIB else SshEngine.JSCH
}

/**
 * Engine choice from raw SSH Options text (the `ConnectionProfile.sshOptions`
 * column), for callers that pick the engine before a [ConnectionConfig] exists.
 */
fun sshEngineFromOptionsText(sshOptions: String?): SshEngine =
    sshEngineFrom(ConnectionConfig.parseSshOptions(sshOptions))

/** Engine choice carried by this config's SSH Options; [SshEngine.JSCH] unless valid. */
val ConnectionConfig.sshEngine: SshEngine
    get() = sshEngineFrom(sshOptions)
